package app.hushmessenger.extension;

import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class RecentMediaTest {
    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        CrashGuard.resetForTests();
    }

    @Test public void onlyItsOwnSwitchHidesTheCardAndEachChatAsksAgain() {
        assertFalse(Settings.hideRecentMedia());
        assertEquals(0, Settings.lastActive("recent_media"));
        Settings.preferences.edit().putBoolean("recent_media", true).commit();
        assertTrue(Settings.hideRecentMedia());
        assertTrue(Settings.lastActive("recent_media") > 0);
        Settings.preferences.edit().putBoolean("recent_media", false).commit();
        assertFalse("The next chat after switching off looks for new photos again", Settings.hideRecentMedia());
        assertEquals(Collections.singletonMap("recent_media", false), Settings.preferences.getAll());
    }

    @Test public void offPauseSafeModeAndAMissingControlKeepTheCard() {
        for (String state : new String[] {"off", "paused", "safe", "missing"}) {
            Settings.initialize(RuntimeEnvironment.getApplication());
            Settings.preferences.edit().clear().putBoolean("recent_media", !state.equals("off"))
                .putBoolean("paused", state.equals("paused")).putBoolean("safe_mode", state.equals("safe")).commit();
            CrashGuard.resetForTests();
            CrashGuard.onProcessStart(RuntimeEnvironment.getApplication());
            if (state.equals("missing")) Settings.installed = Collections.emptySet();
            Settings.activeAt.clear();
            assertFalse(state, Settings.hideRecentMedia());
            assertEquals(state, 0, Settings.lastActive("recent_media"));
        }
    }

    @Test public void theSwitchSitsUnderPhotosAndVideosAndStartsOff() {
        String[] spec = null;
        for (String[] control : SettingsActivity.CONTROLS) if (control[0].equals("recent_media")) spec = control;
        assertNotNull(spec);
        assertEquals("Hide the recent photo card", spec[1]);
        assertEquals("photos_videos", spec[3]);
        assertTrue(Settings.installed.contains("recent_media"));
        assertFalse(Settings.preferences.getBoolean("recent_media", false));
    }
}
