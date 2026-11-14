package app.hushmessenger.extension;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Hide tab badges (#81): the bottom tabs and the Facebook button lose their dots and counts, each with a Keep switch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class TabBadgesTest {
    private static final String[] TABS = {"inbox", "people", "jewel", "more", "facebook"};

    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        Settings.hookErrors.clear();
        CrashGuard.resetForTests();
        Settings.hiddenBadges = null;
    }

    @After public void restart() {
        Settings.hiddenBadges = null;
    }

    @Test public void offHidesNothingAndOnHidesEveryBadgeButTheKeptOnes() {
        for (String tab : TABS) assertFalse(tab, Settings.hideTabBadge(tab));
        assertTrue("A badge the bar asks for unhidden isn't a use", Settings.activeAt.isEmpty());
        assertTrue(Settings.facebookButtonBadge(true));

        Settings.hiddenBadges = null;
        Settings.preferences.edit().putBoolean(Settings.TAB_BADGES, true).putBoolean("tab_badges_keep_chats", true).commit();
        assertFalse("Keep the Chats badge", Settings.hideTabBadge("inbox"));
        for (String tab : new String[] {"people", "jewel", "more", "facebook"}) assertTrue(tab, Settings.hideTabBadge(tab));
        assertFalse("The Me tab never has a badge to hide", Settings.hideTabBadge("me_setting"));
        assertFalse(Settings.hideTabBadge(null));
        assertFalse(Settings.facebookButtonBadge(true));
        assertFalse("No badge stays no badge", Settings.facebookButtonBadge(false));
        assertTrue(Settings.lastActive(Settings.TAB_BADGES) > 0);
    }

    @Test public void theFirstAnswerHoldsUntilARestartThroughKeepPauseAndSafeMode() {
        assertNull("Nothing is held before Messenger asks", Settings.heldUntilRestart(Settings.TAB_BADGES));
        Settings.preferences.edit().putBoolean(Settings.TAB_BADGES, true).commit();
        assertTrue(Settings.hideTabBadge("people"));
        assertNull("The held answer matches the switches", Settings.heldUntilRestart(Settings.TAB_BADGES));

        Settings.preferences.edit().putBoolean("tab_badges_keep_people", true).commit();
        assertTrue("The bar keeps the badges it was handed first", Settings.hideTabBadge("people"));
        assertEquals("A changed Keep waits for a restart", Boolean.FALSE, Settings.heldUntilRestart(Settings.TAB_BADGES));

        Settings.preferences.edit().putBoolean("paused", true).commit();
        assertTrue(Settings.hideTabBadge("people"));
        assertEquals("Pause shows every badge again after a restart", Boolean.TRUE, Settings.heldUntilRestart(Settings.TAB_BADGES));
        Settings.preferences.edit().putBoolean("paused", false).putBoolean(Settings.TAB_BADGES, false).commit();
        assertEquals(Boolean.TRUE, Settings.heldUntilRestart(Settings.TAB_BADGES));
        Settings.preferences.edit().putBoolean(Settings.TAB_BADGES, true).putBoolean("safe_mode", true).commit();
        CrashGuard.resetForTests();
        CrashGuard.onProcessStart(RuntimeEnvironment.getApplication());
        assertEquals(Boolean.TRUE, Settings.heldUntilRestart(Settings.TAB_BADGES));

        Settings.hiddenBadges = null;
        assertNull("A restart asks again", Settings.heldUntilRestart(Settings.TAB_BADGES));
        for (String tab : TABS) assertFalse("Safe mode hides nothing after the restart: " + tab, Settings.hideTabBadge(tab));

        Settings.hiddenBadges = null;
        Settings.installed = Collections.emptySet();
        CrashGuard.resetForTests();
        Settings.preferences.edit().putBoolean("safe_mode", false).commit();
        assertFalse("Without the patch nothing is hidden", Settings.hideTabBadge("people"));
    }

    @Test public void theKeepSwitchesSitUnderTheRowAndTheRowSaysWhenItWaits() {
        Settings.preferences.edit().putBoolean(Settings.TAB_BADGES, true).commit();
        assertTrue(Settings.hideTabBadge("more"));
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            View row = root.findViewWithTag(Settings.TAB_BADGES);
            View wrapper = (View) ((View) row.getParent()).getParent();
            for (String keep : Settings.KEEP_BADGES.values()) {
                Switch control = root.findViewWithTag(keep);
                assertNotNull(keep, control);
                assertSame("Inside the Hide tab badges block: " + keep, wrapper, ((View) control.getParent()).getParent());
                assertFalse(control.isChecked());
            }
            TextView label = root.findViewWithTag("active_" + Settings.TAB_BADGES);
            assertFalse(label.getText().toString().contains("restarts"));

            Switch menu = root.findViewWithTag("tab_badges_keep_menu");
            menu.performClick();
            assertTrue(Settings.preferences.getBoolean("tab_badges_keep_menu", false));
            assertTrue("Keep leaves Hide tab badges on", Settings.preferences.getBoolean(Settings.TAB_BADGES, false));
            assertEquals("Takes effect after Messenger restarts", label.getText().toString());
            assertTrue("Still hidden until the restart", Settings.hideTabBadge("more"));

            ((Switch) root.findViewWithTag(Settings.TAB_BADGES)).performClick();
            assertEquals("Stays in effect until Messenger restarts", label.getText().toString());

            // "menu badge" is only in a Keep switch's own words.
            ((EditText) root.findViewWithTag("find_control")).setText("menu badge");
            assertEquals(View.VISIBLE, wrapper.getVisibility());
            assertTrue(menu.isShown());
        }
    }

    @Test public void aBackupCarriesTheKeepSwitchesOnlyWithHideTabBadges() {
        Settings.preferences.edit().putBoolean(Settings.TAB_BADGES, true).putBoolean("tab_badges_keep_facebook", true).commit();
        String backup = ChoiceCodec.encode(Settings.preferences, Set.of(Settings.TAB_BADGES));
        assertEquals(Map.of("paused", false, Settings.TAB_BADGES, true, "tab_badges_keep_chats", false, "tab_badges_keep_people", false,
            "tab_badges_keep_notifications", false, "tab_badges_keep_menu", false, "tab_badges_keep_facebook", true), ChoiceCodec.parse(backup));
        assertFalse(ChoiceCodec.encode(Settings.preferences, Set.of("stories")).contains("tab_badges"));
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            Settings.preferences.edit().putBoolean(Settings.TAB_BADGES, false).putBoolean("tab_badges_keep_facebook", false).commit();
            importChoices(root, backup);
            assertTrue(Settings.preferences.getBoolean("tab_badges_keep_facebook", false));
            assertTrue(((Switch) root.findViewWithTag("tab_badges_keep_facebook")).isChecked());

            Settings.installed = Set.of("stories");
            importChoices(root, ChoiceCodec.HEADER + "\ntab_badges_keep_facebook=false\n");
            assertTrue("Without the patch the backup leaves it as it was", Settings.preferences.getBoolean("tab_badges_keep_facebook", false));
        }
    }

    private static void importChoices(View root, String raw) {
        RuntimeEnvironment.getApplication().getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("choices", raw));
        root.findViewWithTag("import_choices").performClick();
    }
}
