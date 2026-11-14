package app.hushmessenger.extension;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Private local snapshots used only by Keep unsent messages. */
final class AntiUnsendStore extends SQLiteOpenHelper {
    static final String DATABASE_NAME = "hush_unsent.db";
    private static final int DATABASE_VERSION = 4;
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_NOTIFICATION_ROWS = 30;
    private static final int MAX_MEDIA_ROWS = 2_000;
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

    static final class MediaEntry {
        final String messageId;
        final String mimeType;
        final String localPath;
        final long mediaKeyTimestamp;
        final long receivedAt;
        final long unsentAt;

        MediaEntry(String messageId, String mimeType, String localPath,
                long mediaKeyTimestamp, long receivedAt, long unsentAt) {
            this.messageId = messageId;
            this.mimeType = mimeType;
            this.localPath = localPath;
            this.mediaKeyTimestamp = mediaKeyTimestamp;
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
        createMediaTable(db);
    }

    private static void createMediaTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS media (" +
            "media_key TEXT PRIMARY KEY NOT NULL," +
            "message_id TEXT," +
            "thread_id TEXT," +
            "message_timestamp INTEGER," +
            "mime_type TEXT," +
            "content_hash TEXT," +
            "local_path TEXT," +
            "received_at INTEGER NOT NULL," +
            "last_seen_at INTEGER NOT NULL," +
            "unsent_at INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_message_id ON media(message_id, unsent_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_thread_time ON media(thread_id, message_timestamp)");
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
        if (oldVersion < 3) createMediaTable(db);
        else if (oldVersion < 4) db.execSQL("ALTER TABLE media ADD COLUMN content_hash TEXT");
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

