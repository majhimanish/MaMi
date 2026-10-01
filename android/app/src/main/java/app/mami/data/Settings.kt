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

/**
 * What the two of you share: the day you got together and the next time
 * you'll meet. Each value remembers when it was set; the newest wins.
 */
@Serializable
data class TogetherInfo(
    /** "yyyy-mm-dd". */
    val since: String? = null,
    val sinceAt: Long = 0,
    val nextMeetingMs: Long? = null,
    val nextMeetingAt: Long = 0,
    val nextMeetingLabel: String? = null,
    val nextMeetingLabelAt: Long = 0,
)

/** Sharing is paused until turned back on. */
const val PAUSED_INDEFINITELY = Long.MAX_VALUE

fun pausedIndefinitely(until: Long): Boolean = until >= PAUSED_INDEFINITELY / 2

/** The partner's live location, as last received. */
@Serializable
data class SharedLocation(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val atMs: Long,
    val untilMs: Long,
    val ended: Boolean = false,
) {
    fun live(now: Long) = !ended && untilMs > now
}

/** Small, non-sensitive preferences plus a few Keystore-protected secrets. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mami", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _shares = MutableStateFlow(readShares())
    val sharesFlow: StateFlow<Set<ShareKind>> = _shares.asStateFlow()

    private val _quickStatus = MutableStateFlow(readQuickStatus())
    val quickStatusFlow: StateFlow<SavedQuickStatus?> = _quickStatus.asStateFlow()

    private val _together = MutableStateFlow(read("together") ?: TogetherInfo())
    val togetherFlow: StateFlow<TogetherInfo> = _together.asStateFlow()

    private val _pausedUntil = MutableStateFlow(prefs.getLong("paused_until", 0).takeIf { it > 0 })
    /** Sharing is paused until then (null: not paused). */
    val pausedUntilFlow: StateFlow<Long?> = _pausedUntil.asStateFlow()

    private val _partnerLocation = MutableStateFlow(read<SharedLocation>("partner_location"))
    val partnerLocationFlow: StateFlow<SharedLocation?> = _partnerLocation.asStateFlow()

    private val _locationUntil = MutableStateFlow(prefs.getLong("location_until", 0).takeIf { it > 0 })
    /** I'm sharing my live location until then. */
    val locationUntilFlow: StateFlow<Long?> = _locationUntil.asStateFlow()

    var together: TogetherInfo
        get() = _together.value
        set(value) {
            _together.value = value
            prefs.edit { putString("together", json.encodeToString(value)) }
        }

    var sharingPausedUntil: Long?
        get() = _pausedUntil.value
        set(value) {
            _pausedUntil.value = value
            prefs.edit { putLong("paused_until", value ?: 0) }
        }

    var partnerLocation: SharedLocation?
        get() = _partnerLocation.value
        set(value) {
            _partnerLocation.value = value
            prefs.edit { if (value == null) remove("partner_location") else putString("partner_location", json.encodeToString(value)) }
        }

    /** My live location share: its chat message id and when it ends. */
    var locationShareId: String?
        get() = prefs.getString("location_share_id", null)
        set(value) = prefs.edit { putString("location_share_id", value) }

    var locationShareUntil: Long?
        get() = _locationUntil.value
        set(value) {
            _locationUntil.value = value
            prefs.edit { putLong("location_until", value ?: 0) }
        }

    /** Let the partner see "🚗 Driving" automatically. */
    var autoDriving: Boolean
        get() = prefs.getBoolean("auto_driving", false)
        set(value) = prefs.edit { putBoolean("auto_driving", value) }

    /** Let the partner see "Woke up at 7:12" automatically. */
    var autoWakeUp: Boolean
        get() = prefs.getBoolean("auto_wake_up", false)
        set(value) = prefs.edit { putBoolean("auto_wake_up", value) }

    /** The status the phone set by itself (driving), if any. */
    var autoStatus: SavedQuickStatus?
        get() = read("auto_status")
        set(value) = prefs.edit { if (value == null) remove("auto_status") else putString("auto_status", json.encodeToString(value)) }

    var wokeAtMs: Long
        get() = prefs.getLong("woke_at", 0)
        set(value) = prefs.edit { putLong("woke_at", value) }

    var lastScreenOffAt: Long
        get() = prefs.getLong("screen_off_at", 0)
        set(value) = prefs.edit { putLong("screen_off_at", value) }

    /** When the background check first saw the phone idle (0: it wasn't). */
    var idleSince: Long
        get() = prefs.getLong("idle_since", 0)
        set(value) = prefs.edit { putLong("idle_since", value) }

    private inline fun <reified T> read(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SERVER_URL
        set(value) = prefs.edit { putString(KEY_SERVER, value.trim().trimEnd('/')) }

    @Volatile
    private var cachedToken: String? = null

    /** The sign-in token, kept in memory after the first Keystore decryption. */
    var token: String?
        get() = cachedToken ?: prefs.getString(KEY_TOKEN, null)?.let(SecretBox::open)?.decodeToString()?.also {
            cachedToken = it
        }
        set(value) {
            cachedToken = value
            prefs.edit {
                if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, SecretBox.seal(value.encodeToByteArray()))
            }
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

    /** Make link previews on this phone before sending a link. */
    var linkPreviews: Boolean
        get() = prefs.getBoolean("link_previews", true)
        set(value) = prefs.edit { putBoolean("link_previews", value) }

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
        _together.value = TogetherInfo()
        _partnerLocation.value = null
        _locationUntil.value = null
        remove("together")
        remove("partner_location")
        remove("location_until")
        remove("location_share_id")
        remove("partner")
        remove("partner_status")
        remove("partner_status_at")
        remove("verified_partner_key")
        remove("partner_key_changed")
        remove("status_fingerprint")
        remove("status_sent_at")
    }

    /** Theme colours ("ROSE", "OCEAN", ...), kept across sign-outs. */
    var palette: String?
        get() = prefs.getString(KEY_PALETTE, null)
        set(value) = prefs.edit { putString(KEY_PALETTE, value) }

    /** "SYSTEM", "LIGHT" or "DARK", kept across sign-outs. */
    var darkMode: String?
        get() = prefs.getString(KEY_DARK_MODE, null)
        set(value) = prefs.edit { putString(KEY_DARK_MODE, value) }

    var chatWallpaper: Boolean
        get() = prefs.getBoolean(KEY_WALLPAPER, true)
        set(value) = prefs.edit { putBoolean(KEY_WALLPAPER, value) }

    /** Debug builds only: use the simulated partner instead of the server. */
    var demoMode: Boolean
        get() = prefs.getBoolean(KEY_DEMO, false)
        set(value) = prefs.edit { putBoolean(KEY_DEMO, value) }

    /** Forgets everything except the server address, appearance and demo mode. */
    fun clearAll() {
        cachedToken = null
        val kept = listOf(KEY_SERVER, KEY_PALETTE, KEY_DARK_MODE).associateWith { prefs.getString(it, null) }
        val wallpaper = chatWallpaper
        val demo = demoMode
        prefs.edit(commit = true) {
            clear()
            kept.forEach { (key, value) -> if (value != null) putString(key, value) }
            putBoolean(KEY_WALLPAPER, wallpaper)
            putBoolean(KEY_DEMO, demo)
        }
        _shares.value = readShares()
        _quickStatus.value = null
        _together.value = TogetherInfo()
        _pausedUntil.value = null
        _partnerLocation.value = null
        _locationUntil.value = null
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
        const val KEY_PALETTE = "palette"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_WALLPAPER = "chat_wallpaper"
        const val KEY_DEMO = "demo_mode"
    }
}
