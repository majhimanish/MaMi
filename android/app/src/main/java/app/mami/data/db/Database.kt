package app.mami.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Where a message is on its way. Only ever moves forward. */
object MessageState {
    /** Saved on this phone, not yet accepted by the server. */
    const val PENDING = 0
    /** The server has it. */
    const val SENT = 1
    /** It reached the partner's phone. */
    const val DELIVERED = 2
    /** It was on the partner's screen. */
    const val READ = 3
}

object MessageKind {
    const val TEXT = "text"
    const val NUDGE = "nudge"
    const val ALERT = "alert"
    /** A photo, video, voice note or file. */
    const val MEDIA = "media"
    /** A call, shown in the chat afterwards. */
    const val CALL = "call"
    /** A love letter: [MessageEntity.title], the letter in `body`, the paper in [MessageEntity.paper]. */
    const val LETTER = "letter"
    /** "Home safe" and friends; the [app.mami.core.CheckInKind] name in `body`. */
    const val CHECKIN = "checkin"
    /** Live location being shared until [MessageEntity.untilMs]. */
    const val LIVE_LOCATION = "live_location"
}

/** Where an attachment's bytes are. */
object Transfer {
    /** On this phone and (for mine) uploaded. */
    const val DONE = 0
    /** Mine, waiting to be encrypted and uploaded. */
    const val UPLOAD = 1
    /** Theirs, waiting to be downloaded and decrypted. */
    const val DOWNLOAD = 2
    /** Theirs, too big to fetch on mobile data without asking. */
    const val ASK = 3
    const val FAILED_UPLOAD = 4
    const val FAILED_DOWNLOAD = 5
    /** The server no longer has it (it expired, or a view-once was opened). */
    const val GONE = 6
}

/** The `mediaKind` column: the [app.mami.core.MediaKind] name. */
object MediaType {
    const val PHOTO = "PHOTO"
    const val VIDEO = "VIDEO"
    const val VOICE = "VOICE"
    const val FILE = "FILE"
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val fromMe: Boolean,
    val kind: String,
    /** The text (or a photo's caption), or the nudge/alert kind name. */
    val body: String,
    val batteryPercent: Int? = null,
    /** When the sender wrote it, by the sender's clock. */
    val sentAtMs: Long,
    /** Position in the conversation (server time once known). */
    val sortAtMs: Long,
    val state: Int,
    /** When the server accepted it. */
    val serverAtMs: Long? = null,
    /** When it reached the recipient's phone. */
    val deliveredAtMs: Long? = null,
    /** When the recipient saw it. */
    val readAtMs: Long? = null,
    val readReceiptSent: Boolean = false,
    val replyTo: String? = null,

    // ---- attachments ----
    /** PHOTO, VIDEO, VOICE or FILE (see [MediaType]). */
    val mediaKind: String? = null,
    /** File name inside the app's private media folder, once the file is here. */
    val mediaFile: String? = null,
    val mediaMime: String? = null,
    /** Original file name, for documents. */
    val mediaName: String? = null,
    val mediaSize: Long? = null,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val mediaDurationMs: Long? = null,
    /** Tiny JPEG, base64, shown blurred until the real file is here. */
    val mediaThumb: String? = null,
    /** Voice note loudness, 0..255 per bar, base64. */
    val mediaWaveform: String? = null,
    val blobId: String? = null,
    val blobKey: String? = null,
    @ColumnInfo(defaultValue = "0") val transfer: Int = Transfer.DONE,
    @ColumnInfo(defaultValue = "0") val viewOnce: Boolean = false,
    /** A view-once photo was opened (by me if it's theirs, by them if it's mine). */
    val openedAtMs: Long? = null,

    // ---- link preview ----
    val linkUrl: String? = null,
    val linkTitle: String? = null,
    val linkDescription: String? = null,
    /** Small JPEG, base64. */
    val linkImage: String? = null,

    // ---- after sending ----
    val myReaction: String? = null,
    val theirReaction: String? = null,
    val editedAtMs: Long? = null,
    /** Unsent by its sender: shown as a placeholder, contents wiped. */
    @ColumnInfo(defaultValue = "0") val unsent: Boolean = false,
    val pinnedAtMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
    /** Call messages: "VOICE"/"VIDEO" in [mediaKind], outcome here, length in [mediaDurationMs]. */
    val callOutcome: String? = null,

    // ---- together ----
    /** A scheduled message: hidden until then (on both phones). */
    val scheduledAtMs: Long? = null,
    /** A letter's title. */
    val title: String? = null,
    /** A letter's paper. */
    val paper: String? = null,
    /** A letter sealed until then. */
    val unlockAtMs: Long? = null,
    /** Live location: shared until then. */
    val untilMs: Long? = null,
    /** Live location: stopped early at. */
    val endedAtMs: Long? = null,
) {
    val isMedia: Boolean get() = kind == MessageKind.MEDIA

    /** Scheduled for later and not shown yet. */
    fun hiddenUntil(now: Long): Boolean = scheduledAtMs != null && scheduledAtMs > now
}

