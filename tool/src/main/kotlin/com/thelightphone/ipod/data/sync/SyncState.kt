package com.thelightphone.ipod.data.sync

/** One song that did not make it, and why, in words for the screen (PRD §5 "never fail silently"). */
data class ItemFailure(val path: String, val reason: String)

/** Outcome of the most recent completed sync, persisted so the main menu can show it. */
data class LastSync(val atEpochMs: Long, val summary: String)

sealed interface SyncState {
    data object NotPaired : SyncState
    data class Idle(val macName: String) : SyncState
    data class Connecting(val macName: String) : SyncState
    data class Downloading(val macName: String, val done: Int, val total: Int, val currentName: String) : SyncState
    data class Done(val macName: String, val received: Int, val failures: List<ItemFailure>) : SyncState
    data class Failed(val macName: String, val message: String, val receivedBeforeFailure: Int = 0) : SyncState
}

/** Every sentence the sync feature shows the user lives here so wording stays consistent and testable. */
object SyncMessages {
    const val NOT_PAIRED_TITLE = "Not connected to a Mac"
    const val NOT_PAIRED_BODY = "Open the music app on your Mac and scan the code it shows."
    const val BAD_CODE = "That code isn't from the music app on your Mac. Try scanning again."

    const val UNPLAYABLE = "Your phone can't play this kind of file"
    const val BAD_NAME = "This file has a name your phone can't store"
    const val INCOMPLETE = "The copy was cut off before it finished"
    const val NOT_SAVED = "Your phone couldn't save this file"
    const val GONE_FROM_MAC = "Your Mac no longer has this file"

    fun unreachable(macName: String, onWifi: Boolean?): String = when (onWifi) {
        false -> "Your phone isn't on Wi-Fi. Join the same Wi-Fi as $macName and try again."
        else -> "Can't find $macName. Make sure the music app is open there and both devices are on the same Wi-Fi."
    }

    fun lostConnection(macName: String, received: Int): String {
        val soFar = if (received > 0) " after ${songs(received)}" else ""
        return "Lost the connection to $macName$soFar. Open Sync again to pick up where it left off."
    }

    fun unauthorized(macName: String): String =
        "$macName doesn't recognize this phone anymore. Scan the code on your Mac again."

    fun wrongMac(macName: String): String =
        "This doesn't look like $macName. Scan the code on your Mac again to reconnect."

    fun outOfSpace(): String =
        "Your phone is out of space. No more music can be added right now."

    fun protocol(macName: String): String =
        "Something went wrong talking to $macName. Try again in a moment."

    fun summary(received: Int, failed: Int): String = when {
        received == 0 && failed == 0 -> "Nothing new on your Mac"
        failed == 0 -> "${songs(received)} added"
        received == 0 -> "${songs(failed)} couldn't be copied"
        else -> "${songs(received)} added, $failed couldn't be copied"
    }

    fun progress(done: Int, total: Int): String = "Copying ${done + 1} of $total"

    private fun songs(n: Int): String = if (n == 1) "1 song" else "$n songs"
}
