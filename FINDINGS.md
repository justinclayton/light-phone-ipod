# Music tool spike — findings

**Question:** Can a custom LightOS tool have its own UI but read the same
uploaded music files as the built-in Music tool?

**Short answer:** Not with the current SDK. You can build the player (custom UI
+ audio playback both work great), but a tool cannot reach the shared music
library. That needs Light to add a sanctioned API.

## What blocks it

The SDK build plugin enforces a hard sandbox at BUILD time (not just lint —
the build fails). Confirmed by trying it: importing a `ContentResolver` to query
Android's `MediaStore` (the normal way to read a device's music) produced:

    HomeScreen.kt: blocked import 'androidx.compose.ui.platform.LocalContext'
    HomeScreen.kt: contentResolver access is not allowed

The blocklist (in `plugin/.../LightSdkPlugin.kt`) covers every workaround too:
`android.content.Context`, `getSystemService()`, `contentResolver`, casting to
any Android framework type, and reflection. So MediaStore is closed off by
design, regardless of where the uploaded files physically live on disk. This is
what makes tools safe to run; it's also what stops this feature today.

## What a tool CAN play

The audio APIs themselves are full-featured. A tool can play:
- files bundled in its own `assets/` (this spike plays 3 sample tones from there)
- files it downloaded itself (INTERNET permission) or received
- files in its OWN `LightFileShare` folder (`lightContext.fileShare`)

...with a full player: queue, play/pause/skip/seek, and "detached" background
playback (`docs/design_decisions/detached_audio.md`) where LightOS shows the
controls while your tool is closed.

## This spike

`tool/` is a working "Music+": custom track list in the Light UI, tap-to-play,
tap-again-to-pause, sourced from bundled sample tones plus anything in the
tool's own `music` share folder. Verified running in the LightOS emulator.
The MediaStore attempt is preserved in git history (first commit of HomeScreen).

## Recommended next step

The library access is a feature request to Light, and their README explicitly
invites these ("if there is a stable, open-source library you'd like us to
allow, please let us know" — restrictions are meant to ease over time). The ask:
a read-only SDK API to enumerate + play the user's music library, gated by the
already-allowlisted `READ_MEDIA_AUDIO` permission. Worth checking the light-sdk
GitHub issues first in case it's already requested.

## Optional hardware check (nice-to-have, not required)

Not needed to answer the question, but useful evidence for the feature request:
plug in your LP3 (`adb devices` after enabling dev mode) and run
`adb shell content query --uri content://media/external/audio/media --projection title`.
If your uploaded songs show up, the files ARE in shared storage and only the
SDK gate stands between tools and them — a strong, concrete point for the ask.

## Update: getting files in WITHOUT waiting on Light (proven working)

Since the tool can't read the built-in library, we get music in through the
tool's OWN storage instead. Three routes, best-first:

1. **`make sync` (works today, verified on the emulator).** Drop audio files
   into `music-tool/library/` and run `make sync`. It adb-pushes each file and
   uses `run-as` (allowed because debug builds are debuggable) to copy it into
   the app's private `files/shared/music/` — the folder the tool already lists
   as "your library". Works identically on a USB-connected LP3 once the tool
   is sideloaded there. Downside: needs a cable and a debug build.

2. **Wi-Fi sync (next build, fully sanctioned).** `okhttp`/`ktor` are on the
   SDK's dependency allowlist and INTERNET is an allowed permission, so the
   tool can have a "sync" screen that downloads from a URL — e.g. the Mac
   serving the library folder on the LAN (`python3 -m http.server`), or any
   personal server. No cable, no debuggable build, and it would pass vetting.
   This is the product-shaped version.

3. **Bundle in assets.** Bake files into the APK (like the sample tones).
   Rebuild whenever the library changes. Fine for testing only.

The trade-off vs the built-in Music tool: files live twice on the phone (its
copy + ours) until Light exposes the shared library.

---

# Lock-screen / now-playing controls for iPod (issue #3)

**Question:** Can iPod playback show play/pause (and prev/next, title) on the
LP3 lock screen, like the built-in Music tool?

**Short answer:** No, and it isn't something iPod can fix. Playback continues
when the phone locks, but LightOS shows no controls. iPod's `MediaSession` is
complete; LightOS appears not to consume tool sessions.

## Evidence (real LP3, TLP301, 2026-10-10)

Playing a track in iPod, then `adb shell dumpsys media_session`:

- Session `androidx.media3.session.id.com.thelightphone.ipod` is `active=true`
  and is the current media-button session.
- `state=PLAYING`, queue size 2.
- Metadata present: title, artist, album (album shows "Unknown Album" only
  because that file has no album tag).
- `actions=7340027` = stop, pause, skip-prev, skip-next, seek, play/pause.
  (Play is absent only because it is already playing; media3 omits it.)
- LightOS's own `com.lightos/AudioPlayer` session was `active=false`, `PAUSED`
  the whole time, while the iPod session played.
- iPod's session has `launchIntent=null`. Not known whether LightOS needs it.

Nothing appeared on the lock screen; the user confirmed controls did not display.

## Conclusion

Nothing iPod controls is missing: the session is active, carries metadata, and
advertises transport actions. The gap is on the LightOS side. LightOS source
isn't available here, so *why* (allowlist, only its own player, a missing
`launchIntent`) is unconfirmed. The emulator can't help: its lock screen
(`LightLockscreen`) is an unlock screen only and never queries
`MediaSessionManager`.

## Upstream request to Light

Render lock-screen and now-playing controls from active `MediaSession`s owned by
tools with `detached-audio`, or document what gate applies (capability flag,
session requirements such as a `launchIntent`) so tools can meet it.
`docs/design_decisions/detached_audio.md` describes this as the intended LightOS
contract; it doesn't currently hold on LP3 hardware.
