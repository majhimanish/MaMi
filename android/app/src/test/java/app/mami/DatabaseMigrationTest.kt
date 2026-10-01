package app.mami

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mami.data.db.AnswerEntity
import app.mami.data.db.MamiDatabase
import app.mami.data.db.MoodEntity
import app.mami.data.db.MessageKind
import app.mami.data.db.OutboxEntity
import app.mami.data.db.Transfer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Testers' phones have the first release's database; updating the app must keep their conversation. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DatabaseMigrationTest {
    @Test
    fun upgradesTheFirstReleaseWithoutLosingMessages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)

        // Exactly the table the first release's Room created.
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `fromMe` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                    "`body` TEXT NOT NULL, `batteryPercent` INTEGER, `sentAtMs` INTEGER NOT NULL, `sortAtMs` INTEGER NOT NULL, " +
                    "`state` INTEGER NOT NULL, `serverAtMs` INTEGER, `deliveredAtMs` INTEGER, `readAtMs` INTEGER, " +
                    "`readReceiptSent` INTEGER NOT NULL, `replyTo` TEXT, PRIMARY KEY(`id`))",
            )
            old.execSQL(
                "INSERT INTO messages (id, fromMe, kind, body, sentAtMs, sortAtMs, state, readReceiptSent) " +
                    "VALUES ('m1', 1, 'text', 'hello from v1', 1, 1, 3, 1)",
            )
            old.version = 1
        }

        val db = Room.databaseBuilder(context, MamiDatabase::class.java, name)
            .addMigrations(MamiDatabase.MIGRATION_1_2, MamiDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val message = db.messages().get("m1")!!
            assertEquals("hello from v1", message.body)
            assertEquals(MessageKind.TEXT, message.kind)
            assertEquals(Transfer.DONE, message.transfer)
            assertFalse(message.unsent)
            assertNull(message.mediaKind)

            // The new columns and table work.
            db.messages().setMyReaction("m1", "❤️")
            db.messages().setStarred("m1", true)
            assertEquals("❤️", db.messages().get("m1")!!.myReaction)
            assertEquals(1, db.messages().search("%from v1%").size)
            db.outbox().add(OutboxEntity("o1", "{}", 1))
            assertEquals(1, db.outbox().all().size)

            // Version 3: answers, moods and scheduled messages.
            db.together().putAnswer(AnswerEntity(day = 20_000, questionId = "q001", mine = "You", mineAtMs = 5))
            assertEquals("You", db.together().answer(20_000)?.mine)
            db.together().addMood(MoodEntity("m1", fromMe = true, mood = "😌", atMs = 6))
            assertNull(db.messages().nextScheduled(0))
        }
        db.close()
        context.deleteDatabase(name)
    }
}
