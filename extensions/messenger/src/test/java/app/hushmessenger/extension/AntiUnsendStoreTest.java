package app.hushmessenger.extension;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class AntiUnsendStoreTest {
    private AntiUnsendStore store;

    @Before public void reset() {
        store = AntiUnsendStore.get(RuntimeEnvironment.getApplication());
        store.clearForTests();
    }

    @Test public void capturesMarksRestoresAndListsText() {
        store.captureText("m1", "thread-1", "sender-1", "hello", 90, 100);
        assertNull(store.unsentText("m1"));
        store.markUnsent("m1", 200);
        assertEquals("hello", store.unsentText("m1"));
        var entries = store.listUnsent(10);
        assertEquals(1, entries.size());
        assertEquals("m1", entries.get(0).messageId);
        assertEquals("hello", entries.get(0).text);
        assertEquals("thread-1", entries.get(0).threadId);
        assertEquals("sender-1", entries.get(0).senderId);
        assertEquals(90, entries.get(0).messageTimestamp);
        assertEquals(100, entries.get(0).receivedAt);
        assertEquals(200, entries.get(0).unsentAt);
    }

    @Test public void emptyOrLaterTextDoesNotDestroyTheFirstUsefulSnapshot() {
        store.captureText("m1", null, null, "first", 0, 100);
        store.captureText("m1", null, null, "", 0, 110);
        store.captureText("m1", "thread-later", "sender-later", "later", 0, 120);
        store.markUnsent("m1", 130);
        assertEquals("first", store.unsentText("m1"));
    }

    @Test public void revokeBeforeCaptureCanStillGainTextLater() {
        store.markUnsent("m1", 100);
        assertNull(store.unsentText("m1"));
        store.captureText("m1", "thread-1", "sender-1", "arrived", 105, 110);
        assertEquals("arrived", store.unsentText("m1"));
    }

    @Test public void clearHistoryRemovesOnlyRecordedUnsentRows() {
        store.captureText("kept", null, null, "text", 0, 100);
        store.captureText("normal", null, null, "normal", 0, 100);
        store.markUnsent("kept", 110);
        assertEquals(1, store.clearUnsentHistory());
        assertTrue(store.listUnsent(10).isEmpty());
        store.markUnsent("normal", 120);
        assertEquals("normal", store.unsentText("normal"));
    }

    @Test public void imageSignatureCheckRejectsMismatchedOrEncryptedData() throws Exception {
        java.io.File image = java.io.File.createTempFile("hush-image-signature", ".png",
            RuntimeEnvironment.getApplication().getCacheDir());
        try {
            byte[] pngHeader = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52
            };
            java.nio.file.Files.write(image.toPath(), pngHeader);
            assertTrue(Settings.hasImageFileSignature(image, "image/png"));
            assertFalse(Settings.hasImageFileSignature(image, "image/jpeg"));

            // An E2EE encrypted download must not be treated as a local, viewable image.
            java.nio.file.Files.write(image.toPath(), new byte[32]);
            assertFalse(Settings.hasImageFileSignature(image, "image/png"));
        } finally {
            image.delete();
        }
    }

    @Test public void imageMetadataLinksByDirectPathAndMediaKeyTimestamp() {
        store.captureMediaMetadata("/cdn/image/abc", 900, "image/jpeg", 100);
        store.captureMessageMetadata("m1", "thread-1", "sender-1", 800, 110);
        store.captureMediaMetadataForMessage("/cdn/image/abc", "m1", "thread-1",
            900, "image/jpeg", 115);
        store.markUnsent("m1", 120);

        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "m1.jpg");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFile("/cdn/image/abc", 900, image.getAbsolutePath(), 130));
            var images = store.listUnsentMedia("m1");
            assertEquals(1, images.size());
            assertEquals("image/jpeg", images.get(0).mimeType);
            assertEquals(image.getAbsolutePath(), images.get(0).localPath);
            assertEquals(900, images.get(0).mediaKeyTimestamp);
            assertEquals(120, images.get(0).unsentAt);
        } catch (java.io.IOException error) {
            fail(error.getMessage());
        } finally {
            image.delete();
        }
    }

    @Test public void downloadCallbackCanSaveImageBeforeMessageModelArrives() {
        store.captureMediaMetadata("/cdn/image/late-model", 1234, "image/png", 100);
        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "late-model.png");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFile("/cdn/image/late-model", 1234, image.getAbsolutePath(), 110));
            store.captureMessageMetadata("m2", "thread-2", "sender-2", 1500, 120);
            store.captureMediaMetadataForMessage("/cdn/image/late-model", "m2", "thread-2",
                1234, "image/png", 130);
            store.markUnsent("m2", 140);
            var entries = store.listUnsentMedia("m2");
            assertEquals(1, entries.size());
            assertEquals(140, entries.get(0).unsentAt);
            assertEquals(image.getAbsolutePath(), entries.get(0).localPath);
        } catch (java.io.IOException error) {
            fail(error.getMessage());
        } finally {
            image.delete();
        }
    }

    @Test public void imageMetadataAlreadyMarkedUnsentIsLinkedToExactMessage() {
        store.captureMessageMetadata("m3", "thread-3", "sender-3", 3000, 100);
        store.markUnsent("m3", 110);
        store.captureMediaMetadataForMessage("/cdn/image/already-unsent", "m3", "thread-3",
            7777, "image/jpeg", 120);
        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "already-unsent.jpg");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFile("/cdn/image/already-unsent", 7777, image.getAbsolutePath(), 130));
            var entries = store.listUnsentMedia("m3");
            assertEquals(1, entries.size());
            assertEquals(110, entries.get(0).unsentAt);
        } catch (java.io.IOException error) {
            fail(error.getMessage());
        } finally {
            image.delete();
        }
    }

    @Test public void sameMediaIdentityCannotBeLinkedToTwoDifferentMessages() {
        store.captureMediaMetadataForMessage("/cdn/image/collision", "m4", "thread-4",
            4444, "image/jpeg", 100);
        store.captureMediaMetadataForMessage("/cdn/image/collision", "m5", "thread-5",
            4444, "image/jpeg", 110);
        store.markUnsent("m4", 120);
        store.markUnsent("m5", 130);
        assertTrue(store.listUnsentMedia("m4").isEmpty());
        assertTrue(store.listUnsentMedia("m5").isEmpty());
    }

    @Test public void mediaIdentityDoesNotDependOnThreadKey() {
        store.captureMediaMetadataForMessage("/cdn/image/thread-independent", "m6", "thread-6",
            6666, "image/webp", 100);
        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "thread-independent.webp");
        try {
            assertTrue(image.createNewFile());
            // The completed-download callback has no thread key, but it must update the same row.
            assertTrue(store.saveMediaFile("/cdn/image/thread-independent", 6666, image.getAbsolutePath(), 110));
            store.markUnsent("m6", 120);
            assertEquals(1, store.listUnsentMedia("m6").size());
        } catch (java.io.IOException error) {
            fail(error.getMessage());
        } finally {
            image.delete();
        }
    }

    @Test public void clearingUnsentHistoryDeletesItsPrivateImageFiles() {
        store.captureMessageMetadata("m7", "thread-7", "sender-7", 999, 100);
        store.captureMediaMetadataForMessage("/cdn/image/delete-me", "m7", "thread-7",
            9999, "image/jpeg", 101);
        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "unsent-image.jpg");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFile("/cdn/image/delete-me", 9999, image.getAbsolutePath(), 102));
            store.markUnsent("m7", 103);
            assertTrue(image.exists());
            assertEquals(1, store.clearUnsentHistory());
            assertFalse(image.exists());
            assertTrue(store.listUnsentMedia("m7").isEmpty());
        } catch (java.io.IOException error) {
            fail(error.getMessage());
        } finally {
            image.delete();
        }
    }

    @Test public void retentionPrunesOldSnapshotsAndTheirImageFiles() {
        long now = 40L * 24 * 60 * 60 * 1000;
        store.captureText("old", null, null, "old", 0, 1);
        store.captureMessageMetadata("old", "thread-old", "sender-old", 10, 1);
        store.captureMediaMetadataForMessage("/cdn/image/old", "old", "thread-old",
            1010, "image/jpeg", 1);
        store.captureText("new", null, null, "new", 0, now);
        store.markUnsent("old", 2);
        store.markUnsent("new", now);

        java.io.File oldImage = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "expired-image.jpg");
        try {
            assertTrue(oldImage.createNewFile());
            assertTrue(store.saveMediaFile("/cdn/image/old", 1010, oldImage.getAbsolutePath(), 2));
            store.pruneNow(now);
            var entries = store.listUnsent(10);
            assertEquals(1, entries.size());
            assertEquals("new", entries.get(0).messageId);
            assertFalse(oldImage.exists());
        } catch (java.io.IOException error) {
            fail(error.getMessage());
        } finally {
            oldImage.delete();
        }
    }
}
