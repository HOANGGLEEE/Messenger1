package app.hushmessenger.extension;

import android.content.Context;

import org.json.JSONObject;
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

    @Test public void reportKeepsOnlyAllowListedStageAndResultTokens() throws Exception {
        UnsentDiagnostics.recordFromProvider("capture.callback", "entered");
        assertFalse(UnsentDiagnostics.recordFromProvider("capture.callback", "private-message-id"));

        JSONObject report = new JSONObject(UnsentDiagnostics.report(context));
        assertEquals("hush-unsent-debug-v3", report.getString("schema"));
        assertTrue(report.getJSONObject("counters").getLong("capture.callback") >= 1);
        assertTrue(report.getJSONObject("counters").getLong("provider.bridge") >= 1);
        assertFalse(report.toString().contains("private-message-id"));
        assertFalse(report.toString().contains("private chat text"));
    }

    @Test public void reportIncludesOnlyRedactedImageCallbackObjectShapes() throws Exception {
        assertTrue(UnsentDiagnostics.recordObjectShape(context, "downloader",
            "Lx/Downloader;[A07:Lx/Metadata;=set,A01:Ljava/lang/String;=null]"));
        assertTrue(UnsentDiagnostics.recordObjectShape(context, "result",
            "Lx/Result;[A00:Landroid/net/Uri;=set,A01:Ljava/lang/Throwable;=null]"));
        assertFalse(UnsentDiagnostics.recordObjectShape(context, "downloader",
            "private-message-id"));
        assertFalse(UnsentDiagnostics.recordObjectShape(context, "downloader",
            "Lx/Downloader;[messageId:java.lang.String=secret]"));
        assertFalse(UnsentDiagnostics.recordObjectShape(context, "unknown",
            "Lx/Downloader;"));
        assertFalse(UnsentDiagnostics.recordFromProvider("object.shape", "downloader",
            "private-message-id"));
        assertTrue(UnsentDiagnostics.recordFromProvider("object.shape", "metadata",
            "Lx/Metadata;[directPath:java.lang.String=set]"));

        JSONObject report = new JSONObject(UnsentDiagnostics.report(context));
        String json = report.toString();
        assertTrue(json.contains("object.shape"));
        assertTrue(json.contains("Lx/Downloader;"));
        assertTrue(json.contains("A07:Lx/Metadata;=set"));
        assertFalse(json.contains("private-message-id"));
    }

    @Test public void summaryReportsMediaAndCrossProcessHookStatus() {
        String summary = UnsentDiagnostics.summary(context);
        assertTrue(summary.contains("Hook media:"));
        assertTrue(summary.contains("Sự kiện từ tiến trình phụ:"));
    }

    @Test public void providerBridgeRejectsUnlistedStagesAndResults() {
        assertFalse(UnsentDiagnostics.recordFromProvider("arbitrary.stage", "entered"));
        assertFalse(UnsentDiagnostics.recordFromProvider("capture.callback", "arbitrary payload"));
        assertTrue(UnsentDiagnostics.recordFromProvider("revoke.save", "ok"));
    }

    @Test public void mediaMetadataFailureReasonsRemainAllowListedAndPrivate() throws Exception {
        assertTrue(UnsentDiagnostics.recordFromProvider("media.skip", "metadata_container_missing"));
        assertTrue(UnsentDiagnostics.recordFromProvider("media.skip", "direct_path_missing"));
        assertTrue(UnsentDiagnostics.recordFromProvider("media.skip", "mime_missing"));
        assertTrue(UnsentDiagnostics.recordFromProvider("media.skip", "timestamp_missing"));
        assertTrue(UnsentDiagnostics.recordFromProvider("media.skip", "metadata_type_mismatch"));

        JSONObject report = new JSONObject(UnsentDiagnostics.report(context));
        String json = report.toString();
        assertTrue(json.contains("metadata_container_missing"));
        assertTrue(json.contains("direct_path_missing"));
        assertFalse(json.contains("private-message-id"));
    }
}
