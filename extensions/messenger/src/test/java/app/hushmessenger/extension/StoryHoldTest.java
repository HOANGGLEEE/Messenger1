package app.hushmessenger.extension;

import java.util.Arrays;
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
public class StoryHoldTest {
    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        CrashGuard.resetForTests();
    }

    @Test public void aStoryWaitsOnlyWhileTheSwitchIsOn() {
        assertFalse(Settings.holdStoryAtEnd());
        assertEquals(0, Settings.lastActive("story_hold"));
        Settings.preferences.edit().putBoolean("anonymous_stories", true).putBoolean("stories", true).commit();
        assertFalse("The other story switches leave the timing alone", Settings.holdStoryAtEnd());

        Settings.preferences.edit().clear().putBoolean("story_hold", true).commit();
        assertTrue(Settings.holdStoryAtEnd());
        assertTrue(Settings.lastActive("story_hold") > 0);

        Settings.preferences.edit().putBoolean("story_hold", false).commit();
        assertFalse("The next story to end after switching off moves on", Settings.holdStoryAtEnd());
    }

    @Test public void offPauseSafeModeAndAMissingControlLetStoriesMoveOn() {
        for (String state : new String[] {"off", "paused", "safe", "missing"}) {
            Settings.initialize(RuntimeEnvironment.getApplication());
            Settings.preferences.edit().clear().putBoolean("story_hold", !state.equals("off"))
                .putBoolean("paused", state.equals("paused")).putBoolean("safe_mode", state.equals("safe")).commit();
            CrashGuard.resetForTests();
            CrashGuard.onProcessStart(RuntimeEnvironment.getApplication());
            if (state.equals("missing")) Settings.installed = Collections.emptySet();
            Settings.activeAt.clear();
            assertFalse(state, Settings.holdStoryAtEnd());
            assertEquals(state, 0, Settings.lastActive("story_hold"));
        }
    }

    @Test public void theSwitchSitsUnderNavigationStartsOffIsExperimentalAndStaysOutOfTheQuickSetups() {
        assertTrue(Settings.installed.contains("story_hold"));
        String[] row = Arrays.stream(SettingsActivity.CONTROLS).filter(c -> c[0].equals("story_hold")).findFirst().orElseThrow();
        assertEquals("Hold stories at the end", row[1]);
        assertEquals("navigation", row[3]);
        assertTrue(SettingsActivity.EXPERIMENTAL.contains("story_hold"));
        assertFalse(Settings.preferences.contains("story_hold"));
        assertFalse(Settings.wouldUse("story_hold"));
        // A matter of taste rather than privacy or quiet, so no one-tap setup turns it on.
        assertFalse(QuickSetups.PRIVACY.contains("story_hold"));
        assertFalse(QuickSetups.QUIET.contains("story_hold"));
    }
}
