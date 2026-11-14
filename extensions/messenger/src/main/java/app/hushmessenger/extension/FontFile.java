package app.hushmessenger.extension;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * The picked font's copy in Messenger's own files. Drawing reads the copy, never the picker's URI, which its provider can
 * take back. A file has to start like an sfnt font, be at most {@link #MAX_MEGABYTES} and load in Android before its copy
 * replaces the old one in one rename.
 */
final class FontFile {
    /** A full Chinese, Japanese and Korean font runs to about 20 MB. A Latin one is well under 1 MB. */
    static final int MAX_MEGABYTES = 20;
    static final long MAX_BYTES = MAX_MEGABYTES * 1024L * 1024L;
    static final String NAME = "hushmessenger-font";
    /** The start of each copy's own partial file. A stopped copy can still be writing one when the next starts. */
    static final String PARTIAL = NAME + ".part";
    /** Read calls in a row that return nothing before a stream counts as broken. A working one waits for a byte instead. */
    static final int MOST_EMPTY_READS = 16;
    /**
     * Held while the saved name and the file it names change together, so one writer's name never ends up with another's
     * file. Never taken on the main thread, since a writer holds it through a disk write.
     */
    static final Object LOCK = new Object();

    static final int TRUETYPE = 0x00010000;
    /** 'true', Apple's TrueType tag. */
    static final int TRUETYPE_APPLE = 0x74727565;
    /** 'OTTO', OpenType with CFF outlines. */
    static final int OPENTYPE_CFF = 0x4F54544F;
    /** 'ttcf', a collection. Android builds its first font. */
    static final int COLLECTION = 0x74746366;

    private static final int FVAR = 0x66766172;
    private static final int WGHT = 0x77676874;
    private static final int HEAD = 0x68656164;
    private static final int HHEA = 0x68686561;
    private static final int OS2 = 0x4F532F32;

    /** #48: the picked font's size in percent, and how far its text sits lower in the line, in percent of that size. */
    static final String ADJUSTED = NAME + "-adjusted";
    /** A copy still being written. Another process only removes one this old, so it never pulls one out from under a writer. */
    static final String SIZING = NAME + "-sizing";
    static final long SIZING_STALE_MS = 10 * 60_000L;
    static final int SCALE_LEAST = 70, SCALE_MOST = 160, SCALE_STEP = 5;
    static final int SHIFT_MOST = 20, SHIFT_STEP = 2;
    private static final int MAX_TABLES = 512;
    private static final int MAX_AXES = 64;

    enum Refusal { UNREADABLE, NOT_A_FONT, TOO_LARGE, WONT_LOAD, NOT_SAVED }

    /** A file turned down. The copy it would have replaced is still there. */
    static final class Refused extends Exception {
        final Refusal reason;

        Refused(Refusal reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    /** Whether Android builds a typeface from a file, the last check a copy passes. */
    interface Check {
        boolean loads(File file);
    }

    /**
     * The saved name that goes with the copy. save runs after the checks and before the rename, and undo puts the old name
     * back when save fails or the rename then does. A throw from save counts as a name that didn't save. Android takes a
     * commit into memory before the disk answers, so a failed save can still have changed the name in use.
     */
    interface Choice {
        boolean save();

        void undo();
    }

    private static final Choice FILE_ONLY = new Choice() {
        @Override public boolean save() { return true; }
        @Override public void undo() { }
    };

    /**
     * A folder of their own for tests. Robolectric shares one files folder across a run, and Windows won't replace a copy
     * an earlier test's typeface still maps. Null on a phone.
     */
    static volatile File testFolder;

    private FontFile() { }

    static File file(Context context) {
        File folder = testFolder;
        return new File(folder != null ? folder : context.getFilesDir(), NAME);
    }

    static boolean isFontTag(int tag) {
        return tag == TRUETYPE || tag == TRUETYPE_APPLE || tag == OPENTYPE_CFF || tag == COLLECTION;
    }

    static void copy(InputStream input, File target, Check check) throws Refused {
        copy(input, target, check, FILE_ONLY);
    }

    /**
     * Copies [input] to [target] through a partial file of its own beside it, and closes [input]. The first four bytes are
     * read before anything is written, so a photo picked by mistake is never copied, and reading stops one chunk past the
     * limit. An interrupt stops the reading, and the name and file change under {@link #LOCK}.
     */
    static void copy(InputStream input, File target, Check check, Choice choice) throws Refused {
        if (input == null) throw new Refused(Refusal.UNREADABLE, "No stream to read");
        File partial = null;
        boolean kept = false;
        try {
            byte[] head = new byte[4];
            int got = readFully(input, head);
            if (got < head.length || !isFontTag(tagOf(head)))
                throw new Refused(Refusal.NOT_A_FONT, got < head.length ? "Shorter than a font header" : "No font tag");
            partial = newPartial(target.getParentFile());
            write(input, head, partial);
            // Closed before anything saves, so a provider that stalls in close holds up a copy that can still be stopped.
            close(input);
            boolean loads;
            try {
                loads = check.loads(partial);
            } catch (RuntimeException failure) {
                loads = false;
            }
            if (!loads) throw new Refused(Refusal.WONT_LOAD, "Android built no typeface from it");
            synchronized (LOCK) {
                boolean saved;
                try {
                    saved = choice.save();
                } catch (RuntimeException failure) {
                    saved = false;
                }
                if (!saved) {
                    undo(choice);
                    throw new Refused(Refusal.NOT_SAVED, "The font file's name couldn't be saved");
                }
                try {
                    moveIn(partial, target);
                } catch (Refused refused) {
                    undo(choice);
                    throw refused;
                }
            }
            kept = true;
        } finally {
            close(input);
            if (!kept && partial != null) partial.delete();
        }
    }

    private static void close(InputStream input) {
        try {
            input.close();
        } catch (IOException | RuntimeException ignored) {
            // Every byte wanted was read, or the copy was already turned down.
        }
    }

    private static File newPartial(File folder) throws Refused {
        try {
            if (folder == null) throw new IOException("No folder");
            return File.createTempFile(PARTIAL + "-", null, folder);
        } catch (IOException | RuntimeException failure) {
            throw new Refused(Refusal.NOT_SAVED, failure.getClass().getSimpleName());
        }
    }

    private static void undo(Choice choice) {
        try {
            choice.undo();
        } catch (RuntimeException ignored) {
            // Picking again mends a name that points at the old copy.
        }
    }

    /** Replaces [target] whole or leaves it as it was. The old copy is never deleted first. */
    private static void moveIn(File partial, File target) throws Refused {
        try {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            // The class only: a message can carry a path.
            throw new Refused(Refusal.NOT_SAVED, failure.getClass().getSimpleName());
        }
    }

    private static void write(InputStream input, byte[] head, File partial) throws Refused {
        OutputStream output = null;
        try {
            output = new FileOutputStream(partial);
            output.write(head);
            long total = head.length;
            byte[] buffer = new byte[64 * 1024];
            int count, empty = 0;
            while ((count = read(input, buffer, 0, buffer.length)) != -1) {
                if (count == 0 && ++empty > MOST_EMPTY_READS) throw new Refused(Refusal.UNREADABLE, "Reads return nothing");
                if (count > 0) empty = 0;
                total += count;
                if (total > MAX_BYTES) throw new Refused(Refusal.TOO_LARGE, "Larger than " + MAX_BYTES + " bytes");
                output.write(buffer, 0, count);
            }
            output.close();
            output = null;
        } catch (IOException error) {
            throw new Refused(Refusal.NOT_SAVED, error.getClass().getSimpleName());
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                    // The copy is turned down already and its file removed after this.
                }
            }
        }
    }

    /** One read, after a check that nobody has stopped the copy. */
    private static int read(InputStream input, byte[] buffer, int offset, int length) throws Refused {
        if (Thread.currentThread().isInterrupted()) throw new Refused(Refusal.UNREADABLE, "Stopped");
        try {
            return input.read(buffer, offset, length);
        } catch (IOException | RuntimeException error) {
            throw new Refused(Refusal.UNREADABLE, error.getClass().getSimpleName());
        }
    }

    private static int readFully(InputStream input, byte[] buffer) throws Refused {
        int filled = 0, empty = 0;
        while (filled < buffer.length) {
            int count = read(input, buffer, filled, buffer.length - filled);
            if (count < 0) break;
            if (count == 0 && ++empty > MOST_EMPTY_READS) throw new Refused(Refusal.UNREADABLE, "Reads return nothing");
            filled += count;
        }
        return filled;
    }

    private static int tagOf(byte[] head) {
        return ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
    }

    /**
     * The least and greatest value of [file]'s 'wght' axis from its fvar table, or null for a font without one or with
     * tables that can't be read. A collection answers for its first font.
     */
    static float[] weightAxis(File file) {
        try (RandomAccessFile font = new RandomAccessFile(file, "r")) {
            long start = 0;
            if (readInt(font, 0) == COLLECTION) {
                if (readInt(font, 8) < 1) return null;
                start = readUnsignedInt(font, 12);
            }
            int tables = readUnsignedShort(font, start + 4);
            if (tables > MAX_TABLES) return null;
            for (int index = 0; index < tables; index++) {
                long record = start + 12 + 16L * index;
                if (readInt(font, record) != FVAR) continue;
                long fvar = readUnsignedInt(font, record + 8);
                int axesAt = readUnsignedShort(font, fvar + 4);
                int axes = readUnsignedShort(font, fvar + 8);
                int axisSize = readUnsignedShort(font, fvar + 10);
                if (axisSize < 20 || axes > MAX_AXES) return null;
                for (int axis = 0; axis < axes; axis++) {
                    long at = fvar + axesAt + (long) axisSize * axis;
                    if (readInt(font, at) != WGHT) continue;
                    // Fixed 16.16: minimum, default, maximum.
                    float least = readInt(font, at + 4) / 65536f;
                    float greatest = readInt(font, at + 12) / 65536f;
                    return least >= 1 && greatest <= 1000 && least <= greatest ? new float[] {least, greatest} : null;
                }
                return null;
            }
            return null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * A copy of [source] drawn at [scale] percent of the size Messenger asks for, with its text [shift] percent of that size
     * lower in the line (higher when negative). Null when the copy can't be made, so the picked file is used as it is. The
     * name carries the choices and the source's time and length, so a new pick or choice never reuses an old copy, and
     * older copies are removed.
     */
    static File adjusted(File source, int scale, int shift) {
        File folder = source.getParentFile();
        long length = source.length();
        if (folder == null || length <= 0 || length > MAX_BYTES) return null;
        String name = ADJUSTED + "-" + scale + "-" + (shift + SHIFT_MOST) + "-" + Long.toHexString(source.lastModified()) + "-" + length;
        File target = new File(folder, name);
        dropAdjusted(folder, name);
        // Only a finished copy ever carries the name, so one that's there is whole.
        if (target.isFile() && target.length() == length) return target;
        File partial = null;
        try {
            byte[] font = Files.readAllBytes(source.toPath());
            if (!adjust(font, scale, shift)) return null;
            // Each of Messenger's processes can get here at once, so each writes its own partial file.
            partial = File.createTempFile(SIZING + "-", null, folder);
            Files.write(partial.toPath(), font);
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            if (partial != null) partial.delete();
            android.util.Log.w("HushMessenger", "custom_font: couldn't size the font file: " + failure.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Removes the sized copies other than [keep], or all of them when it's null, and sized or picked partial files left
     * behind by a crash or a copy that never finished.
     */
    static void dropAdjusted(File folder, String keep) {
        String prefix = ADJUSTED + "-", sizing = SIZING + "-";
        long abandoned = System.currentTimeMillis() - SIZING_STALE_MS;
        File[] found = folder == null ? null : folder.listFiles((dir, file) -> file.startsWith(prefix) && !file.equals(keep)
                || (file.startsWith(sizing) || file.startsWith(PARTIAL)) && new File(dir, file).lastModified() < abandoned);
        if (found != null) for (File stale : found) stale.delete();
    }

    /**
     * Rewrites the first font in [font] in place. The outlines stay as they are: the head table's units per em shrinks or
     * grows, which draws every glyph and its advance at [scale] percent, and [shift] moves between the ascent and descent in
     * hhea, OS/2 and the head table's box, so the line keeps its height while the text moves. False when a table it needs
     * is missing or a value would leave its range.
     */
    static boolean adjust(byte[] font, int scale, int shift) {
        if (scale < SCALE_LEAST || scale > SCALE_MOST || Math.abs(shift) > SHIFT_MOST) return false;
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(font);
        try {
            int start = 0;
            if (bytes.getInt(0) == COLLECTION) {
                if (bytes.getInt(8) < 1) return false;
                start = bytes.getInt(12);
            }
            if (!isFontTag(bytes.getInt(start)) || bytes.getInt(start) == COLLECTION) return false;
            int tables = bytes.getShort(start + 4) & 0xFFFF;
            if (tables > MAX_TABLES) return false;
            int head = -1, hhea = -1, os2 = -1;
            for (int index = 0; index < tables; index++) {
                int record = start + 12 + 16 * index;
                int tag = bytes.getInt(record), at = bytes.getInt(record + 8), size = bytes.getInt(record + 12);
                if (tag != HEAD && tag != HHEA && tag != OS2) continue;
                if (at < 0 || size < 0 || at > font.length - size) return false;
                if (tag == HEAD && size >= 54) head = at;
                else if (tag == HHEA && size >= 36) hhea = at;
                else if (tag == OS2 && size >= 78) os2 = at;
            }
            if (head < 0 || hhea < 0) return false;
            int upem = bytes.getShort(head + 18) & 0xFFFF;
            int units = Math.round(upem * 100f / scale);
            if (upem < 16 || units < 16 || units > 16384) return false;
            int move = Math.round(units * shift / 100f);
            // A pair left at zero means "not set", and the reader falls back to the next pair, so it stays zero.
            int hheaMove = unset(bytes, hhea + 4, hhea + 6) ? 0 : move;
            int typoMove = os2 < 0 || unset(bytes, os2 + 68, os2 + 70) ? 0 : move;
            int winMove = os2 < 0 || unset(bytes, os2 + 74, os2 + 76) ? 0 : move;
            int[][] signed = {{head + 38, move}, {head + 42, move}, {hhea + 4, hheaMove}, {hhea + 6, hheaMove}};
            int[][] typo = os2 < 0 ? new int[0][] : new int[][] {{os2 + 68, typoMove}, {os2 + 70, typoMove}};
            int[][] win = os2 < 0 ? new int[0][] : new int[][] {{os2 + 74, winMove}, {os2 + 76, -winMove}};
            for (int[][] group : new int[][][] {signed, typo})
                for (int[] field : group) if (!fits(bytes.getShort(field[0]) + field[1], Short.MIN_VALUE, Short.MAX_VALUE)) return false;
            for (int[] field : win) if (!fits((bytes.getShort(field[0]) & 0xFFFF) + field[1], 0, 0xFFFF)) return false;
            bytes.putShort(head + 18, (short) units);
            for (int[][] group : new int[][][] {signed, typo, win})
                for (int[] field : group) bytes.putShort(field[0], (short) (bytes.getShort(field[0]) + field[1]));
            return true;
        } catch (IndexOutOfBoundsException truncated) {
            return false;
        }
    }

    private static boolean unset(java.nio.ByteBuffer bytes, int first, int second) {
        return bytes.getShort(first) == 0 && bytes.getShort(second) == 0;
    }

    private static boolean fits(int value, int least, int most) {
        return value >= least && value <= most;
    }

    private static int readInt(RandomAccessFile font, long at) throws IOException {
        font.seek(at);
        return font.readInt();
    }

    private static long readUnsignedInt(RandomAccessFile font, long at) throws IOException {
        return readInt(font, at) & 0xFFFFFFFFL;
    }

    private static int readUnsignedShort(RandomAccessFile font, long at) throws IOException {
        font.seek(at);
        return font.readUnsignedShort();
    }
}
