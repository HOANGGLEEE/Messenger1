package app.hushmessenger.extension;

import com.facebook.ui.media.attachments.model.MediaResource;
import com.facebook.xapp.messaging.composer.audio.recordcontrols.fragments.XappRecordingControlsDialogFragment;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class AudioPickerActivityTest {
    @Test public void selectedMediaResourceUsesMessenger581NativeVoiceComposer() throws Exception {
        XappRecordingControlsDialogFragment fragment = new XappRecordingControlsDialogFragment();
        MediaResource resource = new MediaResource();

        AudioPickerActivity.sendThroughMessenger(fragment, resource);

        assertSame(resource, fragment.state.resource);
        assertEquals(1, fragment.sendCount);
    }

    @Test public void unrelatedFragmentIsRejectedBeforeTheSendMethodRuns() throws Exception {
        try {
            AudioPickerActivity.sendThroughMessenger(new Object(), new MediaResource());
            fail("Expected the Messenger Xapp fragment contract to be enforced");
        } catch (IllegalStateException expected) {
            assertEquals("The Messenger 581 voice composer is no longer available.", expected.getMessage());
        }
    }
}
