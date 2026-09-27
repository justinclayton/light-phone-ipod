import XCTest
import Security
import CryptoKit
@testable import LP3MusicLoader

/// A queue in a box for the API.
final class FakeQueue: SyncQueueSource {
    var items: [(id: String, path: String, fileURL: URL, sizeBytes: Int)] = []
    var requested: [String] = []
    var acked: [String] = []
    func waitingItems() -> [(id: String, path: String, fileURL: URL, sizeBytes: Int)] { items }
    func fileRequested(id: String) { requested.append(id) }
    func acknowledged(id: String) {
        acked.append(id)
        items.removeAll { $0.id == id }
    }
}

final class SyncAPITests: XCTestCase {
    private func request(_ method: String, _ path: String, token: String? = "tok") -> HTTPRequest {
        var headers: [String: String] = [:]
        if let token { headers["authorization"] = "Bearer \(token)" }
        return HTTPRequest(method: method, path: path, headers: headers, body: Data(), remoteHost: nil)
    }

    func testAuthAndRoutes() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("s-\(UUID()).mp3")
        try Data(repeating: 7, count: 1234).write(to: file)
        let queue = FakeQueue()
        queue.items = [("id one", "A/B/c.mp3", file, 1234)]
        let api = SyncAPI(token: "tok", source: queue)

        XCTAssertEqual(api.handle(request("GET", "/v1/queue", token: nil)).status, 401)
        XCTAssertEqual(api.handle(request("GET", "/v1/queue", token: "wrong")).status, 401)

        let q = api.handle(request("GET", "/v1/queue"))
        XCTAssertEqual(q.status, 200)
        guard case .data(let body) = q.body else { return XCTFail() }
        let json = try JSONSerialization.jsonObject(with: body) as! [String: [[String: Any]]]
        XCTAssertEqual(json["items"]?.count, 1)
        XCTAssertEqual(json["items"]?[0]["path"] as? String, "A/B/c.mp3")
        XCTAssertEqual(json["items"]?[0]["sizeBytes"] as? Int, 1234)

        let f = api.handle(request("GET", "/v1/files/id%20one"))
        XCTAssertEqual(f.status, 200)
        XCTAssertEqual(f.body.contentLength, 1234)
        XCTAssertEqual(queue.requested, ["id one"])

        XCTAssertEqual(api.handle(request("GET", "/v1/files/nope")).status, 404)
        XCTAssertEqual(api.handle(request("POST", "/v1/ack/nope")).status, 204, "ack is idempotent")
        XCTAssertEqual(api.handle(request("POST", "/v1/ack/id%20one")).status, 204)
        XCTAssertEqual(queue.acked, ["nope", "id one"])
        XCTAssertEqual(api.handle(request("GET", "/v1/files/id%20one")).status, 404, "acked items are gone")
        XCTAssertEqual(api.handle(request("DELETE", "/v1/queue")).status, 405)
        XCTAssertEqual(api.handle(request("GET", "/v2/queue")).status, 404)
    }

    func testChangedFileIsReportedGone() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("s-\(UUID()).mp3")
        try Data(repeating: 1, count: 10).write(to: file)
        let queue = FakeQueue()
        queue.items = [("x", "x.mp3", file, 10)]
        let api = SyncAPI(token: "tok", source: queue)
        try Data(repeating: 1, count: 11).write(to: file) // edited after drop
        XCTAssertEqual(api.handle(request("GET", "/v1/files/x")).status, 404)
        try FileManager.default.removeItem(at: file)
        XCTAssertEqual(api.handle(request("GET", "/v1/files/x")).status, 404)
    }
}

/// Runs the real Network.framework server with a freshly minted identity and
/// talks to it over TLS with a fingerprint-pinning URLSession, the way the
/// phone's `PinnedCertificateTrustManager` does.
final class HTTPServerIntegrationTests: XCTestCase {
    private var server: HTTPServer!
    private var port = 0
    private var fingerprint = ""
    private var queue: FakeQueue!
    private var file: URL!
    private let bytes = Data((0..<300_000).map { UInt8($0 % 251) })

