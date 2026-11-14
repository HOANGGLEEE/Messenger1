package app.hushmessenger.extension;

import org.junit.Before;
import org.robolectric.annotation.Config;

/** Startup, normal and Root Mount, on an API 37 host. Every case in HostScreensTest runs again here with Messenger's target. */
@Config(sdk = Api37Host.TARGET)
public class HostScreensApi37Test extends HostScreensTest {
    @Before public void onApi37Host() {
        Api37Host.target();
    }
}
