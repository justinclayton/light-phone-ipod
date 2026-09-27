import Foundation
import Security

/// The Mac's TLS identity: one self-signed P-256 certificate whose fingerprint
/// rides in the pairing code. It must stay the same across launches, otherwise
/// every paired phone stops trusting this Mac (protocol §1 "Mac requirements").
///
/// Storage: the private key lives in the login keychain under `keyTag`; the
/// certificate DER lives next to the rest of the app's state so the fingerprint
/// can be computed without touching the keychain. Both are needed to serve.
struct ServerIdentity {
    let identity: SecIdentity
    let certificateDER: Data
    var fingerprintHex: String { SelfSignedCertificate.sha256Hex(certificateDER) }

    enum LoadResult {
        case existing(ServerIdentity)
        /// A new identity had to be made (first run, or the old key vanished). Paired phones must rescan.
        case regenerated(ServerIdentity, reason: String)
    }

    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    static let keyTag = "com.thelightphone.ipod.macloader.tls-key"
    static let certLabel = "LP3 Music Loader"

    static func loadOrCreate(certificateFile: URL, keyTag: String = ServerIdentity.keyTag) throws -> LoadResult {
        if let der = try? Data(contentsOf: certificateFile), !der.isEmpty {
            if let identity = try? identity(forCertificateDER: der) {
                return .existing(ServerIdentity(identity: identity, certificateDER: der))
            }
            let fresh = try create(certificateFile: certificateFile, keyTag: keyTag)
            return .regenerated(fresh, reason: "This Mac's saved connection key was missing, so a new one was made.")
        }
        let fresh = try create(certificateFile: certificateFile, keyTag: keyTag)
        return .regenerated(fresh, reason: "first run")
    }

    // MARK: - Creation

    private static func create(certificateFile: URL, keyTag: String) throws -> ServerIdentity {
        deleteKeys(tag: keyTag)
        let privateKey = try makePrivateKey(tag: keyTag)
        guard let publicKey = SecKeyCopyPublicKey(privateKey),
              let publicBytes = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?
        else { throw Failure(message: "Couldn't read the new key's public half.") }

        var serial = Data(count: 16)
        serial.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        serial[0] &= 0x7f

        let now = Date()
        let der = try SelfSignedCertificate.make(
            commonName: certLabel,
            publicKeyX963: publicBytes,
            notBefore: now.addingTimeInterval(-3600),
            notAfter: now.addingTimeInterval(20 * 365 * 24 * 3600),
            serial: serial
        ) { tbs in
            var error: Unmanaged<CFError>?
            guard let sig = SecKeyCreateSignature(privateKey, .ecdsaSignatureMessageX962SHA256, tbs as CFData, &error) as Data?
            else { throw Failure(message: "Couldn't sign the new certificate: \(error?.takeRetainedValue().localizedDescription ?? "unknown error")") }
            return sig // X9.62 = already a DER SEQUENCE {r, s}
        }

        guard let cert = SecCertificateCreateWithData(nil, der as CFData) else {
            throw Failure(message: "The generated certificate was not accepted by macOS.")
        }
        // Remove any stale copy of a previous certificate with our label, then add ours.
        if keyTag == ServerIdentity.keyTag {
            SecItemDelete([kSecClass: kSecClassCertificate, kSecAttrLabel: certLabel] as CFDictionary)
        }
        let addStatus = SecItemAdd([
            kSecClass: kSecClassCertificate,
            kSecValueRef: cert,
            kSecAttrLabel: certLabel,
        ] as CFDictionary, nil)
        guard addStatus == errSecSuccess || addStatus == errSecDuplicateItem else {
            throw Failure(message: "Couldn't save the certificate to the keychain (\(addStatus)).")
        }

        try FileManager.default.createDirectory(at: certificateFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        try der.write(to: certificateFile, options: .atomic)

        let identity = try identity(forCertificateDER: der)
        return ServerIdentity(identity: identity, certificateDER: der)
    }

    private static func makePrivateKey(tag: String) throws -> SecKey {
        let attrs: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits: 256,
            kSecAttrLabel: certLabel,
            kSecPrivateKeyAttrs: [
                kSecAttrIsPermanent: true,
                kSecAttrApplicationTag: Data(tag.utf8),
                kSecAttrLabel: certLabel,
            ] as [CFString: Any],
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attrs as CFDictionary, &error) else {
            throw Failure(message: "Couldn't create a key in the keychain: \(error?.takeRetainedValue().localizedDescription ?? "unknown error")")
        }
        return key
    }

    static func deleteKeys(tag: String) {
        SecItemDelete([
            kSecClass: kSecClassKey,
            kSecAttrApplicationTag: Data(tag.utf8),
        ] as CFDictionary)
    }

    // MARK: - Lookup

    private static func identity(forCertificateDER der: Data) throws -> SecIdentity {
        guard let cert = SecCertificateCreateWithData(nil, der as CFData) else {
            throw Failure(message: "Saved certificate is unreadable.")
        }
        var identity: SecIdentity?
        let status = SecIdentityCreateWithCertificate(nil, cert, &identity)
        guard status == errSecSuccess, let identity else {
            throw Failure(message: "No private key for the saved certificate (\(status)).")
        }
        return identity
    }
}
