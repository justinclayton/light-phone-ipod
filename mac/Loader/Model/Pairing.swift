import Foundation

/// Everything the phone needs to find and trust this Mac. Mirrors `MacPairing`
/// in the tool (`tool/.../data/sync/PairingCode.kt`).
struct Pairing: Equatable {
    var host: String
    var port: Int
    var token: String
    var certSha256: String
    var macName: String
}

/// Wire format of the QR code (docs/mac-loader.protocol.md §1):
///
///     lp3music://pair?v=1&h=<host>&p=<port>&t=<token>&f=<cert sha256 hex>&n=<mac name>
///
/// Values are `application/x-www-form-urlencoded`, exactly what Java's
/// `URLEncoder` produces, because that is what the phone decodes with.
enum PairingCode {
    static let prefix = "lp3music://pair?"

    static func encode(_ p: Pairing) -> String {
        let fields: [(String, String)] = [
            ("v", "1"),
            ("h", p.host),
            ("p", String(p.port)),
            ("t", p.token),
            ("f", p.certSha256.lowercased()),
            ("n", p.macName),
        ]
        return prefix + fields.map { "\($0.0)=\(formEncode($0.1))" }.joined(separator: "&")
    }

    /// Inverse of `encode`, used by tests to prove the two sides agree.
    static func decode(_ raw: String) -> Pairing? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.lowercased().hasPrefix(prefix) else { return nil }
        var params: [String: String] = [:]
        for part in trimmed.dropFirst(prefix.count).split(separator: "&") where !part.isEmpty {
            let kv = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let key = formDecode(String(kv[0]))
            let value = kv.count > 1 ? formDecode(String(kv[1])) : ""
            params[key] = value
        }
        guard (params["v"] ?? "1") == "1",
              let host = params["h"], !host.isEmpty,
              let port = params["p"].flatMap(Int.init), (1...65535).contains(port),
              let token = params["t"], !token.isEmpty,
              let fp = params["f"], fp.count == 64, fp.allSatisfy(\.isHexDigit)
        else { return nil }
        let name = params["n"]?.trimmingCharacters(in: .whitespaces)
        return Pairing(host: host, port: port, token: token, certSha256: fp.lowercased(),
                       macName: (name?.isEmpty == false) ? name! : "your Mac")
    }

    /// Java `URLEncoder.encode(s, "UTF-8")`: keeps `A-Z a-z 0-9 - _ . *`, space → `+`,
    /// everything else → `%XX` per UTF-8 byte.
    static func formEncode(_ s: String) -> String {
        var out = ""
        for byte in s.utf8 {
            switch byte {
            case UInt8(ascii: "A")...UInt8(ascii: "Z"),
                 UInt8(ascii: "a")...UInt8(ascii: "z"),
                 UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "-"), UInt8(ascii: "_"), UInt8(ascii: "."), UInt8(ascii: "*"):
                out.unicodeScalars.append(Unicode.Scalar(byte))
            case UInt8(ascii: " "):
                out.append("+")
            default:
                out += String(format: "%%%02X", byte)
            }
        }
        return out
    }

    static func formDecode(_ s: String) -> String {
        let plusFixed = s.replacingOccurrences(of: "+", with: " ")
        return plusFixed.removingPercentEncoding ?? plusFixed
    }
}
