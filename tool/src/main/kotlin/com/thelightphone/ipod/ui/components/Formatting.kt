package com.thelightphone.ipod.ui.components

/** "m:ss", e.g. 83_000L -> "1:23". */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

/** "-m:ss" for time remaining, iPod-style. */
fun formatRemaining(positionMs: Long, durationMs: Long): String =
    "-" + formatDuration((durationMs - positionMs).coerceAtLeast(0L))
