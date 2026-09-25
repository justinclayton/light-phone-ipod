# PRD: macOS companion app for the LP3 music tool

- **Status:** Draft — ready for technical design
- **Date:** 2026-09-01
- **Related:** `docs/ipod.adr.md` (the on-phone music tool, v1 implemented); `docs/mac-loader.protocol.md` (the wire protocol the phone side implements)
- **Audience of record:** one household — the operator is a teenager, not the author

## 1. What this is

A small macOS app that gets music from a Mac onto a Light Phone III, so that it
appears in the phone's music tool. The entire product is one gesture: **drop
songs on the app, then open the music tool on the phone and they're there.**

Everything else in this document exists to make that one gesture reliable for
someone who will never read a log file.

## 2. Why it exists

The music tool on the phone plays audio from its own private storage. Nothing
else can put files there — not the built-in Music tool, not Finder, not a cable.
Today the only way to load music is a developer command run from a terminal on
the author's machine. That is not a thing that can be handed to someone else.

Without a companion app, the phone's music library can only be changed by its
developer, which makes the music tool unusable as a real product for its actual
user.

## 3. The constraint that shapes everything

**This app cannot push files to the phone. The phone pulls from the Mac.**

The phone's tool storage is sealed. A host computer has no write path into it —
not over USB, not through any file-transfer mode, not through the system's own
file provider. The one mechanism that works today (a developer copy command)
requires a debug build of the tool, which is not what will be installed on the
phone.

What *is* available: the tool itself can fetch over the local network. So the
Mac holds music and waits; the phone, when opened, comes and gets it.

Three consequences follow, and they are product facts, not implementation
details:

1. **Both ends must be awake at the same time.** A drop does not reach the phone
   until the phone asks for it.
2. **The transfer happens over the home Wi-Fi network**, not a cable. USB is not
   part of this product.
3. **The phone must grow a receiving capability.** This PRD covers both halves.

## 4. Who it's for

One user: a teenager with an LP3 and a Mac. She is not technical, will not be
supervised while using it, and will not have the author available at the moment
something goes wrong.

Build the experience as if it were public. Every failure state must be legible
to her without help. "It didn't work" is a defect; "your phone and your Mac
aren't on the same Wi-Fi" is a feature.

## 5. Product principles

- **One gesture.** Drag and drop is the whole interface. Anything that isn't
  drag-and-drop is a fallback or a diagnostic.
- **Never fail silently.** Every song that doesn't make it says why, in a
  sentence she can act on.
- **Honest about waiting.** A pull-based transfer has a gap between "dropped"
  and "on the phone." Show the gap; don't pretend it isn't there.
- **Don't become iTunes.** This app moves files. It does not organize,
  transcode, rate, or curate them.

## 6. The experience

### First run (once)

The app shows a code on screen. She scans it with the phone, in the music tool.
That's the pairing — one deliberate act, no typing on the phone's keyboard, and
it's remembered afterward. From then on the two devices know each other and only
each other.

### Every time after

1. She drags songs onto the app window.
2. The app tells her what it's about to send, and flags anything that won't work
   — a file the phone can't play, a song that will show up as "Unknown Artist,"
   a track that's protected and can't be copied at all.
3. The songs sit in a visible queue, with the app saying plainly that it needs to
   stay open until the phone picks them up.
4. She opens the music tool on her phone. It finds the Mac, pulls the queue, and
   the songs appear in her library.
5. The Mac shows the queue drain, and says when it's done.

### What the app shows her

- What's queued and what's already gone across.
- Whether the phone is currently reachable.
- Which songs have a problem, and what the problem is.
- Whether it's safe to close the app.

## 7. Preflight: what the app tells her before sending

The phone reads tags from the files themselves, and those tags drive everything
about how music is browsed — artists, albums, sort order, compilations. Files
with bad tags produce a bad library, and this is invisible until it's on the
phone.

So before sending, the app shows **what the phone will see**: title, artist,
album, track number, and whether it will be grouped as a compilation. Anything
that will land in an "Unknown Artist" or "Unknown Album" bucket is flagged.

**The app does not change the files.** It reports; it does not repair. Tag
editing is a future idea (§11), not v1.

Three categories get flagged:

| Situation | What she sees |
| --- | --- |
| Missing or empty tags | "This will show up as Unknown Artist on your phone" |
| Format the phone can't play | "Your phone can't play this kind of file" |
| Copy-protected (Apple Music) | "Songs from Apple Music can't be copied — this only works with music you own" |

