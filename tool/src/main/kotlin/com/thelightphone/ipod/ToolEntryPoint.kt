package com.thelightphone.ipod

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {}

    override suspend fun onPushNotification(data: ByteArray) {}

    // Volume rocker adjusts music, not the ringer, while iPod is in the foreground.
    override val volumeKeysControlMedia = true
}
