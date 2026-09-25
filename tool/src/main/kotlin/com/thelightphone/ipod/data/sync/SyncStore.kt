package com.thelightphone.ipod.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** What the sync feature remembers across launches. An interface so the engine is testable on the JVM. */
interface SyncStore {
    val pairing: Flow<MacPairing?>
    val lastSync: Flow<LastSync?>
    suspend fun savePairing(pairing: MacPairing?)
    suspend fun saveLastSync(lastSync: LastSync?)
}

private val PAIRING_KEY = stringPreferencesKey("sync_pairing_json")
private val LAST_SYNC_AT_KEY = longPreferencesKey("sync_last_at_ms")
private val LAST_SYNC_SUMMARY_KEY = stringPreferencesKey("sync_last_summary")

class DataStoreSyncStore(private val dataStore: DataStore<Preferences>) : SyncStore {
    private val json = Json { ignoreUnknownKeys = true }

    override val pairing: Flow<MacPairing?> = dataStore.data.map { prefs ->
        prefs[PAIRING_KEY]?.let { raw -> runCatching { json.decodeFromString<MacPairing>(raw) }.getOrNull() }
    }

    override val lastSync: Flow<LastSync?> = dataStore.data.map { prefs ->
        val at = prefs[LAST_SYNC_AT_KEY]
        val summary = prefs[LAST_SYNC_SUMMARY_KEY]
        if (at != null && summary != null) LastSync(at, summary) else null
    }

    override suspend fun savePairing(pairing: MacPairing?) {
        dataStore.edit { prefs ->
            if (pairing == null) prefs.remove(PAIRING_KEY) else prefs[PAIRING_KEY] = json.encodeToString(pairing)
        }
    }

    override suspend fun saveLastSync(lastSync: LastSync?) {
        dataStore.edit { prefs ->
            if (lastSync == null) {
                prefs.remove(LAST_SYNC_AT_KEY)
                prefs.remove(LAST_SYNC_SUMMARY_KEY)
            } else {
                prefs[LAST_SYNC_AT_KEY] = lastSync.atEpochMs
                prefs[LAST_SYNC_SUMMARY_KEY] = lastSync.summary
            }
        }
    }
}
