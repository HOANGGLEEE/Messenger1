package app.hushmessenger.extension;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class UnsentMediaArchiveTest {
    private Context context;
    private AntiUnsendStore store;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        store = AntiUnsendStore.get(context);
        store.clearForTests();
    }

    private File createPng() throws Exception {
        File source = File.createTempFile("hush-source-", ".png", context.getCacheDir());
        Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(source)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            output.flush();
        } finally {
            bitmap.recycle();
        }
        return source;
    }

    @Test public void fileUriIsCopiedToPersistentStorageAndLinkedAfterMessageMetadataArrives() throws Exception {
        File source = createPng();
        try {
            UnsentMediaArchive.Result result = UnsentMediaArchive.capture(
                context, Uri.fromFile(source), null, "image/png", 21001,
                getClass().getClassLoader());

            assertTrue(result.failure, result.saved);
            assertFalse(result.linked);
            assertFalse(result.usedDirectPath);
            File archived = new File(result.localPath);
            assertTrue(archived.isFile());
            assertTrue(archived.getAbsolutePath().startsWith(context.getFilesDir().getAbsolutePath()));

            store.captureMessageMetadata("archive-1", "thread-archive-1", "sender-1", 21000, 110);
            assertTrue(store.captureMediaMetadataForMessageWithoutDirectPath(
                "archive-1", "thread-archive-1", 21001, "image/png", 120));
            store.markUnsent("archive-1", 130);

            var images = store.listUnsentMedia("archive-1");
            assertEquals(1, images.size());
            assertEquals(archived.getAbsolutePath(), images.get(0).localPath);
            assertTrue(archived.isFile());
        } finally {
            source.delete();
        }
    }

    @Test public void directPathRemainsPreferredWhenMessengerProvidesIt() throws Exception {
        File source = createPng();
        String directPath = "/messenger/e2ee/direct-path-test";
        try {
            store.captureMessageMetadata("archive-2", "thread-archive-2", "sender-2", 22000, 100);
            store.captureMediaMetadataForMessage(directPath, "archive-2", "thread-archive-2",
                22001, "image/png", 105);

            UnsentMediaArchive.Result result = UnsentMediaArchive.capture(
                context, Uri.fromFile(source), directPath, "image/png", 22001,
                getClass().getClassLoader());

            assertTrue(result.failure, result.saved);
            assertTrue(result.usedDirectPath);
            assertTrue(result.linked);
            store.markUnsent("archive-2", 130);
            var images = store.listUnsentMedia("archive-2");
            assertEquals(1, images.size());
            assertEquals(result.localPath, images.get(0).localPath);
        } finally {
            source.delete();
        }
    }

    @Test public void imageSignatureAloneIsNotEnoughWhenDecoderCannotDecodeThePayload() throws Exception {
        File source = File.createTempFile("hush-invalid-png-", ".png", context.getCacheDir());
        try {
            byte[] header = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52
            };
            try (FileOutputStream output = new FileOutputStream(source)) {
                output.write(header);
            }
            UnsentMediaArchive.Result result = UnsentMediaArchive.capture(
                context, Uri.fromFile(source), null, "image/png", 23001,
                getClass().getClassLoader());
            assertFalse(result.saved);
            assertEquals("decode_failed", result.failure);
        } finally {
            source.delete();
        }
    }

    @Test public void nonImagePayloadIsNeverArchivedAsAnImage() throws Exception {
        File source = File.createTempFile("hush-not-image-", ".bin", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(source)) {
                output.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
            }
            UnsentMediaArchive.Result result = UnsentMediaArchive.capture(
                context, Uri.fromFile(source), null, "image/png", 24001,
                getClass().getClassLoader());
            assertFalse(result.saved);
            assertEquals("bad_signature", result.failure);
        } finally {
            source.delete();
        }
    }
}
