package app.hushmessenger.extension;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * The supported Messenger build targets API 37 while the extension's own preview targets 36. The Api37 test classes
 * rerun selected suites on an API 37 runtime with the app's target raised to match, so target-gated platform paths
 * take the branch they take inside Messenger.
 */
final class Api37Host {
    private Api37Host() {}

    static final int TARGET = 37;

    /** A case its suite pins to another SDK already runs there, so its copy here is skipped. */
    static void target() {
        assumeTrue("pinned to another SDK in its own suite", Build.VERSION.SDK_INT == TARGET);
        ApplicationInfo info = RuntimeEnvironment.getApplication().getApplicationInfo();
        info.targetSdkVersion = TARGET;
        assertEquals("Messenger's target", TARGET, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
}
