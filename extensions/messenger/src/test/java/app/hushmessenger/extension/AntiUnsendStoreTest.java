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

    @Test public void imageMetadataCanBeLinkedToARevokedMessage() {
        store.captureMediaMetadata("/cdn/image/abc", "thread-1", 900, "image/jpeg", 100);
        assertTrue(store.listUnsentMedia("m1").isEmpty());

        store.captureMessageMetadata("m1", "thread-1", "sender-1", 900, 110);
        store.markUnsent("m1", 120);

        // A completed-download callback records the private copy path.
        assertTrue(store.saveMediaFile("/cdn/image/abc", "thread-1", 900,
            "/private/hush/media/m1.jpg", 130));
        var images = store.listUnsentMedia("m1");
        assertEquals(1, images.size());
        assertEquals("image/jpeg", images.get(0).mimeType);
        assertEquals("/private/hush/media/m1.jpg", images.get(0).localPath);
        assertEquals(900, images.get(0).messageTimestamp);
        assertEquals(120, images.get(0).unsentAt);
    }

    @Test public void imageArrivingAfterRevokeInheritsTheUnsentMarker() {
        store.captureMessageMetadata("m2", "thread-2", "sender-2", 1234, 100);
        store.markUnsent("m2", 110);
        store.captureMediaMetadata("/cdn/image/xyz", "thread-2", 1234, "image/png", 120);

        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "late-image.png");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFile("/cdn/image/xyz", "thread-2", 1234, image.getAbsolutePath(), 130));
            var entries = store.listUnsentMedia("m2");
            assertEquals(1, entries.size());
            assertEquals(110, entries.get(0).unsentAt);
            assertEquals(image.getAbsolutePath(), entries.get(0).localPath);
        } catch (java.io.IOException error) {
            fail(error.getMessage());
        } finally {
            image.delete();
        }
    }

    @Test public void ambiguousThreadTimestampDoesNotAttachImageToWrongMessage() {
        store.captureMessageMetadata("m3", "thread-3", "sender-1", 5678, 100);
        store.captureMessageMetadata("m4", "thread-3", "sender-2", 5678, 101);
        store.markUnsent("m3", 110);
        store.markUnsent("m4", 111);
        store.captureMediaMetadata("/cdn/image/ambiguous", "thread-3", 5678, "image/jpeg", 120);

        assertTrue(store.listUnsentMedia("m3").isEmpty());
        assertTrue(store.listUnsentMedia("m4").isEmpty());
    }

    @Test public void laterTimestampCollisionDetachesAnEarlierInferredImageLink() {
        store.captureMediaMetadata("/cdn/image/late-collision", "thread-4", 7777, "image/jpeg", 90);
        store.captureMessageMetadata("m6", "thread-4", "sender-1", 7777, 100);
        store.markUnsent("m6", 110);
        assertEquals(1, store.listUnsentMedia("m6").size());

        // A second message with the same timestamp makes the earlier inferred link ambiguous.
        store.captureMessageMetadata("m7", "thread-4", "sender-2", 7777, 120);
        assertTrue(store.listUnsentMedia("m6").isEmpty());
        store.markUnsent("m7", 130);
        assertTrue(store.listUnsentMedia("m7").isEmpty());
    }

    @Test public void clearingUnsentHistoryDeletesItsPrivateImageFiles() {
        store.captureMediaMetadata("/cdn/image/delete-me", "thread-5", 999, "image/jpeg", 100);
        store.captureMessageMetadata("m5", "thread-5", "sender-5", 999, 101);
        java.io.File image = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "unsent-image.jpg");
        try {
            assertTrue(image.createNewFile());
            assertTrue(store.saveMediaFile("/cdn/image/delete-me", "thread-5", 999, image.getAbsolutePath(), 102));
            store.markUnsent("m5", 103);
            assertTrue(image.exists());
            assertEquals(1, store.clearUnsentHistory());
            assertFalse(image.exists());
            assertTrue(store.listUnsentMedia("m5").isEmpty());
        } catch (java.io.IOException error) {
            fail(error.getMessage());
        } finally {
            image.delete();
        }
    }

    @Test public void retentionPrunesOldSnapshotsAndTheirImageFiles() {
        long now = 40L * 24 * 60 * 60 * 1000;
        store.captureText("old", null, null, "old", 0, 1);
        store.captureMediaMetadata("/cdn/image/old", "thread-old", 10, "image/jpeg", 1);
        store.captureMessageMetadata("old", "thread-old", "sender-old", 10, 1);
        store.captureText("new", null, null, "new", 0, now);
        store.markUnsent("old", 2);
        store.markUnsent("new", now);

        java.io.File oldImage = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "expired-image.jpg");
        try {
            assertTrue(oldImage.createNewFile());
            assertTrue(store.saveMediaFile("/cdn/image/old", "thread-old", 10, oldImage.getAbsolutePath(), 2));
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
