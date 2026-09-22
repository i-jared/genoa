package app.gather.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow

class DraftStore(context: Context) : SQLiteOpenHelper(context, "gather.db", null, 3) {
    val changes = MutableStateFlow(0L)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE drafts(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL,file TEXT NOT NULL,kind TEXT NOT NULL,mime TEXT NOT NULL,duration_ms INTEGER NOT NULL,reply_to TEXT,state TEXT NOT NULL,percent INTEGER NOT NULL,error TEXT,created_at INTEGER NOT NULL,owner_id TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE TABLE cache(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        createWatchProgress(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2)
            db.execSQL("ALTER TABLE drafts ADD COLUMN owner_id TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3) createWatchProgress(db)
    }

    private fun createWatchProgress(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE watch_progress(account TEXT NOT NULL,message_id TEXT NOT NULL,conversation_id TEXT NOT NULL,position_ms INTEGER NOT NULL,watched INTEGER NOT NULL,revision TEXT NOT NULL,dirty INTEGER NOT NULL,PRIMARY KEY(account,message_id))")
    }

    fun watchProgress(account: String): List<LocalWatchProgress> =
        readableDatabase.rawQuery("SELECT * FROM watch_progress WHERE account=?", arrayOf(account)).use { c ->
            buildList {
                while (c.moveToNext()) add(LocalWatchProgress(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) == 1, c.getString(5), c.getInt(6) == 1))
            }
        }

    fun saveWatchProgress(progress: LocalWatchProgress) {
        writableDatabase.execSQL(
            "INSERT INTO watch_progress VALUES(?,?,?,?,?,?,1) ON CONFLICT(account,message_id) DO UPDATE SET position_ms=excluded.position_ms,watched=MAX(watched,excluded.watched),revision=excluded.revision,dirty=1",
            arrayOf<Any>(progress.account, progress.messageId, progress.conversationId, progress.positionMs, if (progress.watched) 1 else 0, progress.revision),
        )
    }

    fun acknowledgeWatchProgress(progress: LocalWatchProgress) {
        writableDatabase.execSQL("UPDATE watch_progress SET dirty=0 WHERE account=? AND message_id=? AND revision=?", arrayOf(progress.account, progress.messageId, progress.revision))
    }

    fun removeWatchProgress(account: String, messageId: String? = null) {
        writableDatabase.delete("watch_progress", if (messageId == null) "account=?" else "account=? AND message_id=?", if (messageId == null) arrayOf(account) else arrayOf(account, messageId))
    }

    fun save(d: Draft) {
        writableDatabase.insertWithOnConflict(
            "drafts",
            null,
            ContentValues().apply {
                put("id", d.id)
                put("conversation_id", d.conversationId)
                put("file", d.file)
                put("kind", d.kind)
                put("mime", d.mime)
                put("duration_ms", d.durationMs)
                put("reply_to", d.replyTo)
                put("state", d.state.name)
                put("percent", d.percent)
                put("error", d.error)
                put("created_at", d.createdAt)
                put("owner_id", d.ownerId)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        changes.value++
    }

    fun all(): List<Draft> =
        readableDatabase.rawQuery("SELECT * FROM drafts ORDER BY created_at", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Draft(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getLong(5),
                        c.getString(6),
                        DraftState.valueOf(c.getString(7)),
                        c.getInt(8),
                        c.getString(9),
                        c.getLong(10),
                        c.getString(11),
                    )
                )
            }
        }

    fun get(id: String) = all().firstOrNull { it.id == id }

    fun remove(id: String) {
        writableDatabase.delete("drafts", "id=?", arrayOf(id))
        changes.value++
    }

    fun cache(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "cache",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun cached(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM cache WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun clearCache() {
        writableDatabase.delete("cache", null, null)
    }
}