    synchronized void captureMessageMetadata(String messageId, String threadId, String senderId,
            long messageTimestamp, long now) {
        if (messageId == null || messageId.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues first = new ContentValues();
        first.put("message_id", messageId);
        if (threadId != null && !threadId.isEmpty()) first.put("thread_id", threadId);
        if (senderId != null && !senderId.isEmpty()) first.put("sender_id", senderId);
        if (messageTimestamp > 0) first.put("message_timestamp", messageTimestamp);
        first.put("received_at", now);
        first.put("last_seen_at", now);
        long inserted = db.insertWithOnConflict("messages", null, first, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted == -1) {
            db.execSQL("UPDATE messages SET " +
                "thread_id=COALESCE(NULLIF(thread_id,''),?), " +
                "sender_id=COALESCE(NULLIF(sender_id,''),?), " +
                "message_timestamp=CASE WHEN message_timestamp IS NULL OR message_timestamp=0 THEN ? ELSE message_timestamp END, " +
                "last_seen_at=? WHERE message_id=?",
                new Object[] {threadId, senderId, messageTimestamp > 0 ? messageTimestamp : null, now, messageId});
        }
        pruneOccasionally(db, now);
    }

    /**
     * Record image metadata from the message model itself. Prefer the direct path; if it is absent,
     * link only one pending file with the exact media-key timestamp and MIME type.
     */
    synchronized void captureMediaMetadataForMessage(String directPath, String messageId,
            String threadId, long mediaKeyTimestamp, String mimeType, long now) {
        if (directPath == null || directPath.isEmpty() || messageId == null || messageId.isEmpty() ||
                mimeType == null || !mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return;
        SQLiteDatabase db = getWritableDatabase();
        invalidateConflictingLocalMedia(db, messageId, mediaKeyTimestamp, mimeType, now);
        if (linkPendingMediaForMessage(messageId, threadId, mediaKeyTimestamp, mimeType, now)) return;
        if (hasSavedMediaForMessage(db, messageId, mediaKeyTimestamp, mimeType)) return;
        String key = mediaKey(directPath, mediaKeyTimestamp);
        if (key == null) return;
        long messageUnsentAt = 0;
        try (Cursor cursor = db.query("messages", new String[] {"unsent_at"},
                "message_id=?", new String[] {messageId}, null, null, null, "1")) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) messageUnsentAt = cursor.getLong(0);
        }
        ContentValues values = new ContentValues();
        values.put("media_key", key);
        values.put("message_id", messageId);
        if (threadId != null && !threadId.isEmpty()) values.put("thread_id", threadId);
        if (mediaKeyTimestamp > 0) values.put("message_timestamp", mediaKeyTimestamp);
        values.put("mime_type", mimeType);
        if (messageUnsentAt > 0) values.put("unsent_at", messageUnsentAt);
        values.put("received_at", now);
        values.put("last_seen_at", now);
        long inserted = db.insertWithOnConflict("media", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted == -1) {
            String existingMessageId = null;
            String existingLocalPath = null;
            long existingUnsentAt = 0;
            try (Cursor cursor = db.query("media", new String[] {"message_id", "unsent_at", "local_path"},
                    "media_key=?", new String[] {key}, null, null, null, "1")) {
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) existingMessageId = cursor.getString(0);
                    if (!cursor.isNull(1)) existingUnsentAt = cursor.getLong(1);
                    if (!cursor.isNull(2)) existingLocalPath = cursor.getString(2);
                }
            }
            if (existingMessageId != null && existingMessageId.isEmpty()) {
                // A previously detected direct-path collision remains unresolved.
            } else if (existingMessageId != null && !existingMessageId.equals(messageId)) {
                if (existingLocalPath != null) new File(existingLocalPath).delete();
                ContentValues ambiguous = new ContentValues();
                ambiguous.put("message_id", "");
                ambiguous.putNull("unsent_at");
                ambiguous.putNull("local_path");
                ambiguous.put("last_seen_at", now);
                db.update("media", ambiguous, "media_key=?", new String[] {key});
            } else {
                ContentValues update = new ContentValues();
                update.put("message_id", messageId);
                if (threadId != null && !threadId.isEmpty()) update.put("thread_id", threadId);
                if (mediaKeyTimestamp > 0) update.put("message_timestamp", mediaKeyTimestamp);
                update.put("mime_type", mimeType);
                if (existingUnsentAt > 0) update.put("unsent_at", existingUnsentAt);
                else if (messageUnsentAt > 0) update.put("unsent_at", messageUnsentAt);
                update.put("last_seen_at", now);
                db.update("media", update, "media_key=?", new String[] {key});
            }
        }
        pruneOccasionally(db, now);
    }

    /**
     * Link a completed local download to a message when Messenger omitted direct_path.
     * The media-key timestamp and MIME type must identify exactly one pending file.
     */
    synchronized boolean linkPendingMediaForMessage(String messageId, String threadId,
            long mediaKeyTimestamp, String mimeType, long now) {
        if (messageId == null || messageId.isEmpty() || mediaKeyTimestamp <= 0 ||
                mimeType == null || !mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return false;
        SQLiteDatabase db = getWritableDatabase();
        if (hasOtherMessageMediaMetadata(db, messageId, mediaKeyTimestamp, mimeType)) return false;
        String pendingKey = null;
        boolean pendingHasFile = false;
        int matches = 0;
        try (Cursor cursor = db.query("media", new String[] {"media_key", "local_path"},
                "message_timestamp=? AND mime_type=? AND message_id IS NULL AND content_hash IS NOT NULL",
                new String[] {Long.toString(mediaKeyTimestamp), mimeType}, null, null,
                "received_at DESC", "2")) {
            while (cursor.moveToNext()) {
                matches++;
                pendingKey = cursor.getString(0);
                pendingHasFile = !cursor.isNull(1) && !cursor.getString(1).isEmpty();
            }
        }
        if (matches != 1 || pendingKey == null || !pendingHasFile) return false;
        long messageUnsentAt = 0;
        try (Cursor cursor = db.query("messages", new String[] {"unsent_at"},
                "message_id=?", new String[] {messageId}, null, null, null, "1")) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) messageUnsentAt = cursor.getLong(0);
        }
        ContentValues linked = new ContentValues();
        linked.put("message_id", messageId);
        if (threadId != null && !threadId.isEmpty()) linked.put("thread_id", threadId);
        if (messageUnsentAt > 0) linked.put("unsent_at", messageUnsentAt);
        linked.put("last_seen_at", now);
        int updated = db.update("media", linked, "media_key=? AND message_id IS NULL",
            new String[] {pendingKey});
        pruneOccasionally(db, now);
        return updated == 1;
    }

    /** Store message-side image metadata even when the E2EE direct path is absent. */
    synchronized boolean captureMediaMetadataForMessageWithoutDirectPath(String messageId, String threadId,
            long mediaKeyTimestamp, String mimeType, long now) {
        if (messageId == null || messageId.isEmpty() || mediaKeyTimestamp <= 0 ||
                mimeType == null || !mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return false;
        SQLiteDatabase db = getWritableDatabase();
        invalidateConflictingLocalMedia(db, messageId, mediaKeyTimestamp, mimeType, now);
        if (linkPendingMediaForMessage(messageId, threadId, mediaKeyTimestamp, mimeType, now)) return true;
        if (hasSavedMediaForMessage(db, messageId, mediaKeyTimestamp, mimeType)) return true;
        String key = messageMediaKey(messageId, mediaKeyTimestamp, mimeType);
        if (key == null) return false;
        long messageUnsentAt = 0;
        try (Cursor cursor = db.query("messages", new String[] {"unsent_at"},
                "message_id=?", new String[] {messageId}, null, null, null, "1")) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) messageUnsentAt = cursor.getLong(0);
        }
        ContentValues values = new ContentValues();
        values.put("media_key", key);
        values.put("message_id", messageId);
        if (threadId != null && !threadId.isEmpty()) values.put("thread_id", threadId);
        values.put("message_timestamp", mediaKeyTimestamp);
        values.put("mime_type", mimeType);
        if (messageUnsentAt > 0) values.put("unsent_at", messageUnsentAt);
        values.put("received_at", now);
        values.put("last_seen_at", now);
        db.insertWithOnConflict("media", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        pruneOccasionally(db, now);
        return true;
    }

    /**
     * Resolve a downloaded image to one exact message-side row when possible. If no unique
     * message row exists, keep it as pending media and wait for the model callback.
     */
    synchronized String capturePendingDownloadedMedia(String fallbackKey, long mediaKeyTimestamp,
            String mimeType, String contentHash, long now) {
        if (fallbackKey == null || fallbackKey.isEmpty() || mediaKeyTimestamp <= 0 ||
                mimeType == null || !mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/") ||
                contentHash == null || contentHash.isEmpty()) return null;
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.query("media", new String[] {"media_key"},
                "media_key=?", new String[] {fallbackKey}, null, null, null, "1")) {
            if (cursor.moveToFirst()) {
                ContentValues seen = new ContentValues();
                seen.put("content_hash", contentHash);
                seen.put("last_seen_at", now);
                db.update("media", seen, "media_key=?", new String[] {fallbackKey});
                pruneOccasionally(db, now);
                return fallbackKey;
            }
        }
        int pendingCandidates = 0;
        try (Cursor cursor = db.query("media", new String[] {"media_key"},
                "message_timestamp=? AND mime_type=? AND message_id IS NULL AND content_hash IS NOT NULL",
                new String[] {Long.toString(mediaKeyTimestamp), mimeType}, null, null, null, "2")) {
            while (cursor.moveToNext()) pendingCandidates++;
        }
        String exactMessageKey = null;
        String exactLocalPath = null;
        String exactContentHash = null;
        int candidates = 0;
        if (pendingCandidates == 0) {
            try (Cursor cursor = db.query("media", new String[] {"media_key", "local_path", "content_hash"},
                    "message_timestamp=? AND mime_type=? AND message_id IS NOT NULL AND message_id!=''",
                    new String[] {Long.toString(mediaKeyTimestamp), mimeType}, null, null,
                    "received_at DESC", "2")) {
                while (cursor.moveToNext()) {
                    candidates++;
                    exactMessageKey = cursor.getString(0);
                    exactLocalPath = cursor.isNull(1) ? null : cursor.getString(1);
                    exactContentHash = cursor.isNull(2) ? null : cursor.getString(2);
                }
            }
        }
        boolean unfilledUniqueMessageRow = candidates == 1 && exactContentHash == null && exactLocalPath == null;
        boolean identicalPreviouslyCapturedImage = candidates == 1 && contentHash.equals(exactContentHash);
        if (candidates == 1 && exactMessageKey != null &&
                (unfilledUniqueMessageRow || identicalPreviouslyCapturedImage)) {
            ContentValues matched = new ContentValues();
            matched.put("content_hash", contentHash);
            matched.put("last_seen_at", now);
            db.update("media", matched, "media_key=?", new String[] {exactMessageKey});
            pruneOccasionally(db, now);
            return exactMessageKey;
        }
        ContentValues pending = new ContentValues();
        pending.put("media_key", fallbackKey);
        pending.put("message_timestamp", mediaKeyTimestamp);
        pending.put("mime_type", mimeType);
        pending.put("content_hash", contentHash);
        pending.put("received_at", now);
        pending.put("last_seen_at", now);
        db.insertWithOnConflict("media", null, pending, SQLiteDatabase.CONFLICT_IGNORE);
        pruneOccasionally(db, now);
        return fallbackKey;
    }

    synchronized boolean saveMediaFileByKey(String mediaKey, String localPath,
            String contentHash, long now) {
        if (mediaKey == null || mediaKey.isEmpty() || localPath == null || localPath.isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("local_path", localPath);
        if (contentHash != null && !contentHash.isEmpty()) values.put("content_hash", contentHash);
        values.put("last_seen_at", now);
        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update("media", values,
            "media_key=? AND (message_id IS NULL OR message_id!='')",
            new String[] {mediaKey});
        pruneOccasionally(db, now);
        return updated > 0;
    }

    synchronized boolean mediaIsLinked(String mediaKey) {
        if (mediaKey == null || mediaKey.isEmpty()) return false;
        try (Cursor cursor = getReadableDatabase().query("media", new String[] {"message_id"},
                "media_key=?", new String[] {mediaKey}, null, null, null, "1")) {
            return cursor.moveToFirst() && !cursor.isNull(0) && !cursor.getString(0).isEmpty();
        }
    }

    private static boolean hasOtherMessageMediaMetadata(SQLiteDatabase db, String messageId,
            long mediaKeyTimestamp, String mimeType) {
        try (Cursor cursor = db.query("media", new String[] {"media_key"},
                "message_timestamp=? AND mime_type=? AND message_id IS NOT NULL AND message_id!='' AND message_id!=?",
                new String[] {Long.toString(mediaKeyTimestamp), mimeType, messageId},
                null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    /**
     * If a second message later appears with the same timestamp/MIME pair, a previously
     * timestamp-linked local capture is no longer provably attributable. Remove that link
     * rather than showing the image under the wrong message.
     */
    private static void invalidateConflictingLocalMedia(SQLiteDatabase db, String messageId,
            long mediaKeyTimestamp, String mimeType, long now) {
        if (mediaKeyTimestamp <= 0 || mimeType == null) return;
        String selection = "media_key LIKE 'local-%' AND message_timestamp=? AND mime_type=? " +
            "AND message_id IS NOT NULL AND message_id!='' AND message_id!=?";
        String[] args = new String[] {Long.toString(mediaKeyTimestamp), mimeType, messageId};
        List<String> keys = new ArrayList<>();
        try (Cursor cursor = db.query("media", new String[] {"media_key", "local_path"},
                selection, args, null, null, null)) {
            while (cursor.moveToNext()) {
                keys.add(cursor.getString(0));
                if (!cursor.isNull(1)) new File(cursor.getString(1)).delete();
            }
        }
        ContentValues ambiguous = new ContentValues();
        ambiguous.put("message_id", "");
        ambiguous.putNull("unsent_at");
        ambiguous.putNull("local_path");
        ambiguous.put("last_seen_at", now);
        for (String key : keys) {
            db.update("media", ambiguous, "media_key=?", new String[] {key});
        }
    }

    private static boolean hasSavedMediaForMessage(SQLiteDatabase db, String messageId,
            long mediaKeyTimestamp, String mimeType) {
        try (Cursor cursor = db.query("media", new String[] {"media_key"},
                "message_id=? AND message_timestamp=? AND mime_type=? AND local_path IS NOT NULL",
                new String[] {messageId, Long.toString(mediaKeyTimestamp), mimeType}, null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    static String messageMediaKey(String messageId, long mediaKeyTimestamp, String mimeType) {
        if (messageId == null || messageId.isEmpty() || mimeType == null) return null;
        String digest = mediaKey("message:" + messageId + ":" + mimeType, mediaKeyTimestamp);
        return digest == null ? null : "message-" + digest;
    }

    static String downloadedMediaKey(long mediaKeyTimestamp, String mimeType, String contentHash) {
        if (mediaKeyTimestamp <= 0 || mimeType == null || contentHash == null || contentHash.isEmpty()) return null;
        String digest = mediaKey("local:" + mimeType + ":" + contentHash, mediaKeyTimestamp);
        return digest == null ? null : "local-" + digest;
    }

    synchronized void captureMediaMetadata(String directPath, long mediaKeyTimestamp,
            String mimeType, long now) {
        if (directPath == null || directPath.isEmpty() || mimeType == null ||
                !mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return;
        String key = mediaKey(directPath, mediaKeyTimestamp);
        if (key == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("media_key", key);
        if (mediaKeyTimestamp > 0) values.put("message_timestamp", mediaKeyTimestamp);
        values.put("mime_type", mimeType);
        values.put("received_at", now);
        values.put("last_seen_at", now);
        long inserted = db.insertWithOnConflict("media", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted == -1) {
            ContentValues update = new ContentValues();
            if (mediaKeyTimestamp > 0) update.put("message_timestamp", mediaKeyTimestamp);
            update.put("mime_type", mimeType);
            update.put("last_seen_at", now);
            db.update("media", update, "media_key=?", new String[] {key});
        }
        pruneOccasionally(db, now);
    }

    synchronized String mediaLocalPath(String directPath, long mediaKeyTimestamp) {
        if (directPath == null || directPath.isEmpty()) return null;
        try (Cursor cursor = getReadableDatabase().query("media", new String[] {"local_path"},
                "media_key=?", new String[] {mediaKey(directPath, mediaKeyTimestamp)}, null, null, null, "1")) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null;
            return cursor.getString(0);
        }
    }

    synchronized boolean saveMediaFile(String directPath, long mediaKeyTimestamp,
            String localPath, long now) {
        return saveMediaFile(directPath, mediaKeyTimestamp, localPath, null, now);
    }

    synchronized boolean saveMediaFile(String directPath, long mediaKeyTimestamp,
            String localPath, String contentHash, long now) {
        if (directPath == null || directPath.isEmpty() || localPath == null || localPath.isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("local_path", localPath);
        if (contentHash != null && !contentHash.isEmpty()) values.put("content_hash", contentHash);
        values.put("last_seen_at", now);
        SQLiteDatabase db = getWritableDatabase();
        // Empty message_id is a deliberate ambiguity marker set when one media identity
        // appears on two different messages. Never archive/display that file as an exact match.
        int updated = db.update("media", values,
            "media_key=? AND (message_id IS NULL OR message_id!='')",
            new String[] {mediaKey(directPath, mediaKeyTimestamp)});
        pruneOccasionally(db, now);
        return updated > 0;
    }

    synchronized List<MediaEntry> listUnsentMedia(String messageId) {
        if (messageId == null || messageId.isEmpty()) return Collections.emptyList();
        List<MediaEntry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("media",
                new String[] {"message_id", "mime_type", "local_path", "message_timestamp", "received_at", "unsent_at"},
                "message_id=? AND unsent_at IS NOT NULL AND local_path IS NOT NULL",
                new String[] {messageId}, null, null, "received_at ASC")) {
            while (cursor.moveToNext()) {
                entries.add(new MediaEntry(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.isNull(3) ? 0 : cursor.getLong(3), cursor.getLong(4),
                    cursor.isNull(5) ? 0 : cursor.getLong(5)));
            }
        }
        return entries;
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
            ContentValues seen = new ContentValues();
            seen.put("last_seen_at", now);
            db.update("messages", seen, "message_id=?", new String[] {messageId});
            // Duplicate or out-of-order revoke callbacks must not move the original unsent time forward.
            db.execSQL("UPDATE messages SET unsent_at=CASE " +
                "WHEN unsent_at IS NULL OR unsent_at=0 OR unsent_at>? THEN ? ELSE unsent_at END " +
                "WHERE message_id=?", new Object[] {now, now, messageId});
        }
        ContentValues mediaSeen = new ContentValues();
        mediaSeen.put("last_seen_at", now);
        db.update("media", mediaSeen, "message_id=?", new String[] {messageId});
        db.execSQL("UPDATE media SET unsent_at=CASE " +
            "WHEN unsent_at IS NULL OR unsent_at=0 OR unsent_at>? THEN ? ELSE unsent_at END " +
            "WHERE message_id=?", new Object[] {now, now, messageId});
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
        SQLiteDatabase db = getWritableDatabase();
        deleteMediaFiles(db, "unsent_at IS NOT NULL", null);
        db.delete("media", "unsent_at IS NOT NULL", null);
        return db.delete("messages", "unsent_at IS NOT NULL", null);
    }

    synchronized int clearAllHistory() {
        SQLiteDatabase db = getWritableDatabase();
        deleteMediaFiles(db, null, null);
        int deleted = db.delete("media", null, null);
        deleted += db.delete("messages", null, null);
        deleted += db.delete("notification_snapshots", null, null);
        return deleted;
    }

    private void pruneOccasionally(SQLiteDatabase db, long now) {
        if ((++writes & 127) != 0) return;
        prune(db, now);
    }

    private void prune(SQLiteDatabase db, long now) {
        String cutoff = Long.toString(now - RETENTION_MS);
        deleteMediaFiles(db, "received_at<?", new String[] {cutoff});
        db.delete("media", "received_at<?", new String[] {cutoff});
        deleteMediaForMessageIds(db,
            "SELECT message_id FROM messages WHERE received_at<?", new String[] {cutoff});
        db.delete("messages", "received_at<?", new String[] {cutoff});
        int count = 0;
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM messages", null)) {
            if (cursor.moveToFirst()) count = cursor.getInt(0);
        }
        int excess = count - MAX_ROWS;
        if (excess > 0) {
            String oldestMessages = "SELECT message_id FROM messages ORDER BY last_seen_at ASC LIMIT " + excess;
            deleteMediaForMessageIds(db, oldestMessages, null);
            db.execSQL("DELETE FROM messages WHERE message_id IN (" + oldestMessages + ")");
        }
        int mediaCount = 0;
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM media", null)) {
            if (cursor.moveToFirst()) mediaCount = cursor.getInt(0);
        }
        int mediaExcess = mediaCount - MAX_MEDIA_ROWS;
        if (mediaExcess > 0) {
            deleteOldestMediaFiles(db, mediaExcess);
            db.execSQL("DELETE FROM media WHERE media_key IN (" +
                "SELECT media_key FROM media ORDER BY last_seen_at ASC LIMIT " + mediaExcess + ")");
        }
    }

    synchronized void pruneNow(long now) {
        prune(getWritableDatabase(), now);
    }

    static String mediaKey(String directPath, long mediaKeyTimestamp) {
        if (directPath == null || directPath.isEmpty()) return null;
        try {
            String identity = directPath + "\n" + mediaKeyTimestamp;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void deleteMediaForMessageIds(SQLiteDatabase db, String messageIdQuery, String[] args) {
        String where = "message_id IN (" + messageIdQuery + ")";
        deleteMediaFiles(db, where, args);
        db.delete("media", where, args);
    }

    private static void deleteMediaFiles(SQLiteDatabase db, String where, String[] args) {
        try (Cursor cursor = db.query("media", new String[] {"local_path"}, where, args,
                null, null, null)) {
            while (cursor.moveToNext()) {
                if (!cursor.isNull(0)) new File(cursor.getString(0)).delete();
            }
        }
    }

    private static void deleteOldestMediaFiles(SQLiteDatabase db, int limit) {
        try (Cursor cursor = db.query("media", new String[] {"local_path"}, null, null,
                null, null, "last_seen_at ASC", Integer.toString(limit))) {
            while (cursor.moveToNext()) {
                if (!cursor.isNull(0)) new File(cursor.getString(0)).delete();
            }
        }
    }

    synchronized void clearForTests() {
        SQLiteDatabase db = getWritableDatabase();
        deleteMediaFiles(db, null, null);
        db.delete("media", null, null);
        db.delete("messages", null, null);
        db.delete("notification_snapshots", null, null);
        writes = 0;
    }
}
