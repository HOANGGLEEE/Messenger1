package app.hushmessenger.extension;

import org.junit.Before;
import org.robolectric.annotation.Config;

/** Window flags on an API 37 host. Every case in ScreenshotFlagsTest runs again here with Messenger's target. */
@Config(sdk = Api37Host.TARGET)
public class ScreenshotFlagsApi37Test extends ScreenshotFlagsTest {
    @Before public void onApi37Host() {
        Api37Host.target();
    }
}
