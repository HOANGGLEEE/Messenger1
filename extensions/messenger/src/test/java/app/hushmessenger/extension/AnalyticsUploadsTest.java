package app.hushmessenger.extension;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class AnalyticsUploadsTest {
    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        CrashGuard.resetForTests();
    }

    @Test public void onlyItsOwnSwitchStopsUploadsAndEachStartAsksAgain() {
        assertFalse(Settings.stopAnalyticsUploads());
        assertEquals(0, Settings.lastActive("analytics_uploads"));
        Settings.preferences.edit().putBoolean("analytics_uploads", true).commit();
        assertTrue(Settings.stopAnalyticsUploads());
        assertTrue(Settings.lastActive("analytics_uploads") > 0);
        Settings.preferences.edit().putBoolean("analytics_uploads", false).commit();
        assertFalse("The next upload after switching off runs as usual", Settings.stopAnalyticsUploads());
        assertEquals(Collections.singletonMap("analytics_uploads", false), Settings.preferences.getAll());
    }

    @Test public void offPauseSafeModeAndAMissingControlLetUploadsRun() {
        for (String state : new String[] {"off", "paused", "safe", "missing"}) {
            Settings.initialize(RuntimeEnvironment.getApplication());
            Settings.preferences.edit().clear().putBoolean("analytics_uploads", !state.equals("off"))
                .putBoolean("paused", state.equals("paused")).putBoolean("safe_mode", state.equals("safe")).commit();
            CrashGuard.resetForTests();
            CrashGuard.onProcessStart(RuntimeEnvironment.getApplication());
            if (state.equals("missing")) Settings.installed = Collections.emptySet();
            Settings.activeAt.clear();
            assertFalse(state, Settings.stopAnalyticsUploads());
            assertEquals(state, 0, Settings.lastActive("analytics_uploads"));
        }
    }

    @Test public void aSkippedStartAnswersItsStarterOnceSoItsWakelockGoesAtOnce() {
        Settings.hookErrors.clear();
        List<Message> answers = new ArrayList<>();
        Handler starter = new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) { answers.add(Message.obtain(message)); }
        };
        Intent start = new Intent("com.facebook.analytics2.logger.UPLOAD_NOW")
            .putExtra(Settings.UPLOAD_STARTER, new Messenger(starter)).putExtra("_job_id", 7);
        Settings.releaseUploadStarter(start);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        // Messenger's own service sends one empty message, and the starter's handler reads nothing from it.
        assertEquals(1, answers.size());
        assertEquals(0, answers.get(0).what);
        assertNull(answers.get(0).obj);
        assertTrue(Settings.hookErrors.isEmpty());
    }

    @Test public void aStartWithNoStarterHasNothingToAnswer() {
        Settings.hookErrors.clear();
        Settings.releaseUploadStarter(null);
        Settings.releaseUploadStarter(new Intent());
        Settings.releaseUploadStarter(new Intent().putExtra(Settings.UPLOAD_STARTER, "not a messenger"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(Settings.hookErrors.isEmpty());
    }

    @Test public void extrasThatWontUnpackStillLetTheSkipStopTheStart() {
        Settings.hookErrors.clear();
        Intent broken = new Intent("com.facebook.analytics2.logger.UPLOAD_NOW") {
            @Override public android.os.Bundle getExtras() { throw new RuntimeException("Parcel: unknown type code"); }
        };
        // Reading the starter is the only new step on the skip path, so a failure there is logged instead of thrown.
        Settings.releaseUploadStarter(broken);
        assertEquals(java.util.Collections.singleton("analytics_uploads"), Settings.hookErrors.keySet());
        Settings.hookErrors.clear();
    }

    @Test public void pauseLetsTheNextUploadRunWithoutForgettingTheChoice() {
        Settings.preferences.edit().putBoolean("analytics_uploads", true).commit();
        assertTrue(Settings.stopAnalyticsUploads());
        Settings.preferences.edit().putBoolean("paused", true).commit();
        assertFalse(Settings.stopAnalyticsUploads());
        Settings.preferences.edit().putBoolean("paused", false).commit();
        assertTrue(Settings.stopAnalyticsUploads());
    }
}
