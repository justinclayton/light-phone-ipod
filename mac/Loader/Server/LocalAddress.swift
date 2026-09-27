import Foundation
import Darwin

/// Finds the IPv4 address the phone should dial (protocol §1 `h`). Prefers the
/// built-in Wi-Fi/Ethernet interfaces (`en0`, `en1`, …) over anything virtual.
enum LocalAddress {
    struct Candidate: Equatable {
        let interface: String
        let address: String
    }

    static func candidates() -> [Candidate] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }
        var result: [Candidate] = []
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            defer { ptr = p.pointee.ifa_next }
            guard let addr = p.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let flags = Int32(p.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0, flags & IFF_RUNNING != 0 else { continue }
            let name = String(cString: p.pointee.ifa_name)
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let address = String(cString: buffer)
            // Skip link-local (169.254.x) and obvious virtual adapters.
            if address.hasPrefix("169.254.") { continue }
            if name.hasPrefix("utun") || name.hasPrefix("bridge") || name.hasPrefix("awdl") || name.hasPrefix("llw") || name.hasPrefix("vmnet") { continue }
            result.append(Candidate(interface: name, address: address))
        }
        return result.sorted { a, b in rank(a.interface) < rank(b.interface) }
    }

    static func best() -> Candidate? { candidates().first }

    private static func rank(_ name: String) -> Int {
        if name == "en0" { return 0 }
        if name.hasPrefix("en") { return 1 + (Int(name.dropFirst(2)) ?? 50) }
        return 1000
    }
}
