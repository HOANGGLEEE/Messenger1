package app.hushmessenger.extension;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Private local snapshots used only by Keep unsent messages. */
final class AntiUnsendStore extends SQLiteOpenHelper {
    static final String DATABASE_NAME = "hush_unsent.db";
    private static final int DATABASE_VERSION = 2;
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_NOTIFICATION_ROWS = 30;
    private static volatile AntiUnsendStore instance;
    private int writes;

    static final class Entry {
        final String messageId;
        final String threadId;
        final String senderId;
        final String text;
        final long messageTimestamp;
        final long receivedAt;
        final long unsentAt;

        Entry(String messageId, String threadId, String senderId, String text,
                long messageTimestamp, long receivedAt, long unsentAt) {
            this.messageId = messageId;
            this.threadId = threadId;
            this.senderId = senderId;
            this.text = text;
            this.messageTimestamp = messageTimestamp;
            this.receivedAt = receivedAt;
            this.unsentAt = unsentAt;
        }
    }

    static final class NotificationEntry {
        final String title;
        final String text;
        final long postedAt;

        NotificationEntry(String title, String text, long postedAt) {
            this.title = title;
            this.text = text;
            this.postedAt = postedAt;
        }
    }

    static AntiUnsendStore get(Context context) {
        Context app = context.getApplicationContext();
        Context safe = app != null ? app : context;
        AntiUnsendStore current = instance;
        if (current != null) return current;
        synchronized (AntiUnsendStore.class) {
            if (instance == null) instance = new AntiUnsendStore(safe);
            return instance;
        }
    }

