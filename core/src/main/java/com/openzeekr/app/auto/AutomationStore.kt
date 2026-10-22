package com.openzeekr.app.auto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Persists [AutomationConfig] (what the user set up) and [AutomationState] (what the worker
 * remembers between checks). Encrypted, like [com.openzeekr.app.config.ConfigStore], because the
 * safe zones are the user's home / work locations.
 *
 * Process-wide singleton: the UI and [AutomationWorker] share the same flows.
 */
class AutomationStore private constructor(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _config = MutableStateFlow(read(KEY_CONFIG) { json.decodeFromString<AutomationConfig>(it) } ?: AutomationConfig())
    val config: StateFlow<AutomationConfig> = _config.asStateFlow()

    private val _state = MutableStateFlow(read(KEY_STATE) { json.decodeFromString<AutomationState>(it) } ?: AutomationState())
    val state: StateFlow<AutomationState> = _state.asStateFlow()

    private fun <T> read(key: String, decode: (String) -> T): T? =
        prefs.getString(key, null)?.let { raw ->
            runCatching { decode(raw) }
                .onFailure { Logx.w("auto", "couldn't read $key: ${it.message}") }
                .getOrNull()
        }

    @Synchronized
    fun updateConfig(transform: (AutomationConfig) -> AutomationConfig) {
        val next = transform(_config.value)
        _config.value = next
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(next)).apply()
    }

    @Synchronized
    fun updateState(transform: (AutomationState) -> AutomationState) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit().putString(KEY_STATE, json.encodeToString(next)).apply()
    }

    fun log(text: String) = updateState { it.withLog(System.currentTimeMillis(), text) }

    companion object {
        private const val FILE = "openzeekr_automations"
        private const val KEY_CONFIG = "config"
        private const val KEY_STATE = "state"

        @Volatile private var INSTANCE: AutomationStore? = null

        fun get(context: Context): AutomationStore = INSTANCE ?: synchronized(this) {
            INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
        }

        private fun build(appCtx: Context): AutomationStore {
            val prefs = runCatching {
                val masterKey = MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appCtx,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrElse {
                // A broken keystore must not take the whole app down; fall back to private prefs.
                Logx.w("auto", "encrypted prefs unavailable (${it.message}); using private prefs")
                appCtx.getSharedPreferences("${FILE}_plain", Context.MODE_PRIVATE)
            }
            return AutomationStore(prefs)
        }
    }
}
