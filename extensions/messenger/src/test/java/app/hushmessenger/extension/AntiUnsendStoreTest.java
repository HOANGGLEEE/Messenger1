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
        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "ambiguous.jpg");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFile("/cdn/image/collision", 4444, image.getAbsolutePath(), 105));
            store.captureMediaMetadataForMessage("/cdn/image/collision", "m5", "thread-5",
                4444, "image/jpeg", 110);
            store.markUnsent("m4", 120);
            store.markUnsent("m5", 130);
            assertTrue(store.listUnsentMedia("m4").isEmpty());
            assertTrue(store.listUnsentMedia("m5").isEmpty());
            assertFalse("Ambiguous media must be deleted, not orphaned", image.exists());
            assertFalse("Ambiguous media must not be relinked", store.saveMediaFile(
                "/cdn/image/collision", 4444, image.getAbsolutePath(), 140));
        } catch (java.io.IOException error) {
            fail(error.getMessage());
        } finally {
            image.delete();
        }
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

    @Test public void duplicateAndOutOfOrderRevokesKeepOneRowAndEarliestTimestamp() {
        store.captureMessageMetadata("m8", "thread-8", "sender-8", 800, 100);
        store.markUnsent("m8", 130);
        store.markUnsent("m8", 150);
        store.markUnsent("m8", 120);

        var entries = store.listUnsent(10);
        assertEquals(1, entries.size());
        assertEquals("m8", entries.get(0).messageId);
        assertEquals(120, entries.get(0).unsentAt);
    }

    @Test public void multipleImagesAndRepeatedMetadataStayLinkedToTheirExactMessage() throws Exception {
        store.captureMessageMetadata("m9", "thread-9", "sender-9", 900, 100);
        store.captureMediaMetadataForMessage("/cdn/image/one", "m9", "thread-9",
            901, "image/jpeg", 101);
        store.captureMediaMetadataForMessage("/cdn/image/two", "m9", "thread-9",
            902, "image/png", 102);
        // A repeated model event must update the existing media row, not create another one.
        store.captureMediaMetadataForMessage("/cdn/image/one", "m9", "thread-9",
            901, "image/jpeg", 103);

        java.io.File one = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "m9-one.jpg");
        java.io.File two = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "m9-two.png");
        try {
            assertTrue(one.createNewFile());
            assertTrue(two.createNewFile());
            assertTrue(store.saveMediaFile("/cdn/image/one", 901, one.getAbsolutePath(), 104));
            // Repeated completion callback for the same media identity must remain one row.
            assertTrue(store.saveMediaFile("/cdn/image/one", 901, one.getAbsolutePath(), 106));
            assertTrue(store.saveMediaFile("/cdn/image/two", 902, two.getAbsolutePath(), 105));
            store.markUnsent("m9", 110);
            var images = store.listUnsentMedia("m9");
            assertEquals(2, images.size());
            assertEquals("m9", images.get(0).messageId);
            assertEquals("m9", images.get(1).messageId);
            assertTrue(images.stream().anyMatch(item -> one.getAbsolutePath().equals(item.localPath)));
            assertTrue(images.stream().anyMatch(item -> two.getAbsolutePath().equals(item.localPath)));
        } finally {
            one.delete();
            two.delete();
        }
    }

    @Test public void metadataWithoutACompletedDownloadDoesNotInventAnImageFile() {
        store.captureMessageMetadata("m10", "thread-10", "sender-10", 1000, 100);
        store.captureMediaMetadataForMessage("/cdn/image/not-downloaded", "m10", "thread-10",
            1010, "image/jpeg", 101);
        store.markUnsent("m10", 110);
        assertTrue(store.listUnsentMedia("m10").isEmpty());
    }


    @Test public void textAndPrivateImageSurviveDatabaseReopen() throws Exception {
        store.captureText("m11", "thread-11", "sender-11", "persisted text", 1100, 100);
        store.captureMediaMetadataForMessage("/cdn/image/persist", "m11", "thread-11",
            1110, "image/jpeg", 101);
        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "m11-persisted.jpg");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFile("/cdn/image/persist", 1110, image.getAbsolutePath(), 102));
            store.markUnsent("m11", 103);

            // Closing and reopening SQLite simulates a fresh process reading the same private archive.
            store.close();
            store = AntiUnsendStore.get(RuntimeEnvironment.getApplication());
            assertEquals("persisted text", store.unsentText("m11"));
            var images = store.listUnsentMedia("m11");
            assertEquals(1, images.size());
            assertEquals(image.getAbsolutePath(), images.get(0).localPath);
            assertTrue(new java.io.File(images.get(0).localPath).exists());
        } finally {
            image.delete();
        }
    }


    @Test public void missingDirectPathLinksDownloadedFileWhenModelArrivesAfterDownload() throws Exception {
        long timestamp = 12001;
        String mime = "image/png";
        String hash = "content-hash-one";
        String key = AntiUnsendStore.downloadedMediaKey(timestamp, mime, hash);
        assertEquals(key, store.capturePendingDownloadedMedia(key, timestamp, mime, hash, 100));

        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(), "pending-after-download.png");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFileByKey(key, image.getAbsolutePath(), hash, 110));
            store.captureMessageMetadata("m12", "thread-12", "sender-12", 12000, 115);
            assertTrue(store.captureMediaMetadataForMessageWithoutDirectPath(
                "m12", "thread-12", timestamp, mime, 120));
            store.markUnsent("m12", 130);

            var images = store.listUnsentMedia("m12");
            assertEquals(1, images.size());
            assertEquals(image.getAbsolutePath(), images.get(0).localPath);
            assertEquals(130, images.get(0).unsentAt);
            assertTrue(store.mediaIsLinked(key));
        } finally {
            image.delete();
        }
    }

    @Test public void missingDirectPathUsesMessageModelRowWhenDownloadArrivesLater() throws Exception {
        long timestamp = 13001;
        String mime = "image/jpeg";
        String hash = "content-hash-two";
        store.captureMessageMetadata("m13", "thread-13", "sender-13", 13000, 100);
        assertTrue(store.captureMediaMetadataForMessageWithoutDirectPath(
            "m13", "thread-13", timestamp, mime, 105));

        String key = store.capturePendingDownloadedMedia(
            AntiUnsendStore.downloadedMediaKey(timestamp, mime, hash), timestamp, mime, hash, 110);
        assertEquals(AntiUnsendStore.messageMediaKey("m13", timestamp, mime), key);
        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(), "pending-before-download.jpg");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFileByKey(key, image.getAbsolutePath(), hash, 115));
            store.markUnsent("m13", 120);
            var images = store.listUnsentMedia("m13");
            assertEquals(1, images.size());
            assertEquals(image.getAbsolutePath(), images.get(0).localPath);
        } finally {
            image.delete();
        }
    }

    @Test public void identicalTimestampAndMimeAcrossMessagesNeverGuessWhichMessageOwnsImage() throws Exception {
        long timestamp = 14001;
        String mime = "image/webp";
        store.captureMediaMetadataForMessageWithoutDirectPath("m14", "thread-14", timestamp, mime, 100);
        store.captureMediaMetadataForMessageWithoutDirectPath("m15", "thread-15", timestamp, mime, 101);

        String hash = "ambiguous-content-hash";
        String key = AntiUnsendStore.downloadedMediaKey(timestamp, mime, hash);
        assertEquals(key, store.capturePendingDownloadedMedia(key, timestamp, mime, hash, 105));
        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(), "ambiguous-pending.webp");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFileByKey(key, image.getAbsolutePath(), hash, 110));
            store.markUnsent("m14", 120);
            store.markUnsent("m15", 121);
            assertTrue(store.listUnsentMedia("m14").isEmpty());
            assertTrue(store.listUnsentMedia("m15").isEmpty());
            assertFalse(store.mediaIsLinked(key));
        } finally {
            image.delete();
        }
    }


    @Test public void lateTimestampCollisionInvalidatesAnEarlierFallbackLink() throws Exception {
        long timestamp = 15001;
        String mime = "image/jpeg";
        String hash = "late-collision-hash";
        String key = AntiUnsendStore.downloadedMediaKey(timestamp, mime, hash);
        store.capturePendingDownloadedMedia(key, timestamp, mime, hash, 100);

        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(), "late-collision.jpg");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFileByKey(key, image.getAbsolutePath(), hash, 105));
            assertTrue(store.captureMediaMetadataForMessageWithoutDirectPath(
                "m16", "thread-16", timestamp, mime, 110));
            assertTrue(store.mediaIsLinked(key));

            // A second message with the same timestamp/MIME makes the first fallback association ambiguous.
            assertTrue(store.captureMediaMetadataForMessageWithoutDirectPath(
                "m17", "thread-17", timestamp, mime, 115));
            store.markUnsent("m16", 120);
            store.markUnsent("m17", 121);

            assertFalse(store.mediaIsLinked(key));
            assertFalse(image.exists());
            assertTrue(store.listUnsentMedia("m16").isEmpty());
            assertTrue(store.listUnsentMedia("m17").isEmpty());
        } finally {
            image.delete();
        }
    }

}
