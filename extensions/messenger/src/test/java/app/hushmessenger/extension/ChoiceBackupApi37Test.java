package app.hushmessenger.extension;

import org.junit.Before;
import org.robolectric.annotation.Config;

/** Choice backups through document URIs on an API 37 host. Every case in ChoiceBackupTest runs again here with Messenger's target. */
@Config(sdk = Api37Host.TARGET)
public class ChoiceBackupApi37Test extends ChoiceBackupTest {
    @Before public void onApi37Host() {
        Api37Host.target();
    }
}
