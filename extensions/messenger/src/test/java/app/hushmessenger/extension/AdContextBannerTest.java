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
public class AdContextBannerTest {
    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        CrashGuard.resetForTests();
    }

    @Test public void theBannerGateAsksOnlyItsOwnSwitch() {
        assertFalse(Settings.enabled("ad_context_banner"));
        Settings.preferences.edit().putBoolean("chat_promotions", true).commit();
        assertFalse("Hide chat promotions leaves the ad banner alone", Settings.enabled("ad_context_banner"));
        Settings.preferences.edit().putBoolean("ad_context_banner", true).putBoolean("chat_promotions", false).commit();
        assertTrue(Settings.enabled("ad_context_banner"));
        assertFalse("The ad banner switch leaves chat promotions alone", Settings.enabled("chat_promotions"));
        assertTrue(Settings.lastActive("ad_context_banner") > 0);
    }

    @Test public void offPauseSafeModeAndAMissingControlShowTheBanner() {
        for (String state : new String[] {"off", "paused", "safe", "missing"}) {
            Settings.initialize(RuntimeEnvironment.getApplication());
            Settings.preferences.edit().clear().putBoolean("ad_context_banner", !state.equals("off"))
                .putBoolean("paused", state.equals("paused")).putBoolean("safe_mode", state.equals("safe")).commit();
            CrashGuard.resetForTests();
            CrashGuard.onProcessStart(RuntimeEnvironment.getApplication());
            if (state.equals("missing")) Settings.installed = Collections.emptySet();
            Settings.activeAt.clear();
            assertFalse(state, Settings.enabled("ad_context_banner"));
            assertEquals(state, 0, Settings.lastActive("ad_context_banner"));
        }
    }

    @Test public void theSwitchSitsUnderConversationsBesideChatPromotions() {
        assertTrue(Settings.installed.contains("ad_context_banner"));
        String[][] controls = SettingsActivity.CONTROLS;
        int promotions = Arrays.asList(controls).indexOf(Arrays.stream(controls).filter(c -> c[0].equals("chat_promotions")).findFirst().orElseThrow());
        assertEquals("ad_context_banner", controls[promotions + 1][0]);
        assertEquals("Hide ad banners in business chats", controls[promotions + 1][1]);
        assertEquals("conversations", controls[promotions + 1][3]);
    }
}
