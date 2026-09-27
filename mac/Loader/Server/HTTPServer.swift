import Foundation
import Network

/// One parsed HTTP/1.1 request.
struct HTTPRequest {
    var method: String
    var path: String
    var headers: [String: String] // lowercased names
    var body: Data
    var remoteHost: String?

    func header(_ name: String) -> String? { headers[name.lowercased()] }
}

/// A response body is either bytes in memory or a file streamed from disk.
enum HTTPBody {
    case data(Data)
    case file(URL, length: Int)
    case empty

    var contentLength: Int {
        switch self {
        case .data(let d): return d.count
        case .file(_, let n): return n
        case .empty: return 0
        }
    }
}

struct HTTPResponse {
    var status: Int
    var headers: [(String, String)] = []
    var body: HTTPBody = .empty

    static func json(_ object: Any, status: Int = 200) -> HTTPResponse {
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8)
        return HTTPResponse(status: status, headers: [("Content-Type", "application/json")], body: .data(data))
    }

    static func text(_ status: Int, _ message: String) -> HTTPResponse {
        HTTPResponse(status: status, headers: [("Content-Type", "text/plain; charset=utf-8")], body: .data(Data(message.utf8)))
    }

    static let noContent = HTTPResponse(status: 204)

    var reason: String {
        switch status {
        case 200: return "OK"
        case 204: return "No Content"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 404: return "Not Found"
        case 405: return "Method Not Allowed"
        case 413: return "Payload Too Large"
        default: return status >= 500 ? "Internal Server Error" : "Unknown"
        }
    }
}

/// Just enough HTTP/1.1 over TLS for docs/mac-loader.protocol.md: keep-alive,
/// Content-Length bodies, streamed file responses. Built on Network.framework so
/// the app needs no third-party dependency and the TLS identity comes straight
/// from the keychain.
final class HTTPServer {
    typealias Handler = (HTTPRequest) -> HTTPResponse

    enum Event {
        case ready(port: Int)
        case failed(String)
        case request(HTTPRequest)
    }

