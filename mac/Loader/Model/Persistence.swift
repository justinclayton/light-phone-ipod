import Foundation
import Security

/// Where the app keeps the little it must remember: the bearer token, the
/// certificate, the chosen port, and the current queue. Everything lives under
/// `~/Library/Application Support/LP3 Music Loader/`.
struct AppFiles {
    let root: URL

    init(root: URL? = nil) {
        if let root {
            self.root = root
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            self.root = base.appendingPathComponent("LP3 Music Loader", isDirectory: true)
        }
    }

    var certificate: URL { root.appendingPathComponent("server-cert.der") }
    var token: URL { root.appendingPathComponent("token.txt") }
    var port: URL { root.appendingPathComponent("port.txt") }
    var queue: URL { root.appendingPathComponent("queue.json") }

    func ensureRoot() throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    /// Bearer token: 32 random bytes, base64url, generated once (protocol §1 `t`).
    func loadOrCreateToken() throws -> String {
        try ensureRoot()
        if let existing = try? String(contentsOf: token, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines),
           existing.count >= 32 {
            return existing
        }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw ServerIdentity.Failure(message: "Couldn't generate a random token.")
        }
        let value = Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        try value.write(to: token, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: token.path)
        return value
    }

    static let defaultPort = 48123

    func loadPort() -> Int {
        guard let text = try? String(contentsOf: port, encoding: .utf8),
              let value = Int(text.trimmingCharacters(in: .whitespacesAndNewlines)),
              (1024...65535).contains(value)
        else { return AppFiles.defaultPort }
        return value
    }

    func savePort(_ value: Int) {
        try? ensureRoot()
        try? String(value).write(to: port, atomically: true, encoding: .utf8)
    }
}
