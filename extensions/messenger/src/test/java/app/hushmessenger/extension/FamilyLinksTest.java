package app.hushmessenger.extension;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class FamilyLinksTest {
    private static final Uri LINK = Uri.parse("https://www.threads.com/@someone/post/1");
    private static final String THREADS = "com.instagram.barcelona";

    private Application app;
    private ShadowApplication shadow;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        shadow = shadowOf(app);
        shadow.clearNextStartedActivities();
    }

    private static Intent threadsView() {
        return new Intent(Intent.ACTION_VIEW, LINK).setPackage(THREADS);
    }

    private void install(String packageName, String activity) throws Exception {
        ComponentName component = new ComponentName(packageName, activity);
        shadowOf(app.getPackageManager()).addActivityIfNotPresent(component);
        IntentFilter filter = new IntentFilter(Intent.ACTION_VIEW);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        filter.addDataScheme("https");
        shadowOf(app.getPackageManager()).addIntentFilterForActivity(component, filter);
    }

    @Test public void aLaunchTheFamilyLauncherStartedIsLeftAlone() {
        FamilyLinks.launched(true, app, threadsView());
        assertNull(shadow.getNextStartedActivity());
    }

    @Test public void aRefusedLaunchOpensTheSameLinkInItsApp() {
        Activity chat = Robolectric.buildActivity(Activity.class).setup().get();
        Intent given = threadsView();
        FamilyLinks.launched(false, chat, given);
        Intent opened = shadowOf(chat).getNextStartedActivity();
        assertNotNull(opened);
        assertNotSame("Messenger's own intent isn't changed", given, opened);
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals(LINK, opened.getData());
        assertEquals(THREADS, opened.getPackage());
        assertEquals("An activity keeps its task", 0, opened.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test public void fromAnApplicationContextTheLinkGetsItsOwnTask() {
        FamilyLinks.launched(false, app, threadsView());
        Intent opened = shadow.getNextStartedActivity();
        assertEquals(THREADS, opened.getPackage());
        assertNotEquals(0, opened.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test public void whenTheAppCantTakeItTheBrowserGetsTheLink() throws Exception {
        shadow.checkActivities(true);
        install("com.android.chrome", "com.google.android.apps.chrome.Main");
        FamilyLinks.launched(false, app, threadsView());
        Intent opened = shadow.getNextStartedActivity();
        assertNotNull(opened);
        assertNull(opened.getPackage());
        assertEquals(LINK, opened.getData());
        assertNull(shadow.getNextStartedActivity());
    }

    @Test public void withNothingToOpenItNothingThrows() {
        shadow.checkActivities(true);
        FamilyLinks.launched(false, app, threadsView());
        FamilyLinks.launched(false, app, new Intent(Intent.ACTION_VIEW, LINK));
        FamilyLinks.launched(false, null, threadsView());
        FamilyLinks.launched(false, app, null);
        assertNull(shadow.getNextStartedActivity());
    }
}
