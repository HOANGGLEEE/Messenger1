package app.hushmessenger.extension;

import org.junit.Before;
import org.robolectric.annotation.Config;

/** Settings search and categories on an API 37 host. Every case in SettingsNavigationTest runs again here with Messenger's target. */
@Config(sdk = Api37Host.TARGET)
public class SettingsNavigationApi37Test extends SettingsNavigationTest {
    @Before public void onApi37Host() {
        Api37Host.target();
    }
}
