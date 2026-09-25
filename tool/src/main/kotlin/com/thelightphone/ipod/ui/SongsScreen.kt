package com.thelightphone.ipod.ui

import com.thelightphone.ipod.ui.components.TrackListScreen
import com.thelightphone.sdk.SealedLightActivity

/** All songs, iPod-sorted (artist, album, disc/track). */
fun SongsScreen(sealedActivity: SealedLightActivity) =
    TrackListScreen(sealedActivity, "Songs") { repository -> repository.songs }
