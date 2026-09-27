# iPod for the Light Phone III

A music player tool for LightOS, modelled on the iPod classic, plus a small
macOS app that gets songs from a Mac onto the phone over Wi-Fi.

Browse by **Artists / Albums / Songs / Playlists / Search**, pick a song, and it
plays. Music keeps playing after you leave the tool, and LightOS shows the
transport controls. There is no album art, no streaming, and no account. The
library is files you own, tagged the way iTunes tagged them.

> **Status:** v1 of both halves is implemented and verified on the LightOS
> emulator. Nothing has been tested on real LP3 hardware yet. See
> [What is unverified](#what-is-unverified) before relying on it.

## How it fits together

```
 Mac (mac/)                                   Light Phone III (tool/)
 ┌────────────────────────┐   home Wi-Fi     ┌──────────────────────────────┐
 │ Drop songs on window   │                  │ iPod tool opens, pulls queue │
 │ Preflight tag check    │ ◀─ GET /v1/queue─│ downloads into files/shared/ │
 │ HTTPS server, pinned   │ ◀─ GET /v1/files─│   music/<Artist>/<Album>/..  │
 │ cert + bearer token    │ ◀─ POST /v1/ack ─│ rescans tags, updates Room   │
 └────────────────────────┘                  └──────────────────────────────┘
```

**The phone pulls, the Mac serves.** A LightOS tool's storage is sealed: nothing
on a host computer can write into it, over USB or otherwise. The tool *can*
fetch over the local network, so the Mac holds a queue and the phone comes and
gets it when the tool is opened. Both devices have to be awake and on the same
Wi-Fi at the same time. That constraint drives the whole design and is written
up in [docs/mac-loader.prd.md](docs/mac-loader.prd.md).

Pairing is one QR scan. The Mac shows an `lp3music://pair?...` code carrying its
address, a bearer token, and the SHA-256 fingerprint of its self-signed TLS
certificate. The phone trusts only that certificate, the Mac accepts only that
token. The wire contract is in [docs/mac-loader.protocol.md](docs/mac-loader.protocol.md).

**Why the library lives inside the tool.** The Light SDK's build plugin fails
the build on any use of `Context`, `contentResolver`, `Intent`, casts to
framework types, or reflection. A tool therefore cannot read the phone's shared
MediaStore or the built-in Music tool's files. This project's library is the
tool's own `files/shared/music/` folder, and the tool reads ID3 and iTunes tags
itself with `MediaMetadataRetriever` and caches them in Room. The spike that
established this is in [FINDINGS.md](FINDINGS.md).

## What the phone tool does

- **Browsing** follows iPod rules: leading "The", "A", "An" ignored for sort,
  albums grouped by album artist, compilations in their own bucket, artists who
  appear only on compilations left out of the Artists list, missing tags fall
  back to "Unknown Artist" / "Unknown Album".
- **Playback** uses one shared detached `LightAudioPlayer`. Selecting a song in
  any list replaces the queue with that list, positioned at the selection.
  Shuffle and repeat are supported. The queue and position survive relaunch.
- **Playlists** live in Room and are mirrored to `files/shared/playlists/*.m3u8`
  after every change. Any `.m3u8` the Mac side drops in is imported on scan.
- **Sync** runs automatically when the tool opens (throttled to once a minute)
  and on demand from the Sync screen. Every failure is a sentence the user can
  act on, listed in the protocol doc's section 4.
- **Rescan Library** from the main menu diffs the folder by path, size and
  mtime and only re-reads tags for new or changed files.

Design decisions and the v1 scope are recorded in [docs/ipod.adr.md](docs/ipod.adr.md).

## Repository layout

This repo started as a fork of Light's official
[light-sdk](https://github.com/lightphone/light-sdk) scaffolding, and the SDK
modules are still here so the tool builds against a pinned copy of them.

| Path | What it is |
| --- | --- |
| `tool/` | The iPod tool. All app code is under `com.thelightphone.ipod`: `data/` (Room, scanner, M3U, sync), `player/`, `ui/`. |
| `mac/` | The macOS companion, "Music Loader". SwiftUI, no third-party dependencies. Has its own [README](mac/README.md). |
| `docs/ipod.adr.md` | Architecture decision record for the tool. |
| `docs/mac-loader.prd.md` | Product requirements for the Mac app and the phone's receiving side. |
| `docs/mac-loader.protocol.md` | The HTTPS API and pairing-code format both sides implement. |
| `FINDINGS.md` | The original spike: why a tool cannot read the phone's shared music. |
| `sideloading.md` | How to install a locally built APK on an LP3 or the emulator via Light's Tool Manager. |
| `sdk/` | Light's SDK modules (client, ui, shared, server, emulator). Upstream code. |
| `plugin/`, `lint-rules/`, `builder/` | Light's build-time sandbox plugin, lint rules, and signing pipeline. Upstream code. |
| `examples/` | Light's demo tools. `audio-demo` is the reference for detached playback. |
| `.scratch/` | Backlog notes for known gaps, written as issue drafts. |

## Building and running

### Prerequisites

- JDK 17
- Android SDK with an emulator, or a Light Phone III in developer mode
- For the Mac app: Xcode and [xcodegen](https://github.com/yonaskolb/XcodeGen)

### Phone tool

```bash
./gradlew :tool:assembleDebug          # build the APK
./gradlew :tool:testDebugUnitTest      # JVM unit tests
./gradlew check                        # what CI runs on every PR
```

The `Makefile` wraps the common loop with `make build`, `make install`,
`make run`, `make logs` and `make emu`. Its `JAVA_HOME`, SDK and `adb` paths
are hard-coded for a Homebrew install on macOS, so edit the top of the file
or export your own values if your setup differs.

For an emulator that feels like an LP3, create an AVD at 1080x1240, API 34,
without Google Play. To exercise detached playback, permissions or push, the
LightOS emulator app has to be installed as a system app and the AVD booted
with `-writable-system`. Instructions are in [docs/system_app](docs/system_app).
In LightOS, set Settings → Allowed Tools to "Built with SDK" or the tool will
not appear.

### Getting music on during development

You do not need the Mac app to test. Drop audio files into a `library/` folder
at the repo root and run:

```bash
make sync
```

This pushes them into the debug build's private `files/shared/music/` via
`adb run-as`, then relaunches the tool. It works on the emulator and on a
USB-connected LP3 running a debug build. Supported extensions are
`mp3 m4a aac wav ogg flac`.

### Mac companion

```bash
cd mac && xcodegen generate
open LP3MusicLoader.xcodeproj
```

Full build and test notes, including how to run the phone's contract test
against a live copy of the Mac app, are in [mac/README.md](mac/README.md).

### Installing on a real phone

Follow [sideloading.md](sideloading.md), which is Light's upstream guide to
uploading an APK through their Tool Manager. Two caveats: Light had not yet
shipped Tool Manager support to production LightOS builds as of that document's
last update, and the `uploadTool` Gradle task it mentions is not present in
this fork's `tool/build.gradle.kts`, so use the browser upload path.

## What is unverified

From the PRD's assumptions section, none of these have been tested on hardware:

- That an LP3 can reach the Mac over a real home network.
- That the phone camera can scan the pairing QR from a Mac screen in one shot.
- That the tool can be sideloaded and stays installed after adjusting the
  phone's tool-permission setting.
- How much music the phone can hold, and whether Wi-Fi transfer speed is
  tolerable for an album-sized drop.

## Known limitations

- **Music can be added but not removed.** Neither side deletes files. A draft
  for fixing this is in `.scratch/delete-synced-music/`.
- **The Mac app must stay open** while songs are queued. There is no
  background helper.
- **Duplicates are possible** when the same song is dropped under two
  different filenames. Same path and same size is treated as the same song.
- **No album art, ratings, play counts, gapless, or hardware key mapping** in
  v1.
- **The name.** "iPod" is Apple's trademark. It is fine as a personal label
  and must change before any public submission to Light's tool library.

## Contributing and license

The upstream [CONTRIBUTING.md](CONTRIBUTING.md) and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) are Light's and describe how to
contribute to the SDK itself. For this project, open an issue first. CI runs
`./gradlew check` on every pull request against `main`.

Licensed under the [MIT License](LICENSE).
