package app.hushmessenger.extension;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a picked font has to be before its copy is kept, and what its fvar table says. The fonts
 * are Android's own, as Robolectric's native runtime ships them: a static TrueType font, a
 * variable one with a 'wght' axis and a collection.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FontFileTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    public static final String STATIC_FONT = "fonts/Rubik-Regular.ttf";
    public static final String VARIABLE_FONT = "fonts/NotoSansKhmer-VF.ttf";
    public static final String COLLECTION = "fonts/NotoSansCJK-Regular.ttc";

    /** A font Robolectric's native runtime ships, as bytes. */
    public static byte[] font(String resource) throws IOException {
        try (InputStream input = FontFileTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource + " isn't on the test classpath", input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    /** A stream that counts what was read from it and whether it was closed. */
    static final class Watched extends InputStream {
        private final InputStream inner;
        long read;
        boolean closed;

        Watched(InputStream inner) {
            this.inner = inner;
        }

        @Override
        public int read() throws IOException {
            int value = inner.read();
            if (value >= 0) read++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = inner.read(buffer, offset, length);
            if (count > 0) read += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            inner.close();
        }
    }

    /** [length] bytes that start with a TrueType tag and are zeros after it, made as they're read. */
    static InputStream fontShaped(long length) {
        return new InputStream() {
            private long at;

            @Override
            public int read() {
                if (at >= length) return -1;
                int value = at == 1 ? 1 : 0;
                at++;
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int count) {
                if (at >= length) return -1;
                int n = (int) Math.min(count, length - at);
                for (int i = 0; i < n; i++) buffer[offset + i] = (byte) (at + i == 1 ? 1 : 0);
                at += n;
                return n;
            }
        };
    }

    private File existingCopy() throws IOException {
        File target = new File(folder.getRoot(), FontFile.NAME);
        Files.write(target.toPath(), "the font picked before".getBytes(StandardCharsets.UTF_8));
        return target;
    }

    private static FontFile.Refusal refusal(InputStream input, File target, FontFile.Check check) {
        try {
            FontFile.copy(input, target, check);
        } catch (FontFile.Refused refused) {
            return refused.reason;
        }
        fail("the file was taken");
        return null;
    }

    private void assertUntouched(File target) throws IOException {
        assertEquals("the copy picked before changed", "the font picked before",
                new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertNoPartial();
    }

    private void assertNoPartial() {
        String[] partials = folder.getRoot().list((dir, name) -> name.startsWith(FontFile.PARTIAL));
        assertEquals("a partial copy was left behind", 0, partials == null ? 0 : partials.length);
    }

    @Test
    public void aFontIsKnownByItsFirstFourBytes() {
        assertTrue(FontFile.isFontTag(0x00010000));
        assertTrue(FontFile.isFontTag(tag("true")));
        assertTrue(FontFile.isFontTag(tag("OTTO")));
        assertTrue(FontFile.isFontTag(tag("ttcf")));
        // Web fonts, which Android can't draw from, a zip, a photo and text.
        for (String other : new String[]{"wOFF", "wOF2", "PK\u0003\u0004", "\u0089PNG", "GIF8", "{\"fo", "TRUE", "otto"}) {
            assertFalse(other, FontFile.isFontTag(tag(other)));
        }
        assertFalse(FontFile.isFontTag(0));
    }

    private static int tag(String four) {
        return ByteBuffer.wrap(four.getBytes(StandardCharsets.ISO_8859_1)).getInt();
    }

    @Test
    public void aFontIsCopiedWholeOnceAndroidBuildsIt() throws Exception {
        byte[] rubik = font(STATIC_FONT);
        File target = existingCopy();
        List<byte[]> checked = new ArrayList<>();
        Watched input = new Watched(new ByteArrayInputStream(rubik));
        FontFile.copy(input, target, file -> {
            try {
                checked.add(Files.readAllBytes(file.toPath()));
            } catch (IOException unreadable) {
                throw new AssertionError(unreadable);
            }
            assertFalse("the check saw the copy in place of the old one", file.equals(target));
            return true;
        });
        assertArrayEquals(rubik, Files.readAllBytes(target.toPath()));
        assertEquals("the check didn't see the whole file", 1, checked.size());
        assertArrayEquals(rubik, checked.get(0));
        assertTrue("the picker's stream was left open", input.closed);
        assertNoPartial();
    }

    /**
     * A stream that answers every read with nothing is broken, not slow, so it's turned down instead of read forever,
     * before or after the font header. A stopped copy reads no further either.
     */
    @Test
    public void aStreamThatReadsNothingOrAStoppedCopyIsTurnedDown() throws Exception {
        File target = existingCopy();
        for (int header : new int[] {0, 4}) {
            AtomicInteger reads = new AtomicInteger();
            InputStream nothing = new InputStream() {
                private int served;

                @Override
                public int read() {
                    throw new AssertionError("reads go through the buffer");
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    reads.incrementAndGet();
                    if (served >= header) return 0;
                    buffer[offset] = (byte) (served == 1 ? 1 : 0);
                    served++;
                    return 1;
                }
            };
            assertEquals(FontFile.Refusal.UNREADABLE, refusal(nothing, target, file -> true));
            assertTrue("read " + reads.get() + " times", reads.get() <= header + FontFile.MOST_EMPTY_READS + 1);
            assertUntouched(target);
        }
        Thread.currentThread().interrupt();
        try {
            assertEquals(FontFile.Refusal.UNREADABLE, refusal(new ByteArrayInputStream(font(STATIC_FONT)), target, file -> true));
        } finally {
            Thread.interrupted();
        }
        assertUntouched(target);
    }

    /** A partial copy a crash or a stopped copy left behind goes once it's old, and one still being written stays. */
    @Test
    public void anOldPartialCopyIsRemoved() throws Exception {
        File old = new File(folder.getRoot(), FontFile.PARTIAL + "-1.tmp");
        File fresh = new File(folder.getRoot(), FontFile.PARTIAL + "-2.tmp");
        File legacy = new File(folder.getRoot(), FontFile.PARTIAL);
        for (File partial : new File[] {old, fresh, legacy}) Files.write(partial.toPath(), new byte[] {1});
        long stale = System.currentTimeMillis() - FontFile.SIZING_STALE_MS - 60_000;
        assertTrue(old.setLastModified(stale));
        assertTrue(legacy.setLastModified(stale));
        FontFile.dropAdjusted(folder.getRoot(), null);
        assertFalse(old.exists());
        assertFalse(legacy.exists());
        assertTrue(fresh.exists());
    }

    @Test
    public void everyKindOfFontFileIsTaken() throws Exception {
        for (String resource : new String[]{STATIC_FONT, VARIABLE_FONT}) {
            File target = new File(folder.getRoot(), FontFile.NAME);
            FontFile.copy(new ByteArrayInputStream(font(resource)), target, file -> true);
            assertArrayEquals(resource, font(resource), Files.readAllBytes(target.toPath()));
        }
        // CFF outlines, and Apple's tag: only the first four bytes are the question here.
        for (String head : new String[]{"OTTO", "true", "ttcf"}) {
            File target = new File(folder.getRoot(), FontFile.NAME);
            byte[] bytes = (head + " and the rest of a font").getBytes(StandardCharsets.ISO_8859_1);
            FontFile.copy(new ByteArrayInputStream(bytes), target, file -> true);
            assertArrayEquals(head, bytes, Files.readAllBytes(target.toPath()));
        }
    }

    /** A photo picked by mistake is turned down on its first four bytes, before anything is written. */
    @Test
    public void aFileThatIsntAFontIsTurnedDownBeforeAnythingIsWritten() throws Exception {
        File target = existingCopy();
        byte[] photo = new byte[100_000];
        byte[] head = {(byte) 0x89, 'P', 'N', 'G'};
        System.arraycopy(head, 0, photo, 0, head.length);
        Watched input = new Watched(new ByteArrayInputStream(photo));
        AtomicBoolean asked = new AtomicBoolean();
        assertEquals(FontFile.Refusal.NOT_A_FONT, refusal(input, target, file -> asked.getAndSet(true)));
        assertEquals("read past the font header", 4, input.read);
        assertTrue(input.closed);
        assertFalse("Android was asked about a photo", asked.get());
        assertUntouched(target);

        assertEquals("an empty file is no font", FontFile.Refusal.NOT_A_FONT,
                refusal(new ByteArrayInputStream(new byte[0]), target, file -> true));
        assertEquals(FontFile.Refusal.NOT_A_FONT,
                refusal(new ByteArrayInputStream(new byte[]{0, 1, 0}), target, file -> true));
        assertUntouched(target);
    }

    @Test
    public void aFileLargerThanTheCapIsTurnedDown() throws Exception {
        File target = existingCopy();
        assertEquals(FontFile.Refusal.TOO_LARGE, refusal(fontShaped(FontFile.MAX_BYTES + 1), target, file -> true));
        assertUntouched(target);

        // The cap itself is taken.
        FontFile.copy(fontShaped(FontFile.MAX_BYTES), target, file -> true);
        assertEquals(FontFile.MAX_BYTES, target.length());
        assertEquals(20, FontFile.MAX_MEGABYTES);
    }

    /** A file that starts like a font and isn't one Android builds is turned down, the check's own failure too. */
    @Test
    public void aFontAndroidWontBuildIsTurnedDown() throws Exception {
        File target = existingCopy();
        assertEquals(FontFile.Refusal.WONT_LOAD,
                refusal(new ByteArrayInputStream(font(STATIC_FONT)), target, file -> false));
        assertUntouched(target);
        assertEquals(FontFile.Refusal.WONT_LOAD, refusal(new ByteArrayInputStream(font(STATIC_FONT)), target,
                file -> {
                    throw new IllegalStateException("native font parser gave up");
                }));
        assertUntouched(target);
    }

    @Test
    public void aStreamThatBreaksOffIsUnreadable() throws Exception {
        File target = existingCopy();
        InputStream breaks = new InputStream() {
            private int at;

            @Override
            public int read() throws IOException {
                if (at >= 10) throw new IOException("content://provider/private-name.ttf went away");
                return at++ == 1 ? 1 : 0;
            }
        };
        assertEquals(FontFile.Refusal.UNREADABLE, refusal(breaks, target, file -> true));
        assertUntouched(target);
        assertEquals(FontFile.Refusal.UNREADABLE, refusal(null, target, file -> true));
        assertUntouched(target);
    }

    /** The name is saved once the copy has passed and before it moves in; one that won't save changes nothing. */
    @Test
    public void theNameIsSavedBeforeTheCopyMovesIn() throws Exception {
        byte[] rubik = font(STATIC_FONT);
        File target = existingCopy();
        List<String> said = new ArrayList<>();
        FontFile.copy(new ByteArrayInputStream(rubik), target, file -> {
            said.add("check");
            return true;
        }, new FontFile.Choice() {
            @Override
            public boolean save() {
                try {
                    said.add("save, the copy before still " + (Files.readAllBytes(target.toPath()).length
                            == "the font picked before".length() ? "in place" : "gone"));
                } catch (IOException unreadable) {
                    throw new AssertionError(unreadable);
                }
                return true;
            }

            @Override
            public void undo() {
                said.add("undo");
            }
        });
        assertEquals(Arrays.asList("check", "save, the copy before still in place"), said);
        assertArrayEquals(rubik, Files.readAllBytes(target.toPath()));

        // A name that won't save, or whose saving throws, leaves the copy before where it was. Android takes a commit into
        // memory before the disk answers, so the old name is put back either way.
        File again = existingCopy();
        AtomicInteger undone = new AtomicInteger();
        for (boolean throwing : new boolean[]{false, true}) {
            try {
                FontFile.copy(new ByteArrayInputStream(rubik), again, file -> true, new FontFile.Choice() {
                    @Override
                    public boolean save() {
                        if (throwing) throw new IllegalStateException("injected commit failure");
                        return false;
                    }

                    @Override
                    public void undo() {
                        undone.incrementAndGet();
                    }
                });
                fail("the copy was taken without its name");
            } catch (FontFile.Refused refused) {
                assertEquals(FontFile.Refusal.NOT_SAVED, refused.reason);
            }
            assertUntouched(again);
        }
        assertEquals("a name that didn't save was left in memory", 2, undone.get());
    }

    /**
     * A copy that can't move in leaves the one picked before in place, never neither, and puts the
     * name back. Windows won't rename a file another handle holds open without sharing its
     * deletion, which stands in here for a rename that fails.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void aCopyThatCantMoveInLeavesTheOneBefore() throws Exception {
        assumeTrue("only Windows refuses to rename a file held open",
                System.getProperty("os.name", "").startsWith("Windows"));
        // The JDK's own option, looked up by name: the test sources compile against Android's classes.
        OpenOption noShareDelete = (OpenOption) Enum.valueOf(
                (Class) Class.forName("com.sun.nio.file.ExtendedOpenOption"), "NOSHARE_DELETE");
        File target = existingCopy();
        List<Closeable> held = new ArrayList<>();
        AtomicInteger saved = new AtomicInteger();
        AtomicInteger undone = new AtomicInteger();
        try {
            FontFile.copy(new ByteArrayInputStream(font(STATIC_FONT)), target, file -> {
                try {
                    held.add(Files.newByteChannel(file.toPath(), StandardOpenOption.READ, noShareDelete));
                } catch (IOException unreadable) {
                    throw new AssertionError(unreadable);
                }
                return true;
            }, new FontFile.Choice() {
                @Override
                public boolean save() {
                    saved.incrementAndGet();
                    return true;
                }

                @Override
                public void undo() {
                    undone.incrementAndGet();
                }
            });
            fail("a copy held open moved in");
        } catch (FontFile.Refused refused) {
            assertEquals(FontFile.Refusal.NOT_SAVED, refused.reason);
        } finally {
            for (Closeable handle : held) handle.close();
        }
        assertTrue("the copy picked before was lost", target.isFile());
        assertEquals("the copy picked before changed", "the font picked before",
                new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, saved.get());
        assertEquals("the name wasn't put back", 1, undone.get());
    }

    @Test
    public void aCopyWithNowhereToGoIsNotSaved() throws Exception {
        File nowhere = new File(new File(folder.getRoot(), "missing-folder"), FontFile.NAME);
        assertEquals(FontFile.Refusal.NOT_SAVED,
                refusal(new ByteArrayInputStream(font(STATIC_FONT)), nowhere, file -> true));
        assertFalse(nowhere.exists());
    }

    @Test
    public void theWeightAxisComesFromTheFvarTable() throws Exception {
        assertArrayEquals("Noto Sans Khmer's early axis runs from 26 to 190", new float[]{26f, 190f},
                FontFile.weightAxis(write("khmer.ttf", font(VARIABLE_FONT))), 0.001f);
        assertNull("a static font has no axis", FontFile.weightAxis(write("rubik.ttf", font(STATIC_FONT))));
        assertNull("a collection of static fonts has none", FontFile.weightAxis(write("cjk.ttc", font(COLLECTION))));
        assertNull(FontFile.weightAxis(new File(folder.getRoot(), "not-there.ttf")));

        byte[] variable = sfntWithAxes(new String[]{"wdth", "wght"}, new float[][]{{75, 100}, {100, 900}}, 20);
        assertArrayEquals(new float[]{100f, 900f}, FontFile.weightAxis(write("made.ttf", variable)), 0.001f);
        assertArrayEquals("the first font of a collection", new float[]{100f, 900f},
                FontFile.weightAxis(write("made.ttc", collectionOf(variable))), 0.001f);
        assertNull("no wght axis", FontFile.weightAxis(write("wdth.ttf",
                sfntWithAxes(new String[]{"wdth"}, new float[][]{{75, 100}}, 20))));
        assertNull("an axis record too short to hold one", FontFile.weightAxis(write("short.ttf",
                sfntWithAxes(new String[]{"wght"}, new float[][]{{100, 900}}, 16))));
        assertNull("weights no font takes", FontFile.weightAxis(write("zero.ttf",
                sfntWithAxes(new String[]{"wght"}, new float[][]{{0, 900}}, 20))));
        byte[] cut = new byte[variable.length - 30];
        System.arraycopy(variable, 0, cut, 0, cut.length);
        assertNull("a table cut off", FontFile.weightAxis(write("cut.ttf", cut)));
        byte[] boastful = variable.clone();
        boastful[4] = (byte) 0xFF;
        boastful[5] = (byte) 0xFF;
        assertNull("a directory claiming 65535 tables", FontFile.weightAxis(write("boastful.ttf", boastful)));
    }

    /** #48: a size rewrites the units per em alone, and a move trades ascent for descent so the line keeps its height. */
    @Test
    public void aSizeChangesOnlyTheUnitsPerEmAndAMoveTradesAscentForDescent() throws Exception {
        byte[] plain = font(STATIC_FONT);
        ByteBuffer before = ByteBuffer.wrap(plain);
        int head = table(plain, 0, "head"), hhea = table(plain, 0, "hhea"), os2 = table(plain, 0, "OS/2");
        int upem = before.getShort(head + 18) & 0xFFFF;

        byte[] sized = plain.clone();
        assertTrue(FontFile.adjust(sized, 125, 0));
        assertEquals(Math.round(upem / 1.25f), ByteBuffer.wrap(sized).getShort(head + 18) & 0xFFFF);
        ByteBuffer.wrap(sized).putShort(head + 18, (short) upem);
        assertArrayEquals("the outlines and every metric stay in the font's own units", plain, sized);

        byte[] lower = plain.clone();
        assertTrue(FontFile.adjust(lower, 100, 10));
        ByteBuffer moved = ByteBuffer.wrap(lower);
        int units = Math.round(upem * 10 / 100f);
        assertEquals(upem, moved.getShort(head + 18) & 0xFFFF);
        // yMin, yMax, hhea ascender and descender, OS/2 typo ascender and descender: all up by the same units.
        for (int at : new int[] {head + 38, head + 42, hhea + 4, hhea + 6, os2 + 68, os2 + 70})
            assertEquals("at " + at, before.getShort(at) + units, moved.getShort(at));
        assertEquals((before.getShort(os2 + 74) & 0xFFFF) + units, moved.getShort(os2 + 74) & 0xFFFF);
        assertEquals("usWinDescent counts down from the baseline", (before.getShort(os2 + 76) & 0xFFFF) - units,
                moved.getShort(os2 + 76) & 0xFFFF);
        assertEquals(before.getShort(hhea + 4) - before.getShort(hhea + 6), moved.getShort(hhea + 4) - moved.getShort(hhea + 6));

        byte[] higher = plain.clone();
        assertTrue(FontFile.adjust(higher, 100, -10));
        assertEquals(before.getShort(hhea + 4) - units, ByteBuffer.wrap(higher).getShort(hhea + 4));

        byte[] both = plain.clone();
        assertTrue(FontFile.adjust(both, 125, 10));
        assertEquals("the move counts in the new units", before.getShort(hhea + 4) + Math.round(Math.round(upem / 1.25f) * 10 / 100f),
                ByteBuffer.wrap(both).getShort(hhea + 4));
    }

    /** #48: hhea or OS/2 metrics left at zero tell the reader to look at the next pair, so a move keeps them at zero. */
    @Test
    public void aMoveLeavesMetricsTheFontLeftUnsetAtZero() throws Exception {
        byte[] plain = font(STATIC_FONT);
        int head = table(plain, 0, "head"), hhea = table(plain, 0, "hhea"), os2 = table(plain, 0, "OS/2");
        ByteBuffer.wrap(plain).putShort(hhea + 4, (short) 0).putShort(hhea + 6, (short) 0);
        ByteBuffer before = ByteBuffer.wrap(plain.clone());
        assertTrue("the control: typo metrics are set", before.getShort(os2 + 68) != 0);
        byte[] lower = plain.clone();
        assertTrue(FontFile.adjust(lower, 100, 10));
        ByteBuffer moved = ByteBuffer.wrap(lower);
        int units = Math.round((before.getShort(head + 18) & 0xFFFF) * 10 / 100f);
        assertEquals(0, moved.getShort(hhea + 4));
        assertEquals(0, moved.getShort(hhea + 6));
        assertEquals(before.getShort(os2 + 68) + units, moved.getShort(os2 + 68));
        assertEquals(before.getShort(head + 42) + units, moved.getShort(head + 42));

        ByteBuffer.wrap(plain).putShort(os2 + 68, (short) 0).putShort(os2 + 70, (short) 0);
        lower = plain.clone();
        assertTrue(FontFile.adjust(lower, 100, 10));
        moved = ByteBuffer.wrap(lower);
        assertEquals(0, moved.getShort(os2 + 68));
        assertEquals(0, moved.getShort(os2 + 70));
        assertEquals("the win pair still moves", (before.getShort(os2 + 74) & 0xFFFF) + units, moved.getShort(os2 + 74) & 0xFFFF);

        // Unsigned, so a win descent moved off zero would leave its range and turn the whole move down.
        ByteBuffer.wrap(plain).putShort(os2 + 74, (short) 0).putShort(os2 + 76, (short) 0);
        lower = plain.clone();
        assertTrue(FontFile.adjust(lower, 100, 10));
        moved = ByteBuffer.wrap(lower);
        assertEquals(0, moved.getShort(os2 + 74));
        assertEquals(0, moved.getShort(os2 + 76));
        assertEquals("the head box still moves", before.getShort(head + 38) + units, moved.getShort(head + 38));
    }

    @Test
    public void aSizeOrMoveTheFontCantTakeLeavesItAlone() throws Exception {
        byte[] plain = font(STATIC_FONT);
        for (int[] choice : new int[][] {{65, 0}, {165, 0}, {100, 22}, {100, -22}}) {
            byte[] font = plain.clone();
            assertFalse(Arrays.toString(choice), FontFile.adjust(font, choice[0], choice[1]));
            assertArrayEquals(plain, font);
        }
        byte[] tall = plain.clone();
        ByteBuffer.wrap(tall).putShort(table(tall, 0, "hhea") + 4, (short) 32700);
        byte[] tallBefore = tall.clone();
        assertFalse("an ascender past what its field holds", FontFile.adjust(tall, 100, 20));
        assertArrayEquals("nothing is written before every field is checked", tallBefore, tall);
        byte[] shallow = plain.clone();
        ByteBuffer.wrap(shallow).putShort(table(shallow, 0, "OS/2") + 76, (short) 10);
        byte[] shallowBefore = shallow.clone();
        assertFalse("a win descent below zero", FontFile.adjust(shallow, 100, 20));
        assertArrayEquals(shallowBefore, shallow);

        byte[] noHhea = plain.clone();
        retag(noHhea, "hhea", "xxxx");
        assertFalse(FontFile.adjust(noHhea, 125, 0));
        assertFalse("a table cut off", FontFile.adjust(Arrays.copyOf(plain, table(plain, 0, "head") + 20), 125, 0));
        assertFalse(FontFile.adjust("not a font at all, only words".getBytes(StandardCharsets.UTF_8), 125, 0));
        assertFalse(FontFile.adjust(new byte[3], 125, 0));
    }

    @Test
    public void theFirstFontOfACollectionIsTheOneSized() throws Exception {
        byte[] plain = font(COLLECTION);
        int first = ByteBuffer.wrap(plain).getInt(12);
        int head = table(plain, first, "head"), hhea = table(plain, first, "hhea");
        int upem = ByteBuffer.wrap(plain).getShort(head + 18) & 0xFFFF;
        byte[] sized = plain.clone();
        assertTrue(FontFile.adjust(sized, 80, -4));
        int units = Math.round(upem * 100 / 80f);
        assertEquals(units, ByteBuffer.wrap(sized).getShort(head + 18) & 0xFFFF);
        assertEquals(ByteBuffer.wrap(plain).getShort(hhea + 4) + Math.round(units * -4 / 100f), ByteBuffer.wrap(sized).getShort(hhea + 4));
    }

    @Test
    public void aSizedCopyIsNamedForItsChoicesAndReplacesTheOneBefore() throws Exception {
        File source = write(FontFile.NAME, font(STATIC_FONT));
        File sized = FontFile.adjusted(source, 125, 4);
        assertNotNull(sized);
        assertEquals(source.getParentFile(), sized.getParentFile());
        assertTrue(sized.getName(), sized.getName().startsWith(FontFile.ADJUSTED + "-125-24-"));
        assertTrue(sized.getName(), sized.getName().endsWith("-" + source.length()));
        byte[] expected = font(STATIC_FONT);
        assertTrue(FontFile.adjust(expected, 125, 4));
        assertArrayEquals(expected, Files.readAllBytes(sized.toPath()));
        assertArrayEquals("the picked copy stays as picked", font(STATIC_FONT), Files.readAllBytes(source.toPath()));

        long stamped = sized.lastModified() - 60_000;
        Files.setLastModifiedTime(sized.toPath(), java.nio.file.attribute.FileTime.fromMillis(stamped));
        assertEquals(sized, FontFile.adjusted(source, 125, 4));
        assertEquals("the same choices reuse the copy", stamped, sized.lastModified());

        File smaller = FontFile.adjusted(source, 90, 0);
        assertNotEquals(sized, smaller);
        assertFalse("the copy before goes", sized.exists());
        Files.setLastModifiedTime(source.toPath(), java.nio.file.attribute.FileTime.fromMillis(source.lastModified() + 5_000));
        File repicked = FontFile.adjusted(source, 90, 0);
        assertNotEquals("a new pick isn't the old copy", smaller.getName(), repicked.getName());
        assertFalse(smaller.exists());
        assertEquals("no partial file is left", Arrays.asList(FontFile.NAME, repicked.getName()), sorted(folder.getRoot().list()));

        // Another process's copy still being written stays. One a crash left behind goes once it's old.
        File writing = File.createTempFile(FontFile.SIZING + "-", null, folder.getRoot());
        File abandoned = File.createTempFile(FontFile.SIZING + "-", null, folder.getRoot());
        Files.setLastModifiedTime(abandoned.toPath(),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - FontFile.SIZING_STALE_MS - 60_000));
        FontFile.dropAdjusted(folder.getRoot(), null);
        assertFalse(repicked.exists());
        assertTrue(source.exists());
        assertTrue(writing.exists());
        assertFalse(abandoned.exists());
        assertTrue(writing.delete());
        assertNull(FontFile.adjusted(new File(folder.getRoot(), "not-there"), 125, 0));
        File words = new File(folder.newFolder("words"), FontFile.NAME);
        Files.write(words.toPath(), "not a font at all, only words".getBytes(StandardCharsets.UTF_8));
        assertNull(FontFile.adjusted(words, 125, 0));
        assertEquals(Arrays.asList(FontFile.NAME), sorted(words.getParentFile().list()));
    }

    private static List<String> sorted(String[] names) {
        List<String> list = new ArrayList<>(Arrays.asList(names));
        list.sort(null);
        return list;
    }

    /** Where the [name] table of the font whose directory is at [start] begins, or -1. */
    static int table(byte[] font, int start, String name) {
        ByteBuffer bytes = ByteBuffer.wrap(font);
        int tables = bytes.getShort(start + 4) & 0xFFFF;
        for (int index = 0; index < tables; index++) {
            int record = start + 12 + 16 * index;
            if (bytes.getInt(record) == tag(name)) return bytes.getInt(record + 8);
        }
        return -1;
    }

    private static void retag(byte[] font, String from, String to) {
        ByteBuffer bytes = ByteBuffer.wrap(font);
        int tables = bytes.getShort(4) & 0xFFFF;
        for (int index = 0; index < tables; index++)
            if (bytes.getInt(12 + 16 * index) == tag(from)) bytes.putInt(12 + 16 * index, tag(to));
    }

    private File write(String name, byte[] bytes) throws IOException {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    /** A TrueType header with one table, an fvar holding [tags] with their least and greatest values. */
    static byte[] sfntWithAxes(String[] tags, float[][] ranges, int axisSize) {
        int fvarAt = 12 + 16;
        int axesAt = 16;
        ByteBuffer bytes = ByteBuffer.allocate(fvarAt + axesAt + axisSize * tags.length + 8);
        bytes.putInt(0x00010000).putShort((short) 1).putShort((short) 16).putShort((short) 0).putShort((short) 0);
        bytes.putInt(tag("fvar")).putInt(0).putInt(fvarAt).putInt(axesAt + axisSize * tags.length);
        bytes.putShort((short) 1).putShort((short) 0).putShort((short) axesAt).putShort((short) 2)
                .putShort((short) tags.length).putShort((short) axisSize).putShort((short) 0).putShort((short) 0);
        for (int i = 0; i < tags.length; i++) {
            int at = fvarAt + axesAt + axisSize * i;
            bytes.position(at);
            bytes.putInt(tag(tags[i]));
            if (axisSize >= 16) {
                bytes.putInt(Math.round(ranges[i][0] * 65536)).putInt(Math.round(ranges[i][1] * 65536))
                        .putInt(Math.round(ranges[i][1] * 65536));
            }
        }
        return bytes.array();
    }

    /** A 'ttcf' header whose one font is [font], placed after it. */
    static byte[] collectionOf(byte[] font) {
        // The font's own table offsets count from the start of the file, so it goes where its
        // tables still land: the header is 16 bytes, and the font's first table sits past that.
        ByteBuffer bytes = ByteBuffer.allocate(16 + font.length + 16);
        bytes.putInt(tag("ttcf")).putInt(0x00010000).putInt(1).putInt(16);
        // The font's directory at 16; its fvar offset moves by the same 16.
        byte[] moved = font.clone();
        ByteBuffer directory = ByteBuffer.wrap(moved);
        int fvar = directory.getInt(12 + 8);
        directory.putInt(12 + 8, fvar + 16);
        bytes.put(moved);
        return bytes.array();
    }
}
