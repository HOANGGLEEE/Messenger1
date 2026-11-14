package app.hushmessenger.extension;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.util.DataSource;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class XheAudioTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private java.util.concurrent.Executor pruneWork;

    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        Settings.hookErrors.clear();
        CrashGuard.resetForTests();
        // The clear that follows a handed-out copy runs in place, so it can't touch a later test's cache.
        pruneWork = XheAudio.pruneWork;
        XheAudio.pruneWork = Runnable::run;
        XheAudio.pruneAt = 0;
    }

    @After public void restoreLimits() {
        XheAudio.pruneWork = pruneWork;
        XheAudio.pruneAt = 0;
        XheAudio.limits = new XheAudio.Limits();
        assertEquals("every conversion slot is given back", XheAudio.SLOTS, XheAudio.WORKERS.availablePermits());
        assertEquals("every claim on the disk and the cache is given back", 0, XheAudio.claimed());
    }

    /** A conversion's budget of [ms], watching the disk this test writes to with no reserve. */
    private XheAudio.Work budget(long ms) {
        return new XheAudio.Work(ms, folder.getRoot(), 0);
    }

    private static ByteBuffer config(int... bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
        for (int b : bytes) buffer.put((byte) b);
        buffer.flip();
        return buffer;
    }

    /** Runs the hook the way Messenger does, on its background copy thread. */
    private static void fromCopyThread(File file) throws InterruptedException {
        Thread copy = new Thread(() -> XheAudio.fixSharedFile(file));
        copy.start();
        copy.join(10_000);
        assertFalse(copy.isAlive());
    }

    @Test public void theObjectTypeIsReadFromTheAudioConfig() {
        assertEquals("AAC-LC", 2, XheAudio.objectType(config(0x12, 0x10)));
        assertEquals("HE-AAC", 5, XheAudio.objectType(config(0x2B, 0x92)));
        // 31 escapes to six more bits: 11111 001|010 is 32 + 10.
        assertEquals("xHE-AAC", XheAudio.XHE_OBJECT_TYPE, XheAudio.objectType(config(0xF9, 0x40)));
        assertEquals(-1, XheAudio.objectType(config(0xF9)));
        assertEquals(-1, XheAudio.objectType(config()));
        assertEquals(-1, XheAudio.objectType(null));
        ByteBuffer shared = config(0xF9, 0x40);
        XheAudio.objectType(shared);
        assertEquals("the format's own buffer isn't moved", 0, shared.position());
    }

    @Test public void aSoundTrackStartingBelowZeroIsKeptAndBothTracksMoveTogether() {
        // ffmpeg's AAC encoder delay: the sound's first sample sits at -23219 us, the picture's at 0.
        assertEquals(23_219L, XheAudio.startShift(true, 0L, true, -23_219L));
        assertEquals(23_219L, XheAudio.startShift(false, -1L, true, -23_219L));
        assertEquals(0L, XheAudio.startShift(true, 0L, true, 0L));
        assertEquals("a finished track's -1 isn't a start", 0L, XheAudio.startShift(true, 0L, false, -1L));
        assertFalse("the negative sound sample goes first", XheAudio.takePicture(true, 0L, true, -23_219L));
        assertFalse("the sound isn't over just because its time is negative", XheAudio.takePicture(false, -1L, true, -23_219L));
        assertTrue(XheAudio.takePicture(true, 0L, true, 0L));
        assertTrue(XheAudio.takePicture(true, 40_000L, false, -1L));
    }

    @Test public void aCopyThatCantBeReadStaysAsItWasAndLeavesNothingBehind() throws Exception {
        File copy = folder.newFile("orca_share_media123.mp4");
        byte[] bytes = "not a video".getBytes();
        Files.write(copy.toPath(), bytes);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        fromCopyThread(copy);
        assertTrue("the switch was asked", Settings.lastActive(XheAudio.KEY) > 0);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals(bytes, Files.readAllBytes(copy.toPath()));
        assertEquals(Arrays.asList("orca_share_media123.mp4"), Arrays.asList(folder.getRoot().list()));
    }

    @Test public void offPauseAndTheMainThreadLeaveTheCopyAlone() throws Exception {
        File copy = folder.newFile("orca_share_media7.mp4");
        byte[] bytes = "not a video".getBytes();
        Files.write(copy.toPath(), bytes);
        fromCopyThread(copy);
        assertEquals("off", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).putBoolean("paused", true).commit();
        fromCopyThread(copy);
        assertEquals("paused", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean("paused", false).commit();
        // Robolectric runs the test on the main thread, where a conversion could freeze Messenger.
        XheAudio.fixSharedFile(copy);
        assertEquals("main thread", 0, Settings.lastActive(XheAudio.KEY));
        XheAudio.fixSharedFile(null);
        assertArrayEquals(bytes, Files.readAllBytes(copy.toPath()));
    }

    @Test public void onlyMp4CopiesAreConsidered() throws Exception {
        for (String name : new String[] {"orca_share_media1.mov", "orca_share_media2.3gp", "orca_share_media3.webm", "orca_share_media4"}) {
            File copy = folder.newFile(name);
            Files.write(copy.toPath(), "not a video".getBytes());
            assertFalse(name, XheAudio.fix(copy));
        }
    }

    /** Runs the transcoder hook the way Messenger does, off the main thread, and answers what it gave back. */
    private static String fromTranscoder(String source) throws InterruptedException {
        String[] answer = new String[1];
        Thread transcoder = new Thread(() -> answer[0] = XheAudio.transcodeSource(source));
        transcoder.start();
        transcoder.join(10_000);
        assertFalse(transcoder.isAlive());
        return answer[0];
    }

    @Test public void aPickedVideoThatDoesntNeedItComesBackAsItWas() throws Exception {
        File picked = folder.newFile("VID_20261014.mp4");
        byte[] bytes = "not a video".getBytes();
        Files.write(picked.toPath(), bytes);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        for (String source : new String[] {picked.getPath(), "file://" + picked.getPath(),
            "content://media/external/video/media/1", "https://example.com/v.mp4", "not a path", ""}) {
            assertEquals(source, fromTranscoder(source));
        }
        assertNull(fromTranscoder(null));
        assertTrue("the switch was asked", Settings.lastActive(XheAudio.KEY) > 0);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals("the picked file is never touched", bytes, Files.readAllBytes(picked.toPath()));
        File cache = new File(RuntimeEnvironment.getApplication().getCacheDir(), XheAudio.PICKED);
        assertFalse("nothing was converted, so no cache folder", cache.exists());
    }

    @Test public void offPauseAndTheMainThreadGiveTheSourceBack() throws Exception {
        File picked = folder.newFile("VID_1.mp4");
        assertEquals(picked.getPath(), fromTranscoder(picked.getPath()));
        assertEquals("off", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).putBoolean("paused", true).commit();
        assertEquals(picked.getPath(), fromTranscoder(picked.getPath()));
        assertEquals("paused", 0, Settings.lastActive(XheAudio.KEY));
        Settings.preferences.edit().putBoolean("paused", false).commit();
        assertEquals(picked.getPath(), XheAudio.transcodeSource(picked.getPath()));
        assertEquals("main thread", 0, Settings.lastActive(XheAudio.KEY));
    }

    /** One box: a 32-bit size, the type and [body]. */
    private static byte[] box(String type, byte[] body) {
        ByteBuffer box = ByteBuffer.allocate(8 + body.length);
        box.putInt(8 + body.length).put(type.getBytes(StandardCharsets.US_ASCII)).put(body);
        return box.array();
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static final byte[] FILE_TYPE = box("ftyp", "isom\0\0\2\0isommp42".getBytes(StandardCharsets.US_ASCII));

    /** Shaped like the #58 recording: ftyp, the picture data, moov with Samsung's udta records, then sefd at the end. */
    private static byte[] samsungRecording() {
        return samsungRecording(0);
    }

    /** [samsungRecording] with its picture data filled with [fill], so two recordings differ only there. */
    private static byte[] samsungRecording(int fill) {
        byte[] picture = new byte[64];
        Arrays.fill(picture, (byte) fill);
        return join(FILE_TYPE, box("mdat", picture),
            box("moov", join(box("mvhd", new byte[100]), box("udta", box("smta", new byte[12])))),
            box("sefd", "\0\0\u0080\0Samsung_Capture_Info\0Screen recording".getBytes(StandardCharsets.ISO_8859_1)));
    }

    /** What [samsungRecording] looks like once its sefd is renamed. */
    private static byte[] renamed(byte[] recording) {
        return renamedAt(recording, recording.length - 8 - 41);
    }

    /** The bytes of the file a transcoder answer names, a path or a file Uri. */
    private static byte[] sentBytes(String sent) throws Exception {
        String path = sent;
        if (sent.startsWith("file:")) {
            // SDK 28 on a Windows host builds file://C%3A%5C... from the backslash path, with the path in the authority.
            path = Uri.parse(sent).getPath();
            if (path == null || path.isEmpty()) path = Uri.decode(sent.substring("file://".length()));
        }
        File file = new File(path);
        assertTrue(sent, file.isFile());
        return Files.readAllBytes(file.toPath());
    }

    private static File pickedCache() {
        return new File(RuntimeEnvironment.getApplication().getCacheDir(), XheAudio.PICKED);
    }

    /** Every file the cache holds is a finished copy, named by its bytes, with nothing half-made left beside it. */
    private static void onlyFinishedCopies(int count) {
        String[] names = pickedCache().list();
        assertNotNull(names);
        assertEquals(Arrays.toString(names), count, names.length);
        for (String name : names) assertTrue(name, name.matches("[0-9a-f]{64}\\.mp4"));
    }

    /** The cache name convertPicked used before 2026-10-18: a 32-bit hash of the Uri, size and time. */
    private static String oldKey(File file) {
        return Integer.toHexString((Uri.fromFile(file) + ":" + file.length() + ":" + file.lastModified()).hashCode());
    }

    @Test public void twoVideosWhoseOldCacheNamesMatchEachSendTheirOwn() throws Exception {
        File movies = folder.newFolder("Movies");
        File first = new File(movies, "Aa.mp4");
        File second = new File(movies, "BB.mp4");
        byte[] a = samsungRecording(1);
        byte[] b = samsungRecording(2);
        Files.write(first.toPath(), a);
        Files.write(second.toPath(), b);
        assertTrue(first.setLastModified(1_792_108_800_000L));
        assertTrue(second.setLastModified(1_792_108_800_000L));
        // "Aa" and "BB" hash alike in Java, so the same size and time gave both one cache name.
        assertEquals("the old names collide", oldKey(first), oldKey(second));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        String sentA = fromTranscoder(first.toURI().getPath());
        String sentB = fromTranscoder(second.toURI().getPath());
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertNotEquals(sentA, sentB);
        assertArrayEquals("Aa sends Aa", renamed(a), sentBytes(sentA));
        assertArrayEquals("BB sends BB", renamed(b), sentBytes(sentB));
        assertArrayEquals(a, Files.readAllBytes(first.toPath()));
        assertArrayEquals(b, Files.readAllBytes(second.toPath()));
        onlyFinishedCopies(2);
    }

    @Test public void aVideoChangedUnderTheSameNameSizeAndTimeIsConvertedAgain() throws Exception {
        File picked = new File(folder.newFolder("DCIM"), "VID_20261018.mp4");
        byte[] before = samsungRecording(3);
        byte[] after = samsungRecording(4);
        Files.write(picked.toPath(), before);
        assertTrue(picked.setLastModified(1_792_108_800_000L));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        String path = picked.toURI().getPath();
        String first = fromTranscoder(path);
        Files.write(picked.toPath(), after);
        assertTrue(picked.setLastModified(1_792_108_800_000L));
        String second = fromTranscoder(path);
        assertNotEquals("new bytes, new copy", first, second);
        assertArrayEquals(renamed(after), sentBytes(second));
        assertArrayEquals("the first copy is still whole for a send that's using it", renamed(before), sentBytes(first));
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
    }

    static final String VIDEOS = "app.hushmessenger.test.videos";

    /** Serves one file at any Uri, the way a gallery hands a picked video over. */
    public static final class Videos extends ContentProvider {
        static volatile File served;

        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(served, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return "video/mp4"; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }

    @Test public void aContentUriWhoseVideoChangedIsConvertedAgain() throws Exception {
        Videos.served = folder.newFile("gallery.mp4");
        byte[] before = samsungRecording(5);
        byte[] after = samsungRecording(6);
        Files.write(Videos.served.toPath(), before);
        Robolectric.setupContentProvider(Videos.class, VIDEOS);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        String uri = "content://" + VIDEOS + "/video/7";
        String first = fromTranscoder(uri);
        assertTrue(first, first.startsWith("file:"));
        assertArrayEquals(renamed(before), sentBytes(first));
        // The provider now hands over other bytes for the same Uri. A new file stands in for an edit in place, since
        // the SDK 28 runtime keeps the first one open and Windows won't let it be written then.
        Videos.served = folder.newFile("gallery-edited.mp4");
        Files.write(Videos.served.toPath(), after);
        String second = fromTranscoder(uri);
        assertNotEquals("the same Uri with new bytes gets a new copy", first, second);
        assertArrayEquals(renamed(after), sentBytes(second));
        assertEquals("the same bytes again reuse their copy", second, fromTranscoder(uri));
        assertArrayEquals(after, Files.readAllBytes(Videos.served.toPath()));
        onlyFinishedCopies(2);
    }

    static final String LAPSED = "app.hushmessenger.test.lapsed";

    /** Refuses every read, the way a gallery's Uri does once its grant has lapsed before the send starts. */
    public static final class Lapsed extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) {
            throw new SecurityException("Permission Denial: reading " + uri + " requires a grant");
        }
        @Override public String getType(Uri uri) { return "video/mp4"; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }

    @Test public void aPickedVideoWhoseGrantLapsedIsSentAsItIsWithoutAHookFailure() throws Exception {
        Robolectric.setupContentProvider(Lapsed.class, LAPSED);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        String uri = "content://" + LAPSED + "/video/9";
        assertFalse(XheAudio.hasSamsungBox(RuntimeEnvironment.getApplication(), Uri.parse(uri)));
        assertEquals(uri, fromTranscoder(uri));
        assertEquals("a Uri we can't read is Messenger's to send, not a broken hook", 0, Settings.hookErrorAt(XheAudio.KEY));
    }

    static final String LAPSES = "app.hushmessenger.test.lapses";

    /** Serves a Samsung recording to the first look, then refuses the copy, the way a grant runs out partway through. */
    public static final class LapsesAtTheCopy extends ContentProvider {
        static volatile File served;
        static volatile int refused;

        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            // The copy's scratch file is made right before the copy opens the Uri, so it being there marks the copy.
            String[] scratch = pickedCache().list((dir, name) -> name.startsWith(XheAudio.SCRATCH) && name.endsWith(".source"));
            if (scratch != null && scratch.length > 0) {
                refused++;
                throw new SecurityException("Permission Denial: the grant for " + uri + " ran out");
            }
            return ParcelFileDescriptor.open(served, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return "video/mp4"; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }

    @Test public void aPickedVideoWhoseGrantLapsesBeforeTheCopyIsSentAsItIsWithoutAHookFailure() throws Exception {
        LapsesAtTheCopy.served = folder.newFile("gallery.mp4");
        LapsesAtTheCopy.refused = 0;
        Files.write(LapsesAtTheCopy.served.toPath(), samsungRecording(9));
        Robolectric.setupContentProvider(LapsesAtTheCopy.class, LAPSES);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        String uri = "content://" + LAPSES + "/video/10";
        assertEquals(uri, fromTranscoder(uri));
        assertEquals("the first look found the Samsung box, so the copy was tried", 1, LapsesAtTheCopy.refused);
        assertEquals("a grant that ran out partway is Messenger's to send, not a broken hook", 0, Settings.hookErrorAt(XheAudio.KEY));
        onlyFinishedCopies(0);
    }

    /**
     * Stock Android turns a refused grant into an IOException inside the extractor, but nothing promises every phone's
     * does. A refusal that comes through as itself still reads as a video the platform can't open, not a broken hook.
     */
    @Test public void anExtractorThatPassesOnARefusedGrantReadsAsUnreadable() {
        assertNull(XheAudio.Tracks.of(source -> { throw new SecurityException("Permission Denial"); }));
        assertNull(XheAudio.Tracks.of(source -> { throw new IllegalArgumentException("no such file"); }));
        assertNull(XheAudio.Tracks.of(source -> { throw new IOException("unreadable"); }));
    }

    /** An encoder that won't start, the way one does when it can't take the format, counting the releases it gets. */
    @Implements(MediaCodec.class)
    public static final class RefusingCodec extends ShadowMediaCodec {
        static volatile int released;

        @Implementation @Override protected void native_start() {
            throw new IllegalStateException("start refused the format");
        }

        @Implementation protected void native_release() {
            released++;
        }
    }

    @Test @Config(shadows = RefusingCodec.class)
    public void anEncoderThatWontStartIsReleasedBeforeTheConversionFails() throws Exception {
        RefusingCodec.released = 0;
        try {
            XheAudio.startEncoder(44_100, 2);
            fail("the encoder's failure has to reach the conversion");
        } catch (IllegalStateException refused) {
            assertEquals("start refused the format", refused.getMessage());
        }
        assertEquals("nothing else holds the encoder yet, so it's released here, once", 1, RefusingCodec.released);
    }

    @Test public void theStartHelperReleasesOnlyACodecThatFailedToStart() {
        boolean[] released = new boolean[1];
        IllegalStateException refused = new IllegalStateException("configure refused the format");
        try {
            XheAudio.startedOrReleased("encoder", () -> { throw refused; }, () -> released[0] = true);
            fail("the failure has to reach the conversion");
        } catch (IllegalStateException thrown) {
            assertSame(refused, thrown);
        }
        assertTrue("nothing else holds the encoder yet, so it's released here", released[0]);
        boolean[] untouched = new boolean[1];
        assertEquals("encoder", XheAudio.startedOrReleased("encoder", () -> { }, () -> untouched[0] = true));
        assertFalse("a started encoder stays open for the conversion", untouched[0]);
    }

    @Test public void conversionsRunningAtOnceEachKeepTheirOwnFiles() throws Exception {
        File movies = folder.newFolder("Movies");
        File[] picked = {new File(movies, "Aa.mp4"), new File(movies, "BB.mp4")};
        byte[][] bytes = {samsungRecording(7), samsungRecording(8)};
        for (int i = 0; i < 2; i++) {
            Files.write(picked[i].toPath(), bytes[i]);
            assertTrue(picked[i].setLastModified(1_792_108_800_000L));
        }
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        int sends = 8;
        String[] sent = new String[sends];
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[sends];
        for (int i = 0; i < sends; i++) {
            int send = i;
            threads[i] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException stop) {
                    return;
                }
                sent[send] = XheAudio.transcodeSource(picked[send % 2].toURI().getPath());
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
            assertFalse(thread.isAlive());
        }
        assertEquals("not a hook failure: " + Settings.hookErrors.get(XheAudio.KEY), 0, Settings.hookErrorAt(XheAudio.KEY));
        for (int i = 0; i < sends; i++) assertArrayEquals("send " + i, renamed(bytes[i % 2]), sentBytes(sent[i]));
        for (int i = 0; i < 2; i++) assertArrayEquals(bytes[i], Files.readAllBytes(picked[i].toPath()));
        onlyFinishedCopies(2);
    }

    /** [bytes] with the box type at [offset] swapped for free. */
    private static byte[] renamedAt(byte[] bytes, int offset) {
        byte[] renamed = bytes.clone();
        System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, renamed, offset + 4, 4);
        return renamed;
    }

    @Test public void aSamsungRecordingsShareCopyHasItsTagRenamedInPlace() throws Exception {
        File copy = folder.newFile("orca_share_media42.mp4");
        byte[] bytes = samsungRecording();
        Files.write(copy.toPath(), bytes);
        int sefd = bytes.length - box("sefd", "\0\0\u0080\0Samsung_Capture_Info\0Screen recording".getBytes(StandardCharsets.ISO_8859_1)).length;
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        fromCopyThread(copy);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals("only the top-level sefd's type changes, so every offset stays", renamedAt(bytes, sefd), Files.readAllBytes(copy.toPath()));
        assertEquals(Arrays.asList("orca_share_media42.mp4"), Arrays.asList(folder.getRoot().list()));
        assertFalse("nothing is left to rename", XheAudio.dropSamsungBoxes(copy));
    }

    /** Has the platform read [file] as one picture, [xhe] xHE-AAC sound tracks and [plain] AAC-LC ones. */
    private static void readAs(File file, int xhe, int plain) {
        readAs(file, xhe, plain, -1);
    }

    /** [readAs] with every track lasting [us] microseconds, or saying nothing of it when [us] is below zero. */
    private static void readAs(File file, int xhe, int plain, long us) {
        readAs(file, xhe, plain, us, us);
    }

    /** [readAs] with the picture lasting [pictureUs] and every sound [soundUs], below zero for a track that doesn't say. */
    private static void readAs(File file, int xhe, int plain, long pictureUs, long soundUs) {
        DataSource source = DataSource.toDataSource(file.getPath());
        MediaFormat picture = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240);
        if (pictureUs >= 0) picture.setLong(MediaFormat.KEY_DURATION, pictureUs);
        ShadowMediaExtractor.addTrack(source, picture, new byte[16]);
        for (int i = 0; i < xhe + plain; i++) {
            MediaFormat sound = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2);
            sound.setByteBuffer("csd-0", i < xhe ? config(0xF9, 0x40) : config(0x12, 0x10));
            if (soundUs >= 0) sound.setLong(MediaFormat.KEY_DURATION, soundUs);
            ShadowMediaExtractor.addTrack(source, sound, new byte[16]);
        }
    }

    private static void readAsXhe(File file, int sounds) {
        readAs(file, sounds, 0);
    }

    @Test public void onlyAVideoWhoseOneSoundIsXheIsConverted() throws Exception {
        File one = folder.newFile("one.mp4");
        File two = folder.newFile("two.mp4");
        File mixed = folder.newFile("mixed.mp4");
        readAsXhe(one, 1);
        readAsXhe(two, 2);
        readAs(mixed, 1, 1);
        assertTrue(XheAudio.needsFix(one));
        assertFalse("the repair writes one sound, so the second would be lost", XheAudio.needsFix(two));
        assertFalse(XheAudio.needsFix(mixed));
    }

    @Test public void aConversionMissingASoundTrackIsntWhole() throws Exception {
        File one = folder.newFile("one.mp4");
        File two = folder.newFile("two.mp4");
        File madeOne = folder.newFile("made-one.mp4");
        File madeTwo = folder.newFile("made-two.mp4");
        File stillXhe = folder.newFile("still-xhe.mp4");
        readAsXhe(one, 1);
        readAsXhe(two, 2);
        readAs(madeOne, 0, 1);
        readAs(madeTwo, 0, 2);
        readAsXhe(stillXhe, 1);
        assertTrue(XheAudio.repaired(madeOne, one));
        assertFalse("one sound out of two isn't a repair", XheAudio.repaired(madeOne, two));
        assertTrue(XheAudio.repaired(madeTwo, two));
        assertFalse(XheAudio.repaired(madeTwo, one));
        assertFalse(XheAudio.repaired(stillXhe, one));
    }

    @Test public void aConversionThatComesOutShortIsntWhole() throws Exception {
        File from = folder.newFile("from.mp4");
        File whole = folder.newFile("whole.mp4");
        File close = folder.newFile("close.mp4");
        File cut = folder.newFile("cut.mp4");
        File untimed = folder.newFile("untimed.mp4");
        File shortPicture = folder.newFile("short-picture.mp4");
        File shortSound = folder.newFile("short-sound.mp4");
        readAs(from, 1, 0, 10_000_000);
        readAs(whole, 0, 1, 10_000_000);
        readAs(close, 0, 1, 9_600_000);
        readAs(cut, 0, 1, 4_000_000);
        readAs(untimed, 0, 1);
        readAs(shortPicture, 0, 1, 4_000_000, 10_000_000);
        readAs(shortSound, 0, 1, 10_000_000, 4_000_000);
        assertTrue(XheAudio.repaired(whole, from));
        assertTrue("the encoder's start-up and last block may trim a little", XheAudio.repaired(close, from));
        assertFalse("a join that stopped at four seconds", XheAudio.repaired(cut, from));
        assertFalse("a copy that doesn't say how long it is, when its original does", XheAudio.repaired(untimed, from));
        assertFalse(XheAudio.repaired(shortPicture, from));
        assertFalse(XheAudio.repaired(shortSound, from));
        assertTrue("an original that doesn't say can't be compared", XheAudio.repaired(cut, untimed));
        assertTrue("a long video gets a hundredth of its length", XheAudio.lasts(99_100_000, 100_000_000));
        assertFalse(XheAudio.lasts(98_900_000, 100_000_000));
        assertTrue("an original that says it lasts nothing isn't compared", XheAudio.lasts(-1, 0));
    }

    @Test public void aRecordingThatEndsInsideABoxIsSentAsItIs() throws Exception {
        // The picture data says it runs to a megabyte, and the copy stops 64 bytes in.
        ByteBuffer cut = ByteBuffer.allocate(8 + 64);
        cut.putInt(1 << 20).put("mdat".getBytes(StandardCharsets.US_ASCII));
        byte[] bytes = join(FILE_TYPE, box("sefd", new byte[8]), cut.array());
        String path = pickedRecording("VID_cut.mp4", bytes);
        File picked = new File(folder.getRoot(), "VID_cut.mp4");
        assertTrue(XheAudio.hasSamsungBox(picked));
        assertFalse(XheAudio.whole(picked));

        assertEquals(path, fromTranscoder(path));
        assertTrue("the failure is recorded", Settings.hookErrorAt(XheAudio.KEY) > 0);
        assertArrayEquals(bytes, Files.readAllBytes(picked.toPath()));
        noCopies();

        File recording = folder.newFile("VID_whole.mp4");
        Files.write(recording.toPath(), samsungRecording());
        assertTrue(XheAudio.whole(recording));
        File notMp4 = folder.newFile("VID_not.mp4");
        Files.write(notMp4.toPath(), join(box("mdat", new byte[16]), box("sefd", new byte[8])));
        assertFalse(XheAudio.whole(notMp4));
        assertFalse("an empty file holds no boxes", XheAudio.whole(folder.newFile("VID_empty.mp4")));
    }

    @Test public void aSendCancelledDuringTheBoxWalkIsACancel() throws Exception {
        File recording = folder.newFile("VID_cancel.mp4");
        Files.write(recording.toPath(), samsungRecording());
        Throwable[] thrown = new Throwable[1];
        boolean[] answer = new boolean[1];
        Thread send = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                answer[0] = XheAudio.whole(recording);
            } catch (Throwable error) {
                thrown[0] = error;
            }
        });
        send.start();
        send.join(10_000);
        assertFalse(send.isAlive());
        assertTrue("answered " + answer[0] + ", threw " + thrown[0], thrown[0] instanceof ClosedByInterruptException);
    }

    @Test public void aVideoWithTwoXheSoundsIsLeftForMessenger() throws Exception {
        File picked = folder.newFile("VID_two_sounds.mp4");
        byte[] bytes = "two sounds".getBytes(StandardCharsets.US_ASCII);
        Files.write(picked.toPath(), bytes);
        // The path Messenger passes, and the file the transcoder's first look reads for it.
        String path = picked.toURI().getPath();
        readAsXhe(new File(path).getAbsoluteFile(), 2);
        File copy = folder.newFile("orca_share_media44.mp4");
        Files.write(copy.toPath(), bytes);
        readAsXhe(copy, 2);
        File tagged = folder.newFile("orca_share_media45.mp4");
        byte[] recording = samsungRecording();
        Files.write(tagged.toPath(), recording);
        readAsXhe(tagged, 2);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();

        assertEquals(path, fromTranscoder(path));
        fromCopyThread(copy);
        fromCopyThread(tagged);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals(bytes, Files.readAllBytes(picked.toPath()));
        assertArrayEquals(bytes, Files.readAllBytes(copy.toPath()));
        assertArrayEquals("the Samsung box is still renamed, which keeps every track", renamed(recording), Files.readAllBytes(tagged.toPath()));
        File cache = new File(RuntimeEnvironment.getApplication().getCacheDir(), XheAudio.PICKED);
        assertFalse("nothing was converted", cache.exists());

        // The same first look at a video with one xHE sound goes on to convert it, so the one above was really read.
        File one = folder.newFile("VID_one_sound.mp4");
        Files.write(one.toPath(), bytes);
        String onePath = one.toURI().getPath();
        readAsXhe(new File(onePath).getAbsoluteFile(), 1);
        assertEquals("its snapshot isn't readable here, so it still goes as it is", onePath, fromTranscoder(onePath));
        assertTrue("but its conversion started", cache.isDirectory());
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
    }

    private interface Step {
        void run() throws Exception;
    }

    private static void expectOutOfTime(Step step) throws Exception {
        try {
            step.run();
            fail("the time was up");
        } catch (XheAudio.OverBudget expected) {
            assertEquals("the conversion ran out of time", expected.getMessage());
        }
    }

    @Test public void aConversionStopsWhereverItsTimeRunsOut() throws Exception {
        XheAudio.Work work = budget(1_000);
        work.check();
        ShadowSystemClock.advanceBy(Duration.ofMillis(999));
        work.check();
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        expectOutOfTime(work::check);

        // The re-encode and the join check it every round, not only the copy before them.
        File video = folder.newFile("VID_late.mp4");
        File sound = folder.newFile("late.sound.mp4");
        readAsXhe(video, 1);
        readAs(sound, 0, 1);
        expectOutOfTime(() -> XheAudio.reencode(video, folder.newFile("late.out.mp4"), work));
        expectOutOfTime(() -> XheAudio.join(video, sound, folder.newFile("late.joined.mp4"), work));
        File joined = folder.newFile("on-time.joined.mp4");
        XheAudio.join(video, sound, joined, budget(1_000));
        assertTrue("with time left the join runs to the end", joined.length() > 0);
    }

    @Test public void aSamsungRecordingWhoseSoundCantBeConvertedKeepsItsCopyAsItWas() throws Exception {
        File copy = folder.newFile("orca_share_media43.mp4");
        byte[] bytes = samsungRecording();
        Files.write(copy.toPath(), bytes);
        // Robolectric's codecs give back no sound, so the conversion fails after the copy is known to need both fixes.
        readAsXhe(copy, 1);
        assertTrue(XheAudio.needsFix(copy));
        assertTrue(XheAudio.hasSamsungBox(copy));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        fromCopyThread(copy);
        assertTrue("the failure is recorded", Settings.hookErrorAt(XheAudio.KEY) > 0);
        assertArrayEquals("not even the Samsung box is renamed", bytes, Files.readAllBytes(copy.toPath()));
        assertEquals("its own scratch files are gone", Arrays.asList("orca_share_media43.mp4"), Arrays.asList(folder.getRoot().list()));
    }

    @Test public void onlyTopLevelSamsungBoxesInAnMp4AreRenamed() throws Exception {
        byte[] nested = join(FILE_TYPE, box("moov", box("udta", box("sefd", new byte[8]))), box("mdat", new byte[16]));
        byte[] notMp4 = join(box("mdat", new byte[16]), box("sefd", new byte[8]));
        // A 64-bit sized mdat before the tag, as long recordings write it.
        ByteBuffer large = ByteBuffer.allocate(16 + 32);
        large.putInt(1).put("mdat".getBytes(StandardCharsets.US_ASCII)).putLong(16 + 32);
        byte[] wide = join(FILE_TYPE, large.array(), box("sefd", new byte[8]));
        // A box that claims more than the file holds ends the walk, so the sefd after it is never reached.
        ByteBuffer broken = ByteBuffer.allocate(8);
        broken.putInt(1 << 20).put("mdat".getBytes(StandardCharsets.US_ASCII));
        byte[] cut = join(FILE_TYPE, broken.array(), box("sefd", new byte[8]));
        byte[] tiny = join(FILE_TYPE, new byte[] {0, 0, 0, 4, 's', 'e', 'f', 'd'});
        for (byte[] bytes : new byte[][] {nested, notMp4, cut, tiny}) {
            File file = folder.newFile();
            Files.write(file.toPath(), bytes);
            assertFalse(XheAudio.hasSamsungBox(file));
            assertFalse(XheAudio.dropSamsungBoxes(file));
            assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
        }
        File file = folder.newFile();
        Files.write(file.toPath(), wide);
        assertTrue(XheAudio.dropSamsungBoxes(file));
        assertArrayEquals(renamedAt(wide, FILE_TYPE.length + 48), Files.readAllBytes(file.toPath()));
        assertFalse("a missing file has none", XheAudio.hasSamsungBox(new File(folder.getRoot(), "gone.mp4")));
    }

    @Test public void aPickedSamsungRecordingIsSentAsARenamedCopy() throws Exception {
        File picked = folder.newFile("Screen_Recording_20261014.mp4");
        byte[] bytes = samsungRecording();
        Files.write(picked.toPath(), bytes);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        // A path that starts with a slash, as Messenger passes it on the phone (here /C:/... on Windows).
        String path = picked.toURI().getPath();
        String sent = fromTranscoder(path);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        File cache = new File(RuntimeEnvironment.getApplication().getCacheDir(), XheAudio.PICKED);
        assertEquals(cache.getCanonicalPath(), new File(sent).getParentFile().getCanonicalPath());
        assertArrayEquals(renamedAt(bytes, bytes.length - 8 - 41), Files.readAllBytes(new File(sent).toPath()));
        assertArrayEquals("the picked file is never touched", bytes, Files.readAllBytes(picked.toPath()));
        assertEquals("the same video reuses its copy", sent, fromTranscoder(path));
        assertEquals("only the finished copy is kept", 1, cache.list().length);
        assertTrue("a file Uri comes back as one", fromTranscoder("file://" + path).startsWith("file:"));
    }

    @Test public void convertedCopiesOlderThanADayAreCleared() throws Exception {
        File cache = folder.newFolder(XheAudio.PICKED);
        long now = System.currentTimeMillis();
        File stale = new File(cache, "1a2b.mp4");
        File fresh = new File(cache, "3c4d.mp4");
        Files.write(stale.toPath(), new byte[] {1});
        Files.write(fresh.toPath(), new byte[] {1});
        assertTrue(stale.setLastModified(now - XheAudio.KEEP_MS - 60_000));
        assertTrue(fresh.setLastModified(now - 60_000));
        XheAudio.prune(cache, now);
        assertEquals(Arrays.asList("3c4d.mp4"), Arrays.asList(cache.list()));
    }

    /** A finished copy's name, all [c]. */
    private static String finishedName(char c) {
        char[] name = new char[64];
        Arrays.fill(name, c);
        return new String(name) + ".mp4";
    }

    private static void noCopies() {
        String[] left = pickedCache().list();
        assertTrue(Arrays.toString(left), left == null || left.length == 0);
    }

    /** A picked Samsung recording, ready for the transcoder with the switch on, answering the path Messenger passes. */
    private String pickedRecording(String name, byte[] bytes) throws Exception {
        File picked = folder.newFile(name);
        Files.write(picked.toPath(), bytes);
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();
        return picked.toURI().getPath();
    }

    @Test public void scratchFilesAConversionLeftBehindAreCleared() throws Exception {
        File cache = folder.newFolder(XheAudio.PICKED);
        long now = System.currentTimeMillis();
        long abandoned = now - 2 * XheAudio.limits.workMs - 60_000;
        File left = new File(cache, XheAudio.SCRATCH + "1.source");
        File working = new File(cache, XheAudio.SCRATCH + "2.source");
        File copy = new File(cache, finishedName('c'));
        for (File file : new File[] {left, working, copy}) Files.write(file.toPath(), new byte[] {1});
        assertTrue(left.setLastModified(abandoned));
        assertTrue(working.setLastModified(now - 60_000));
        assertTrue("a finished copy keeps its day", copy.setLastModified(abandoned));
        XheAudio.prune(cache, now);
        String[] names = cache.list();
        Arrays.sort(names);
        assertEquals(Arrays.asList(copy.getName(), working.getName()), Arrays.asList(names));
    }

    /** A day's copies go without another pick: once as Messenger starts, then when the next one left falls due. */
    @Test public void oldCopiesAreClearedAtStartAndWhenTheNextOneFallsDue() throws Exception {
        boolean started = HostScreens.started, failed = HostScreens.failed;
        java.util.Set<String> installed = Settings.installed;
        try {
            File cache = pickedCache();
            assertTrue(cache.isDirectory() || cache.mkdirs());
            long now = System.currentTimeMillis();
            File stale = new File(cache, finishedName('a'));
            File later = new File(cache, finishedName('b'));
            for (File file : new File[] {stale, later}) Files.write(file.toPath(), new byte[] {1});
            assertTrue(stale.setLastModified(now - XheAudio.KEEP_MS - 60_000));
            assertTrue("due in ten minutes", later.setLastModified(now - XheAudio.KEEP_MS + 600_000));
            HostScreens.started = false;
            HostScreens.failed = false;
            Settings.installed = java.util.Set.of(XheAudio.KEY);
            HostScreens.start(RuntimeEnvironment.getApplication());
            assertEquals(Arrays.asList(later.getName()), Arrays.asList(cache.list()));
            // Its day runs out with Messenger still open, so only the clear that waits for it can take it.
            assertTrue(later.setLastModified(now - XheAudio.KEEP_MS - 1));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(600_000));
            assertTrue("not before it's due", later.exists());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(2 * XheAudio.PRUNE_SLACK_MS));
            assertFalse(later.exists());
            assertEquals("nothing left to wait for", 0, XheAudio.pruneAt);
        } finally {
            HostScreens.started = started;
            HostScreens.failed = failed;
            Settings.installed = installed;
            CrashGuard.resetForTests();
        }
    }

    @Test public void aCopyUsedTodayStaysAndTheClearWaitsADayForIt() throws Exception {
        File cache = pickedCache();
        assertTrue(cache.isDirectory() || cache.mkdirs());
        File fresh = new File(cache, finishedName('c'));
        Files.write(fresh.toPath(), new byte[] {1});
        assertTrue(fresh.setLastModified(System.currentTimeMillis()));
        long before = android.os.SystemClock.uptimeMillis();
        XheAudio.pruneLater(RuntimeEnvironment.getApplication());
        assertTrue(fresh.exists());
        long wait = XheAudio.pruneAt - before;
        assertTrue("waits " + wait, wait > XheAudio.KEEP_MS - 60_000 && wait <= XheAudio.KEEP_MS + XheAudio.PRUNE_SLACK_MS);
    }

    @Test public void filesAConversionLeftBesideASharedCopyAreCleared() throws Exception {
        byte[] plain = "plain".getBytes(StandardCharsets.US_ASCII);
        File shared = folder.newFile("orca_share_media46.mp4");
        Files.write(shared.toPath(), plain);
        long now = System.currentTimeMillis();
        long abandoned = now - 2 * XheAudio.limits.workMs - 60_000;
        File sound = new File(folder.getRoot(), "orca_share_media12.mp4" + XheAudio.BESIDE + "1.sound.mp4");
        File joined = new File(folder.getRoot(), "orca_share_media12.mp4" + XheAudio.BESIDE + "2.joined.mp4");
        File working = new File(folder.getRoot(), "orca_share_media13.mp4" + XheAudio.BESIDE + "3.joined.mp4");
        File messengers = new File(folder.getRoot(), "orca_share_media12.mp4");
        for (File file : new File[] {sound, joined, working, messengers}) Files.write(file.toPath(), new byte[] {1});
        for (File file : new File[] {sound, joined, messengers}) assertTrue(file.setLastModified(abandoned));
        assertTrue(working.setLastModified(now - 60_000));
        Settings.preferences.edit().putBoolean(XheAudio.KEY, true).commit();

        fromCopyThread(shared);
        assertFalse(sound.exists());
        assertFalse(joined.exists());
        assertTrue("a conversion still running keeps its files", working.exists());
        assertTrue("Messenger's own copies are never touched", messengers.exists());
        assertArrayEquals(plain, Files.readAllBytes(shared.toPath()));
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
    }

    @Test public void aVideoBiggerThanAConversionTakesIsSentAsItIs() throws Exception {
        byte[] bytes = samsungRecording(11);
        String path = pickedRecording("VID_big.mp4", bytes);
        XheAudio.limits.inputBytes = bytes.length - 1;
        assertEquals(path, fromTranscoder(path));
        // A provider doesn't say how much is coming, so its copy stops once it's past the limit.
        Videos.served = new File(folder.getRoot(), "VID_big.mp4");
        Robolectric.setupContentProvider(Videos.class, VIDEOS);
        String uri = "content://" + VIDEOS + "/video/11";
        assertEquals(uri, fromTranscoder(uri));
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals(bytes, Files.readAllBytes(Videos.served.toPath()));
        noCopies();
    }

    @Test public void aStreamThatNeverEndsIsCutOffAtTheLimit() throws Exception {
        XheAudio.Limits limits = new XheAudio.Limits();
        limits.inputBytes = 1 << 20;
        InputStream endless = new InputStream() {
            @Override public int read() { return 7; }
            @Override public int read(byte[] b, int off, int len) {
                Arrays.fill(b, off, off + len, (byte) 7);
                return len;
            }
        };
        File out = folder.newFile("endless.source");
        try {
            XheAudio.copyAndName(endless, out, limits, budget(limits.workMs));
            fail("the copy has no end");
        } catch (XheAudio.OverBudget expected) {
            assertTrue(out.length() + " bytes", out.length() <= limits.inputBytes);
        }
    }

    @Test public void aStreamThatKeepsAnsweringWithNothingStops() throws Exception {
        XheAudio.Limits limits = new XheAudio.Limits();
        InputStream silent = new InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] b, int off, int len) { return 0; }
        };
        try {
            XheAudio.copyAndName(silent, folder.newFile("silent.source"), limits, budget(limits.workMs));
            fail("the copy never ends");
        } catch (XheAudio.OverBudget wrong) {
            fail("a stalled provider is a failure, not a limit: " + wrong.getMessage());
        } catch (IOException expected) {
            assertEquals("the picked video stopped coming", expected.getMessage());
        }
    }

    @Test public void aNearlyFullPhoneLeavesVideosAsTheyAre() throws Exception {
        byte[] bytes = samsungRecording(12);
        String path = pickedRecording("VID_full.mp4", bytes);
        File copy = folder.newFile("orca_share_media46.mp4");
        byte[] shared = "xhe sound".getBytes(StandardCharsets.US_ASCII);
        Files.write(copy.toPath(), shared);
        readAsXhe(copy, 1);
        XheAudio.limits.reserveBytes = Long.MAX_VALUE;
        assertEquals(path, fromTranscoder(path));
        fromCopyThread(copy);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        assertArrayEquals(bytes, Files.readAllBytes(new File(folder.getRoot(), "VID_full.mp4").toPath()));
        assertArrayEquals(shared, Files.readAllBytes(copy.toPath()));
        String[] names = folder.getRoot().list();
        Arrays.sort(names);
        assertEquals(Arrays.asList("VID_full.mp4", "orca_share_media46.mp4"), Arrays.asList(names));
        noCopies();
    }

    @Test public void aConversionThatRunsOutOfTimeKeepsNoCopy() throws Exception {
        byte[] bytes = samsungRecording(13);
        String path = pickedRecording("VID_slow.mp4", bytes);
        XheAudio.limits.workMs = 0;
        assertEquals(path, fromTranscoder(path));
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
        noCopies();
    }

    @Test public void aSendThatFindsBothSlotsBusyGoesAsItIs() throws Exception {
        byte[] bytes = samsungRecording(14);
        String path = pickedRecording("VID_busy.mp4", bytes);
        XheAudio.limits.slotWaitMs = 0;
        assertTrue(XheAudio.WORKERS.tryAcquire(XheAudio.SLOTS));
        try {
            assertEquals(path, fromTranscoder(path));
        } finally {
            XheAudio.WORKERS.release(XheAudio.SLOTS);
        }
        noCopies();
        String sent = fromTranscoder(path);
        assertNotEquals("a free slot converts again", path, sent);
        assertArrayEquals(renamed(bytes), sentBytes(sent));
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
    }

    @Test public void aCancelledSendGoesAsItIsAndStaysCancelled() throws Exception {
        byte[] bytes = samsungRecording(15);
        String path = pickedRecording("VID_cancel.mp4", bytes);
        XheAudio.limits.slotWaitMs = 10_000;
        String[] answer = new String[1];
        boolean[] cancelled = new boolean[1];
        Thread send = new Thread(() -> {
            answer[0] = XheAudio.transcodeSource(path);
            cancelled[0] = Thread.currentThread().isInterrupted();
        });
        assertTrue(XheAudio.WORKERS.tryAcquire(XheAudio.SLOTS));
        try {
            send.start();
            long until = System.currentTimeMillis() + 10_000;
            while (!XheAudio.WORKERS.hasQueuedThreads() && System.currentTimeMillis() < until) Thread.sleep(10);
            assertTrue("the send waits for a slot", XheAudio.WORKERS.hasQueuedThreads());
            send.interrupt();
            send.join(10_000);
            assertFalse(send.isAlive());
        } finally {
            XheAudio.WORKERS.release(XheAudio.SLOTS);
        }
        assertEquals(path, answer[0]);
        assertTrue("Messenger still sees its cancel", cancelled[0]);
        noCopies();

        // A copy under way stops at its next read once the send is cancelled.
        File out = folder.newFile("cancelled.source");
        Thread copy = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                XheAudio.copyAndName(new ByteArrayInputStream(bytes), out, XheAudio.limits, budget(XheAudio.limits.workMs));
                answer[0] = "copied";
            } catch (InterruptedIOException expected) {
                answer[0] = "stopped";
            } catch (IOException other) {
                answer[0] = other.toString();
            }
        });
        copy.start();
        copy.join(10_000);
        assertFalse(copy.isAlive());
        assertEquals("stopped", answer[0]);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
    }

    @Test public void aFullCacheClearsOnlyCopiesNoSendIsUsing() throws Exception {
        File cache = pickedCache();
        assertTrue(cache.isDirectory() || cache.mkdirs());
        byte[] bytes = samsungRecording(16);
        long now = System.currentTimeMillis();
        File old = new File(cache, finishedName('a'));
        File recent = new File(cache, finishedName('b'));
        Files.write(old.toPath(), new byte[bytes.length]);
        Files.write(recent.toPath(), new byte[bytes.length]);
        assertTrue(old.setLastModified(now - XheAudio.ACTIVE_MS - 60_000));
        assertTrue(recent.setLastModified(now - 60_000));
        // Room for two copies and a half: the new one fits once the copy nobody used in an hour is gone.
        XheAudio.limits.cacheBytes = 2L * bytes.length + bytes.length / 2;
        String path = pickedRecording("VID_room.mp4", bytes);
        String sent = fromTranscoder(path);
        assertNotEquals(path, sent);
        assertArrayEquals(renamed(bytes), sentBytes(sent));
        assertFalse("the copy no send used in an hour made room", old.exists());
        assertTrue("a copy a send may still be reading stays", recent.exists());

        // Every copy left is recent now, so the next video has no room and goes as it is.
        byte[] other = samsungRecording(17);
        String otherPath = pickedRecording("VID_no_room.mp4", other);
        assertEquals(otherPath, fromTranscoder(otherPath));
        assertTrue(recent.exists());
        assertArrayEquals(renamed(bytes), sentBytes(sent));
        onlyFinishedCopies(2);
        assertEquals("not a hook failure", 0, Settings.hookErrorAt(XheAudio.KEY));
    }

    @Test public void twoConversionsAtOnceNeverCountTheSameRoom() throws Exception {
        File cache = pickedCache();
        assertTrue(cache.isDirectory() || cache.mkdirs());
        long now = System.currentTimeMillis();
        long need = 1L << 30;
        XheAudio.Limits limits = new XheAudio.Limits();
        // Free space for one conversion and a half above the reserve, and a cache share that holds plenty.
        limits.reserveBytes = cache.getUsableSpace() - need - need / 2;
        limits.cacheBytes = Long.MAX_VALUE / 4;
        XheAudio.makeRoom(cache, need, now, limits);
        try {
            XheAudio.makeRoom(cache, need, now, limits);
            fail("the second conversion counted the first one's room");
        } catch (XheAudio.OverBudget expected) {
            assertEquals("the phone is nearly full", expected.getMessage());
        }
        try {
            XheAudio.claimDisk(cache, need, limits);
            fail("a shared video's conversion counted it too");
        } catch (XheAudio.OverBudget expected) {
            assertEquals("the phone is nearly full", expected.getMessage());
        }
        XheAudio.unclaim(need, true);
        XheAudio.claimDisk(cache, need, limits);
        XheAudio.unclaim(need, false);

        // The cache's share is held the same way.
        limits.reserveBytes = Long.MIN_VALUE / 4;
        limits.cacheBytes = need + need / 2;
        XheAudio.makeRoom(cache, need, now, limits);
        try {
            XheAudio.makeRoom(cache, need, now, limits);
            fail("the second conversion counted the first one's share of the cache");
        } catch (XheAudio.OverBudget expected) {
            assertEquals("the converted copies fill their share of the cache", expected.getMessage());
        }
        XheAudio.unclaim(need, true);
    }

    @Test public void aConversionStopsOnceTheDiskReachesItsReserve() throws Exception {
        XheAudio.Work roomy = new XheAudio.Work(60_000, folder.getRoot(), 0);
        for (int i = 0; i < 3 * XheAudio.ROOM_ROUNDS; i++) roomy.check();
        XheAudio.Work full = new XheAudio.Work(60_000, folder.getRoot(), Long.MAX_VALUE);
        try {
            full.check();
            fail("the disk is below its reserve");
        } catch (XheAudio.OverBudget expected) {
            assertEquals("the phone is nearly full", expected.getMessage());
        }
        File out = folder.newFile("full.source");
        try {
            XheAudio.copyAndName(new ByteArrayInputStream(samsungRecording()), out, XheAudio.limits,
                new XheAudio.Work(60_000, folder.getRoot(), Long.MAX_VALUE));
            fail("the copy went on below the reserve");
        } catch (XheAudio.OverBudget expected) {
            assertEquals("nothing was written", 0, out.length());
        }
    }

    @Test public void aCopyHandedOutAgainIsntClearedForRoom() throws Exception {
        File cache = pickedCache();
        assertTrue(cache.isDirectory() || cache.mkdirs());
        File done = new File(cache, finishedName('d'));
        Files.write(done.toPath(), new byte[64]);
        assertTrue(done.setLastModified(System.currentTimeMillis() - XheAudio.ACTIVE_MS - 60_000));
        assertTrue(XheAudio.reuse(done));
        XheAudio.Limits limits = new XheAudio.Limits();
        limits.cacheBytes = 64;
        try {
            XheAudio.makeRoom(cache, 1, System.currentTimeMillis(), limits);
            fail("the only copy is in use, so there's no room");
        } catch (XheAudio.OverBudget expected) {
            assertTrue("the send reading it keeps it", done.exists());
        }
        assertFalse("a copy that's gone isn't handed out", XheAudio.reuse(new File(cache, finishedName('e'))));
    }

    @Test public void aConversionCountsItsNewSoundAsWellAsTheVideo() throws Exception {
        File timed = folder.newFile("VID_timed.mp4");
        File untimed = folder.newFile("VID_untimed.mp4");
        Files.write(timed.toPath(), new byte[1000]);
        Files.write(untimed.toPath(), new byte[1000]);
        readAs(timed, 1, 0, 10_000_000);
        readAsXhe(untimed, 1);
        long secondOfSound = XheAudio.BIT_RATE / 8;
        assertEquals("a joined copy, and eleven seconds of sound twice", 1000 + 2 * 11 * secondOfSound, XheAudio.converting(timed));
        assertEquals("a second of sound when the track doesn't say", 1000 + 2 * secondOfSound, XheAudio.converting(untimed));
    }

    @Test public void theSwitchSitsUnderPhotosAndVideosAndStartsOff() {
        assertTrue(Settings.installed.contains(XheAudio.KEY));
        String[] row = Arrays.stream(SettingsActivity.CONTROLS).filter(c -> c[0].equals(XheAudio.KEY)).findFirst().orElseThrow();
        assertEquals("Fix videos that won't send", row[1]);
        assertEquals("photos_videos", row[3]);
        assertFalse(Settings.preferences.contains(XheAudio.KEY));
        assertFalse(Settings.wouldUse(XheAudio.KEY));
    }
}