    override func setUpWithError() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("loader-test-\(UUID())", isDirectory: true)
        let identity: ServerIdentity
        switch try ServerIdentity.loadOrCreate(certificateFile: dir.appendingPathComponent("cert.der"), keyTag: Self.testKeyTag) {
        case .existing(let i), .regenerated(let i, _): identity = i
        }
        fingerprint = identity.fingerprintHex
        certificateDER = identity.certificateDER
        file = dir.appendingPathComponent("song.mp3")
        try bytes.write(to: file)
        queue = FakeQueue()
        queue.items = [("abc", "A/B/song.mp3", file, bytes.count)]
        let api = SyncAPI(token: "good-token", source: queue)

        let ready = expectation(description: "ready")
        server = HTTPServer(identity: identity.identity, handler: { api.handle($0) }) { [weak self] event in
            if case .ready(let p) = event { self?.port = p; ready.fulfill() }
            if case .failed(let m) = event { XCTFail(m) }
        }
        try server.start(port: 0)
        wait(for: [ready], timeout: 5)
    }

    static let testKeyTag = "com.thelightphone.ipod.macloader.tls-key.tests"

    private var certificateDER = Data()

    override func tearDown() {
        server.stop()
        ServerIdentity.deleteKeys(tag: Self.testKeyTag)
        if let cert = SecCertificateCreateWithData(nil, certificateDER as CFData) {
            SecItemDelete([kSecClass: kSecClassCertificate, kSecValueRef: cert] as CFDictionary)
        }
    }

    private func session(pin: String) -> URLSession {
        URLSession(configuration: .ephemeral, delegate: Pinner(expected: pin), delegateQueue: nil)
    }

    private func get(_ path: String, token: String = "good-token", pin: String? = nil, method: String = "GET") async throws -> (Data, HTTPURLResponse) {
        var req = URLRequest(url: URL(string: "https://127.0.0.1:\(port)\(path)")!)
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await session(pin: pin ?? fingerprint).data(for: req)
        return (data, response as! HTTPURLResponse)
    }

    func testFullRoundTripOverPinnedTLS() async throws {
        let (queueBody, queueResponse) = try await get("/v1/queue")
        XCTAssertEqual(queueResponse.statusCode, 200)
        let json = try JSONSerialization.jsonObject(with: queueBody) as! [String: [[String: Any]]]
        XCTAssertEqual(json["items"]?.first?["id"] as? String, "abc")

        let (fileBody, fileResponse) = try await get("/v1/files/abc")
        XCTAssertEqual(fileResponse.statusCode, 200)
        XCTAssertEqual(fileResponse.expectedContentLength, Int64(bytes.count))
        XCTAssertEqual(fileBody, bytes)

        let (_, ack) = try await get("/v1/ack/abc", method: "POST")
        XCTAssertEqual(ack.statusCode, 204)
        XCTAssertEqual(queue.acked, ["abc"])

        let (_, gone) = try await get("/v1/files/abc")
        XCTAssertEqual(gone.statusCode, 404)
    }

    func testWrongTokenIs401() async throws {
        let (_, r) = try await get("/v1/queue", token: "stale")
        XCTAssertEqual(r.statusCode, 401)
    }

    func testWrongFingerprintIsRefusedByAPinningClient() async {
        do {
            _ = try await get("/v1/queue", pin: String(repeating: "0", count: 64))
            XCTFail("should not connect")
        } catch {
            let code = (error as? URLError)?.code
            XCTAssertTrue(code == .cancelled || code == .serverCertificateUntrusted, "unexpected \(error)")
        }
    }

    func testManyRequestsOnOneConnection() async throws {
        for _ in 0..<20 {
            let (_, r) = try await get("/v1/queue")
            XCTAssertEqual(r.statusCode, 200)
        }
    }
}

/// Trusts exactly one leaf certificate by SHA-256 of its DER, like the phone.
final class Pinner: NSObject, URLSessionDelegate {
    let expected: String
    init(expected: String) { self.expected = expected }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard let trust = challenge.protectionSpace.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first
        else { return completionHandler(.cancelAuthenticationChallenge, nil) }
        let der = SecCertificateCopyData(leaf) as Data
        let hex = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
        if hex == expected {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
