package app.mami.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
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
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val fromMe: Boolean,
    val kind: String,
    /** The text, or the nudge/alert kind name. */
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
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY sortAtMs ASC, sentAtMs ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    /** Returns -1 if a message with this id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE fromMe = 1 AND state = 0 ORDER BY sortAtMs ASC")
    suspend fun pendingOutgoing(): List<MessageEntity>

    @Query("UPDATE messages SET state = 1, serverAtMs = :atMs, sortAtMs = :atMs WHERE id = :id AND fromMe = 1 AND state = 0")
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

    @Query("UPDATE messages SET readAtMs = :atMs WHERE fromMe = 0 AND readAtMs IS NULL")
    suspend fun markIncomingRead(atMs: Long): Int

    @Query("SELECT id FROM messages WHERE fromMe = 0 AND readAtMs IS NOT NULL AND readReceiptSent = 0")
    suspend fun unsentReadReceipts(): List<String>

    @Query("UPDATE messages SET readReceiptSent = 1 WHERE id IN (:ids)")
    suspend fun markReadReceiptsSent(ids: List<String>)

    @Query("SELECT * FROM messages WHERE fromMe = 0 AND readAtMs IS NULL ORDER BY sortAtMs DESC LIMIT :limit")
    suspend fun unreadIncoming(limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class MamiDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
}
