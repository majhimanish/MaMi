package app.mami.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.mami.BuildConfig
import app.mami.core.ShareKind
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A status the person set themselves, like "🚗 Driving until 6 pm". */
@Serializable
data class SavedQuickStatus(val emoji: String, val label: String, val untilMs: Long? = null)

/** Small, non-sensitive preferences plus a few Keystore-protected secrets. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mami", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _shares = MutableStateFlow(readShares())
    val sharesFlow: StateFlow<Set<ShareKind>> = _shares.asStateFlow()

    private val _quickStatus = MutableStateFlow(readQuickStatus())
    val quickStatusFlow: StateFlow<SavedQuickStatus?> = _quickStatus.asStateFlow()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SERVER_URL
        set(value) = prefs.edit { putString(KEY_SERVER, value.trim().trimEnd('/')) }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)?.let(SecretBox::open)?.decodeToString()
        set(value) = prefs.edit {
            if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, SecretBox.seal(value.encodeToByteArray()))
        }

    var email: String?
        get() = prefs.getString("email", null)
        set(value) = prefs.edit { putString("email", value) }

    var displayName: String
        get() = prefs.getString("display_name", null).orEmpty()
        set(value) = prefs.edit { putString("display_name", value) }

    /** The person has seen and confirmed what they share. */
    var sharingConfirmed: Boolean
        get() = prefs.getBoolean("sharing_confirmed", false)
        set(value) = prefs.edit { putBoolean("sharing_confirmed", value) }

    var shares: Set<ShareKind>
        get() = _shares.value
        set(value) {
            prefs.edit { putStringSet("shares", value.map { it.name }.toSet()) }
            _shares.value = value
        }

    var quickStatus: SavedQuickStatus?
        get() = _quickStatus.value?.takeIf { it.untilMs == null || it.untilMs > System.currentTimeMillis() }
        set(value) {
            prefs.edit {
                if (value == null) remove("quick_status") else putString("quick_status", json.encodeToString(value))
            }
            _quickStatus.value = value
        }

    var hideNotificationText: Boolean
        get() = prefs.getBoolean("hide_notification_text", false)
        set(value) = prefs.edit { putBoolean("hide_notification_text", value) }

    /** Tell the partner automatically when the battery is about to run out. */
    var lowBatteryAlerts: Boolean
        get() = prefs.getBoolean("low_battery_alerts", true)
        set(value) = prefs.edit { putBoolean("low_battery_alerts", value) }

    var lowBatteryAlertSent: Boolean
        get() = prefs.getBoolean("low_battery_alert_sent", false)
        set(value) = prefs.edit { putBoolean("low_battery_alert_sent", value) }

    var lastStatusFingerprint: String
        get() = prefs.getString("status_fingerprint", null).orEmpty()
        set(value) = prefs.edit { putString("status_fingerprint", value) }

    var lastStatusSentAt: Long
        get() = prefs.getLong("status_sent_at", 0)
        set(value) = prefs.edit { putLong("status_sent_at", value) }

    /** Cached server view of the partner, so the app opens instantly offline. */
    var partnerJson: String?
        get() = prefs.getString("partner", null)
        set(value) = prefs.edit { putString("partner", value) }

    /** The partner's last status payload (JSON) and when it arrived. */
    var partnerStatusJson: String?
        get() = prefs.getString("partner_status", null)
        set(value) = prefs.edit { putString("partner_status", value) }

    var partnerStatusReceivedAt: Long
        get() = prefs.getLong("partner_status_at", 0)
        set(value) = prefs.edit { putLong("partner_status_at", value) }

    /** The partner key the person compared in person. */
    var verifiedPartnerKey: String?
        get() = prefs.getString("verified_partner_key", null)
        set(value) = prefs.edit { putString("verified_partner_key", value) }

    /** The partner's key changed since we last saw it (new phone?). */
    var partnerKeyChanged: Boolean
        get() = prefs.getBoolean("partner_key_changed", false)
        set(value) = prefs.edit { putBoolean("partner_key_changed", value) }

    /** 32 random bytes that encrypt the saved encryption state, themselves sealed by the Keystore. */
    @Synchronized
    fun pickleKey(): ByteArray {
        prefs.getString(KEY_PICKLE, null)?.let(SecretBox::open)?.let { return it }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        prefs.edit(commit = true) { putString(KEY_PICKLE, SecretBox.seal(key)) }
        return key
    }

    fun clearPartner() = prefs.edit {
        remove("partner")
        remove("partner_status")
        remove("partner_status_at")
        remove("verified_partner_key")
        remove("partner_key_changed")
        remove("status_fingerprint")
        remove("status_sent_at")
    }

    /** Forgets everything except the server address. */
    fun clearAll() {
        val server = prefs.getString(KEY_SERVER, null)
        prefs.edit(commit = true) {
            clear()
            if (server != null) putString(KEY_SERVER, server)
        }
        _shares.value = readShares()
        _quickStatus.value = null
    }

    private fun readShares(): Set<ShareKind> {
        val saved = prefs.getStringSet("shares", null) ?: return ShareKind.entries.toSet() - ShareKind.UNKNOWN
        return saved.mapNotNull { name -> ShareKind.entries.firstOrNull { it.name == name } }.toSet()
    }

    private fun readQuickStatus(): SavedQuickStatus? =
        prefs.getString("quick_status", null)?.let { runCatching { json.decodeFromString<SavedQuickStatus>(it) }.getOrNull() }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_PICKLE = "pickle_key"
    }
}