/** A mood check-in, mine or theirs. */
@Entity(tableName = "moods")
data class MoodEntity(
    @PrimaryKey val id: String,
    val fromMe: Boolean,
    val mood: String,
    val note: String? = null,
    val atMs: Long,
)

/**
 * Small encrypted payloads that must reach the partner but aren't messages
 * in the list themselves: reactions, edits, unsends, pins, "opened".
 * Stored as the core's JSON so they survive the app being closed.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: String,
    val payloadJson: String,
    val createdAtMs: Long,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY sortAtMs ASC, sentAtMs ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    /** Returns -1 if a message with this id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE fromMe = 1 AND state = 0 ORDER BY sortAtMs ASC")
    suspend fun pendingOutgoing(): List<MessageEntity>

    @Query(
        "UPDATE messages SET state = 1, serverAtMs = :atMs, sortAtMs = COALESCE(scheduledAtMs, :atMs) " +
            "WHERE id = :id AND fromMe = 1 AND state = 0",
    )
    suspend fun markSent(id: String, atMs: Long)

    @Query(
        "UPDATE messages SET state = MAX(state, 2), deliveredAtMs = COALESCE(deliveredAtMs, :atMs) " +
            "WHERE id = :id AND fromMe = 1",
    )
    suspend fun markDelivered(id: String, atMs: Long)

    @Query(
        "UPDATE messages SET state = 3, readAtMs = COALESCE(readAtMs, :atMs), " +
            "deliveredAtMs = COALESCE(deliveredAtMs, :atMs) WHERE id IN (:ids) AND fromMe = 1",
    )
    suspend fun markReadByPartner(ids: List<String>, atMs: Long)

    /** After the partner's keys changed, anything they never received is sent again. */
    @Query("UPDATE messages SET state = 0 WHERE fromMe = 1 AND state = 1")
    suspend fun requeueUndelivered(): Int

    /** Everything that has arrived and is showing counts as read (scheduled ones wait for their time). */
    @Query("UPDATE messages SET readAtMs = :atMs WHERE fromMe = 0 AND readAtMs IS NULL AND (scheduledAtMs IS NULL OR scheduledAtMs <= :atMs)")
    suspend fun markIncomingRead(atMs: Long): Int

    @Query("SELECT id FROM messages WHERE fromMe = 0 AND readAtMs IS NOT NULL AND readReceiptSent = 0")
    suspend fun unsentReadReceipts(): List<String>

    @Query("UPDATE messages SET readReceiptSent = 1 WHERE id IN (:ids)")
    suspend fun markReadReceiptsSent(ids: List<String>)

    @Query(
        "SELECT * FROM messages WHERE fromMe = 0 AND readAtMs IS NULL AND (scheduledAtMs IS NULL OR scheduledAtMs <= :now) " +
            "ORDER BY sortAtMs DESC LIMIT :limit",
    )
    suspend fun unreadIncoming(limit: Int, now: Long): List<MessageEntity>

    /** The next scheduled message still waiting for its time. */
    @Query("SELECT MIN(scheduledAtMs) FROM messages WHERE scheduledAtMs > :now")
    suspend fun nextScheduled(now: Long): Long?

    @Query("UPDATE messages SET openedAtMs = COALESCE(openedAtMs, :atMs) WHERE id = :id")
    suspend fun markLetterOpened(id: String, atMs: Long)

    @Query("UPDATE messages SET endedAtMs = :atMs WHERE id = :id")
    suspend fun endLiveLocation(id: String, atMs: Long)

    // ---- attachments ----

    @Query("SELECT * FROM messages WHERE transfer IN (1, 2) ORDER BY sortAtMs ASC")
    suspend fun waitingTransfers(): List<MessageEntity>

    @Query("UPDATE messages SET transfer = :transfer WHERE id = :id")
    suspend fun setTransfer(id: String, transfer: Int)

    @Query("UPDATE messages SET transfer = 0, blobId = :blobId, blobKey = :key WHERE id = :id")
    suspend fun markUploaded(id: String, blobId: String, key: String)

    @Query("UPDATE messages SET transfer = 0, mediaFile = :file WHERE id = :id")
    suspend fun markDownloaded(id: String, file: String)

    @Query("UPDATE messages SET mediaFile = NULL, openedAtMs = COALESCE(openedAtMs, :atMs), transfer = 6 WHERE id = :id")
    suspend fun markOpened(id: String, atMs: Long)

    @Query("UPDATE messages SET mediaFile = NULL WHERE id = :id")
    suspend fun clearMediaFile(id: String)

    @Query("UPDATE messages SET openedAtMs = COALESCE(openedAtMs, :atMs) WHERE id = :id AND fromMe = 1")
    suspend fun markOpenedByPartner(id: String, atMs: Long)

    // ---- after sending ----

    @Query("UPDATE messages SET myReaction = :emoji WHERE id = :id")
    suspend fun setMyReaction(id: String, emoji: String?)

    @Query("UPDATE messages SET theirReaction = :emoji WHERE id = :id")
    suspend fun setTheirReaction(id: String, emoji: String?)

    @Query("UPDATE messages SET body = :body, editedAtMs = :atMs, linkUrl = NULL, linkTitle = NULL, linkDescription = NULL, linkImage = NULL WHERE id = :id AND fromMe = :fromMe AND unsent = 0")
    suspend fun edit(id: String, fromMe: Boolean, body: String, atMs: Long): Int

    @Query(
        "UPDATE messages SET unsent = 1, body = '', mediaFile = NULL, mediaThumb = NULL, mediaWaveform = NULL, " +
            "blobId = NULL, blobKey = NULL, transfer = 0, linkUrl = NULL, linkTitle = NULL, linkDescription = NULL, " +
            "linkImage = NULL, myReaction = NULL, theirReaction = NULL, pinnedAtMs = NULL, starred = 0 " +
            "WHERE id = :id AND fromMe = :fromMe",
    )
    suspend fun unsend(id: String, fromMe: Boolean): Int

    @Query("UPDATE messages SET pinnedAtMs = :atMs WHERE id = :id")
    suspend fun setPinned(id: String, atMs: Long?)

    @Query("UPDATE messages SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    /** Words anywhere in a message, a caption, a file name or a link title. */
    @Query(
        "SELECT * FROM messages WHERE unsent = 0 AND kind IN ('text', 'media') AND " +
            "(body LIKE :pattern ESCAPE '\\' OR mediaName LIKE :pattern ESCAPE '\\' OR linkTitle LIKE :pattern ESCAPE '\\') " +
            "ORDER BY sortAtMs DESC LIMIT 200",
    )
    suspend fun search(pattern: String): List<MessageEntity>

    @Query("SELECT mediaFile FROM messages WHERE mediaFile IS NOT NULL")
    suspend fun mediaFiles(): List<String>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface TogetherDao {
    @Query("SELECT * FROM moods ORDER BY atMs DESC LIMIT 200")
    fun observeMoods(): Flow<List<MoodEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMood(mood: MoodEntity)

    @Query("DELETE FROM moods")
    suspend fun clearMoods()
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(item: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAtMs ASC")
    suspend fun all(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun remove(id: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Database(entities = [MessageEntity::class, OutboxEntity::class, MoodEntity::class], version = 4, exportSchema = false)
abstract class MamiDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun outbox(): OutboxDao
    abstract fun together(): TogetherDao

    companion object {
        /** Attachments, link previews, reactions, edits, unsend, pins, stars and calls. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = listOf(
                    "mediaKind TEXT", "mediaFile TEXT", "mediaMime TEXT", "mediaName TEXT",
                    "mediaSize INTEGER", "mediaWidth INTEGER", "mediaHeight INTEGER", "mediaDurationMs INTEGER",
                    "mediaThumb TEXT", "mediaWaveform TEXT", "blobId TEXT", "blobKey TEXT",
                    "transfer INTEGER NOT NULL DEFAULT 0", "viewOnce INTEGER NOT NULL DEFAULT 0", "openedAtMs INTEGER",
                    "linkUrl TEXT", "linkTitle TEXT", "linkDescription TEXT", "linkImage TEXT",
                    "myReaction TEXT", "theirReaction TEXT", "editedAtMs INTEGER",
                    "unsent INTEGER NOT NULL DEFAULT 0", "pinnedAtMs INTEGER", "starred INTEGER NOT NULL DEFAULT 0",
                    "callOutcome TEXT",
                )
                columns.forEach { db.execSQL("ALTER TABLE messages ADD COLUMN $it") }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS outbox (id TEXT NOT NULL, payloadJson TEXT NOT NULL, " +
                        "createdAtMs INTEGER NOT NULL, PRIMARY KEY(id))",
                )
            }
        }

        /** Scheduled messages, letters, check-ins, live location and moods. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "scheduledAtMs INTEGER", "title TEXT", "paper TEXT", "unlockAtMs INTEGER",
                    "untilMs INTEGER", "endedAtMs INTEGER",
                ).forEach { db.execSQL("ALTER TABLE messages ADD COLUMN $it") }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS moods (id TEXT NOT NULL, fromMe INTEGER NOT NULL, mood TEXT NOT NULL, " +
                        "note TEXT, atMs INTEGER NOT NULL, PRIMARY KEY(id))",
                )
            }
        }

        /** The question of the day was dropped; test builds at version 3 had its table. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS answers")
            }
        }
    }
}
