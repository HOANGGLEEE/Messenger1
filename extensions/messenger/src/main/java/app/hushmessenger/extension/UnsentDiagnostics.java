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

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Privacy-preserving, process-local diagnostics for Keep unsent messages.
 *
 * The event ring accepts only fixed stage/result tokens. It never stores message text,
 * message/thread/account IDs, attachment URLs, notification contents or media bytes.
 * Counters are in memory to avoid repeated disk writes from message-render hooks.
 */
public final class UnsentDiagnostics {
    private static final String TAG = "HushMessengerUnsend";
    private static final int MAX_EVENTS = 80;
    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();
    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();

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

    private static final class Event {
        final long at;
        final String stage;
        final String result;
        Event(long at, String stage, String result) {
            this.at = at;
            this.stage = stage;
            this.result = result;
        }
    }

    private UnsentDiagnostics() {}

    private static Context appContext(Context context) {
        if (context == null) return null;
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    /** Records only allow-listed stage and result tokens; arbitrary payloads are discarded. */
    public static void record(Context ignored, String stage, String result) {
        String safeStage = STAGES.contains(stage) ? stage : "other";
        String safeResult = RESULTS.contains(result) ? result : "other";
        long now = System.currentTimeMillis();

        COUNTERS.computeIfAbsent(safeStage, key -> new AtomicLong()).incrementAndGet();
        synchronized (LOCK) {
            while (EVENTS.size() >= MAX_EVENTS) EVENTS.removeFirst();
            EVENTS.addLast(new Event(now, safeStage, safeResult));
        }

        // delta_unsent may be called for every rendered row. Keep false observations in the
        // report counters, but avoid flooding logcat; true observations remain visible.
        if (!"hook.delta_unsent".equals(safeStage) || "true".equals(safeResult)) {
            Log.i(TAG, safeStage + "=" + safeResult);
        }
    }

    /** Human-readable, compact summary shown on the Unsent History page. */
    public static String summary(Context context) {
        Context app = appContext(context);
        if (app == null) return "Debug: runtime chưa khởi tạo";

        SharedPreferences prefs = Settings.preferences != null
            ? Settings.preferences
            : app.getSharedPreferences("hushmessenger", Context.MODE_PRIVATE);
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
            SharedPreferences prefs = Settings.preferences != null
                ? Settings.preferences
                : app.getSharedPreferences("hushmessenger", Context.MODE_PRIVATE);
            Bundle metadata = metadata(app);
            JSONObject root = new JSONObject();
            root.put("schema", "hush-unsent-debug-v1");
            root.put("exportedAtEpochMs", System.currentTimeMillis());
            root.put("sessionOnly", true);
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
            for (Map.Entry<String, AtomicLong> entry : COUNTERS.entrySet()) {
                counters.put(entry.getKey(), entry.getValue().get());
            }
            root.put("counters", counters);

            JSONArray events = new JSONArray();
            synchronized (LOCK) {
                for (Event item : EVENTS) {
                    JSONObject event = new JSONObject();
                    event.put("atEpochMs", item.at);
                    event.put("stage", item.stage);
                    event.put("result", item.result);
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

    static void clearForTests(Context ignored) {
        COUNTERS.clear();
        synchronized (LOCK) {
            EVENTS.clear();
        }
    }
}
