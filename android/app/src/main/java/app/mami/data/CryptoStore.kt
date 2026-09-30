package app.mami.data

import android.util.AtomicFile
import app.mami.core.Ciphertext
import app.mami.core.CoreException
import app.mami.core.CryptoAccount
import app.mami.core.CryptoSession
import app.mami.core.IdentityBundle
import app.mami.core.Payload
import app.mami.core.SignedOneTimeKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * This phone's keys and its encrypted sessions with the partner, saved to
 * private storage after every change and encrypted with a Keystore-protected
 * key.
 *
 * Several sessions can exist at once (for example when both phones started
 * one at the same moment). Incoming messages are tried against all of them,
 * and outgoing messages use the one that most recently received something,
 * so both phones settle on the same session.
 */
class CryptoStore(private val dir: File, private val settings: Settings) {
    private val lock = Mutex()
    private var account: CryptoAccount? = null
    private var sessions: MutableList<Entry>? = null

    private class Entry(
        val session: CryptoSession,
        val partnerCurve: String,
        val createdMs: Long,
        var lastReceivedMs: Long,
    )

    @Serializable
    private data class SavedSession(
        val pickle: String,
        val partnerCurve: String,
        val createdMs: Long,
        val lastReceivedMs: Long,
    )

    suspend fun identity(): IdentityBundle = locked { account().identity() }

    /** Generates [count] new one-time keys and returns every key not uploaded yet. */
    suspend fun oneTimeKeysToUpload(count: Int): List<SignedOneTimeKey> = locked {
        account().unpublishedOneTimeKeys(count).also { saveAccount() }
    }

    suspend fun markKeysPublished() = locked {
        account().markKeysPublished()
        saveAccount()
    }

    suspend fun hasSessionWith(partnerCurve: String): Boolean = locked {
        sessions().any { it.partnerCurve == partnerCurve }
    }

    /** Starts a session from a one-time key claimed from the server. */
    suspend fun startSession(partner: IdentityBundle, oneTimeKey: SignedOneTimeKey) = locked {
        val session = account().startSession(partner, oneTimeKey)
        add(Entry(session, partner.curve25519, now(), 0))
    }

    /** Returns null if there is no session with this partner yet. */
    suspend fun encrypt(partnerCurve: String, payload: Payload): Ciphertext? = locked {
        val best = sessions()
            .filter { it.partnerCurve == partnerCurve }
            .maxWithOrNull(compareBy<Entry>({ it.lastReceivedMs }, { it.createdMs }))
            ?: return@locked null
        best.session.encrypt(payload).also { saveSessions() }
    }

    /** Decrypts a message from the partner, starting a new session if it is the first message of one. */
    suspend fun decrypt(partner: IdentityBundle, ciphertext: Ciphertext): Payload = locked {
        val candidates = sessions()
            .filter { it.partnerCurve == partner.curve25519 }
            .sortedByDescending { it.lastReceivedMs }
        if (ciphertext.messageType == 0) {
            val owner = candidates.firstOrNull { it.session.ownsPrekeyMessage(ciphertext) }
            if (owner != null) return@locked decryptWith(owner, ciphertext)
            val accepted = account().acceptSession(partner, ciphertext)
            saveAccount()
            add(Entry(accepted.session, partner.curve25519, now(), now()))
            return@locked accepted.payload
        }
        var failure: CoreException? = null
        for (entry in candidates) {
            try {
                return@locked decryptWith(entry, ciphertext)
            } catch (e: CoreException) {
                failure = e
            }
        }
        throw failure ?: CoreException.Decryption("no session with this partner")
    }

    /** Drops sessions with anyone but [keepPartnerCurve] (all of them if null). */
    suspend fun forgetSessions(keepPartnerCurve: String? = null) = locked {
        val list = sessions()
        list.filter { it.partnerCurve != keepPartnerCurve }.forEach { it.session.close() }
        list.removeAll { it.partnerCurve != keepPartnerCurve }
        saveSessions()
    }

    /** Deletes this phone's identity. Used when signing out. */
    suspend fun reset() = locked {
        sessions?.forEach { it.session.close() }
        account?.close()
        sessions = null
        account = null
        dir.deleteRecursively()
    }

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    private fun decryptWith(entry: Entry, ciphertext: Ciphertext): Payload {
        val payload = entry.session.decrypt(ciphertext)
        entry.lastReceivedMs = now()
        saveSessions()
        return payload
    }

    private fun add(entry: Entry) {
        val list = sessions()
        list.add(entry)
        val mine = list.filter { it.partnerCurve == entry.partnerCurve }
        if (mine.size > MAX_SESSIONS) {
            mine.sortedWith(compareBy<Entry>({ it.lastReceivedMs }, { it.createdMs }))
                .take(mine.size - MAX_SESSIONS)
                .forEach {
                    it.session.close()
                    list.remove(it)
                }
        }
        saveSessions()
    }

    private fun account(): CryptoAccount {
        account?.let { return it }
        val file = AtomicFile(File(dir, "account.pickle"))
        val loaded = if (file.baseFile.exists()) {
            CryptoAccount.restore(file.readFully().decodeToString(), settings.pickleKey())
        } else {
            CryptoAccount().also { account = it; saveAccount() }
        }
        account = loaded
        return loaded
    }

    private fun sessions(): MutableList<Entry> {
        sessions?.let { return it }
        val file = AtomicFile(File(dir, "sessions.json"))
        val key = settings.pickleKey()
        val loaded = if (file.baseFile.exists()) {
            MamiJson.decodeFromString(ListSerializer(SavedSession.serializer()), file.readFully().decodeToString())
                .mapNotNull { saved ->
                    runCatching {
                        Entry(CryptoSession.restore(saved.pickle, key), saved.partnerCurve, saved.createdMs, saved.lastReceivedMs)
                    }.getOrNull()
                }
                .toMutableList()
        } else {
            mutableListOf()
        }
        sessions = loaded
        return loaded
    }

    private fun saveAccount() {
        val current = account ?: return
        write("account.pickle", current.pickle(settings.pickleKey()))
    }

    private fun saveSessions() {
        val key = settings.pickleKey()
        val saved = sessions().map { SavedSession(it.session.pickle(key), it.partnerCurve, it.createdMs, it.lastReceivedMs) }
        write("sessions.json", MamiJson.encodeToString(ListSerializer(SavedSession.serializer()), saved))
    }

    private fun write(name: String, content: String) {
        dir.mkdirs()
        val file = AtomicFile(File(dir, name))
        val out = file.startWrite()
        try {
            out.write(content.encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val MAX_SESSIONS = 4
    }
}
