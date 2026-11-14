package app.hushmessenger.extension;

import org.junit.Before;
import org.robolectric.annotation.Config;

/** Crash counting, safe mode and its reset on an API 37 host. Every case in CrashGuardTest runs again here with Messenger's target. */
@Config(sdk = Api37Host.TARGET)
public class CrashGuardApi37Test extends CrashGuardTest {
    @Before public void onApi37Host() {
        Api37Host.target();
    }
}
