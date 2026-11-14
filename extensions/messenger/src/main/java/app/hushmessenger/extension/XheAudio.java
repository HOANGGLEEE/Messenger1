/*
 * The sound re-encode is adapted from Hushfacebook's AacReencode.java (same author), 2026.
 */
package app.hushmessenger.extension;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Makes two kinds of video sendable that Messenger, stock included, refuses with "Unable to send", because Meta's
 * own media scanner (Kaleidoscope) scores them as suspect, so the file itself has to change.
 *
 * <p>A video whose only sound is xHE-AAC: Android's AAC decoder has read xHE-AAC since Android 9 and every phone has
 * an AAC-LC encoder, so the sound is decoded and encoded again, the picture samples are copied as they are, and the
 * result replaces Messenger's copy of the shared file. A Samsung screen recording (#58): the box Samsung adds at the
 * top level of the file ({@code sefd}, its capture record) is renamed to {@code free}, the standard box players
 * skip. Its size stays, so no offset in the file moves.
 *
 * <p>Messenger calls {@link #fixSharedFile} on its background copy thread, right after it closes the copy and before
 * anything reads it. Any failure leaves the copy exactly as it was. A video picked inside a chat has no copy of its
 * own, so its transcoder calls {@link #transcodeSource} instead, which converts into the cache and never touches the
 * picked file.
 */
public final class XheAudio {
    static final String KEY = "xhe_audio";

    /** The AAC object type xHE-AAC (USAC) declares, as in {@code mp4a.40.42}. */
    static final int XHE_OBJECT_TYPE = 42;

    /** The top-level box Samsung writes its capture record in, {@code sefd}. Meta's scanner rejects a file with one. */
    static final int SAMSUNG_BOX = 0x73656664;

    /** The box every MP4 opens with, {@code ftyp}. A file without it is left alone. */
    static final int FILE_TYPE_BOX = 0x66747970;

    /** What a Samsung box is renamed to: {@code free}, which every reader skips. */
    private static final byte[] FREE = {'f', 'r', 'e', 'e'};

    /** How many top-level boxes are read before giving up. A real video has a handful. */
    static final int MOST_BOXES = 4096;

    /** What the new sound is encoded at. Facebook sends xHE-AAC at 48 to 96 kbps. */
    static final int BIT_RATE = 160_000;

    /** How long one wait on a codec lasts. */
    private static final long WAIT_US = 10_000;

    /** A codec that gives nothing back for this long has stalled. */
    static final long STALL_MS = 10_000;

    /** Rounds with nothing done that also count as a stall, so a codec that answers every wait at once can't spin. */
    static final int STALL_ROUNDS = 3_000;

    /** Room for one picture sample while joining; a larger one throws and the copy stays as it was. */
    static final int SAMPLE_BYTES = 8 << 20;

    /**
     * How much shorter than its original a converted track may come out, for the encoder's start-up delay and the last
     * block's rounding. A long video gets a hundredth of its length instead when that's more.
     */
    static final long SLACK_US = 500_000;

    /** The cache folder that holds converted copies of videos picked in a chat until Messenger has sent them. */
    static final String PICKED = "hush-xhe";

    /** How long a converted copy is kept. A send still going after a day has failed anyway. */
    static final long KEEP_MS = 24L * 60 * 60 * 1000;

    /**
     * Goes into the name of every converted copy along with the picked video's bytes. Bump it when what a conversion
     * makes changes, so a copy made the old way is never handed out again.
     */
    static final String CONVERSION = "2";

    /** How the files one conversion works in start. Each gets a name of its own, so two conversions never share one. */
    static final String SCRATCH = "pick-";

    /** What goes after a shared copy's name in the files its conversion writes beside it. */
    static final String BESIDE = ".hush-";

    /** How many conversions run at once. A send that finds both busy waits {@link Limits#slotWaitMs}, then goes as is. */
    static final int SLOTS = 2;
    static final Semaphore WORKERS = new Semaphore(SLOTS, true);

    /** A converted copy sent or reused this recently may still be read by its send, so it's never cleared for room. */
    static final long ACTIVE_MS = 60L * 60 * 1000;

    /** Guards the cache's files and the room conversions have claimed, so two at once never count the same space. */
    private static final Object ROOM = new Object();

    /** Bytes the conversions running now may still write, held against the disk's reserve. */
    private static long claimedDisk;

    /** The part of {@link #claimedDisk} headed for the picked-video cache, held against {@link Limits#cacheBytes}. */
    private static long claimedCache;

    /** How many rounds of a conversion pass between looks at the disk's free space. */
    static final int ROOM_ROUNDS = 16;

    /**
     * What one conversion may take on. Past any of these the video goes to Messenger as it is, which isn't a hook
     * failure. Tests swap in smaller ones.
     */
    static final class Limits {
        /** The most a video can hold for a conversion to start. */
        long inputBytes = 1L << 30;
        /** What the converted copies in the cache may hold together, the conversion in progress included. */
        long cacheBytes = 3L << 30;
        /** Space the disk keeps after a conversion, so one never fills the phone. */
        long reserveBytes = 256L << 20;
        /** How long one conversion may run, its copy of the picked video included. */
        long workMs = 10L * 60 * 1000;
        /** How long a send waits for a free conversion slot. */
        long slotWaitMs = 30_000;
    }

    static volatile Limits limits = new Limits();

    /** A video beyond what a conversion takes on. It goes to Messenger as it is, and isn't a hook failure. */
    static final class OverBudget extends IOException {
        OverBudget(String why) {
            super(why);
        }
    }

    /**
     * One conversion's time, checked between reads and between codec rounds and once more before its copy is kept,
     * so work that ran too long or was cancelled never puts a copy in place later. Every {@link #ROOM_ROUNDS} rounds
     * it also looks at the free space where the conversion writes, so conversions writing at once, or anything else
     * filling the phone, stop it at the reserve. A read that the platform or a provider blocks in can't be stopped
     * from here: it holds its slot until it returns, and other sends go ahead without a conversion once
     * {@link Limits#slotWaitMs} is up.
     */
    static final class Work {
        final long deadline;
        private final File folder;
        private final long reserveBytes;
        private int rounds;

        Work(long budgetMs, File folder, long reserveBytes) {
            deadline = SystemClock.elapsedRealtime() + budgetMs;
            this.folder = folder;
            this.reserveBytes = reserveBytes;
        }

        void check() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("the conversion was cancelled");
            if (SystemClock.elapsedRealtime() >= deadline) throw new OverBudget("the conversion ran out of time");
            if (rounds++ % ROOM_ROUNDS == 0 && folder.getUsableSpace() < reserveBytes) throw new OverBudget("the phone is nearly full");
        }
    }

    /** Takes a conversion slot, or throws when both stay busy for {@link Limits#slotWaitMs}. */
    private static void takeSlot() throws IOException {
        try {
            if (!WORKERS.tryAcquire(limits.slotWaitMs, TimeUnit.MILLISECONDS)) throw new OverBudget("both conversion slots stayed busy");
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("the send was cancelled while it waited to convert");
        }
    }

    private XheAudio() {}

    /**
     * Called by the patched share copy with Messenger's finished copy of a shared item. The copy runs on a background
     * thread; on the main thread a conversion could freeze Messenger, so it's skipped there.
     */
    public static void fixSharedFile(File file) {
        try {
            if (file == null || Looper.myLooper() == Looper.getMainLooper() || !Settings.enabled(KEY)) return;
            fix(file);
        } catch (OverBudget | InterruptedIOException | ClosedByInterruptException skipped) {
            // A file read when Messenger cancels the send ends in ClosedByInterruptException, which isn't a failure.
            android.util.Log.w("HushMessenger", "Left the shared video as it is: " + skipped.getMessage());
        } catch (Throwable error) {
            Settings.hookFailedPrivately(KEY, "Can't convert the shared video's sound", error);
        }
    }

    /**
     * Called by the patched video transcoder with the source of a video Messenger is about to send, which every video
     * picked in a chat goes through (#57). Answers the path of a copy in Messenger's cache whose sound is re-encoded to
     * AAC-LC when the video's only sound is xHE-AAC, or whose Samsung box is renamed (#58), else [source] as it was. A
     * plain path comes back as a path, a file or content Uri as a file Uri. Off, Pause, the main thread and any failure
     * give [source] back.
     */
    public static String transcodeSource(String source) {
        try {
            if (source == null || source.isEmpty() || Looper.myLooper() == Looper.getMainLooper() || !Settings.enabled(KEY)) {
                return source;
            }
            Context context = Settings.appContext;
            File copy = context == null ? null : convertPicked(context, source);
            if (copy == null) return source;
            pruneLater(context);
            return source.startsWith("/") ? copy.getPath() : Uri.fromFile(copy).toString();
        } catch (OverBudget | InterruptedIOException | ClosedByInterruptException skipped) {
            android.util.Log.w("HushMessenger", "Sent the picked video as it is: " + skipped.getMessage());
            return source;
        } catch (Throwable error) {
            Settings.hookFailedPrivately(KEY, "Can't convert the picked video's sound", error);
            return source;
        }
    }

    /**
     * The converted copy of [source] in the cache's {@link #PICKED} folder, made now or kept from an earlier try at
     * the same bytes, or null when the video doesn't need one. Copies older than {@link #KEEP_MS} are cleared first.
     *
     * <p>The picked video is copied once into a file only this call uses, and everything after reads that copy. The
     * result is kept under a name made from those bytes and {@link #CONVERSION}, so another video, or the same name
     * holding different bytes, never gets this one's copy. It's moved into place only once it's whole.
     *
     * <p>Each conversion holds one of {@link #SLOTS} slots and stays inside {@link #limits}: a bigger video, a full
     * cache or disk, a busy wait for a slot or a run past its time sends the video as it is.
     */
    static File convertPicked(Context context, String source) throws IOException {
        Uri uri = source.startsWith("/") ? Uri.fromFile(new File(source)) : Uri.parse(source);
        boolean isFile = "file".equals(uri.getScheme());
        if (!isFile && !"content".equals(uri.getScheme())) return null;
        File input = isFile ? new File(uri.getPath()) : null;
        // A first look at the picked video itself, so one that needs nothing is never copied.
        boolean wanted = isFile ? needsFix(input) || hasSamsungBox(input) : needsFix(context, uri) || hasSamsungBox(context, uri);
        if (!wanted) return null;
        Limits limits = XheAudio.limits;
        if (isFile && input.length() > limits.inputBytes) throw new OverBudget("the picked video is bigger than a conversion takes");
        File folder = new File(context.getCacheDir(), PICKED);
        takeSlot();
        try {
            return convertPicked(context, uri, input, folder, limits, new Work(limits.workMs, folder, limits.reserveBytes));
        } finally {
            WORKERS.release();
        }
    }

    private static File convertPicked(Context context, Uri uri, File input, File folder, Limits limits, Work work) throws IOException {
        // Made first and checked after, since the other conversion can make it in between and its mkdirs answers false.
        if (!folder.mkdirs() && !folder.isDirectory()) throw new IOException("the cache has no room for the converted video");
        long now = System.currentTimeMillis();
        prune(folder, now);
        if (folder.getUsableSpace() < limits.reserveBytes) throw new OverBudget("the phone is nearly full");
        File copied = File.createTempFile(SCRATCH, ".source", folder);
        File sound = null;
        File joined = null;
        long claimed = 0;
        try {
            String name;
            InputStream opened;
            try {
                opened = input != null ? new FileInputStream(input) : context.getContentResolver().openInputStream(uri);
            } catch (SecurityException lapsed) {
                // The grant can run out between the first look and this copy, and then Messenger can't read it either.
                return null;
            }
            try (InputStream in = opened) {
                if (in == null) throw new IOException("the picked video could not be opened");
                name = copyAndName(in, copied, limits, work);
            }
            File done = new File(folder, name + ".mp4");
            if (reuse(done)) return done;
            boolean xhe = needsFix(copied);
            // The video changed after the first look, and what was copied needs nothing.
            if (!xhe && !hasSamsungBox(copied)) return null;
            // A conversion writes beside the copy; a renamed Samsung box only moves it.
            long need = xhe ? converting(copied) : 0;
            makeRoom(folder, need, now, limits);
            claimed = need;
            if (xhe) {
                sound = File.createTempFile(SCRATCH, ".sound.mp4", folder);
                joined = File.createTempFile(SCRATCH, ".joined.mp4", folder);
                // The muxer writes only the tracks, so a Samsung box doesn't come along.
                reencode(copied, sound, work);
                join(copied, sound, joined, work);
                if (!repaired(joined, copied)) throw new IOException("the converted video came out incomplete");
            } else {
                // Nothing here reads the picture, so a copy that came in cut short would go out that way.
                if (!whole(copied)) throw new IOException("the picked video came in cut short");
                long size = copied.length();
                dropSamsungBoxes(copied);
                if (copied.length() != size || hasSamsungBox(copied)) throw new IOException("the renamed video came out wrong");
                joined = copied;
            }
            work.check();
            return publish(joined, done);
        } finally {
            copied.delete();
            if (sound != null) sound.delete();
            if (joined != null) joined.delete();
            unclaim(claimed, true);
        }
    }

    /**
     * Copies [in] to [out] and answers the name its converted copy is kept under: the SHA-256 of {@link #CONVERSION}
     * and every byte copied, in hex. Stops past {@link Limits#inputBytes}, when [work] runs out or finds the disk at
     * its reserve, and when the stream keeps answering with nothing.
     */
    static String copyAndName(InputStream in, File out, Limits limits, Work work) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IOException("SHA-256 is missing", missing);
        }
        digest.update(CONVERSION.getBytes(StandardCharsets.US_ASCII));
        try (OutputStream to = new FileOutputStream(out)) {
            byte[] buffer = new byte[1 << 16];
            long copied = 0;
            int empty = 0;
            for (int read; (read = in.read(buffer)) != -1; ) {
                work.check();
                if (read == 0) {
                    if (++empty >= STALL_ROUNDS) throw new IOException("the picked video stopped coming");
                    continue;
                }
                empty = 0;
                copied += read;
                if (copied > limits.inputBytes) throw new OverBudget("the picked video is bigger than a conversion takes");
                to.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder name = new StringBuilder(64);
        for (byte b : digest.digest()) name.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return name.toString();
    }

    /**
     * Moves the finished [made] to [done] and answers [done]. When another send of the same bytes got there first,
     * its copy is kept and [made] is left for the caller to delete. A rename puts the whole file in place at once, so
     * a reader never sees half of one.
     */
    static File publish(File made, File done) throws IOException {
        if (reuse(done) || made.renameTo(done) || reuse(done)) return done;
        throw new IOException("the converted video could not be kept");
    }

    /**
     * Whether [done] is a finished copy to hand out. It's marked used now, under the lock clearing takes too, so no
     * other conversion clears it for room while this send reads it.
     */
    static boolean reuse(File done) {
        synchronized (ROOM) {
            if (done.length() <= 0) return false;
            done.setLastModified(System.currentTimeMillis());
            return done.isFile();
        }
    }

    /**
     * Deletes what's in [folder] from more than {@link #KEEP_MS} before [now], and scratch files from more than twice a
     * conversion's time, which a conversion that's still running can't have left.
     */
    static void prune(File folder, long now) {
        synchronized (ROOM) {
            File[] old = folder.listFiles();
            if (old == null) return;
            long scratch = 2 * limits.workMs;
            for (File file : old) {
                long age = now - file.lastModified();
                if (age > KEEP_MS || (file.getName().startsWith(SCRATCH) && age > scratch)) file.delete();
            }
        }
    }

    /** Where {@link #pruneLater} clears the cache, off the thread that asked. Tests run it in place. */
    static java.util.concurrent.Executor pruneWork = work -> {
        Thread thread = new Thread(work, "HushXhePrune");
        thread.setDaemon(true);
        thread.start();
    };

    /** How long after a file is due a later clear runs, so one clear takes the files that fall due close together. */
    static final long PRUNE_SLACK_MS = 60_000;

    /** When the later clear that's waiting is due, in uptime, 0 when none is. Guarded by {@link #ROOM}; tests reset it. */
    static long pruneAt;

    /**
     * Clears what {@link #prune} would in the picked-video cache now, off this thread, and again once the next file left
     * there is due, so a day's copies don't wait for another pick. Messenger's start and every handed-out copy call it.
     */
    static void pruneLater(Context context) {
        pruneWork.execute(() -> pruneNow(new File(context.getCacheDir(), PICKED)));
    }

    private static void pruneNow(File folder) {
        try {
            long now = System.currentTimeMillis();
            prune(folder, now);
            long due = nextPrune(folder, now);
            if (due == 0) return;
            // Handler time stops while the phone sleeps, so this can run later than due, never sooner.
            long at = SystemClock.uptimeMillis() + due - now + PRUNE_SLACK_MS;
            synchronized (ROOM) {
                if (pruneAt != 0 && pruneAt <= at) return;
                pruneAt = at;
            }
            new android.os.Handler(Looper.getMainLooper()).postAtTime(() -> {
                synchronized (ROOM) {
                    if (pruneAt == at) pruneAt = 0;
                }
                pruneWork.execute(() -> pruneNow(folder));
            }, at);
        } catch (Throwable error) {
            android.util.Log.w("HushMessenger", "Can't clear old converted videos", error);
        }
    }

    /**
     * When {@link #prune} next has a file in [folder] to delete, in wall time, or 0 when it has none. A file already
     * due at [now] is one a clear just failed to delete, which the next start tries again.
     */
    static long nextPrune(File folder, long now) {
        File[] files = folder.listFiles();
        if (files == null) return 0;
        long next = Long.MAX_VALUE;
        long scratch = 2 * limits.workMs;
        for (File file : files) {
            long due = file.lastModified() + (file.getName().startsWith(SCRATCH) ? scratch : KEEP_MS);
            if (due >= now) next = Math.min(next, due);
        }
        return next == Long.MAX_VALUE ? 0 : next;
    }

    /** Whether [name] is a finished converted copy's, as {@link #copyAndName} names them. */
    static boolean finished(String name) {
        return name.length() == 64 + 4 && name.endsWith(".mp4") && name.substring(0, 64).matches("[0-9a-f]+");
    }

    /**
     * Makes room in [folder] for [need] more bytes inside {@link Limits#cacheBytes} by clearing the oldest finished
     * copies that no send has used in {@link #ACTIVE_MS}, and throws when that isn't enough or the disk would drop
     * below its reserve. Copies a send may still be reading, and other conversions' files, are never cleared. The room
     * stays claimed against both until {@link #unclaim}, so a conversion starting meanwhile counts it as taken.
     */
    static void makeRoom(File folder, long need, long now, Limits limits) throws IOException {
        synchronized (ROOM) {
            if (folder.getUsableSpace() - claimedDisk - need < limits.reserveBytes) throw new OverBudget("the phone is nearly full");
            File[] files = folder.listFiles();
            if (files == null) throw new IOException("the converted copies could not be listed");
            // What another conversion has written so far counts on disk and again in its claim, which errs toward room.
            long used = claimedCache;
            // Each file's time is read once: one still being written changes, and a sort on changing keys can throw.
            List<long[]> clearable = new ArrayList<>();
            for (int i = 0; i < files.length; i++) {
                used += files[i].length();
                long modified = files[i].lastModified();
                if (finished(files[i].getName()) && now - modified >= ACTIVE_MS) clearable.add(new long[] {modified, i});
            }
            clearable.sort(Comparator.comparingLong(entry -> entry[0]));
            for (long[] entry : clearable) {
                if (used + need <= limits.cacheBytes) break;
                File file = files[(int) entry[1]];
                long size = file.length();
                if (file.delete()) used -= size;
            }
            if (used + need > limits.cacheBytes) throw new OverBudget("the converted copies fill their share of the cache");
            claimedDisk += need;
            claimedCache += need;
        }
    }

    /** Holds [need] bytes against the disk's reserve in [folder] until {@link #unclaim}, or throws when they don't fit. */
    static void claimDisk(File folder, long need, Limits limits) throws IOException {
        synchronized (ROOM) {
            if (folder.getUsableSpace() - claimedDisk - need < limits.reserveBytes) throw new OverBudget("the phone is nearly full");
            claimedDisk += need;
        }
    }

    /** Gives back the [need] bytes {@link #makeRoom} ([cached]) or {@link #claimDisk} held for one conversion. */
    static void unclaim(long need, boolean cached) {
        synchronized (ROOM) {
            claimedDisk -= need;
            if (cached) claimedCache -= need;
        }
    }

    /** What the conversions running now have claimed, nothing once all of them are done. */
    static long claimed() {
        synchronized (ROOM) {
            return claimedDisk + claimedCache;
        }
    }

    /**
     * The most converting [video] writes beside it: a joined copy the size of the video, and the new sound twice, on
     * its own and inside that copy. The sound's length comes from its track, or counts as a second when it gives none.
     */
    static long converting(File video) {
        Tracks tracks = Tracks.of(source -> source.setDataSource(video.getPath()));
        long seconds = (tracks == null ? 0 : Math.max(0, tracks.soundUs)) / 1_000_000 + 1;
        return video.length() + 2 * seconds * BIT_RATE / 8;
    }

    /**
     * Re-encodes [file]'s sound when it's xHE-AAC, else renames its Samsung box in place. Answers whether the file
     * changed. Only MP4 copies, since the result is an MP4 and Messenger keeps the type it read before the copy.
     *
     * <p>A conversion is written beside the copy and replaces it only once it's whole, and its muxer writes only the
     * tracks, so a Samsung box doesn't come along. Until then the copy isn't touched, and a conversion that fails
     * leaves it exactly as it was. The conversion takes a slot and stays inside {@link #limits} like a picked one.
     */
    static boolean fix(File file) throws IOException {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".mp4") && !name.endsWith(".m4v")) return false;
        Limits limits = XheAudio.limits;
        File folder = file.getAbsoluteFile().getParentFile();
        clearLeftovers(folder, System.currentTimeMillis(), limits);
        if (!needsFix(file)) return dropSamsungBoxes(file);
        if (file.length() > limits.inputBytes) throw new OverBudget("the shared video is bigger than a conversion takes");
        long need = converting(file);
        takeSlot();
        File sound = null;
        File joined = null;
        long claimed = 0;
        try {
            claimDisk(folder, need, limits);
            claimed = need;
            Work work = new Work(limits.workMs, folder, limits.reserveBytes);
            sound = File.createTempFile(file.getName() + BESIDE, ".sound.mp4", folder);
            joined = File.createTempFile(file.getName() + BESIDE, ".joined.mp4", folder);
            reencode(file, sound, work);
            join(file, sound, joined, work);
            if (!repaired(joined, file)) throw new IOException("the converted video came out incomplete");
            work.check();
            if (!joined.renameTo(file)) throw new IOException("the converted video could not replace the copy");
            return true;
        } finally {
            WORKERS.release();
            if (sound != null) sound.delete();
            if (joined != null) joined.delete();
            unclaim(claimed, false);
        }
    }

    /**
     * Deletes what a shared video's conversion wrote beside the copies in [folder] when Messenger was closed partway,
     * once nothing has written to it for twice as long as a conversion may run.
     */
    static void clearLeftovers(File folder, long now, Limits limits) {
        File[] left = folder == null ? null : folder.listFiles((dir, name) ->
            name.contains(BESIDE) && (name.endsWith(".sound.mp4") || name.endsWith(".joined.mp4")));
        if (left == null) return;
        for (File file : left) {
            if (now - file.lastModified() >= 2 * limits.workMs) file.delete();
        }
    }

    /**
     * Whether [file] has a picture and one sound track, in xHE-AAC. A video with more sound tracks stays Messenger's to
     * handle, since the repair writes only one. A copy the platform can't parse is none of those, so it stays as it is
     * without counting as a hook failure.
     */
    static boolean needsFix(File file) {
        return needsFix(source -> source.setDataSource(file.getPath()));
    }

    /** [needsFix] for a file or content Uri. */
    static boolean needsFix(Context context, Uri uri) {
        return needsFix(source -> source.setDataSource(context, uri, null));
    }

    interface Opener {
        void open(MediaExtractor source) throws IOException;
    }

    private static boolean needsFix(Opener opener) {
        Tracks tracks = Tracks.of(opener);
        return tracks != null && tracks.pictures > 0 && tracks.sounds == 1 && tracks.xhe == 1;
    }

    /**
     * Whether the converted [made] is whole: every picture and sound track [from] has, each as long as the one it came
     * from, and no xHE-AAC left. A join or re-encode that stopped early, a copy whose length it doesn't say when
     * [from]'s does, and a file the platform can't parse aren't.
     */
    static boolean repaired(File made, File from) {
        Tracks out = Tracks.of(source -> source.setDataSource(made.getPath()));
        Tracks in = Tracks.of(source -> source.setDataSource(from.getPath()));
        return out != null && in != null && out.pictures == in.pictures && out.pictures > 0
            && out.sounds == in.sounds && out.sounds > 0 && out.xhe == 0
            && lasts(out.pictureUs, in.pictureUs) && lasts(out.soundUs, in.soundUs);
    }

    /** Whether a track lasting [made] microseconds is all of one lasting [from], within {@link #SLACK_US}. */
    static boolean lasts(long made, long from) {
        return from <= 0 || made >= from - Math.max(SLACK_US, from / 100);
    }

    /**
     * How many picture and sound tracks a video holds, how many of the sounds are xHE-AAC, and how long the longest
     * picture and sound last in microseconds, -1 when no track of that kind says.
     */
    static final class Tracks {
        int pictures;
        int sounds;
        int xhe;
        long pictureUs = -1;
        long soundUs = -1;

        /** The tracks of what [opener] opens, or null when the platform can't read it. */
        static Tracks of(Opener opener) {
            MediaExtractor source = new MediaExtractor();
            try {
                try {
                    opener.open(source);
                } catch (IOException | IllegalArgumentException | SecurityException unreadable) {
                    return null;
                }
                Tracks tracks = new Tracks();
                for (int i = 0; i < source.getTrackCount(); i++) {
                    MediaFormat format = source.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime == null) continue;
                    long us = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : -1;
                    if (mime.startsWith("video/")) {
                        tracks.pictures++;
                        tracks.pictureUs = Math.max(tracks.pictureUs, us);
                    }
                    if (!mime.startsWith("audio/")) continue;
                    tracks.sounds++;
                    tracks.soundUs = Math.max(tracks.soundUs, us);
                    if (MediaFormat.MIMETYPE_AUDIO_AAC.equalsIgnoreCase(mime) && objectType(format.getByteBuffer("csd-0")) == XHE_OBJECT_TYPE) {
                        tracks.xhe++;
                    }
                }
                return tracks;
            } finally {
                source.release();
            }
        }
    }

    /** Whether [file] is an MP4 with a Samsung box at its top level. A file that can't be read has none. */
    static boolean hasSamsungBox(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            return !samsungBoxes(in.getChannel()).isEmpty();
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * [hasSamsungBox] for a content Uri. A provider that only streams can't be read that way, so it has none, and
     * neither has one whose read grant has lapsed.
     */
    static boolean hasSamsungBox(Context context, Uri uri) {
        try {
            ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) return false;
            // The stream owns the descriptor and closes it once, as Android's fd checks want.
            try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                return !samsungBoxes(in.getChannel()).isEmpty();
            }
        } catch (IOException | SecurityException unreadable) {
            return false;
        }
    }

    /** Renames [file]'s top-level Samsung boxes to {@code free} in place. Answers whether there were any. */
    static boolean dropSamsungBoxes(File file) throws IOException {
        if (!hasSamsungBox(file)) return false;
        try (RandomAccessFile access = new RandomAccessFile(file, "rw")) {
            FileChannel channel = access.getChannel();
            List<Long> boxes = samsungBoxes(channel);
            for (long at : boxes) {
                ByteBuffer name = ByteBuffer.wrap(FREE);
                while (name.hasRemaining()) channel.write(name, at + 4 + name.position());
            }
            return !boxes.isEmpty();
        }
    }

    /**
     * Where each Samsung box at the top level of [channel] starts. None when the file doesn't open with {@code ftyp}.
     * The walk stops at the first box whose size can't be right, so a damaged file is never written past its end.
     */
    static List<Long> samsungBoxes(FileChannel channel) throws IOException {
        List<Long> found = new ArrayList<>();
        walk(channel, found);
        return found;
    }

    /**
     * Whether [file] is an MP4 whose top-level boxes reach exactly to its end. A copy cut off partway ends inside its
     * last box, so it isn't, and neither is a file that can't be read or doesn't open with {@code ftyp}. A send
     * cancelled during the walk comes back as the cancel, not as a cut file.
     */
    static boolean whole(File file) throws ClosedByInterruptException {
        try (FileInputStream in = new FileInputStream(file)) {
            FileChannel channel = in.getChannel();
            return walk(channel, new ArrayList<>()) == channel.size();
        } catch (ClosedByInterruptException cancelled) {
            throw cancelled;
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Walks [channel]'s top-level boxes, adding where each Samsung box starts to [found], and answers how far the boxes
     * that fit reach: -1 when it doesn't open with {@code ftyp}, the end once {@link #MOST_BOXES} were read.
     */
    private static long walk(FileChannel channel, List<Long> found) throws IOException {
        long end = channel.size();
        if (end < 8) return -1;
        ByteBuffer header = ByteBuffer.allocate(16);
        long at = 0;
        for (int count = 0; end - at >= 8; count++) {
            if (count == MOST_BOXES) return end;
            header.clear();
            if (end - at < 16) header.limit(8);
            while (header.hasRemaining()) {
                if (channel.read(header, at + header.position()) < 0) return at;
            }
            long size = header.getInt(0) & 0xffffffffL;
            int type = header.getInt(4);
            if (count == 0 && type != FILE_TYPE_BOX) return -1;
            if (size == 1) {
                // A 64-bit size follows the type.
                if (header.limit() < 16) break;
                size = header.getLong(8);
                if (size < 16) break;
            } else if (size == 0) {
                size = end - at;
            } else if (size < 8) {
                break;
            }
            if (size > end - at) break;
            if (type == SAMSUNG_BOX) found.add(at);
            at += size;
        }
        return at;
    }

    /**
     * The audio object type an AAC AudioSpecificConfig ({@code csd-0}) declares, or -1. Five bits, and 31 means six
     * more bits follow, counted from 32.
     */
    static int objectType(ByteBuffer config) {
        if (config == null || config.remaining() < 1) return -1;
        ByteBuffer bytes = config.duplicate();
        int first = bytes.get() & 0xff;
        int type = first >>> 3;
        if (type != 31) return type;
        if (!bytes.hasRemaining()) return -1;
        int second = bytes.get() & 0xff;
        return 32 + (((first & 0x07) << 3) | (second >>> 5));
    }

    /**
     * Writes the first sound track of [in] to [out] as AAC-LC, each block stamped from the decoder's own time for it
     * so the sound still lines up with the picture. Throws when a codec can't be had, stalls or fails, or [work] runs
     * out.
     */
    static void reencode(File in, File out, Work work) throws IOException {
        MediaExtractor source = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxing = false;
        Throwable failure = null;

        try {
            source = new MediaExtractor();
            source.setDataSource(in.getPath());
            MediaFormat format = soundTrack(source);
            if (format == null) throw new IOException("the video holds no sound track");
            // 16-bit samples, which the encoder takes as they come.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);

            String decoderName = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format);
            decoder = decoderName != null
                ? MediaCodec.createByCodecName(decoderName)
                : MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();

            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            boolean sourceDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            boolean endSent = false;
            int track = -1;
            // The block of sound the decoder handed over and the encoder hasn't taken all of yet.
            ByteBuffer pending = null;
            int pendingIndex = -1;
            long pendingTime = 0;
            int frameBytes = 2;
            int sampleRate = 0;
            long lastWork = SystemClock.uptimeMillis();
            int idle = 0;
            int written = 0;

            while (!encoderDone) {
                work.check();
                boolean worked = false;

                // 1. The file's next sample into the decoder.
                if (!sourceDone) {
                    int index = decoder.dequeueInputBuffer(WAIT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer == null ? -1 : source.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sourceDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, Math.max(0L, source.getSampleTime()), 0);
                            source.advance();
                        }
                        worked = true;
                    }
                }

                // 2. The decoder's sound into the encoder, as much as each input buffer holds.
                if (pending == null && !decoderDone) {
                    int index = decoder.dequeueOutputBuffer(info, WAIT_US);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat pcm = decoder.getOutputFormat();
                        int rate = pcm.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
                        int channels = pcm.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
                        if (rate <= 0 || channels <= 0) throw new IOException("the decoder gave no sample rate or channel count");
                        if (encoder == null) {
                            sampleRate = rate;
                            frameBytes = 2 * channels;
                            encoder = startEncoder(rate, channels);
                        } else {
                            sameFormat(rate, channels, sampleRate, frameBytes);
                        }
                        worked = true;
                    } else if (index >= 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decoderDone = true;
                        ByteBuffer decoded = decoder.getOutputBuffer(index);
                        if (decoded != null && info.size > 0) {
                            decoded.position(info.offset);
                            decoded.limit(info.offset + info.size);
                            pending = decoded;
                            pendingIndex = index;
                            pendingTime = Math.max(0L, info.presentationTimeUs);
                        } else {
                            decoder.releaseOutputBuffer(index, false);
                        }
                        worked = true;
                    }
                }
                if (encoder == null) {
                    if (decoderDone) throw new IOException("the decoder gave no sound format");
                    idle = worked ? 0 : idle + 1;
                    if (worked) lastWork = SystemClock.uptimeMillis();
                    else stalled(lastWork, idle, "the decoder");
                    continue;
                }
                if (pending != null) {
                    int index = encoder.dequeueInputBuffer(WAIT_US);
                    if (index >= 0) {
                        ByteBuffer into = encoder.getInputBuffer(index);
                        if (into == null) throw new IOException("the encoder gave no input buffer");
                        into.clear();
                        int take = Math.min(into.remaining(), pending.remaining());
                        take -= take % frameBytes;
                        if (take <= 0) throw new IOException("the encoder's input buffer holds no whole frame");
                        long time = pendingTime;
                        ByteBuffer part = pending.duplicate();
                        part.limit(part.position() + take);
                        into.put(part);
                        pending.position(pending.position() + take);
                        pendingTime += take / frameBytes * 1_000_000L / sampleRate;
                        encoder.queueInputBuffer(index, 0, take, time, 0);
                        if (!pending.hasRemaining()) {
                            decoder.releaseOutputBuffer(pendingIndex, false);
                            pending = null;
                            pendingIndex = -1;
                        }
                        worked = true;
                    }
                } else if (decoderDone && !endSent) {
                    int index = encoder.dequeueInputBuffer(WAIT_US);
                    if (index >= 0) {
                        encoder.queueInputBuffer(index, 0, 0, pendingTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        endSent = true;
                        worked = true;
                    }
                }

                // 3. The encoder's AAC into the new file.
                int index = encoder.dequeueOutputBuffer(info, WAIT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxing) throw new IOException("the encoder changed its format twice");
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxing = true;
                    worked = true;
                } else if (index >= 0) {
                    ByteBuffer encoded = encoder.getOutputBuffer(index);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (encoded != null && info.size > 0 && !config) {
                        if (!muxing) throw new IOException("the encoder gave sound before its format");
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        muxer.writeSampleData(track, encoded, info);
                        written++;
                    }
                    encoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true;
                    worked = true;
                }

                idle = worked ? 0 : idle + 1;
                if (worked) lastWork = SystemClock.uptimeMillis();
                else stalled(lastWork, idle, "the re-encode");
            }
            // A file with no sound in it would join into a silent video.
            if (!muxing || written == 0) throw new IOException("the re-encode made no sound");
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            Throwable closing = failure;
            if (muxer != null) {
                MediaMuxer file = muxer;
                if (muxing) closing = attempt(closing, file::stop);
                closing = attempt(closing, file::release);
            }
            if (encoder != null) {
                MediaCodec codec = encoder;
                closing = attempt(closing, codec::stop);
                closing = attempt(closing, codec::release);
            }
            if (decoder != null) {
                MediaCodec codec = decoder;
                closing = attempt(closing, codec::stop);
                closing = attempt(closing, codec::release);
            }
            if (source != null) closing = attempt(closing, source::release);
            if (failure == null && closing != null) {
                throw new IOException("the re-encoded sound could not be finished: " + closing.getClass().getSimpleName(), closing);
            }
        }
    }

    /**
     * How far to move every sample so the earliest first sample lands at zero. A track that starts below zero (an
     * encoder-delay edit list) moves both tracks by the same amount, so picture and sound stay in sync.
     */
    static long startShift(boolean pictureLeft, long firstPicture, boolean soundLeft, long firstSound) {
        long first = Math.min(pictureLeft ? firstPicture : 0L, soundLeft ? firstSound : 0L);
        return first < 0 ? -first : 0L;
    }

    /** Whether the picture's next sample goes first: it's the earlier of the two, or the sound has run out. */
    static boolean takePicture(boolean pictureLeft, long nextPicture, boolean soundLeft, long nextSound) {
        return !soundLeft || (pictureLeft && nextPicture <= nextSound);
    }

    /**
     * Writes [picture]'s picture tracks and [sound]'s one sound track into [out], each sample as it was, taking the
     * earlier of the two next samples each time so the file stays interleaved. Keeps the picture's rotation. Throws
     * when [work] runs out.
     */
    static void join(File picture, File sound, File out, Work work) throws IOException {
        MediaExtractor pictures = new MediaExtractor();
        MediaExtractor sounds = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxing = false;
        Throwable failure = null;
        try {
            pictures.setDataSource(picture.getPath());
            sounds.setDataSource(sound.getPath());
            List<Integer> pictureTracks = new ArrayList<>();
            List<MediaFormat> pictureFormats = new ArrayList<>();
            for (int i = 0; i < pictures.getTrackCount(); i++) {
                MediaFormat format = pictures.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                pictures.selectTrack(i);
                pictureTracks.add(i);
                pictureFormats.add(format);
            }
            if (pictureTracks.isEmpty()) throw new IOException("the video holds no picture track");
            MediaFormat soundFormat = soundTrack(sounds);
            if (soundFormat == null) throw new IOException("the converted sound holds no track");

            // Opened only once both halves are known to be there, so a missing one leaves [out] as it was.
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(rotation(picture));
            List<Integer> pictureOut = new ArrayList<>();
            for (MediaFormat format : pictureFormats) pictureOut.add(muxer.addTrack(format));
            int soundOut = muxer.addTrack(soundFormat);
            muxer.start();
            muxing = true;

            ByteBuffer buffer = ByteBuffer.allocateDirect(SAMPLE_BYTES);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long shift = startShift(pictures.getSampleTrackIndex() >= 0, pictures.getSampleTime(),
                sounds.getSampleTrackIndex() >= 0, sounds.getSampleTime());
            while (true) {
                work.check();
                // A sample time can be below zero (an encoder-delay edit list), so only the track index marks the end.
                boolean pictureLeft = pictures.getSampleTrackIndex() >= 0;
                boolean soundLeft = sounds.getSampleTrackIndex() >= 0;
                if (!pictureLeft && !soundLeft) break;
                boolean takePicture = takePicture(pictureLeft, pictures.getSampleTime(), soundLeft, sounds.getSampleTime());
                MediaExtractor from = takePicture ? pictures : sounds;
                int track = takePicture ? pictureOut.get(pictureTracks.indexOf(from.getSampleTrackIndex())) : soundOut;
                buffer.clear();
                int size = from.readSampleData(buffer, 0);
                if (size < 0) break;
                boolean sync = (from.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                info.set(0, size, from.getSampleTime() + shift, sync ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(track, buffer, info);
                from.advance();
            }
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            Throwable closing = failure;
            if (muxer != null) {
                MediaMuxer file = muxer;
                if (muxing) closing = attempt(closing, file::stop);
                closing = attempt(closing, file::release);
            }
            closing = attempt(closing, pictures::release);
            closing = attempt(closing, sounds::release);
            if (failure == null && closing != null) {
                throw new IOException("the converted video could not be finished: " + closing.getClass().getSimpleName(), closing);
            }
        }
    }

    /** The rotation [file]'s picture is shown at, 0 when it says none. */
    static int rotation(File file) {
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(file.getPath());
            String value = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int degrees = value == null ? 0 : Integer.parseInt(value.trim());
            return degrees == 90 || degrees == 180 || degrees == 270 ? degrees : 0;
        } catch (RuntimeException unreadable) {
            return 0;
        } finally {
            try { metadata.release(); } catch (Exception ignored) { }
        }
    }

    /**
     * A later format from the decoder has to repeat the first: the encoder was made for that one, and sound at another
     * rate or channel count through it would come out wrong.
     */
    static void sameFormat(int rate, int channels, int sampleRate, int frameBytes) throws IOException {
        if (rate != sampleRate || 2 * channels != frameBytes) throw new IOException("the decoder changed its sound format");
    }

    static MediaCodec startEncoder(int sampleRate, int channels) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
        MediaCodec encoder = name != null
            ? MediaCodec.createByCodecName(name)
            : MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        return startedOrReleased(encoder, () -> {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
        }, encoder::release);
    }

    /**
     * Hands back [codec] once [setUp] ran, else runs [release] and rethrows. The caller only gets the codec on a normal
     * return, so a configure or start that throws would leave it held with nothing left to release it.
     */
    static <T> T startedOrReleased(T codec, Runnable setUp, Runnable release) {
        try {
            setUp.run();
            return codec;
        } catch (RuntimeException failed) {
            try {
                release.run();
            } catch (RuntimeException alsoFailed) {
                failed.addSuppressed(alsoFailed);
            }
            throw failed;
        }
    }

    private static void stalled(long lastWork, int idle, String what) throws IOException {
        if (SystemClock.uptimeMillis() - lastWork > STALL_MS || idle > STALL_ROUNDS) {
            throw new IOException(what + " stalled for " + STALL_MS / 1000 + " seconds");
        }
    }

    /** Selects the first sound track of [source] and answers its format, or null. */
    private static MediaFormat soundTrack(MediaExtractor source) {
        for (int i = 0; i < source.getTrackCount(); i++) {
            MediaFormat format = source.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                source.selectTrack(i);
                return format;
            }
        }
        return null;
    }

    private interface Step {
        void run() throws Exception;
    }

    private static Throwable attempt(Throwable first, Step step) {
        try {
            step.run();
            return first;
        } catch (Throwable t) {
            return first != null ? first : t;
        }
    }
}