The third is the most likely real-world failure and deserves the clearest
wording. A subscription track will look like a normal file and will not work.
She must be told this by the app, not discover it on the phone.

## 8. The phone side

In scope for this PRD, because the Mac app cannot complete the job alone:

- The music tool gains the ability to receive music from a paired Mac.
- Pairing is established by scanning the code the Mac displays.
- **Sync happens automatically when she opens the tool** and the Mac is
  reachable. This is the primary path and requires no decision from her.
- An explicit "Sync" action exists as a fallback for when the automatic path
  didn't do what she expected.
- After receiving, new music is in her library without any further action.

## 9. Non-goals for v1

Stated so they don't creep in:

- **Not a library manager.** The app has no persistent picture of her music
  collection and no opinion about where files live on the Mac. It is a loading
  dock: things pass through it.
- **No syncing folders.** There is no watched folder and no mirroring. Dropping
  is the only way music moves.
- **No deletion.** Removing a song from the Mac does not remove it from the
  phone. See §10.
- **No playlist management.** Playlists are a phone-side feature.
- **No transcoding or tag editing.** The app never modifies a file.
- **No Music.app integration.** Files come from Finder. Dragging out of Music
  works only insofar as it produces a real file, and protected tracks are
  rejected with an explanation.
- **No USB.** Not a simplification — a constraint (§3).

## 10. Known limitations

These are accepted for v1 and should be written down rather than discovered:

- **Music can only be added, never removed.** Neither the app nor the tool can
  delete a song from the phone. The library only grows. When the phone fills up,
  there is no remedy available to her — it requires the author and a cable. This
  is fine for a while and will stop being fine; the trigger for solving it is the
  first time storage is a problem.
- **Both devices must be awake and on the same network.** A closed laptop lid
  means no sync. The app must say so rather than appear broken.
- **The Mac app must stay open** while songs are waiting. A background helper
  that removes this requirement is a known upgrade (§11).
- **Duplicates are possible.** The same song dropped under two different
  filenames will appear twice on the phone.

## 11. Future ideas

Roughly in order of value:

1. **Tag editing before send** — fix "Unknown Artist" on the Mac, where there's a
   keyboard, rather than living with it on the phone.
2. **Background helper** — drops sync whenever the phone next asks, without the
   app being open.
3. **Removing music from the phone**, once storage becomes a real constraint.
4. **Playlists from the Mac** — the tool already imports playlist files, so this
   is largely a matter of authoring them.
5. **Automatic discovery** on the local network, so pairing is invisible after
   the first time.
6. **Format conversion** for files the phone can't play.
7. **Official distribution** — if the tool is ever open-sourced, renamed, and
   submitted for official signing, this app's design does not change.

## 12. Assumptions to verify

**None of the following has been tested on real hardware.** Everything to date
has been verified on an emulator only. Each of these can invalidate part of this
document:

- **The phone can reach the Mac over the local network.** The tool's network
  capability is documented but has never been exercised against a real device on
  a real home network. Guest networks, band separation, and device isolation are
  all plausible spoilers.
- **The phone can scan a code from a Mac screen** reliably enough to be a
  one-shot gesture.
- **The tool can be sideloaded onto her phone and stays installed**, with the
  phone's tool-permission setting adjusted once. The exact acknowledgment flow
  on real hardware is undocumented.
- **The phone has enough storage** for a meaningful library. No figure has been
  established.
- **Transfer speed over Wi-Fi is tolerable** for an album-sized drop.

The first and third are the ones that would force a redesign. They should be
settled before implementation begins, not during.

## 13. Success criteria

- She can put an album on her phone, alone, without asking for help.
- Every song that fails to arrive told her why, in words she understood.
- She can tell at a glance whether it's safe to close the app.
- Adding music never requires a cable, a terminal, or the author.

## 14. Open questions

- **What is this called?** The phone tool is currently named after an Apple
  trademark, which needs to change before anything is shared publicly. The
  companion app needs a name too, and the two should probably agree.
- **What happens when a drop is interrupted** — laptop closed mid-transfer,
  phone walked out of range. Resume, restart, or discard is a design decision
  that should be made deliberately.
- **How does she know an update to the tool is available**, given it's
  sideloaded and there's no store to tell her?
