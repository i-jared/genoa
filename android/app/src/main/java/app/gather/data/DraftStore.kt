package app.gather.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow

/** A discarded draft whose server copy may still exist. The row outlives its local media. */
data class DiscardTombstone(val id: String, val ownerId: String, val file: String, val discardedAt: Long)

/** A live segment written to disk but not yet accepted by the server. */
data class PendingLiveSegment(
    val messageId: String,
    val number: Int,
    val file: String,
    val seconds: Double,
    val bytes: Long,
)

/** Live sharing that has started on this phone and has not yet been ended on the server. */
data class LocalLiveSession(
    val id: String,
    val conversationId: String,
    val ownerId: String,
    val ending: Boolean,
    val directory: String,
)

class DraftStore(context: Context, name: String? = "gather.db") :
    SQLiteOpenHelper(context, name, null, 4) {
    val changes = MutableStateFlow(0L)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE drafts(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL,file TEXT NOT NULL,kind TEXT NOT NULL,mime TEXT NOT NULL,duration_ms INTEGER NOT NULL,reply_to TEXT,state TEXT NOT NULL,percent INTEGER NOT NULL,error TEXT,created_at INTEGER NOT NULL,owner_id TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE TABLE cache(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        createWatchProgress(db)
        createDiscardAndLive(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2)
            db.execSQL("ALTER TABLE drafts ADD COLUMN owner_id TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3) createWatchProgress(db)
        if (oldVersion < 4) createDiscardAndLive(db)
    }

    private fun createDiscardAndLive(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE drafts ADD COLUMN discarded_at INTEGER")
        db.execSQL("CREATE TABLE live_sessions(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL,owner_id TEXT NOT NULL,ending INTEGER NOT NULL,directory TEXT NOT NULL)")
        db.execSQL("CREATE TABLE live_segments(message_id TEXT NOT NULL,number INTEGER NOT NULL,file TEXT NOT NULL,seconds REAL NOT NULL,bytes INTEGER NOT NULL,PRIMARY KEY(message_id,number))")
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

    private fun values(d: Draft) =
        ContentValues().apply {
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
        }

    /**
     * Creates or replaces a draft, except that a discard tombstone is never overwritten.
     * Returns false when the draft was already discarded.
     */
    fun save(d: Draft): Boolean {
        require(d.state != DraftState.DISCARDED) { "Use markDiscarded" }
        val db = writableDatabase
        db.beginTransaction()
        val saved =
            try {
                val discarded =
                    db.rawQuery("SELECT 1 FROM drafts WHERE id=? AND state=?", arrayOf(d.id, DraftState.DISCARDED.name)).use { it.moveToFirst() }
                if (!discarded)
                    db.insertWithOnConflict("drafts", null, values(d).apply { put("id", d.id) }, SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
                !discarded
            } finally {
                db.endTransaction()
            }
        if (saved) changes.value++
        return saved
    }

    /**
     * Updates an existing, undiscarded draft. Background work uses only this, so a late write can
     * neither revive a discarded draft nor recreate a row that discard reconciliation removed.
     */
    fun update(d: Draft): Boolean {
        require(d.state != DraftState.DISCARDED) { "Use markDiscarded" }
        val changed =
            writableDatabase.update("drafts", values(d), "id=? AND state!=?", arrayOf(d.id, DraftState.DISCARDED.name)) > 0
        if (changed) changes.value++
        return changed
    }

    /** Persists the terminal discard decision; returns the draft as it was, if it existed. */
    fun markDiscarded(id: String, now: Long = System.currentTimeMillis()): Draft? {
        val db = writableDatabase
        db.beginTransaction()
        val previous =
            try {
                val row = query(db, "id=?", arrayOf(id)).firstOrNull()
                if (row != null && row.state != DraftState.DISCARDED)
                    db.update(
                        "drafts",
                        ContentValues().apply {
                            put("state", DraftState.DISCARDED.name)
                            put("discarded_at", now)
                        },
                        "id=?",
                        arrayOf(id),
                    )
                db.setTransactionSuccessful()
                row
            } finally {
                db.endTransaction()
            }
        changes.value++
        return previous
    }

    /** True when work for this draft must stop: discarded, or no longer on this phone. */
    fun isStopped(id: String): Boolean =
        readableDatabase.rawQuery("SELECT state FROM drafts WHERE id=?", arrayOf(id)).use {
            !it.moveToFirst() || it.getString(0) == DraftState.DISCARDED.name
        }

    fun discarded(): List<DiscardTombstone> =
        readableDatabase.rawQuery("SELECT id,owner_id,file,discarded_at FROM drafts WHERE state=?", arrayOf(DraftState.DISCARDED.name)).use { c ->
            buildList { while (c.moveToNext()) add(DiscardTombstone(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }

    private fun query(db: SQLiteDatabase, where: String, args: Array<String>): List<Draft> =
        db.rawQuery("SELECT id,conversation_id,file,kind,mime,duration_ms,reply_to,state,percent,error,created_at,owner_id FROM drafts WHERE $where ORDER BY created_at", args).use { c ->
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

    /** Drafts people can see and act on. Discard tombstones are internal. */
    fun all(): List<Draft> = query(readableDatabase, "state!=?", arrayOf(DraftState.DISCARDED.name))

    fun get(id: String) = query(readableDatabase, "id=? AND state!=?", arrayOf(id, DraftState.DISCARDED.name)).firstOrNull()

    fun remove(id: String) {
        writableDatabase.delete("drafts", "id=?", arrayOf(id))
        changes.value++
    }

    /** Removes a finished draft unless it was discarded meanwhile; the tombstone must stay. */
    fun removeUnlessDiscarded(id: String): Boolean {
        val removed = writableDatabase.delete("drafts", "id=? AND state!=?", arrayOf(id, DraftState.DISCARDED.name)) > 0
        if (removed) changes.value++
        return removed
    }

    fun startLive(session: LocalLiveSession) {
        writableDatabase.insertWithOnConflict(
            "live_sessions",
            null,
            ContentValues().apply {
                put("id", session.id)
                put("conversation_id", session.conversationId)
                put("owner_id", session.ownerId)
                put("ending", if (session.ending) 1 else 0)
                put("directory", session.directory)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun markLiveEnding(id: String) {
        writableDatabase.execSQL("UPDATE live_sessions SET ending=1 WHERE id=?", arrayOf(id))
    }

    fun liveSessions(): List<LocalLiveSession> =
        readableDatabase.rawQuery("SELECT id,conversation_id,owner_id,ending,directory FROM live_sessions", null).use { c ->
            buildList { while (c.moveToNext()) add(LocalLiveSession(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4))) }
        }

    fun liveSession(id: String) = liveSessions().firstOrNull { it.id == id }

    fun removeLive(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("live_segments", "message_id=?", arrayOf(id))
            db.delete("live_sessions", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addLiveSegment(segment: PendingLiveSegment) {
        writableDatabase.insertWithOnConflict(
            "live_segments",
            null,
            ContentValues().apply {
                put("message_id", segment.messageId)
                put("number", segment.number)
                put("file", segment.file)
                put("seconds", segment.seconds)
                put("bytes", segment.bytes)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun liveSegments(messageId: String): List<PendingLiveSegment> =
        readableDatabase.rawQuery("SELECT message_id,number,file,seconds,bytes FROM live_segments WHERE message_id=? ORDER BY number", arrayOf(messageId)).use { c ->
            buildList { while (c.moveToNext()) add(PendingLiveSegment(c.getString(0), c.getInt(1), c.getString(2), c.getDouble(3), c.getLong(4))) }
        }

    fun removeLiveSegment(messageId: String, number: Int) {
        writableDatabase.delete("live_segments", "message_id=? AND number=?", arrayOf(messageId, number.toString()))
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
