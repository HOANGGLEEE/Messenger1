package app.hushmessenger.extension;

import org.junit.Before;
import org.robolectric.annotation.Config;

/** Settings sheets on an API 37 host: dialogs, recreation, insets and the log's Recents flags. Every case in SettingsDialogTest runs again here with Messenger's target. */
@Config(sdk = Api37Host.TARGET)
public class SettingsDialogApi37Test extends SettingsDialogTest {
    @Before public void onApi37Host() {
        Api37Host.target();
    }
}
