package app.hushmessenger.extension;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class UnsentDiagnosticsTest {
    private Context context;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        Settings.initialize(context);
        UnsentDiagnostics.clearForTests(context);
    }

    @Test public void reportKeepsOnlyAllowListedStageAndResultTokens() {
        UnsentDiagnostics.record(context, "capture.callback", "entered");
        UnsentDiagnostics.record(context, "capture.callback", "message-id-123-private-text");

        String report = UnsentDiagnostics.report(context);
        assertTrue(report.contains("\"capture.callback\": 2"));
        assertTrue(report.contains("\"result\": \"other\""));
        assertFalse(report.contains("message-id-123"));
        assertFalse(report.contains("private-text"));
    }

    @Test public void summaryIdentifiesMissingMediaHookWithoutClaimingMediaWasSaved() {
        String summary = UnsentDiagnostics.summary(context);
        assertTrue(summary.contains("Media:"));
    }
}
