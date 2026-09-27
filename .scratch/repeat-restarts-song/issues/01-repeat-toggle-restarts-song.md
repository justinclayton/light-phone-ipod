# Changing repeat mode restarts the currently playing song

Status: needs-triage
Type: bug

## Problem

On Now Playing, tapping "REPEAT: …" to cycle Off → All → One restarts the
current song from the beginning. Changing repeat should only affect what
happens when the song ends; playback should continue uninterrupted from the
same position.

## Likely cause

`PlayerController.cycleRepeat()` (`tool/src/main/kotlin/com/thelightphone/ipod/player/PlayerController.kt`)
rebuilds the player queue on every change:

```kotlin
pushToPlayer(_currentIndex.value)   // -> player.setMediaQueue(items, start)
player.seekTo(position)
if (wasPlaying) player.play()
```

Repeat-one is implemented as a single-item queue (ADR D6), so switching into or
out of One swaps the whole media queue. `setMediaQueue` reloads the track, and
the follow-up `seekTo(position)` evidently doesn't restore the position (likely
lost while the new item is still preparing, or `positionMs` is stale/0 at that
moment).

Note: Off ↔ All don't change the queue shape at all, yet still go through
`pushToPlayer`, so they restart too for no reason.

## Fix direction

- Off ↔ All: don't touch the player; repeat-all is already handled in
  `watchForTrackEnd()`. Just update state and persist.
- Into/out of One: avoid replacing the queue mid-song if possible (e.g. handle
  repeat-one at track end in `watchForTrackEnd()` by seeking to 0, instead of a
  single-item queue). If the queue swap must stay, make the seek reliable (wait
  for the new item to be ready before seeking).
- Check `toggleShuffle()` — it uses the same push-then-seek pattern and likely
  has the same restart bug.

## Acceptance

- While a song plays, cycling through all three repeat modes never interrupts
  or rewinds it (verify in emulator and on LP3).
- Repeat One loops the song at its end; All wraps the queue; Off stops at the end.
- Same for paused state: position is kept, stays paused.

## Comments
