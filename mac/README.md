# Music Loader (macOS companion)

The Mac half of `docs/mac-loader.prd.md`: drop songs on the window, keep it
open, and the phone's music tool pulls them the next time it is opened.
Wire contract: `docs/mac-loader.protocol.md`. No third-party dependencies.

## Build

```bash
brew install xcodegen          # once
cd mac && xcodegen generate    # writes LP3MusicLoader.xcodeproj (git-ignored)
open LP3MusicLoader.xcodeproj  # or:
xcodebuild -project LP3MusicLoader.xcodeproj -scheme LP3MusicLoader -destination 'platform=macOS' -derivedDataPath /tmp/lp3-dd test
```

Build output should live outside `~/Documents` (Finder metadata on synced
folders breaks ad-hoc code signing of the test bundle).

## How it works

| Piece | File | Notes |
| --- | --- | --- |
| Pairing code | `Loader/Model/Pairing.swift` | Encodes exactly like the phone's `PairingCode.kt` parses (Java `URLEncoder` rules). |
| TLS identity | `Loader/Server/ServerIdentity.swift`, `DER.swift` | One self-signed P-256 cert, built as raw X.509 DER and signed with a key kept in the login keychain. The cert DER is saved so the fingerprint stays stable; losing the key regenerates both and tells the user to rescan. |
| HTTPS server | `Loader/Server/HTTPServer.swift` | Network.framework listener with keep-alive HTTP/1.1 and streamed file bodies. Default port 48123, falls back to any free port. |
| Routes | `Loader/Server/SyncAPI.swift` | Bearer check, `GET /v1/queue`, `GET /v1/files/{id}`, `POST /v1/ack/{id}`. A file that moved or changed since it was dropped answers 404. |
| Preflight | `Loader/Model/TrackInspector.swift`, `PhonePath.swift` | AVFoundation tags → "what the phone will see", Unknown Artist/Album warnings, protected-content and unplayable-format blockers, sanitized `<Artist>/<Album>/<NN Title>.<ext>` path. |
| State | `Loader/Model/LoaderModel.swift`, `QueueItem.swift`, `Persistence.swift` | Queue persists to `~/Library/Application Support/LP3 Music Loader/queue.json` alongside `token.txt` and `server-cert.der`. |

The app never modifies, moves, or deletes the dropped files.

## Verifying against the phone

`tool/src/test/.../LiveMacContractTest.kt` drives the phone's real ktor
transport at a running copy of this app (downloads, never acks):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :tool:testDebugUnitTest --tests '*LiveMacContractTest*' -Dmac.pairing='lp3music://pair?...'
```

For a full emulator round trip, boot the AVD with
`-camera-back virtualscene` and put the QR image in the scene with
`-virtualscene-poster wall=<png>`; the tool's Scan Code screen reads it.
