import XCTest
import Security
@testable import LP3MusicLoader

final class PairingCodeTests: XCTestCase {
    func testEncodesExactlyLikeThePhoneExpects() {
        let p = Pairing(host: "192.168.1.20", port: 48123, token: "abc_DEF-123",
                        certSha256: "ABCDEF" + String(repeating: "0", count: 58), macName: "Justin's MacBook & co")
        let code = PairingCode.encode(p)
        XCTAssertEqual(code,
            "lp3music://pair?v=1&h=192.168.1.20&p=48123&t=abc_DEF-123&f=abcdef" + String(repeating: "0", count: 58)
            + "&n=Justin%27s+MacBook+%26+co")
        XCTAssertEqual(PairingCode.decode(code), Pairing(host: p.host, port: p.port, token: p.token,
                                                         certSha256: p.certSha256.lowercased(), macName: p.macName))
    }

    func testFormEncodingMatchesJavaURLEncoder() {
        XCTAssertEqual(PairingCode.formEncode("a b*c.d-e_f~g/h"), "a+b*c.d-e_f%7Eg%2Fh")
        XCTAssertEqual(PairingCode.formEncode("é"), "%C3%A9")
    }
}

final class PhonePathTests: XCTestCase {
    func testArtistAlbumTrackTitle() {
        let v = PhoneView(title: "15 Step", artist: "Radiohead", album: "In Rainbows", albumArtist: nil,
                          trackNumber: 1, discNumber: nil, isCompilation: false)
        XCTAssertEqual(PhonePath.build(view: v, originalFilename: "whatever.MP3"), "Radiohead/In Rainbows/01 15 Step.mp3")
    }

    func testCompilationGoesUnderVariousArtistsAndDiscPrefix() {
        let v = PhoneView(title: "Song", artist: "Someone", album: "Now 42", albumArtist: nil,
                          trackNumber: 3, discNumber: 2, isCompilation: true)
        XCTAssertEqual(PhonePath.build(view: v, originalFilename: "x.m4a"), "Various Artists/Now 42/2-03 Song.m4a")
    }

    func testUnknownBucketsAndSanitizing() {
        let v = PhoneView(title: "AC/DC: Live..", artist: PhoneView.unknownArtist, album: PhoneView.unknownAlbum,
                          albumArtist: "..", trackNumber: nil, discNumber: nil, isCompilation: false)
        let path = PhonePath.build(view: v, originalFilename: "x.flac")
        XCTAssertEqual(path, "Unknown Artist/Unknown Album/AC-DC- Live.flac")
        XCTAssertFalse(path.contains(":"))
        XCTAssertFalse(path.split(separator: "/").contains(".."))
    }

    func testControlCharactersAndPartSuffix() {
        XCTAssertEqual(PhonePath.segment("a\tb\u{01}c", fallback: "F"), "a b c")
        XCTAssertEqual(PhonePath.segment("song.part", fallback: "F"), "F")
        XCTAssertEqual(PhonePath.segment("   ", fallback: "F"), "F")
    }
}

final class CertificateTests: XCTestCase {
    func testMintedCertificateIsParseableAndFingerprintStable() throws {
        let attrs: [CFString: Any] = [kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeySizeInBits: 256]
        let key = SecKeyCreateRandomKey(attrs as CFDictionary, nil)!
        let pub = SecKeyCopyExternalRepresentation(SecKeyCopyPublicKey(key)!, nil)! as Data
        let der = try SelfSignedCertificate.make(commonName: "Test", publicKeyX963: pub,
                                                 notBefore: Date(), notAfter: Date().addingTimeInterval(86400),
                                                 serial: Data([0x01, 0x02])) { tbs in
            SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, tbs as CFData, nil)! as Data
        }
        let cert = SecCertificateCreateWithData(nil, der as CFData)
        XCTAssertNotNil(cert, "macOS must accept the hand-built DER")
        XCTAssertEqual(SecCertificateCopySubjectSummary(cert!) as String?, "Test")
        XCTAssertEqual(SelfSignedCertificate.sha256Hex(der).count, 64)
        XCTAssertEqual(SelfSignedCertificate.sha256Hex(der), SelfSignedCertificate.sha256Hex(SecCertificateCopyData(cert!) as Data))

        // The signature must verify with the public key over the TBS section.
        var trust: SecTrust?
        XCTAssertEqual(SecTrustCreateWithCertificates(cert!, SecPolicyCreateBasicX509(), &trust), errSecSuccess)
        SecTrustSetAnchorCertificates(trust!, [cert!] as CFArray)
        var error: CFError?
        XCTAssertTrue(SecTrustEvaluateWithError(trust!, &error), "self-signed cert should verify against itself: \(String(describing: error))")
    }
}
