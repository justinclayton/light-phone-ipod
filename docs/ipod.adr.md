# ADR-001: "iPod" — a music player tool for the Light Phone III

- **Status:** Accepted — ready for implementation
- **Date:** 2026-08-31
- **Project:** `ipod/` in `light-phone-custom-apps`
- **Authors:** JC + Claude (this ADR distills a working spike — see `music-tool/FINDINGS.md`)

## 1. Context

We're building a music player tool for LightOS (Light Phone III) using the
official [light-sdk](https://github.com/lightphone/light-sdk). The product
model is an iPod classic: Artists / Albums / Playlists / Now Playing, fed by
an on-device library of files ripped from CD (iTunes-era metadata). A separate
macOS loader app (out of scope here) will copy files onto the device over USB.

A spike already proved the core loop: the `tool/` module in the `music-tool` project
builds, installs into the LightOS emulator, lists audio from its own library
folder, and plays it through the SDK's audio API. **Keep the spike's plumbing;
replace its UI.**

Do this in a new project directory called `ipod`, pulling from the `music-tool` spike as a base.

### The platform constraint that shapes everything

The SDK enforces a hard sandbox at **build time** (the build FAILS, not lint):

- **Blocked:** `android.content.Context`, `Intent`, `contentResolver`,
  `getSystemService()`, `LocalContext`/`LocalView`/`LocalActivity`,
  `startActivity()`, `registerReceiver()`, casting to any framework type
  (`as Activity`, `as Context`, …), ALL reflection (`.javaClass`, `::class.java`,
  `Class.forName`, `getDeclaredMethod`…). Full list:
  `plugin/src/main/kotlin/com/thelightphone/plugin/LightSdkPlugin.kt`.
- **Consequence:** we cannot read the phone's shared MediaStore or the built-in
  Music tool's files. Our library is the tool's OWN storage:
  `lightContext.fileShare` → on disk `files/shared/` under the app's private dir.
- **Allowed dependencies** (the plugin rejects others): `androidx.compose*`,
  `androidx.lifecycle`, `androidx.room` (+ room-compiler via KSP),
  `androidx.datastore`, `kotlinx-coroutines`, `kotlinx-serialization`,
  `okhttp`, `ktor`, `androidx.media3`, `kotlinx-datetime`, a few others.
- **Reflection ban matters for Room:** write explicit `@Dao` interfaces and let
  KSP generate impls (already how Room works); avoid any library feature that
  reflects at runtime. `tool/build.gradle.kts` already applies
  `ksp(libs.androidx.room.compiler)`.
- **UI must be built from the SDK's components** (`com.thelightphone.sdk.ui`):
  `LightText` (variants Heading/Copy/Detail/Superfine), `LightIcon`,
  `LightScrollView`, `LightTopBar`, `LightBottomBar`, `LightBarButton`,
  `lightClickable`, `LightTheme`/`LightThemeController`/`LightThemeTokens` —
  plus plain Compose layout (`Column`, `Row`, `LazyColumn`). Study
  `examples/ui-demo` and `examples/audio-demo` before writing screens.
- **Navigation:** screens extend `LightScreen<TArgs, TViewModel>`; move between
  them ONLY with `navigateTo { ... }`; LightOS provides the back button
  (override `onBackPressed` in the ViewModel only if needed). `@InitialScreen`
  marks the entry screen. `android.util.Log` is fine for logging.
- **Metadata parsing:** `android.media.MediaMetadataRetriever` is NOT blocked
  and takes a plain file path (no Context needed) — the SDK's own
  `examples/audio-demo/AudioLibraryRepository.kt` uses it. This is our tag reader.

## 2. Decisions

### D1 — Library source: parse tags on-device; files are the only contract
The Mac loader's job is ONLY to place audio files under the tool's library
folder. The tool scans that folder, extracts ID3/iTunes metadata itself with
`MediaMetadataRetriever` (title, artist, album artist, album, track number,
disc number, year, duration, genre, compilation flag), and caches the result
in Room. Rescan = diff by (path, size, mtime); parse only new/changed files.
No index file, no schema coupling with the Mac app.

### D2 — Storage layout (the contract the Mac loader must honor)
```
files/shared/                     <- lightContext.fileShare root
  music/                          <- audio files; any folder structure inside is fine
    **/*.{mp3,m4a,aac,wav,ogg,flac}
  playlists/                      <- exported .m3u8 mirrors (see D3)
```
Development stand-in for the loader: `make sync` adb-pushes `./library/**`
into `files/shared/music/` via `run-as` (works on emulator and USB-connected
LP3 with a debug build). Already implemented and verified.

### D3 — Playlists: Room is the source of truth, mirrored to M3U8
Playlists live in Room (transactional reordering, renames). After every
mutation, export each playlist to `files/shared/playlists/<name>.m3u8` with
`#EXTM3U`/`#EXTINF` and paths relative to `music/`. On scan, import any
`.m3u8` present that Room doesn't know (dedup by playlist name) so the Mac
app can create playlists too. Room wins conflicts; track entries that point
at missing files stay in the playlist but render as unavailable (iPod
behavior: they're skipped during playback).

### D4 — Look: iPod structure, Light skin
Menu structure, navigation depth, and information layout follow the iPod
classic; rendering is 100% Light components and typography, text-only, no
album art in v1 (revisit after seeing the BW screen). This keeps us inside
Light's design language for eventual vetting.

### D5 — v1 scope
Main menu (Artists, Albums, Songs, Playlists, Search, Now Playing-when-active),
browsing, full playlist CRUD + reorder, Now Playing, background playback via
the `detached-audio` capability, shuffle + repeat, all-songs list, search.
Explicitly OUT of v1: hardware key mapping (needs LP3 experimentation), album
art, ratings/play counts, gapless, podcasts/audiobooks.

### D6 — Playback: one shared detached player
One `LightAudioPlayer` created via `DefaultLightAudio(sealedActivity)
.newPlayer(usage = Music, playback = LightAudioPlayback.Detached)`, owned by a
single PlayerController shared by all screens (only ONE detached player may
exist per process — SDK enforces this). Declare `capabilities =
["detached-audio"]` in `lighttool.toml`. Queue semantics: selecting a song in
any list replaces the queue with that list's songs, positioned at the
selection (iPod behavior). Shuffle re-orders the queue (current song first);
repeat one/all via player position/queue handling. Persist last
queue/position in DataStore so Now Playing survives relaunch.
Reference implementation for detached mode quirks: `examples/audio-demo/PlayerScreen.kt`
and `docs/design_decisions/detached_audio.md`.

### D7 — iPod-canonical defaults (overridable later, cheap to change)
- **Album grouping key:** (albumArtist ?? artist, albumName). Tracks with the
  ID3 compilation flag (or albumArtist == "Various Artists") group under a
  single album surfaced in a "Compilations" bucket at the top of Albums.
  iPod rule for the Artists list: an artist who appears ONLY on compilations
  is not listed in Artists.
- **Sort:** case-insensitive, leading "The "/"A "/"An " stripped for sorting
  (displayed intact). Albums under an artist sort by year then name; tracks
  within an album by disc then track number.
- **Missing tags:** title falls back to filename; artist/album fall back to
  "Unknown Artist"/"Unknown Album" buckets, iPod-style.
- **Times on Now Playing:** elapsed and remaining (`-m:ss`), iPod-style.

### D8 — Identity
`tool/lighttool.toml`: `id = "com.thelightphone.ipod"`, `label = "iPod"`,
`serverPackage` stays `com.thelightphone.sdk.emulator` for dev (switch to
`com.lightos` only for real-LP3 builds). Note: "iPod" is Apple's trademark —
fine as a personal-use label, but pick a new name before any public
submission to Light's Tool Library.

## 3. Architecture

All app code in `tool/src/main/kotlin/com/thelightphone/ipod/` (move the
sample package). MVVM per the SDK's pattern; each screen is a
`LightScreen` + `LightScreenViewModel` pair.

```
data/
  db/            Room: entities, DAOs, database (schema below)
  scan/          LibraryScanner: walk fileShare "music/", MediaMetadataRetriever, upsert
  m3u/           M3uCodec: export/import playlists
  LibraryRepository.kt   queries the DB, exposes Flows (artists, albums, songs, playlists)
player/
  PlayerController.kt    the one detached LightAudioPlayer; queue, shuffle, repeat,
                         persisted state (DataStore); exposes StateFlows
ui/
  MainMenuScreen.kt      Artists / Albums / Songs / Playlists / Search / Now Playing
  ArtistsScreen.kt       A–Z artists -> artist's albums (+ "All Songs" row)
  AlbumsScreen.kt        A–Z albums -> track list
  SongsScreen.kt         A–Z all songs
  PlaylistsScreen.kt     list + create ("New Playlist…") / rename / delete
  PlaylistDetailScreen.kt  play, add songs (SongPicker), remove, move up/down, edit mode
  SongPickerScreen.kt    browse/search picker used by playlist editing
  SearchScreen.kt        one query across artists/albums/songs (LightOS keyboard input)
  NowPlayingScreen.kt    see spec below
  components/            shared rows, list index headers, duration formatting
```

**Data flow:** Screens observe Repository/PlayerController Flows → render.
User intents go to ViewModels → Repository (library/playlists) or
PlayerController (transport). Scanner runs from a ViewModel scope on entry
when the folder mtime changed, and behind a "Rescan Library" row on the main
menu (with count summary, e.g. "312 songs").

### Room schema (v1)

```
Track(id PK, path UNIQUE, sizeBytes, mtimeMs,
      title, artist, albumArtist?, album, discNo?, trackNo?,
      year?, genre?, durationMs, isCompilation)
Playlist(id PK, name UNIQUE, createdAt, updatedAt)
PlaylistEntry(playlistId FK, trackId FK, position)   -- ordered, cascade delete
```
Artists/Albums are queries (GROUP BY), not tables. Keep DAOs Flow-returning.

### Now Playing spec (D4 applied)

```
  [ Now Playing ]                      <- LightTopBar, Superfine "3 of 14"
  Song Title                           <- Heading
  Artist Name                          <- Copy, lightened
  Album Name                           <- Detail, lightened
  ───────────█████────────────         <- progress (thin filled bar, theme fg)
  1:23                        -2:41    <- elapsed / remaining, Detail
  [shuffle: on]  [repeat: all]         <- Superfine, only when active
  prev      play/pause      next       <- LightBottomBar / LightBarButton row
```
Tap progress bar to seek (coarse is fine). If nothing is queued, Now Playing
is absent from the main menu (iPod behavior).

## 4. Milestones (each ends runnable in the emulator)

1. **M1 Library:** rename/re-id the tool; Room + scanner + repository; Songs
   screen lists real scanned tracks with correct sort; unit tests for sort
   keys, tag fallbacks, scan diffing (pure JVM — fake `TagReader` interface,
   since MediaMetadataRetriever needs a device).
2. **M2 Player:** PlayerController (detached), queue semantics, Now Playing,
   play from Songs. Persisted queue. Shuffle/repeat.
3. **M3 Browse:** Main menu, Artists, Albums, compilation + unknown-tag rules.
4. **M4 Playlists:** CRUD, reorder, song picker, M3U export/import.
5. **M5 Search + polish:** search screen, rescan row, empty states
   ("No music — sync your library"), long-title ellipsis, screen-off behavior.

## 5. Dev workflow (already set up on this machine — do not re-derive)

- Build: `make build` (or `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :tool:assembleDebug`)
- Emulator: `make emu` boots AVD `lp3` (1080×1240, API 34, LightOS installed
  as a system app — MUST boot with `-writable-system`, the Makefile does).
- Run: `make run`; logs: `make logs`; JVM tests: `./gradlew :tool:testDebugUnitTest`.
- Test music: put files in `./library/`, `make sync` (see D2). Generate test
  fixtures with varied tags rather than assuming real files exist.
- In LightOS, dev tools only appear when Settings → Allowed Tools = "Built
  with SDK" (already set in this emulator).
- Verify UI by screenshotting: `adb shell screencap -p /sdcard/s.png && adb pull ...`.
- `git pull` upstream often — the SDK changes fast and may break APIs.

## 6. Risks / open questions

- **Detached playback in the emulator** worked in audio-demo's design docs but
  is the least-proven area — build M2 against `examples/audio-demo` patterns
  and test early. Recent upstream commit "audio-15min-idle-stop" suggests
  detached sessions auto-stop after idle; read that code.
- **Scan cost:** MediaMetadataRetriever is ~10–50ms/file; a 2,000-song library
  is a ~1-minute first scan. Show progress; scans after the first are diffs.
- **`fileShare.list()` is not recursive** (single-level `listFiles`); the
  scanner needs its own recursive walk — `File(filesDir, "shared/music")`
  via `lightContext.filesDir` is sandbox-legal.
- **LightOS keyboard**: text entry (playlist names, search) via SDK keyboard
  components — check `GetKeyboardOptions` + the ui-demo before designing
  search UX; worst case, search v1 filters as-you-type with a simple on-screen
  approach matching what ui-demo offers.
- **Real-LP3 build** needs `serverPackage = "com.lightos"` and the LightOS
  sideload acknowledgment on the phone; keep a note, don't automate yet.

## 7. Definition of done (v1)

On the emulator with a synced multi-artist/multi-album library including one
compilation and one tagless file: every browse path reaches playback; playlist
create→add→reorder→delete round-trips and the `.m3u8` mirrors match; music
keeps playing after leaving the tool (LightOS shows controls); relaunch
restores Now Playing; all unit tests green; `./gradlew :tool:assembleDebug`
passes the SDK's sandbox gate with zero violations.


## Appendix

We have set up a local dev environment (2026-08) to build and test custom apps for the Light Phone III using Light's official light-sdk (Kotlin/Compose Android scaffolding). The projects live in ~/Documents/_Organized Files/Coding/light-phone-custom-apps on this MacBook Air M4. We also own a real Light Phone III, which can be made available for testing sideloaded tools as needed.