    private AntiUnsendStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (" +
            "message_id TEXT PRIMARY KEY NOT NULL," +
            "thread_id TEXT," +
            "sender_id TEXT," +
            "text TEXT," +
            "message_timestamp INTEGER," +
            "received_at INTEGER NOT NULL," +
            "last_seen_at INTEGER NOT NULL," +
            "unsent_at INTEGER)");
        db.execSQL("CREATE INDEX messages_unsent_at ON messages(unsent_at DESC)");
        db.execSQL("CREATE INDEX messages_last_seen_at ON messages(last_seen_at)");
        createNotificationTable(db);
    }

    private static void createNotificationTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS notification_snapshots (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "title TEXT NOT NULL DEFAULT ''," +
            "text TEXT NOT NULL," +
            "posted_at INTEGER NOT NULL," +
            "UNIQUE(title, text, posted_at))");
        db.execSQL("CREATE INDEX IF NOT EXISTS notification_snapshots_posted_at " +
            "ON notification_snapshots(posted_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createNotificationTable(db);
    }

    synchronized void captureText(String messageId, String threadId, String senderId,
            String text, long messageTimestamp, long now) {
        if (messageId == null || messageId.isEmpty() || text == null || text.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues first = new ContentValues();
        first.put("message_id", messageId);
        if (threadId != null && !threadId.isEmpty()) first.put("thread_id", threadId);
        if (senderId != null && !senderId.isEmpty()) first.put("sender_id", senderId);
        first.put("text", text);
        if (messageTimestamp > 0) first.put("message_timestamp", messageTimestamp);
        first.put("received_at", now);
        first.put("last_seen_at", now);
        long inserted = db.insertWithOnConflict("messages", null, first, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted == -1) {
            ContentValues seen = new ContentValues();
            seen.put("last_seen_at", now);
            db.update("messages", seen, "message_id=?", new String[] {messageId});

            ContentValues missing = new ContentValues();
            if (threadId != null && !threadId.isEmpty()) missing.put("thread_id", threadId);
            if (senderId != null && !senderId.isEmpty()) missing.put("sender_id", senderId);
            missing.put("text", text);
            if (messageTimestamp > 0) missing.put("message_timestamp", messageTimestamp);
            db.update("messages", missing,
                "message_id=? AND (text IS NULL OR text='')", new String[] {messageId});
        }
        pruneOccasionally(db, now);
    }

    synchronized void markUnsent(String messageId, long now) {
        if (messageId == null || messageId.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues first = new ContentValues();
        first.put("message_id", messageId);
        first.put("received_at", now);
        first.put("last_seen_at", now);
        first.put("unsent_at", now);
        long inserted = db.insertWithOnConflict("messages", null, first, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted == -1) {
            ContentValues values = new ContentValues();
            values.put("last_seen_at", now);
            values.put("unsent_at", now);
            db.update("messages", values, "message_id=?", new String[] {messageId});
        }
        pruneOccasionally(db, now);
    }

    synchronized boolean captureNotification(String title, String text, long postedAt) {
        if (text == null || text.isEmpty()) return false;
        long time = postedAt > 0 ? postedAt : System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("title", title == null ? "" : title);
        values.put("text", text);
        values.put("posted_at", time);
        SQLiteDatabase db = getWritableDatabase();
        long inserted = db.insertWithOnConflict(
            "notification_snapshots", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted == -1) return false;
        db.execSQL("DELETE FROM notification_snapshots WHERE id NOT IN (" +
            "SELECT id FROM notification_snapshots ORDER BY posted_at DESC, id DESC LIMIT " +
            MAX_NOTIFICATION_ROWS + ")");
        return true;
    }

    synchronized List<NotificationEntry> listNotificationSnapshots(int limit) {
        if (limit <= 0) return Collections.emptyList();
        List<NotificationEntry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "notification_snapshots", new String[] {"title", "text", "posted_at"},
                null, null, null, null, "posted_at DESC, id DESC", Integer.toString(limit))) {
            while (cursor.moveToNext()) {
                entries.add(new NotificationEntry(
                    cursor.getString(0), cursor.getString(1), cursor.getLong(2)));
            }
        }
        return entries;
    }

    synchronized String unsentText(String messageId) {
        if (messageId == null || messageId.isEmpty()) return null;
        try (Cursor cursor = getReadableDatabase().query(
                "messages", new String[] {"text"}, "message_id=? AND unsent_at IS NOT NULL",
                new String[] {messageId}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            return cursor.isNull(0) ? null : cursor.getString(0);
        }
    }

    synchronized List<Entry> listUnsent(int limit) {
        if (limit <= 0) return Collections.emptyList();
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "messages", new String[] {"message_id", "thread_id", "sender_id", "text",
                    "message_timestamp", "received_at", "unsent_at"},
                "unsent_at IS NOT NULL", null, null, null, "unsent_at DESC", Integer.toString(limit))) {
            while (cursor.moveToNext()) {
                entries.add(new Entry(
                    cursor.getString(0),
                    cursor.isNull(1) ? null : cursor.getString(1),
                    cursor.isNull(2) ? null : cursor.getString(2),
                    cursor.isNull(3) ? null : cursor.getString(3),
                    cursor.isNull(4) ? 0 : cursor.getLong(4),
                    cursor.getLong(5),
                    cursor.getLong(6)));
            }
        }
        return entries;
    }

    synchronized int clearUnsentHistory() {
        return getWritableDatabase().delete("messages", "unsent_at IS NOT NULL", null);
    }

    synchronized int clearAllHistory() {
        SQLiteDatabase db = getWritableDatabase();
        int deleted = db.delete("messages", "unsent_at IS NOT NULL", null);
        deleted += db.delete("notification_snapshots", null, null);
        return deleted;
    }

    private void pruneOccasionally(SQLiteDatabase db, long now) {
        if ((++writes & 127) != 0) return;
        prune(db, now);
    }

    private void prune(SQLiteDatabase db, long now) {
        db.delete("messages", "received_at<?", new String[] {Long.toString(now - RETENTION_MS)});
        int count = 0;
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM messages", null)) {
            if (cursor.moveToFirst()) count = cursor.getInt(0);
        }
        int excess = count - MAX_ROWS;
        if (excess > 0) {
            db.execSQL("DELETE FROM messages WHERE message_id IN (" +
                "SELECT message_id FROM messages ORDER BY last_seen_at ASC LIMIT " + excess + ")");
        }
    }

    synchronized void pruneNow(long now) {
        prune(getWritableDatabase(), now);
    }

    synchronized void clearForTests() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("messages", null, null);
        db.delete("notification_snapshots", null, null);
        writes = 0;
    }
}
