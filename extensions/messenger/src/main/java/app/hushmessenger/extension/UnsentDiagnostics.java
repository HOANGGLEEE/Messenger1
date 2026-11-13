package app.hushmessenger.extension;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Privacy-preserving Keep-unsent diagnostics.
 * Secondary Messenger processes forward allow-listed stage/result tokens through the private
 * SettingsProvider. No message text, message IDs, account IDs, attachment URLs or media bytes
 * are ever passed to this diagnostics channel.
 */
public final class UnsentDiagnostics {
    private static final String TAG = "HushMessengerUnsend";
    private static final int MAX_EVENTS = 80;
    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicBoolean> FORWARDED_SAMPLES = new ConcurrentHashMap<>();
    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    private static volatile Boolean providerAvailable;

    private static final Set<String> STAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "runtime.initialize", "feature.keep_unsent", "hook.intent", "hook.delta_unsent",
        "hook.render_text", "parser.entry", "parser.skip", "model.callback", "model.skip",
        "model.media_metadata", "model.unsent_flag", "capture.callback", "capture.skip",
        "capture.save", "revoke.callback", "revoke.skip", "revoke.save",
        "notification.callback", "notification.skip", "notification.save", "media.callback",
        "media.skip", "media.capture", "media.save", "history.read", "diagnostics.export",
        "provider.bridge"
    )));

    private static final Set<String> RESULTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "ok", "installed", "not_installed", "enabled", "disabled", "entered", "true", "false",
        "null", "missing_id", "empty_text", "no_context", "no_preferences", "no_extras",
        "no_payload", "db_error", "saved", "not_saved", "started", "failed", "other",
        "missing_uri", "missing_metadata", "missing_file", "not_image", "unreadable",
        "too_large", "bad_signature", "linked", "no_images", "duplicate", "forwarded",
        "provider_unavailable"
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

    private UnsentDiagnostics() { }

    private static Context appContext(Context context) {
        if (context == null) return null;
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    private static String processName(Context context) {
        if (Build.VERSION.SDK_INT >= 28) {
            String name = Application.getProcessName();
            if (name != null && !name.isEmpty()) return name;
        }
        return context == null ? "unknown" : context.getPackageName();
    }

    private static boolean isSecondaryProcess(Context context) {
        return context != null && !context.getPackageName().equals(processName(context));
    }

    /** Entry-only observations are sampled once per process; save/skip outcomes are forwarded. */
    private static boolean shouldForward(String stage, String result) {
        boolean sample = "parser.entry".equals(stage) ||
            "model.callback".equals(stage) ||
            "capture.callback".equals(stage) ||
            "hook.render_text".equals(stage) ||
            ("hook.delta_unsent".equals(stage) && "false".equals(result));
        if (!sample) return true;
        String key = stage + ":" + result;
        return FORWARDED_SAMPLES.computeIfAbsent(key, ignored -> new AtomicBoolean())
            .compareAndSet(false, true);
    }

    /** Records only allow-listed stage/result tokens. */
    public static void record(Context context, String stage, String result) {
        String safeStage = STAGES.contains(stage) ? stage : "other";
        String safeResult = RESULTS.contains(result) ? result : "other";
        Context app = appContext(context != null ? context : Settings.appContext);

        if (isSecondaryProcess(app) && shouldForward(safeStage, safeResult) &&
                !Boolean.FALSE.equals(providerAvailable)) {
            try {
                Bundle extras = new Bundle();
                extras.putString("stage", safeStage);
                extras.putString("result", safeResult);
                Bundle response = app.getContentResolver().call(
                    Uri.parse("content://" + app.getPackageName() + ".hush.settings"),
                    SettingsProvider.CALL_RECORD_UNSENT_DEBUG, null, extras);
                if (response != null && response.getBoolean("accepted", false)) {
                    providerAvailable = true;
                    return;
                }
            } catch (Exception ignored) {
                // Root Mount builds may retain the stock manifest without the private provider.
            }
            providerAvailable = false;
            recordLocal("provider.bridge", "provider_unavailable");
        }
        recordLocal(safeStage, safeResult);
    }

    /** Called only by the private main-process provider; never calls ContentResolver again. */
    static boolean recordFromProvider(String stage, String result) {
        if (!STAGES.contains(stage) || !RESULTS.contains(result)) return false;
        recordLocal(stage, result);
        recordLocal("provider.bridge", "forwarded");
        return true;
    }

    private static void recordLocal(String stage, String result) {
        COUNTERS.computeIfAbsent(stage, ignored -> new AtomicLong()).incrementAndGet();
        synchronized (LOCK) {
            while (EVENTS.size() >= MAX_EVENTS) EVENTS.removeFirst();
            EVENTS.addLast(new Event(System.currentTimeMillis(), stage, result));
        }
        if ("hook.delta_unsent".equals(stage) && "false".equals(result)) return;
        if ("hook.render_text".equals(stage) || "parser.entry".equals(stage) ||
                ("capture.callback".equals(stage) && "entered".equals(result)) ||
                ("model.callback".equals(stage) && "entered".equals(result))) return;
        Log.i(TAG, stage + "=" + result);
    }

    private static long count(String stage) {
        AtomicLong value = COUNTERS.get(stage);
        return value == null ? 0 : value.get();
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

    private static int metadataInt(Bundle metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) return -1;
        Object value = metadata.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt((String) value); }
            catch (NumberFormatException ignored) { return -1; }
        }
        return -1;
    }

    private static boolean metadataBoolean(Bundle metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) return false;
        Object value = metadata.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return false;
    }

    private static String installedState(Bundle metadata, String key) {
        return metadata == null || !metadata.containsKey(key) ? "không xác định" :
            (metadataBoolean(metadata, key) ? "đã cài" : "chưa cài");
    }

    /** Compact hook-site and runtime summary shown in the History diagnostics panel. */
    public static String summary(Context context) {
        Context app = appContext(context != null ? context : Settings.appContext);
        if (app == null) return "Debug: runtime chưa khởi tạo";
        SharedPreferences prefs = Settings.preferences != null
            ? Settings.preferences
            : app.getSharedPreferences("hushmessenger", Context.MODE_PRIVATE);
        Bundle meta = metadata(app);
        boolean installed = Settings.installed != null && Settings.installed.contains("keep_unsent");
        boolean enabled = installed && prefs.getBoolean("keep_unsent", false)
            && !prefs.getBoolean("paused", false) && !CrashGuard.isSafeMode() && !HostScreens.failed;

        int parser = metadataInt(meta, "hush.unsent.message_parser_hook_sites");
        int nativeParser = metadataInt(meta, "hush.unsent.native_parser_hook_sites");
        int revoke = metadataInt(meta, "hush.unsent.revoke_hook_sites");
        int media = metadataInt(meta, "hush.unsent.media_capture_hook_sites");
        int notifications = metadataInt(meta, "hush.unsent.notification_hook_sites");

        return "Runtime: " + (HostScreens.started ? "đã chạy" : "chưa chạy")
            + " • Keep unsent: " + (!installed ? "chưa cài" : (enabled ? "đang bật" : "đang tắt"))
            + "\nHook text: " + installedState(meta, "hush.unsent.text_render_hook")
            + " • Delta: " + installedState(meta, "hush.unsent.delta_hook")
            + " • E2EE: " + installedState(meta, "hush.unsent.e2ee_capture_hook")
            + "\nParser: " + (parser < 0 ? "?" : parser)
            + " • Native: " + (nativeParser < 0 ? "?" : nativeParser)
            + " • Revoke: " + (revoke < 0 ? "?" : revoke)
            + "\nHook thông báo: " + (notifications < 0 ? "?" : notifications)
            + " • Hook media: " + (media < 0 ? "?" : (media == 0 ? "chưa có" : media))
            + "\nSự kiện từ tiến trình phụ: " + count("provider.bridge");
    }

    /** Exports build metadata, stage counters and a redacted event ring as JSON. */
    public static String report(Context context) {
        Context app = appContext(context != null ? context : Settings.appContext);
        if (app == null) return "{\"error\":\"runtime_not_initialized\"}";
        try {
            SharedPreferences prefs = Settings.preferences != null
                ? Settings.preferences
                : app.getSharedPreferences("hushmessenger", Context.MODE_PRIVATE);
            Bundle meta = metadata(app);
            JSONObject root = new JSONObject();
            root.put("schema", "hush-unsent-debug-v2");
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
                appInfo.put("versionCode", Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode);
            } catch (PackageManager.NameNotFoundException ignored) {
                appInfo.put("versionName", "unknown");
                appInfo.put("versionCode", -1);
            }
            root.put("app", appInfo);

            JSONObject runtime = new JSONObject();
            boolean installed = Settings.installed != null && Settings.installed.contains("keep_unsent");
            boolean enabled = installed && prefs.getBoolean("keep_unsent", false)
                && !prefs.getBoolean("paused", false) && !CrashGuard.isSafeMode() && !HostScreens.failed;
            runtime.put("processName", processName(app));
            runtime.put("hostStarted", HostScreens.started);
            runtime.put("hostFailed", HostScreens.failed);
            runtime.put("keepUnsentInstalled", installed);
            runtime.put("keepUnsentPreference", prefs.getBoolean("keep_unsent", false));
            runtime.put("paused", prefs.getBoolean("paused", false));
            runtime.put("safeMode", CrashGuard.isSafeMode());
            runtime.put("keepUnsentEnabledNow", enabled);
            runtime.put("e2eeCaptureHook", metadataBoolean(meta, "hush.unsent.e2ee_capture_hook"));
            runtime.put("deltaUnsentHook", metadataBoolean(meta, "hush.unsent.delta_hook"));
            runtime.put("textRenderHook", metadataBoolean(meta, "hush.unsent.text_render_hook"));
            runtime.put("messageParserHookSites", metadataInt(meta, "hush.unsent.message_parser_hook_sites"));
            runtime.put("nativeParserHookSites", metadataInt(meta, "hush.unsent.native_parser_hook_sites"));
            runtime.put("revokeHookSites", metadataInt(meta, "hush.unsent.revoke_hook_sites"));
            runtime.put("notificationHookSites", metadataInt(meta, "hush.unsent.notification_hook_sites"));
            runtime.put("mediaCaptureHookSites", metadataInt(meta, "hush.unsent.media_capture_hook_sites"));
            runtime.put("providerBridgeEvents", count("provider.bridge"));
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
            return "{\"error\":\"report_failed\",\"exception\":\"" +
                error.getClass().getSimpleName() + "\"}";
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
        FORWARDED_SAMPLES.clear();
        providerAvailable = null;
        synchronized (LOCK) {
            EVENTS.clear();
        }
    }
}
