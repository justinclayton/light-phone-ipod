# Lock screen play/pause controls for iPod playback

Status: needs-triage
Type: research

## Question

Can iPod playback show play/pause (and ideally prev/next + track title) on the
LP3 lock screen, the way the official LightOS Music tool does?

## What we already know

- iPod uses **detached audio** (`capabilities = ["detached-audio"]` in
  `tool/lighttool.toml`). Playback runs in the SDK's `LightAudioService`
  (a media3 `MediaSessionService`), which publishes a platform `MediaSession`.
- `docs/design_decisions/detached_audio.md` diagrams LightOS (the launcher,
  uid.system) calling `MediaSessionManager.getActiveSessions()` and feeding a
  **"Now-playing: LockScreen / Toolbox"** surface from the resulting
  `MediaController`. It also says the media3 notification is *not* the LightOS
  now-playing surface — LightOS discovers the session itself.
- The SDK doc hedges: queue/metadata are exposed "to Android, media buttons, and
  future LightOS controls" — so tool-session support on the lock screen may not
  be shipped yet.
- We already pass `LightMediaMetadata(title, artist, album, durationMs)` per
  track (`PlayerController.toAudioItem`), so the session should carry what a
  lock-screen widget needs.
- The Light SDK plugin blocks direct Android framework types, so we can't build
  our own lock-screen surface; this has to come through the SDK session.

## To find out

1. On the emulator and on real LP3 hardware: start playback in iPod, lock the
   phone. Does anything appear? Do play/pause work from there?
2. `adb shell dumpsys media_session` while playing — confirm iPod's session is
   active, has metadata, and advertises play/pause/skip actions.
3. If LightOS ignores tool sessions: is it gated (allowlist, capability flag,
   only `LightOSAudioPlayerService`)? Check the emulator's system app / SDK
   source for the lock-screen consumer.
4. Toolbox now-playing — same question, same answer likely.

## Outcomes

- **Works already** → verify, note in FINDINGS.md, done.
- **Works with a missing piece we control** (metadata, session actions) → file
  a task.
- **LightOS-side gap** → record in FINDINGS.md as an upstream request to Light.

## Comments
