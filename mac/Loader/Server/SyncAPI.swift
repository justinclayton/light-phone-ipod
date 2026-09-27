import Foundation

/// What the server needs to know about the queue, decoupled from the UI model
/// so it can be driven from tests.
protocol SyncQueueSource: AnyObject {
    /// Items still waiting, in the order the phone should copy them.
    func waitingItems() -> [(id: String, path: String, fileURL: URL, sizeBytes: Int)]
    func fileRequested(id: String)
    func acknowledged(id: String)
}

/// The three routes of docs/mac-loader.protocol.md §2, plus the bearer check.
/// Synchronous on purpose: every answer is computed from in-memory state, and
/// file bodies are streamed by the server after this returns.
final class SyncAPI {
    private let token: String
    private let source: SyncQueueSource
    private let lock = NSLock()

    init(token: String, source: SyncQueueSource) {
        self.token = token
        self.source = source
    }

    func handle(_ request: HTTPRequest) -> HTTPResponse {
        guard let auth = request.header("authorization"),
              auth.hasPrefix("Bearer "),
              constantTimeEquals(String(auth.dropFirst("Bearer ".count)), token)
        else { return .text(401, "unauthorized") }

        let path = request.path.split(separator: "?", maxSplits: 1).first.map(String.init) ?? request.path
        let parts = path.split(separator: "/").map(String.init)
        guard parts.count >= 2, parts[0] == "v1" else { return .text(404, "not found") }

        return lock.withLock {
            switch (request.method, parts[1], parts.count) {
            case ("GET", "queue", 2):
                let items = source.waitingItems().map { item -> [String: Any] in
                    ["id": item.id, "path": item.path, "sizeBytes": item.sizeBytes]
                }
                return .json(["items": items])

            case ("GET", "files", 3):
                let id = parts[2].removingPercentEncoding ?? parts[2]
                guard let item = source.waitingItems().first(where: { $0.id == id }) else {
                    return .text(404, "gone")
                }
                let attrs = try? FileManager.default.attributesOfItem(atPath: item.fileURL.path)
                guard let size = attrs?[.size] as? Int, size == item.sizeBytes,
                      FileManager.default.isReadableFile(atPath: item.fileURL.path)
                else { return .text(404, "gone") } // file moved or changed since it was dropped
                source.fileRequested(id: id)
                return HTTPResponse(status: 200,
                                    headers: [("Content-Type", "application/octet-stream")],
                                    body: .file(item.fileURL, length: size))

            case ("POST", "ack", 3):
                let id = parts[2].removingPercentEncoding ?? parts[2]
                source.acknowledged(id: id)
                return .noContent

            case (_, "queue", 2), (_, "files", 3), (_, "ack", 3):
                return .text(405, "method not allowed")

            default:
                return .text(404, "not found")
            }
        }
    }

    private func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<x.count { diff |= x[i] ^ y[i] }
        return diff == 0
    }
}
