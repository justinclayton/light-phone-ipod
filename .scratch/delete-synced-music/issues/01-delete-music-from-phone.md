# Delete songs from the phone library after they've been synced

Status: needs-triage
Type: feature

## Problem

Once a song lands on the phone via Mac sync, there's no way to remove it.
Neither the iPod tool nor the Mac loader can delete music, so the library only
grows. Today the only remedy is the author with a cable and adb.

This is a deliberate v1 limitation, not a bug:

- `docs/mac-loader.prd.md` §9 "No deletion" and §10 "Music can only be added,
  never removed" — "the trigger for solving it is the first time storage is a
  problem." §11 lists it as future idea #3.
- `docs/mac-loader.protocol.md` §3 promises the Mac that "The phone never
  deletes music."

Beyond storage, it also means a bad file (duplicate under another filename,
wrong tags, a mistaken drop) is stuck in Artists / Albums / Songs forever.

## Options to decide between

1. **Phone-side delete** — a "Delete song" (and maybe "Delete album") action in
   the iPod tool, with a confirm step. Simplest mental model; stays within the
   "Mac is a loading dock" design. Needs to delete the file, rescan, and drop
   it from any playlists / the current queue.
2. **Mac-side delete** — Mac asks the phone to remove items. Requires the Mac
   to know what's on the phone (it currently has no library picture, by design)
   and a new protocol endpoint the phone would pull, since the phone initiates
   all traffic.
3. **Both** — phone-side first, Mac-side later if wanted.

Recommendation: start with (1).

## To find out

- Can the tool delete files it wrote to its sync destination under the Light
  SDK's file APIs, or is that storage append-only / blocked by the plugin?
- What happens to a playing / queued track that gets deleted?
- Does removing a file require a manual rescan, or does the existing
  post-sync rescan path cover it?
- Update the PRD §10 and protocol §3 wording once decided (the "never deletes"
  guarantee to the Mac must stay true or be revised deliberately).

## Comments
