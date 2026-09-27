import Foundation
import Security
import CryptoKit

/// Minimal ASN.1 DER encoder, enough to mint one self-signed X.509 v3 certificate.
/// Security.framework can sign and store keys but has no public API to *create*
/// a certificate, so we build the TBSCertificate by hand.
enum DER {
    static func length(_ n: Int) -> Data {
        if n < 0x80 { return Data([UInt8(n)]) }
        var bytes: [UInt8] = []
        var v = n
        while v > 0 { bytes.insert(UInt8(v & 0xff), at: 0); v >>= 8 }
        return Data([0x80 | UInt8(bytes.count)]) + Data(bytes)
    }

    static func tlv(_ tag: UInt8, _ content: Data) -> Data {
        Data([tag]) + length(content.count) + content
    }

    static func sequence(_ parts: [Data]) -> Data { tlv(0x30, parts.reduce(Data(), +)) }
    static func set(_ parts: [Data]) -> Data { tlv(0x31, parts.reduce(Data(), +)) }
    static func explicit(_ n: UInt8, _ content: Data) -> Data { tlv(0xA0 | n, content) }

    static func integer(_ bytes: Data) -> Data {
        var b = bytes
        while b.count > 1 && b[b.startIndex] == 0 && b[b.startIndex + 1] & 0x80 == 0 { b.removeFirst() }
        if b.isEmpty { b = Data([0]) }
        if b[b.startIndex] & 0x80 != 0 { b.insert(0, at: b.startIndex) }
        return tlv(0x02, b)
    }

    static func integer(_ v: Int) -> Data {
        var bytes: [UInt8] = []
        var x = v
        repeat { bytes.insert(UInt8(x & 0xff), at: 0); x >>= 8 } while x > 0
        return integer(Data(bytes))
    }

    static func oid(_ dotted: String) -> Data {
        let arcs = dotted.split(separator: ".").map { Int($0)! }
        var body: [UInt8] = [UInt8(arcs[0] * 40 + arcs[1])]
        for arc in arcs.dropFirst(2) {
            var chunks: [UInt8] = [UInt8(arc & 0x7f)]
            var v = arc >> 7
            while v > 0 { chunks.insert(UInt8(0x80 | (v & 0x7f)), at: 0); v >>= 7 }
            body += chunks
        }
        return tlv(0x06, Data(body))
    }

    static func utf8String(_ s: String) -> Data { tlv(0x0C, Data(s.utf8)) }
    static func bitString(_ d: Data) -> Data { tlv(0x03, Data([0]) + d) }
    static func octetString(_ d: Data) -> Data { tlv(0x04, d) }
    static func boolean(_ b: Bool) -> Data { tlv(0x01, Data([b ? 0xff : 0x00])) }
    static let null = Data([0x05, 0x00])

    static func utcTime(_ date: Date) -> Data {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyMMddHHmmss'Z'"
        return tlv(0x17, Data(f.string(from: date).utf8))
    }

    static func generalizedTime(_ date: Date) -> Data {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyyMMddHHmmss'Z'"
        return tlv(0x18, Data(f.string(from: date).utf8))
    }

    /// X.509 says: UTCTime through 2049, GeneralizedTime from 2050.
    static func time(_ date: Date) -> Data {
        let year = Calendar(identifier: .gregorian).component(.year, from: date)
        return year < 2050 ? utcTime(date) : generalizedTime(date)
    }

    /// Wraps a raw 64-byte P-256 ECDSA signature (r‖s) as the DER SEQUENCE X.509 wants.
    static func ecdsaSignature(rawRS: Data) -> Data {
        let half = rawRS.count / 2
        return sequence([integer(rawRS.prefix(half)), integer(rawRS.suffix(half))])
    }
}

enum SelfSignedCertificate {
    static let ecPublicKeyOID = "1.2.840.10045.2.1"
    static let p256OID = "1.2.840.10045.3.1.7"
    static let ecdsaWithSHA256OID = "1.2.840.10045.4.3.2"
    static let commonNameOID = "2.5.4.3"
    static let basicConstraintsOID = "2.5.29.19"
    static let keyUsageOID = "2.5.29.15"
    static let extKeyUsageOID = "2.5.29.37"
    static let serverAuthOID = "1.3.6.1.5.5.7.3.1"
    static let subjectAltNameOID = "2.5.29.17"

    /// Builds a self-signed server certificate for the given P-256 key.
    /// `publicKeyX963` is the 65-byte uncompressed point (`SecKeyCopyExternalRepresentation`).
    /// `sign` must return the DER-encoded ECDSA-with-SHA256 signature over its input.
    static func make(
        commonName: String,
        publicKeyX963: Data,
        notBefore: Date,
        notAfter: Date,
        serial: Data,
        sign: (Data) throws -> Data
    ) throws -> Data {
        let algorithm = DER.sequence([DER.oid(ecdsaWithSHA256OID)])
        let name = DER.sequence([
            DER.set([DER.sequence([DER.oid(commonNameOID), DER.utf8String(commonName)])])
        ])
        let spki = DER.sequence([
            DER.sequence([DER.oid(ecPublicKeyOID), DER.oid(p256OID)]),
            DER.bitString(publicKeyX963),
        ])
        let extensions = DER.explicit(3, DER.sequence([
            // basicConstraints: CA=false (critical)
            DER.sequence([DER.oid(basicConstraintsOID), DER.boolean(true), DER.octetString(DER.sequence([]))]),
            // keyUsage: digitalSignature (bit 0)  → BIT STRING with 7 unused bits, value 0x80
            DER.sequence([DER.oid(keyUsageOID), DER.boolean(true), DER.octetString(Data([0x03, 0x02, 0x07, 0x80]))]),
            // extendedKeyUsage: serverAuth
            DER.sequence([DER.oid(extKeyUsageOID), DER.octetString(DER.sequence([DER.oid(serverAuthOID)]))]),
            // subjectAltName: dNSName = commonName (harmless; the phone pins the fingerprint, not the name)
            DER.sequence([DER.oid(subjectAltNameOID), DER.octetString(DER.sequence([DER.tlv(0x82, Data(commonName.utf8))]))]),
        ]))
        let tbs = DER.sequence([
            DER.explicit(0, DER.integer(2)), // version v3
            DER.integer(serial),
            algorithm,
            name,
            DER.sequence([DER.time(notBefore), DER.time(notAfter)]),
            name,
            spki,
            extensions,
        ])
        let signature = try sign(tbs)
        return DER.sequence([tbs, algorithm, DER.bitString(signature)])
    }

    static func sha256Hex(_ der: Data) -> String {
        SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
    }
}
