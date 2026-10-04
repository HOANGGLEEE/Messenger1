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
        store.captureText("m1", "hello", 100);
        assertNull(store.unsentText("m1"));
        store.markUnsent("m1", 200);
        assertEquals("hello", store.unsentText("m1"));
        var entries = store.listUnsent(10);
        assertEquals(1, entries.size());
        assertEquals("m1", entries.get(0).messageId);
        assertEquals("hello", entries.get(0).text);
        assertEquals(200, entries.get(0).unsentAt);
    }

    @Test public void emptyOrLaterTextDoesNotDestroyTheFirstUsefulSnapshot() {
        store.captureText("m1", "first", 100);
        store.captureText("m1", "", 110);
        store.captureText("m1", "later", 120);
        store.markUnsent("m1", 130);
        assertEquals("first", store.unsentText("m1"));
    }

    @Test public void revokeBeforeCaptureCanStillGainTextLater() {
        store.markUnsent("m1", 100);
        assertNull(store.unsentText("m1"));
        store.captureText("m1", "arrived", 110);
        assertEquals("arrived", store.unsentText("m1"));
    }

    @Test public void clearHistoryRemovesOnlyRecordedUnsentRows() {
        store.captureText("kept", "text", 100);
        store.captureText("normal", "normal", 100);
        store.markUnsent("kept", 110);
        assertEquals(1, store.clearUnsentHistory());
        assertTrue(store.listUnsent(10).isEmpty());
        store.markUnsent("normal", 120);
        assertEquals("normal", store.unsentText("normal"));
    }

    @Test public void retentionPrunesOldSnapshots() {
        long now = 40L * 24 * 60 * 60 * 1000;
        store.captureText("old", "old", 1);
        store.captureText("new", "new", now);
        store.markUnsent("old", 2);
        store.markUnsent("new", now);
        store.pruneNow(now);
        var entries = store.listUnsent(10);
        assertEquals(1, entries.size());
        assertEquals("new", entries.get(0).messageId);
    }
}
