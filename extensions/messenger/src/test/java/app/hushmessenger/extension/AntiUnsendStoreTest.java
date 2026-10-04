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

    @Test public void retentionPrunesOldSnapshots() {
        long now = 40L * 24 * 60 * 60 * 1000;
        store.captureText("old", null, null, "old", 0, 1);
        store.captureText("new", null, null, "new", 0, now);
        store.markUnsent("old", 2);
        store.markUnsent("new", now);
        store.pruneNow(now);
        var entries = store.listUnsent(10);
        assertEquals(1, entries.size());
        assertEquals("new", entries.get(0).messageId);
    }
}
