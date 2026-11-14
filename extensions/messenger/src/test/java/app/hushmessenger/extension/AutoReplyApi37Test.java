package app.hushmessenger.extension;

import org.junit.Before;
import org.robolectric.annotation.Config;

/** Auto reply on an API 37 host: the queue on the main loop and the notification's Reply action. Every case in AutoReplyTest runs again here with Messenger's target. */
@Config(sdk = Api37Host.TARGET)
public class AutoReplyApi37Test extends AutoReplyTest {
    @Before public void onApi37Host() {
        Api37Host.target();
    }
}