    private let identity: SecIdentity
    private let handler: Handler
    private let onEvent: (Event) -> Void
    private let queue = DispatchQueue(label: "lp3.music-loader.http")
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: Connection] = [:]

    init(identity: SecIdentity, handler: @escaping Handler, onEvent: @escaping (Event) -> Void) {
        self.identity = identity
        self.handler = handler
        self.onEvent = onEvent
    }

    /// Port 0 means "pick any free port".
    func start(port: Int) throws {
        let tls = NWProtocolTLS.Options()
        guard let secIdentity = sec_identity_create(identity) else {
            throw ServerIdentity.Failure(message: "The keychain refused to hand over the connection key.")
        }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, secIdentity)
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        let params = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        params.allowLocalEndpointReuse = true
        params.includePeerToPeer = false

        let listener: NWListener
        if port == 0 {
            listener = try NWListener(using: params)
        } else {
            guard let p = NWEndpoint.Port(rawValue: UInt16(port)) else {
                throw ServerIdentity.Failure(message: "Port \(port) is not valid.")
            }
            listener = try NWListener(using: params, on: p)
        }
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.onEvent(.ready(port: Int(listener.port?.rawValue ?? 0)))
            case .failed(let error):
                self.onEvent(.failed(error.localizedDescription))
            case .cancelled:
                break
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] nwConnection in
            guard let self else { return }
            let conn = Connection(nwConnection, server: self)
            self.connections[ObjectIdentifier(conn)] = conn
            conn.start(on: self.queue)
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    func stop() {
        listener?.cancel()
        listener = nil
        for c in connections.values { c.cancel() }
        connections.removeAll()
    }

    fileprivate func connectionClosed(_ c: Connection) {
        connections.removeValue(forKey: ObjectIdentifier(c))
    }

    fileprivate func serve(_ request: HTTPRequest) -> HTTPResponse {
        onEvent(.request(request))
        return handler(request)
    }

    // MARK: - Connection

    fileprivate final class Connection {
        private let nw: NWConnection
        private unowned let server: HTTPServer
        private var buffer = Data()
        private let maxHeaderBytes = 64 * 1024
        private let maxBodyBytes = 1024 * 1024

        init(_ nw: NWConnection, server: HTTPServer) {
            self.nw = nw
            self.server = server
        }

        func start(on queue: DispatchQueue) {
            nw.stateUpdateHandler = { [weak self] state in
                switch state {
                case .failed, .cancelled:
                    self.map { $0.server.connectionClosed($0) }
                default: break
                }
            }
            nw.start(queue: queue)
            receive()
        }

        func cancel() { nw.cancel() }

        private func receive() {
            nw.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
                guard let self else { return }
                if let data { self.buffer.append(data) }
                if error != nil { self.nw.cancel(); return }
                self.processBuffer()
                if isComplete { self.nw.cancel(); return }
                if self.nw.state == .ready || self.nw.state == .preparing { self.receive() }
            }
        }

        private var remoteHost: String? {
            if case .hostPort(let host, _) = nw.endpoint {
                return host.debugDescription.split(separator: "%").first.map(String.init)
            }
            return nil
        }

        private func processBuffer() {
            while true {
                guard let headerEnd = buffer.range(of: Data("\r\n\r\n".utf8)) else {
                    if buffer.count > maxHeaderBytes { respondAndClose(.text(413, "headers too large")) }
                    return
                }
                let headerData = buffer.subdata(in: buffer.startIndex..<headerEnd.lowerBound)
                guard let head = String(data: headerData, encoding: .isoLatin1) else {
                    respondAndClose(.text(400, "bad request")); return
                }
                var lines = head.components(separatedBy: "\r\n")
                let requestLine = lines.removeFirst().split(separator: " ")
                guard requestLine.count >= 2 else { respondAndClose(.text(400, "bad request")); return }
                var headers: [String: String] = [:]
                for line in lines {
                    guard let colon = line.firstIndex(of: ":") else { continue }
                    headers[line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()] =
                        line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                }
                let contentLength = Int(headers["content-length"] ?? "0") ?? 0
                if contentLength > maxBodyBytes { respondAndClose(.text(413, "body too large")); return }
                let bodyStart = headerEnd.upperBound
                guard buffer.count - bodyStart >= contentLength else { return } // wait for more
                let body = buffer.subdata(in: bodyStart..<(bodyStart + contentLength))
                buffer.removeSubrange(buffer.startIndex..<(bodyStart + contentLength))

                let request = HTTPRequest(
                    method: String(requestLine[0]).uppercased(),
                    path: String(requestLine[1]),
                    headers: headers,
                    body: body,
                    remoteHost: remoteHost
                )
                let close = headers["connection"]?.lowercased() == "close"
                let response = server.serve(request)
                send(response, keepAlive: !close)
                if close { return }
            }
        }

        private func respondAndClose(_ response: HTTPResponse) {
            send(response, keepAlive: false)
        }

        private func send(_ response: HTTPResponse, keepAlive: Bool) {
            var head = "HTTP/1.1 \(response.status) \(response.reason)\r\n"
            for (k, v) in response.headers { head += "\(k): \(v)\r\n" }
            if response.status != 204 { head += "Content-Length: \(response.body.contentLength)\r\n" }
            head += "Connection: \(keepAlive ? "keep-alive" : "close")\r\n\r\n"
            var first = Data(head.utf8)

            switch response.body {
            case .empty:
                nw.send(content: first, completion: .contentProcessed { [weak self] _ in
                    if !keepAlive { self?.nw.cancel() }
                })
            case .data(let data):
                first.append(data)
                nw.send(content: first, completion: .contentProcessed { [weak self] _ in
                    if !keepAlive { self?.nw.cancel() }
                })
            case .file(let url, let length):
                guard let handle = try? FileHandle(forReadingFrom: url) else {
                    // Headers promised bytes we can't deliver; closing is the honest failure.
                    nw.send(content: first, completion: .contentProcessed { [weak self] _ in self?.nw.cancel() })
                    return
                }
                nw.send(content: first, completion: .contentProcessed { [weak self] error in
                    guard let self, error == nil else { try? handle.close(); return }
                    self.streamFile(handle, remaining: length, keepAlive: keepAlive)
                })
            }
        }

        private func streamFile(_ handle: FileHandle, remaining: Int, keepAlive: Bool) {
            if remaining <= 0 {
                try? handle.close()
                if !keepAlive { nw.cancel() }
                return
            }
            let chunkSize = min(remaining, 512 * 1024)
            let chunk = (try? handle.read(upToCount: chunkSize)) ?? nil
            guard let chunk, !chunk.isEmpty else {
                // File shrank underneath us: the phone sees a short body and discards it (protocol §2).
                try? handle.close()
                nw.cancel()
                return
            }
            nw.send(content: chunk, completion: .contentProcessed { [weak self] error in
                guard let self, error == nil else { try? handle.close(); return }
                self.streamFile(handle, remaining: remaining - chunk.count, keepAlive: keepAlive)
            })
        }
    }
}
