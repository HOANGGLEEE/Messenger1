package app.hushmessenger.extension;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Privacy-preserving diagnostics for Keep unsent messages.
 *
 * The event ring accepts only fixed stage/result tokens. It never stores message text,
 * message/thread/account IDs, attachment URLs, notification contents or media bytes.
 */
public final class UnsentDiagnostics {
    private static final String TAG = "HushMessengerUnsend";
    private static final String PREFS_NAME = "hushmessenger";
    private static final String COUNT_PREFIX = "unsent_diag_count_";
    private static final String EVENTS_KEY = "unsent_diag_events";
    private static final int MAX_EVENTS = 80;
    private static final Object LOCK = new Object();

    private static final Set<String> STAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "runtime.initialize", "feature.keep_unsent", "hook.intent", "hook.delta_unsent",
        "hook.render_text", "capture.callback", "capture.skip", "capture.save",
        "revoke.callback", "revoke.skip", "revoke.save", "notification.callback",
        "notification.skip", "notification.save", "history.read", "diagnostics.export"
    )));

    private static final Set<String> RESULTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "ok", "installed", "not_installed", "enabled", "disabled", "entered",
        "true", "false", "null", "missing_id", "empty_text", "no_context",
        "no_preferences", "no_extras", "no_payload", "db_error", "saved",
        "not_saved", "started", "failed", "other"
    )));

    private UnsentDiagnostics() {}

    private static Context appContext(Context context) {
        if (context == null) return null;
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    /** Records only allow-listed stage and result tokens; arbitrary payloads are discarded. */
    public static void record(Context context, String stage, String result) {
        Context app = appContext(context);
        if (app == null) return;

        String safeStage = STAGES.contains(stage) ? stage : "other";
        String safeResult = RESULTS.contains(result) ? result : "other";
        long now = System.currentTimeMillis();
        SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        synchronized (LOCK) {
            String old = prefs.getString(EVENTS_KEY, "");
            String[] previous = old.isEmpty() ? new String[0] : old.split("\n");
            StringBuilder ring = new StringBuilder();
            int first = Math.max(0, previous.length - (MAX_EVENTS - 1));
            for (int i = first; i < previous.length; i++) {
                if (previous[i].isEmpty()) continue;
                if (ring.length() > 0) ring.append('\n');
                ring.append(previous[i]);
            }
            if (ring.length() > 0) ring.append('\n');
            ring.append(now).append('|').append(safeStage).append('|').append(safeResult);

            String countKey = COUNT_PREFIX + safeStage;
            prefs.edit()
                .putLong(countKey, prefs.getLong(countKey, 0) + 1)
                .putString(EVENTS_KEY, ring.toString())
                .apply();
        }
        Log.i(TAG, safeStage + "=" + safeResult);
    }

    /** Human-readable, compact summary shown on the Unsent History page. */
    public static String summary(Context context) {
        Context app = appContext(context);
        if (app == null) return "Debug: runtime chưa khởi tạo";

        SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Bundle metadata = metadata(app);
        boolean installed = Settings.installed != null && Settings.installed.contains("keep_unsent");
        boolean enabled = installed && prefs.getBoolean("keep_unsent", false)
            && !prefs.getBoolean("paused", false) && !CrashGuard.isSafeMode() && !HostScreens.failed;

        String delta = metadata == null || !metadata.containsKey("hush.unsent.delta_hook")
            ? "không xác định" : (metadata.getBoolean("hush.unsent.delta_hook") ? "đã cài" : "chưa cài");
        String text = metadata == null || !metadata.containsKey("hush.unsent.text_render_hook")
            ? "không xác định" : (metadata.getBoolean("hush.unsent.text_render_hook") ? "đã cài" : "chưa cài");
        String e2ee = metadata == null || !metadata.containsKey("hush.unsent.e2ee_capture_hook")
            ? "không xác định" : (metadata.getBoolean("hush.unsent.e2ee_capture_hook") ? "đã cài" : "chưa cài");
        int mediaSites = metadata == null ? -1 : metadata.getInt("hush.unsent.media_capture_hook_sites", -1);
        int notificationSites = metadata == null ? -1 : metadata.getInt("hush.unsent.notification_hook_sites", -1);

        String media = mediaSites < 0 ? "không xác định" : (mediaSites == 0 ? "chưa có hook ảnh" : mediaSites + " hook");
        String notifications = notificationSites < 0 ? "không xác định" : Integer.toString(notificationSites);

        return "Runtime: " + (HostScreens.started ? "đã chạy" : "chưa chạy")
            + " • Keep unsent: " + (!installed ? "chưa cài" : (enabled ? "đang bật" : "đang tắt"))
            + "\nHook text: " + text + " • Delta: " + delta + " • E2EE: " + e2ee
            + "\nHook thông báo: " + notifications + " • Media: " + media;
    }

    /** Exports package/build state, allow-listed counters and redacted events as JSON. */
    public static String report(Context context) {
        Context app = appContext(context);
        if (app == null) return "{\"error\":\"runtime_not_initialized\"}";

        try {
            SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Bundle metadata = metadata(app);
            JSONObject root = new JSONObject();
            root.put("schema", "hush-unsent-debug-v1");
            root.put("exportedAtEpochMs", System.currentTimeMillis());
            root.put("privacy", "No message text, message IDs, account IDs, attachment URLs or media bytes are included.");

            JSONObject device = new JSONObject();
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("model", Build.MODEL);
            device.put("androidRelease", Build.VERSION.RELEASE);
            device.put("sdk", Build.VERSION.SDK_INT);
            device.put("abis", join(Build.SUPPORTED_ABIS));
            root.put("device", device);

            JSONObject appInfo = new JSONObject();
            appInfo.put("package", app.getPackageName());
            try {
                PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                appInfo.put("versionName", info.versionName == null ? "" : info.versionName);
                appInfo.put("versionCode", info.getLongVersionCode());
            } catch (PackageManager.NameNotFoundException ignored) {
                appInfo.put("versionName", "unknown");
                appInfo.put("versionCode", -1);
            }
            root.put("app", appInfo);

            JSONObject runtime = new JSONObject();
            boolean installed = Settings.installed != null && Settings.installed.contains("keep_unsent");
            boolean enabled = installed && prefs.getBoolean("keep_unsent", false)
                && !prefs.getBoolean("paused", false) && !CrashGuard.isSafeMode() && !HostScreens.failed;
            runtime.put("hostStarted", HostScreens.started);
            runtime.put("hostFailed", HostScreens.failed);
            runtime.put("keepUnsentInstalled", installed);
            runtime.put("keepUnsentPreference", prefs.getBoolean("keep_unsent", false));
            runtime.put("paused", prefs.getBoolean("paused", false));
            runtime.put("safeMode", CrashGuard.isSafeMode());
            runtime.put("keepUnsentEnabledNow", enabled);
            runtime.put("e2eeCaptureHook", metadata != null && metadata.getBoolean("hush.unsent.e2ee_capture_hook", false));
            runtime.put("deltaUnsentHook", metadata != null && metadata.getBoolean("hush.unsent.delta_hook", false));
            runtime.put("textRenderHook", metadata != null && metadata.getBoolean("hush.unsent.text_render_hook", false));
            runtime.put("notificationHookSites", metadata == null ? -1 : metadata.getInt("hush.unsent.notification_hook_sites", -1));
            runtime.put("mediaCaptureHookSites", metadata == null ? -1 : metadata.getInt("hush.unsent.media_capture_hook_sites", -1));
            root.put("runtime", runtime);

            JSONObject counters = new JSONObject();
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith(COUNT_PREFIX)) continue;
                Object value = entry.getValue();
                if (value instanceof Number) counters.put(key.substring(COUNT_PREFIX.length()), value);
            }
            root.put("counters", counters);

            JSONArray events = new JSONArray();
            String rawEvents = prefs.getString(EVENTS_KEY, "");
            if (!rawEvents.isEmpty()) {
                for (String line : rawEvents.split("\n")) {
                    String[] parts = line.split("\\|", 3);
                    if (parts.length != 3) continue;
                    JSONObject event = new JSONObject();
                    try { event.put("atEpochMs", Long.parseLong(parts[0])); }
                    catch (NumberFormatException ignored) { continue; }
                    event.put("stage", parts[1]);
                    event.put("result", parts[2]);
                    events.put(event);
                }
            }
            root.put("events", events);

            JSONObject errors = new JSONObject();
            Map<String, String> lastErrors = Settings.lastHookErrors();
            String unsentError = lastErrors.get("keep_unsent");
            if (unsentError != null) errors.put("keep_unsent", unsentError);
            root.put("hookErrors", errors);
            return root.toString(2);
        } catch (Exception error) {
            return "{\"error\":\"report_failed\",\"exception\":\"" + error.getClass().getSimpleName() + "\"}";
        }
    }

    private static Bundle metadata(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                context.getPackageName(), PackageManager.GET_META_DATA);
            return info.metaData;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) return "";
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }

    static void clearForTests(Context context) {
        Context app = appContext(context);
        if (app == null) return;
        SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(COUNT_PREFIX) || EVENTS_KEY.equals(key)) edit.remove(key);
        }
        edit.commit();
    }
}
