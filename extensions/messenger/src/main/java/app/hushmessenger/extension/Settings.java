package app.hushmessenger.extension;

import android.app.Application;
import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime switches default to the original Messenger behavior. */
public final class Settings {
    static volatile SharedPreferences preferences;
    static volatile Context appContext;
    static volatile Set<String> installed = Collections.emptySet();
    static boolean preview;
    static boolean bubbleRoutes;
    static final String BUBBLE_CHAT_HEADS = "bubble_chat_heads";
    static final ConcurrentHashMap<String, Long> activeAt = new ConcurrentHashMap<>();

    public static void initialize(Context context) {
        Context app = context.getApplicationContext();
        // Messenger's Application has no application context of its own until Android finishes attaching it.
        appContext = app != null ? app : context;
        Set<String> features = new HashSet<>();
        preview = false;
        bubbleRoutes = false;
        try {
            Bundle metadata = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA).metaData;
            preview = metadata != null && metadata.getBoolean("hush.preview", false);
            bubbleRoutes = preview || HostScreens.nativeBubbleRoutes() ||
                    (metadata != null && metadata.getBoolean("hush.native_bubble_routes", false));
            if (metadata != null) for (String name : metadata.keySet()) {
                if (name.startsWith("hush.feature.") && metadata.getBoolean(name, false)) features.add(name.substring(13));
            }
        } catch (PackageManager.NameNotFoundException error) {
            android.util.Log.e("HushMessenger", "Can't read installed controls", error);
        }
        // A Root Mount install keeps the stock manifest, so the controls come from the patched code instead.
        features.addAll(bundled(HostScreens.bundledControls()));
        installed = Collections.unmodifiableSet(features);
        // Set last: a hook that sees preferences also sees the installed controls.
        // MODE_MULTI_PROCESS is a best-effort fallback for Root Mount builds without the main-process provider.
        preferences = appContext.getSharedPreferences("hushmessenger", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
        UnsentDiagnostics.record(appContext, "runtime.initialize", "ok");
        UnsentDiagnostics.record(appContext, "feature.keep_unsent", installed.contains("keep_unsent") ? "installed" : "not_installed");
    }

    static Set<String> bundled(String list) {
        Set<String> keys = new HashSet<>();
        if (list != null) for (String key : list.split(",")) if (!key.isEmpty()) keys.add(key);
        return keys;
    }

    public static boolean enabled(String key) {
        boolean on = wouldUse(key);
        if (on) activeAt.put(key, System.currentTimeMillis());
        return on;
    }

    /** Whether a control is in effect right now, without counting it as a use. */
    static boolean wouldUse(String key) {
        if (!HostScreens.started) HostScreens.initializeLate();
        if (HostScreens.failed) return false;
        if ("keep_unsent".equals(key)) {
            Boolean mainProcessState = mainProcessKeepUnsentEnabled();
            if (mainProcessState != null) return mainProcessState;
        }
        return locallyWouldUse(key);
    }

    /** Main-process view used by the private provider when a secondary Messenger process asks. */
    static boolean keepUnsentEnabledLocally() {
        return locallyWouldUse("keep_unsent");
    }

    private static boolean locallyWouldUse(String key) {
        SharedPreferences prefs = preferences;
        return !HostScreens.failed && installed.contains(key) && prefs != null
                && !prefs.getBoolean("paused", false)
                && !prefs.getBoolean("safe_mode", false)
                && !CrashGuard.isSafeMode()
                && prefs.getBoolean(key, false);
    }

    /**
     * Messenger 581 receives encrypted messages in :background_e2ee. SharedPreferences are cached
     * per process, so ask the private main-process provider for the current switch state. Root Mount
     * installs may not expose the provider; in that case the process-local snapshot is the fallback.
     */
    private static Boolean mainProcessKeepUnsentEnabled() {
        Context context = appContext;
        if (context == null) return null;
        String processName = Application.getProcessName();
        if (processName == null || processName.equals(context.getPackageName())) return null;
        try {
            Bundle state = context.getContentResolver().call(
                Uri.parse("content://" + context.getPackageName() + ".hush.settings"),
                SettingsProvider.CALL_KEEP_UNSENT_ENABLED, null, null);
            return state == null || !state.containsKey("enabled") ? null : state.getBoolean("enabled", false);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static long lastActive(String key) {
        Long ts = activeAt.get(key);
        return ts != null ? ts : 0;
    }

    /**
     * Each control's last caught hook failure as "<exception class> at <first HushMessenger frame>|<time>".
     * The exception's message is never kept, since it could hold chat content.
     */
    static final ConcurrentHashMap<String, String> hookErrors = new ConcurrentHashMap<>();
    private static final String HOOK_ERROR = "hook_error_";

    static void hookFailed(String key, String what, Throwable error) {
        android.util.Log.e("HushMessenger", what, error);
        recordHookError(key, error);
    }

    /** For hooks that handle file paths or chat content: logs only the error's type and where it happened. */
    static void hookFailedPrivately(String key, String what, Throwable error) {
        android.util.Log.e("HushMessenger", what + ": " + recordHookError(key, error));
    }

    private static synchronized String recordHookError(String key, Throwable error) {
        StackTraceElement[] stack = error.getStackTrace();
        StackTraceElement frame = stack.length == 0 ? null : stack[0];
        for (StackTraceElement element : stack) {
            if (element.getClassName().startsWith("app.hushmessenger.")) { frame = element; break; }
        }
        String where = frame == null ? "unknown" : frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
            + "." + frame.getMethodName() + (frame.getLineNumber() >= 0 ? ":" + frame.getLineNumber() : "");
        String failure = error.getClass().getName() + " at " + where;
        long now = System.currentTimeMillis();
        hookErrors.put(key, failure + "|" + now);
        SharedPreferences prefs = preferences;
        // A hook can fail on every screen draw, so the saved copy changes only for a new failure or once a minute.
        if (prefs != null) {
            String previous = prefs.getString(HOOK_ERROR + key, null);
            if (previous == null || !previous.startsWith(failure + "|") || now - hookErrorTime(previous) >= 60_000
                    || now < hookErrorTime(previous))
                prefs.edit().putString(HOOK_ERROR + key, failure + "|" + now).apply();
        }
        return failure;
    }

    static long hookErrorTime(String record) {
        try {
            return Long.parseLong(record.substring(record.lastIndexOf('|') + 1));
        } catch (RuntimeException malformed) {
            return 0;
        }
    }

    /** When the control's hook last failed, this run or an earlier one, or 0 if it never did. */
    static long hookErrorAt(String key) {
        String record = hookErrors.get(key);
        SharedPreferences prefs = preferences;
        if (record == null && prefs != null) record = prefs.getString(HOOK_ERROR + key, null);
        return record == null ? 0 : hookErrorTime(record);
    }

    /** Control key to its last failure record, with this run's failures over the saved ones. */
    static Map<String, String> lastHookErrors() {
        Map<String, String> errors = new java.util.TreeMap<>();
        SharedPreferences prefs = preferences;
        if (prefs != null) for (Map.Entry<String, ?> saved : prefs.getAll().entrySet()) {
            if (saved.getKey().startsWith(HOOK_ERROR) && saved.getValue() instanceof String)
                errors.put(saved.getKey().substring(HOOK_ERROR.length()), (String) saved.getValue());
        }
        errors.putAll(hookErrors);
        return errors;
    }

    public static boolean hideStories() { return enabled("stories"); }
    public static boolean hideFacebook() { return enabled("facebook"); }
    public static boolean hideMetaAi() { return enabled("meta_ai"); }

    /** First answer this process gave for the Meta AI tab, or null before the bottom bar asked. */
    static volatile Boolean metaAiTab;

    /**
     * The bottom bar keeps the tab list it counted first, but checks each tab again when it draws, the way Messenger's own
     * kill switch never changes while it runs. So the tab keeps its first answer until a restart instead of following the
     * switch mid-session and leaving the bar and its list out of step.
     */
    public static boolean hideMetaAiTab() {
        Boolean hidden = metaAiTab;
        if (hidden != null) return hidden;
        // Its own lock, so two first askers can't get different answers if the switch flips between them.
        synchronized (META_AI_TAB) {
            if (metaAiTab == null) metaAiTab = hideMetaAi();
            return metaAiTab;
        }
    }

    private static final Object META_AI_TAB = new Object();
    public static boolean showSubtabs(boolean original) { return original && !enabled("subtabs"); }
    public static boolean hidePeopleSection(boolean original) { return original || enabled("people"); }
    public static boolean keepPeopleSection(boolean original) { return original && !enabled("people"); }
    public static boolean suppressTyping() { return enabled("typing"); }
    /** Encrypted chats send typing through one mailbox call; "not typing" is always allowed through. */
    public static boolean outgoingTyping(boolean typing) { return typing && !enabled("typing"); }
    static boolean available(String key) {
        if (!"bubbles".equals(key)) return true;
        if (Build.VERSION.SDK_INT < 30) return false;
        if (!HostScreens.started) HostScreens.initializeLate();
        return !HostScreens.failed && bubbleRoutes;
    }
    public static boolean enableBubbles() {
        return available("bubbles") && enabled("bubbles") && !preferences.getBoolean(BUBBLE_CHAT_HEADS, false);
    }
    public static boolean forceChatHeads() {
        return available("bubbles") && enabled("bubbles") && preferences.getBoolean(BUBBLE_CHAT_HEADS, false);
    }
    public static boolean nativeBubbleRollout(boolean original) {
        return original || enableBubbles();
    }
    static String selectedBubbleMode() {
        if (preferences == null || !preferences.getBoolean("bubbles", false)) return "stock";
        return preferences.getBoolean(BUBBLE_CHAT_HEADS, false) ? "chat_heads" : "native";
    }
    public static boolean allowScreenshot() { return enabled("allow_screenshot"); }
    public static void addScreenshotFlags(Window window, int flags) {
        window.addFlags(allowScreenshot() ? flags & ~WindowManager.LayoutParams.FLAG_SECURE : flags);
    }
    public static void setScreenshotFlags(Window window, int flags, int mask) {
        window.setFlags(allowScreenshot() ? flags & ~WindowManager.LayoutParams.FLAG_SECURE : flags, mask);
    }
    public static boolean hideReadReceipts() { return enabled("hide_read_receipts"); }
    public static boolean keepUnsent() { return wouldUse("keep_unsent"); }
    public static void noteUnsentIntentHook(boolean active) {
        UnsentDiagnostics.record(appContext, "hook.intent", active ? "enabled" : "disabled");
    }
    public static boolean viewStoriesAnonymously() { return enabled("anonymous_stories"); }
    public static boolean saveAnyStory() { return enabled("save_stories"); }

    /** Story cards read on this phone, as "account:card:time kept". A story is up for a day; each entry lasts two. */
    static final String SEEN_STORIES = "anonymous_seen_stories";
    static final long SEEN_STORY_TTL = 48 * 60 * 60 * 1000L;
    private static final Object SEEN_STORIES_LOCK = new Object();

    /** Messenger's session hashes its user ID, so an account keeps its key across restarts and never shares it. */
    static String storyAccount(Object session) { return session == null ? "-" : Integer.toHexString(session.hashCode()); }

    /**
     * Messenger just marked a story card read on this phone. With the switch on, the server never hears about it, so
     * the card is kept for its account until the next launch can hand it back.
     */
    public static void markStorySeen(Object session, String cardId) {
        if (cardId == null || cardId.isEmpty()) return;
        try {
            if (!enabled("anonymous_stories")) return;
            String card = storyAccount(session) + ":" + cardId + ":";
            long now = System.currentTimeMillis();
            synchronized (SEEN_STORIES_LOCK) {
                Set<String> kept = new HashSet<>();
                for (String entry : preferences.getStringSet(SEEN_STORIES, Collections.emptySet())) {
                    if (!entry.startsWith(card) && !seenStoryExpired(entry, now)) kept.add(entry);
                }
                kept.add(card + now);
                preferences.edit().putStringSet(SEEN_STORIES, kept).apply();
            }
        } catch (RuntimeException error) {
            hookFailed("anonymous_stories", "Can't keep a story marked seen", error);
        }
    }

    /**
     * Each session starts Messenger's set of cards read on this phone empty, and its story lists count a card in it
     * as seen. This puts back the cards the session's account opened while the switch was on.
     */
    public static void seedSeenStories(Set<String> readOnPhone, Object session) {
        if (readOnPhone == null) return;
        try {
            if (!wouldUse("anonymous_stories")) return;
            String account = storyAccount(session) + ":";
            long now = System.currentTimeMillis();
            for (String entry : preferences.getStringSet(SEEN_STORIES, Collections.emptySet())) {
                int time = entry.lastIndexOf(':');
                if (entry.startsWith(account) && time > account.length() && !seenStoryExpired(entry, now)) {
                    readOnPhone.add(entry.substring(account.length(), time));
                }
            }
        } catch (RuntimeException error) {
            hookFailed("anonymous_stories", "Can't restore stories marked seen", error);
        }
    }

    static boolean seenStoryExpired(String entry, long now) {
        int time = entry.lastIndexOf(':');
        if (time < 0) return true;
        try {
            return now - Long.parseLong(entry.substring(time + 1)) > SEEN_STORY_TTL;
        } catch (NumberFormatException error) {
            return true;
        }
    }

    /** The icon stays hidden only while the Menu row that replaces it exists. */
    static boolean drawerIconHidden() {
        SharedPreferences prefs = preferences;
        return installed.contains("menu_row") && prefs != null && prefs.getBoolean("hide_drawer_icon", false);
    }

    private static final String KEPT_UNSENT_KEY = "kept_unsent_ids";
    private static final String UNSENT_MODEL_PARSER_ENTRIES = "unsent_model_parser_entries";
    private static final String UNSENT_MODEL_HOOK_HITS = "unsent_model_hook_hits";
    private static final String UNSENT_MODEL_ID_MISSING = "unsent_model_id_missing";
    private static final String UNSENT_CAPTURE_HITS = "unsent_capture_hits";
    private static final String UNSENT_CAPTURE_SAVED = "unsent_capture_saved";
    private static final String UNSENT_REVOKE_HITS = "unsent_revoke_hits";
    private static final String UNSENT_REVOKE_SAVED = "unsent_revoke_saved";
    private static final String UNSENT_NOTIFICATION_HITS = "unsent_notification_hits";
    private static final String UNSENT_NOTIFICATION_SAVED = "unsent_notification_saved";
    private static final String UNSENT_MEDIA_CALLBACK_HITS = "unsent_media_callback_hits";
    private static final String UNSENT_MEDIA_HITS = "unsent_media_hits";
    private static final String UNSENT_MEDIA_SAVED = "unsent_media_saved";
    private static final long MAX_UNSENT_IMAGE_BYTES = 40L * 1024 * 1024;
    private static final Object UNSENT_MEDIA_ARCHIVE_LOCK = new Object();

    private static synchronized void incrementUnsentCounter(String key) {
        SharedPreferences prefs = preferences;
        if (prefs == null) return;
        prefs.edit().putLong(key, prefs.getLong(key, 0) + 1).apply();
    }

    /** Counts entry into Messenger 581's protobuf parser, before its success/fallback branches. */
    public static void noteMessageModelParserEntry() {
        UnsentDiagnostics.record(appContext, "parser.entry", "entered");
        if (!wouldUse("keep_unsent")) {
            UnsentDiagnostics.record(appContext, "parser.skip", "disabled");
            return;
        }
        incrementUnsentCounter(UNSENT_MODEL_PARSER_ENTRIES);
    }

    static long unsentModelParserEntries() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_MODEL_PARSER_ENTRIES, 0);
    }

    static long unsentModelHookHits() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_MODEL_HOOK_HITS, 0);
    }

    static long unsentModelIdMissing() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_MODEL_ID_MISSING, 0);
    }

    static long unsentCaptureHits() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_CAPTURE_HITS, 0);
    }

    static long unsentCaptureSaved() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_CAPTURE_SAVED, 0);
    }

    static long unsentRevokeHits() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_REVOKE_HITS, 0);
    }

    static long unsentRevokeSaved() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_REVOKE_SAVED, 0);
    }

    static long unsentNotificationHits() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_NOTIFICATION_HITS, 0);
    }

    static long unsentNotificationSaved() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_NOTIFICATION_SAVED, 0);
    }

    static long unsentMediaCallbackHits() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_MEDIA_CALLBACK_HITS, 0);
    }

    static long unsentMediaHits() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_MEDIA_HITS, 0);
    }

    static long unsentMediaSaved() {
        SharedPreferences prefs = preferences;
        return prefs == null ? 0 : prefs.getLong(UNSENT_MEDIA_SAVED, 0);
    }

    private static String notificationText(Bundle extras, String... keys) {
        for (String key : keys) {
            CharSequence value = extras.getCharSequence(key);
            if (value != null) {
                String text = value.toString().trim();
                if (!text.isEmpty()) return text;
            }
        }
        return null;
    }

    private static String notificationSender(Bundle message, String fallback) {
        CharSequence sender = message.getCharSequence("sender");
        if (sender != null && sender.length() > 0) return sender.toString();
        Object person = message.get("sender_person");
        if (person instanceof Person) {
            CharSequence name = ((Person) person).getName();
            if (name != null && name.length() > 0) return name.toString();
        }
        return fallback == null ? "" : fallback;
    }

    private static int captureNotificationMessages(AntiUnsendStore store, Bundle extras,
            String key, String fallbackTitle, long fallbackTime) {
        android.os.Parcelable[] raw = extras.getParcelableArray(key);
        if (raw == null || raw.length == 0) return 0;
        int saved = 0;
        for (android.os.Parcelable value : raw) {
            if (!(value instanceof Bundle)) continue;
            Bundle message = (Bundle) value;
            CharSequence body = message.getCharSequence("text");
            if (body == null || body.length() == 0) continue;
            long time = message.getLong("time", fallbackTime);
            if (store.captureNotification(
                    notificationSender(message, fallbackTitle), body.toString(), time)) saved++;
        }
        return saved;
    }

    /**
     * Snapshot plaintext already placed in a Messenger Notification immediately before the stock
     * NotificationManager.notify call. This is a fallback archive only: Android notification
     * bundles do not reliably expose Messenger message IDs, so these rows are never marked unsent.
     */
    public static void captureNotification(Notification notification) {
        UnsentDiagnostics.record(appContext, "notification.callback", notification == null ? "null" : "entered");
        if (notification == null) return;
        if (!wouldUse("keep_unsent")) {
            UnsentDiagnostics.record(appContext, "notification.skip", "disabled");
            return;
        }
        incrementUnsentCounter(UNSENT_NOTIFICATION_HITS);
        Context context = appContext;
        if (context == null) {
            UnsentDiagnostics.record(appContext, "notification.skip", "no_context");
            return;
        }
        try {
            Bundle extras = notification.extras;
            if (extras == null) {
                UnsentDiagnostics.record(appContext, "notification.skip", "no_extras");
                return;
            }
            String title = notificationText(extras,
                "android.conversationTitle", "android.title.big", "android.title",
                "android.hiddenConversationTitle");
            long fallbackTime = notification.when > 0 ? notification.when : System.currentTimeMillis();
            AntiUnsendStore store = AntiUnsendStore.get(context);
            int saved = captureNotificationMessages(store, extras, "android.messages", title, fallbackTime);
            saved += captureNotificationMessages(store, extras, "android.messages.historic", title, fallbackTime);
            if (saved == 0) {
                String body = notificationText(extras,
                    "android.bigText", "android.text", "android.summaryText",
                    "android.infoText", "android.subText");
                if (body == null) {
                    CharSequence[] lines = extras.getCharSequenceArray("android.textLines");
                    if (lines != null) for (int i = lines.length - 1; i >= 0 && body == null; i--) {
                        if (lines[i] != null && lines[i].length() > 0) body = lines[i].toString();
                    }
                }
                if (body != null && title != null && !title.isEmpty() &&
                        store.captureNotification(title, body, fallbackTime)) saved = 1;
            }
            for (int i = 0; i < saved; i++) incrementUnsentCounter(UNSENT_NOTIFICATION_SAVED);
            UnsentDiagnostics.record(appContext, "notification.save", saved > 0 ? "saved" : "not_saved");
            if (saved == 0) UnsentDiagnostics.record(appContext, "notification.skip", "no_payload");
        } catch (RuntimeException error) {
            UnsentDiagnostics.record(appContext, "notification.save", "db_error");
            hookFailedPrivately("keep_unsent", "Can't cache notification text", error);
        }
    }

    /**
     * Capture a plaintext snapshot as soon as a verified Messenger receive/persist hook can provide one.
     * Phase 1 exposes this bridge without guessing an obfuscated hook. It is intentionally a no-op while
     * Keep unsent messages is off, paused or in safe mode.
     */
    public static void captureIncomingMessage(String messageId, String threadId, String senderId,
            String text, long messageTimestamp) {
        UnsentDiagnostics.record(appContext, "capture.callback", "entered");
        if (messageId == null || messageId.isEmpty()) {
            UnsentDiagnostics.record(appContext, "capture.skip", "missing_id");
            return;
        }
        if (!wouldUse("keep_unsent")) {
            UnsentDiagnostics.record(appContext, "capture.skip", "disabled");
            return;
        }
        incrementUnsentCounter(UNSENT_CAPTURE_HITS);
        Context context = appContext;
        if (context == null) {
            UnsentDiagnostics.record(appContext, "capture.save", "no_context");
            return;
        }
        try {
            AntiUnsendStore store = AntiUnsendStore.get(context);
            long now = System.currentTimeMillis();
            store.captureMessageMetadata(messageId, threadId, senderId, messageTimestamp, now);
            if (text != null && !text.isEmpty()) {
                store.captureText(messageId, threadId, senderId, text, messageTimestamp, now);
            }
            incrementUnsentCounter(UNSENT_CAPTURE_SAVED);
            UnsentDiagnostics.record(appContext, "capture.save", "ok");
        } catch (RuntimeException error) {
            UnsentDiagnostics.record(appContext, "capture.save", "db_error");
            hookFailedPrivately("keep_unsent", "Can't cache incoming message metadata", error);
        }
    }

    /**
     * Copy an image that Messenger has already downloaded into this app's private storage.
     * The caller must be a verified post-download callback; this method never fetches a URL.
     */
    private static final int MESSAGE_MODEL_TEXT = 0x0036452d;
    private static final int MESSAGE_MODEL_IS_UNSENT = 0xe9939666;
    private static final int MESSAGE_MODEL_UNSENT_TIMESTAMP = 0x8695313d;
    private static final int MESSAGE_MODEL_DIRECT_PATH = 0xbb595234;
    private static final int MESSAGE_MODEL_DIRECT_PATH_MIME = 0x3b60c01a;
    private static final int MESSAGE_MODEL_MEDIA_KEY_TIMESTAMP = 0xf3017894;

    private static Object invokeModelGetter(Object target, String name) {
        if (target == null) return null;
        Class<?> owner = target.getClass();
        while (owner != null) {
            try {
                java.lang.reflect.Method method = owner.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                owner = owner.getSuperclass();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object readMessageModelValue(Object wrapper, String getter, int key) {
        if (wrapper == null) return null;
        try {
            Object model = readDeclaredField(wrapper, "A00");
            if (model == null) return null;
            Class<?> owner = model.getClass();
            while (owner != null) {
                try {
                    java.lang.reflect.Method method = owner.getDeclaredMethod(getter, int.class);
                    method.setAccessible(true);
                    return method.invoke(model, key);
                } catch (NoSuchMethodException ignored) {
                    owner = owner.getSuperclass();
                }
            }
        } catch (Exception ignored) {
            // A model field can be absent on partial or non-image messages.
        }
        return null;
    }

    private static String messageModelString(Object target, String getter) {
        Object value = invokeModelGetter(target, getter);
        return value instanceof String ? (String) value : null;
    }

    private static long messageModelLong(Object target, String getter) {
        Object value = invokeModelGetter(target, getter);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return Long.parseLong((String) value); }
            catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    /**
     * Snapshot the verified Messenger 581 message-model object without changing Messenger's own
     * state. The model exposes message_id/thread_key/is_unsent and its nested image metadata.
     */
    public static void noteMessenger581MaterializerEntry() {
        UnsentDiagnostics.record(appContext, "materializer.entry", "entered");
    }

    public static void captureIncomingQpl(Object qpl) {
        UnsentDiagnostics.record(appContext, "model.callback", "entered");
        UnsentDiagnostics.recordObjectShape(appContext, "qpl", safeObjectShape(qpl));
        if (!wouldUse("keep_unsent")) {
            UnsentDiagnostics.record(appContext, "model.skip", "disabled");
            return;
        }
        incrementUnsentCounter(UNSENT_MODEL_HOOK_HITS);
        if (qpl == null) {
            UnsentDiagnostics.record(appContext, "model.skip", "null");
            return;
        }
        String messageId = messageModelString(qpl, "A0I");
        if (messageId == null || messageId.isEmpty()) {
            incrementUnsentCounter(UNSENT_MODEL_ID_MISSING);
            UnsentDiagnostics.record(appContext, "model.skip", "missing_id");
            return;
        }
        String threadKey = messageModelString(qpl, "A0L");
        String senderId = messageModelString(qpl, "A0K");
        long messageTimestamp = messageModelLong(qpl, "A0M");
        Object content = invokeModelGetter(qpl, "A0B");
        UnsentDiagnostics.recordObjectShape(appContext, "content", safeObjectShape(content));
        Object messageContent = invokeModelGetter(content, "A01");
        UnsentDiagnostics.recordObjectShape(appContext, "message_content", safeObjectShape(messageContent));
        Object textValue = readMessageModelValue(messageContent, "BOa", MESSAGE_MODEL_TEXT);
        String body = textValue instanceof String ? (String) textValue : null;
        captureIncomingMessage(messageId, threadKey, senderId, body, messageTimestamp);

        Context context = appContext;
        int linkedImages = 0;
        if (context != null && messageContent != null) {
            try {
                Object imageContainer = invokeModelGetter(messageContent, "A03");
                UnsentDiagnostics.recordObjectShape(appContext, "image_container", safeObjectShape(imageContainer));
                Object imageRows = invokeModelGetter(imageContainer, "A00");
                if (imageRows instanceof Iterable<?>) {
                    AntiUnsendStore store = AntiUnsendStore.get(context);
                    long now = System.currentTimeMillis();
                    for (Object image : (Iterable<?>) imageRows) {
                        UnsentDiagnostics.recordObjectShape(appContext, "image_item", safeObjectShape(image));
                        Object rawDirectPath = readMessageModelValue(image, "BOa", MESSAGE_MODEL_DIRECT_PATH);
                        Object rawMimeType = readMessageModelValue(image, "BOa", MESSAGE_MODEL_DIRECT_PATH_MIME);
                        Object rawTimestamp = readMessageModelValue(image, "Akr", MESSAGE_MODEL_MEDIA_KEY_TIMESTAMP);
                        if (!(rawDirectPath instanceof String) || ((String) rawDirectPath).isEmpty() ||
                                !(rawMimeType instanceof String) ||
                                !((String) rawMimeType).toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                            UnsentDiagnostics.record(appContext, "model.skip", "metadata_type_mismatch");
                            continue;
                        }
                        long mediaKeyTimestamp = rawTimestamp instanceof Number
                            ? ((Number) rawTimestamp).longValue() : 0;
                        store.captureMediaMetadataForMessage((String) rawDirectPath, messageId, threadKey,
                            mediaKeyTimestamp, (String) rawMimeType, now);
                        linkedImages++;
                    }
                }
            } catch (Exception error) {
                UnsentDiagnostics.record(appContext, "model.skip", "db_error");
                hookFailedPrivately("keep_unsent", "Can't link image metadata to the message", error);
            }
        } else {
            UnsentDiagnostics.record(appContext, "model.skip", "no_context");
        }
        UnsentDiagnostics.record(appContext, "model.media_metadata", linkedImages > 0 ? "linked" : "no_images");
        Object unsentValue = readMessageModelValue(qpl, "Akp", MESSAGE_MODEL_IS_UNSENT);
        UnsentDiagnostics.record(appContext, "model.unsent_flag", Boolean.TRUE.equals(unsentValue) ? "true" : "false");
        if (Boolean.TRUE.equals(unsentValue) && !isKeptUnsent(messageId)) {
            Object rawUnsentAt = readMessageModelValue(qpl, "Akr", MESSAGE_MODEL_UNSENT_TIMESTAMP);
            long unsentAt = rawUnsentAt instanceof Number ? ((Number) rawUnsentAt).longValue() : 0;
            recordUnsent(messageId, unsentAt);
        }
        UnsentDiagnostics.record(appContext, "model.callback", "ok");
    }

    /**
     * Messenger 581 download callback: p0 is SUm (metadata in A07), p1 is the result object.
     * Its Uri is used only to confirm success; the filesystem File is resolved through the same
     * A0F(directPath) helper Messenger itself uses in this callback.
     */
    public static void captureDownloadedImageFromCallback(Object downloader, Object result) {
        UnsentDiagnostics.record(appContext, "media.callback", "entered");
        if (!wouldUse("keep_unsent")) {
            UnsentDiagnostics.record(appContext, "media.skip", "disabled");
            return;
        }
        incrementUnsentCounter(UNSENT_MEDIA_CALLBACK_HITS);
        if (downloader == null || result == null) {
            UnsentDiagnostics.record(appContext, "media.skip", "missing_uri");
            return;
        }
        try {
            Object resultUri = readDeclaredField(result, "A00");
            Object resultError = readDeclaredField(result, "A01");
            if (!(resultUri instanceof Uri) || resultError != null) {
                UnsentDiagnostics.record(appContext, "media.skip", resultError != null ? "failed" : "missing_uri");
                return;
            }
            String localPath = ((Uri) resultUri).getPath();
            if (localPath == null || localPath.isEmpty()) {
                UnsentDiagnostics.record(appContext, "media.skip", "missing_file");
                return;
            }
            Object metadata = readDeclaredField(downloader, "A07");
            logMediaMetadataShape(downloader, result, metadata);
            if (metadata == null) {
                UnsentDiagnostics.record(appContext, "media.skip", "metadata_container_missing");
                return;
            }
            Object directPath = readDeclaredField(metadata, "A07");
            Object mime = readDeclaredField(metadata, "A06");
            Object timestamp = readDeclaredField(metadata, "A03");
            if (!(directPath instanceof String) || ((String) directPath).isEmpty()) {
                UnsentDiagnostics.record(appContext, "media.skip",
                    directPath == null ? "direct_path_missing" : "metadata_type_mismatch");
                return;
            }
            if (!(mime instanceof String) || ((String) mime).isEmpty()) {
                UnsentDiagnostics.record(appContext, "media.skip",
                    mime == null ? "mime_missing" : "metadata_type_mismatch");
                return;
            }
            if (!(timestamp instanceof Number)) {
                UnsentDiagnostics.record(appContext, "media.skip",
                    timestamp == null ? "timestamp_missing" : "metadata_type_mismatch");
                return;
            }
            ClassLoader loader = downloader.getClass().getClassLoader();
            Class<?> fileHelper = Class.forName("X.001", false, loader);
            java.lang.reflect.Method resolver = fileHelper.getDeclaredMethod("A0F", String.class);
            resolver.setAccessible(true);
            Object resolvedFile = resolver.invoke(null, localPath);
            if (!(resolvedFile instanceof File)) {
                UnsentDiagnostics.record(appContext, "media.skip", "missing_file");
                return;
            }
            captureDownloadedImage((String) directPath, (String) mime,
                ((Number) timestamp).longValue(), ((File) resolvedFile).getAbsolutePath());
        } catch (Exception error) {
            UnsentDiagnostics.record(appContext, "media.save", "db_error");
            hookFailedPrivately("keep_unsent", "Can't inspect completed image download", error);
        }
    }

    private static Object readDeclaredField(Object instance, String name) throws ReflectiveOperationException {
        Class<?> owner = instance.getClass();
        while (owner != null) {
            try {
                java.lang.reflect.Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException ignored) {
                owner = owner.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Privacy-safe reflection summary for runtime diagnostics. It reports class/field/type
     * names and whether a field is null, but never serializes field values, IDs, URLs or text.
     */
    private static String safeObjectShape(Object instance) {
        if (instance == null) return "null";
        StringBuilder out = new StringBuilder(instance.getClass().getName());
        int shown = 0;
        Class<?> owner = instance.getClass();
        while (owner != null && owner != Object.class && shown < 24) {
            for (java.lang.reflect.Field field : owner.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || shown >= 24) continue;
                String state = "unreadable";
                try {
                    field.setAccessible(true);
                    state = field.get(instance) == null ? "null" : "set";
                } catch (Throwable ignored) { }
                out.append(shown == 0 ? "[" : ",")
                    .append(field.getName()).append(":")
                    .append(field.getType().getName()).append("=")
                    .append(state);
                shown++;
            }
            owner = owner.getSuperclass();
        }
        if (shown > 0) out.append("]");
        if (shown == 24) out.append("…");
        return out.toString();
    }

    private static void logMediaMetadataShape(Object downloader, Object result, Object metadata) {
        // Keep only class/field/type/null-state information in the report; never field values.
        UnsentDiagnostics.recordObjectShape(appContext, "downloader", safeObjectShape(downloader));
        UnsentDiagnostics.recordObjectShape(appContext, "result", safeObjectShape(result));
        if (metadata != null) {
            UnsentDiagnostics.recordObjectShape(appContext, "metadata", safeObjectShape(metadata));
        }
    }

    public static void captureDownloadedImage(String directPath, String mimeType,
            long mediaKeyTimestamp, String sourcePath) {
        UnsentDiagnostics.record(appContext, "media.capture", "entered");
        if (!wouldUse("keep_unsent")) {
            UnsentDiagnostics.record(appContext, "media.skip", "disabled");
            return;
        }
        if (directPath == null || directPath.isEmpty()) {
            UnsentDiagnostics.record(appContext, "media.skip", "missing_metadata");
            return;
        }
        if (mimeType == null || !mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            UnsentDiagnostics.record(appContext, "media.skip", "not_image");
            return;
        }
        if (sourcePath == null || sourcePath.isEmpty()) {
            UnsentDiagnostics.record(appContext, "media.skip", "missing_file");
            return;
        }
        incrementUnsentCounter(UNSENT_MEDIA_HITS);
        Context context = appContext;
        if (context == null) {
            UnsentDiagnostics.record(appContext, "media.skip", "no_context");
            return;
        }
        synchronized (UNSENT_MEDIA_ARCHIVE_LOCK) {
            File temporary = null;
            try {
                File source = new File(sourcePath);
                long sourceLength = source.length();
                if (!source.isFile() || !source.canRead() || sourceLength <= 0) {
                    UnsentDiagnostics.record(appContext, "media.skip", "unreadable");
                    return;
                }
                if (sourceLength > MAX_UNSENT_IMAGE_BYTES) {
                    UnsentDiagnostics.record(appContext, "media.skip", "too_large");
                    return;
                }
                if (!hasImageFileSignature(source, mimeType)) {
                    UnsentDiagnostics.record(appContext, "media.skip", "bad_signature");
                    return;
                }
                String key = AntiUnsendStore.mediaKey(directPath, mediaKeyTimestamp);
                if (key == null) {
                    UnsentDiagnostics.record(appContext, "media.skip", "missing_metadata");
                    return;
                }
                AntiUnsendStore store = AntiUnsendStore.get(context);
                store.captureMediaMetadata(directPath, mediaKeyTimestamp, mimeType, System.currentTimeMillis());
                File directory = new File(context.getFilesDir(), "hush_unsent_media");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new java.io.IOException("Can't create private media directory");
                }
                String extension = imageExtension(mimeType);
                File destination = new File(directory, key + "." + extension);
                if (destination.isFile() && destination.length() > 0) {
                    if (hasImageFileSignature(destination, mimeType)) {
                        String destinationPath = destination.getAbsolutePath();
                        boolean alreadyLinked = destinationPath.equals(store.mediaLocalPath(directPath, mediaKeyTimestamp));
                        if (store.saveMediaFile(directPath, mediaKeyTimestamp, destinationPath, System.currentTimeMillis())) {
                            if (!alreadyLinked) incrementUnsentCounter(UNSENT_MEDIA_SAVED);
                            UnsentDiagnostics.record(appContext, "media.save", alreadyLinked ? "duplicate" : "saved");
                        } else {
                            destination.delete();
                            UnsentDiagnostics.record(appContext, "media.save", "not_saved");
                        }
                        return;
                    }
                    if (!destination.delete()) throw new java.io.IOException("Can't replace invalid archived image");
                }
                temporary = new File(directory, key + ".tmp");
                if (temporary.exists() && !temporary.delete()) throw new java.io.IOException("Can't replace temporary image");
                long copied = 0;
                byte[] buffer = new byte[16 * 1024];
                try (FileInputStream input = new FileInputStream(source);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        copied += count;
                        if (copied > MAX_UNSENT_IMAGE_BYTES) throw new java.io.IOException("Image exceeds local archive limit");
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                    output.getFD().sync();
                }
                if (copied != sourceLength || copied == 0) throw new java.io.IOException("Downloaded image changed while being copied");
                if (destination.exists() && !destination.delete()) throw new java.io.IOException("Can't replace archived image");
                if (!temporary.renameTo(destination)) throw new java.io.IOException("Can't move image into private storage");
                temporary = null;
                if (!store.saveMediaFile(directPath, mediaKeyTimestamp, destination.getAbsolutePath(), System.currentTimeMillis())) {
                    destination.delete();
                    UnsentDiagnostics.record(appContext, "media.save", "not_saved");
                    return;
                }
                incrementUnsentCounter(UNSENT_MEDIA_SAVED);
                UnsentDiagnostics.record(appContext, "media.save", "saved");
            } catch (Exception error) {
                if (temporary != null) temporary.delete();
                UnsentDiagnostics.record(appContext, "media.save", "db_error");
                hookFailedPrivately("keep_unsent", "Can't save downloaded image for local history", error);
            }
        }
    }

    /** Basic file-signature guard, not a substitute for Messenger's MIME/security checks. */
    static boolean hasImageFileSignature(File file, String mimeType) throws java.io.IOException {
        if (file == null || !file.isFile() || mimeType == null) return false;
        byte[] signature = new byte[16];
        int bytes = 0;
        try (FileInputStream stream = new FileInputStream(file)) {
            while (bytes < signature.length) {
                int got = stream.read(signature, bytes, signature.length - bytes);
                if (got < 0) break;
                bytes += got;
            }
        }
        if (bytes < 12) return false;
        String mime = mimeType.toLowerCase(java.util.Locale.ROOT);
        if (mime.equals("image/jpeg") || mime.equals("image/jpg")) {
            return (signature[0] & 0xff) == 0xff && (signature[1] & 0xff) == 0xd8 &&
                (signature[2] & 0xff) == 0xff;
        }
        if (mime.equals("image/png")) {
            return (signature[0] & 0xff) == 0x89 &&
                signature[1] == 0x50 && signature[2] == 0x4e && signature[3] == 0x47 &&
                signature[4] == 0x0d && signature[5] == 0x0a &&
                signature[6] == 0x1a && signature[7] == 0x0a;
        }
        if (mime.equals("image/webp")) {
            return signature[0] == 'R' && signature[1] == 'I' &&
                signature[2] == 'F' && signature[3] == 'F' &&
                signature[8] == 'W' && signature[9] == 'E' &&
                signature[10] == 'B' && signature[11] == 'P';
        }
        if (mime.equals("image/gif")) {
            return signature[0] == 'G' && signature[1] == 'I' && signature[2] == 'F' &&
                signature[3] == '8' && (signature[4] == '7' || signature[4] == '9') &&
                signature[5] == 'a';
        }
        if (mime.equals("image/heic") || mime.equals("image/heif") || mime.equals("image/avif")) {
            if (signature[4] != 'f' || signature[5] != 't' ||
                    signature[6] != 'y' || signature[7] != 'p') return false;
            String brand = new String(signature, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (mime.equals("image/avif")) return brand.equals("avif") || brand.equals("avis");
            return brand.equals("heic") || brand.equals("heix") ||
                brand.equals("hevc") || brand.equals("hevx") ||
                brand.equals("mif1") || brand.equals("msf1");
        }
        return false;
    }

    private static String imageExtension(String mimeType) {
        String mime = mimeType.toLowerCase(java.util.Locale.ROOT);
        if (mime.equals("image/jpeg") || mime.equals("image/jpg")) return "jpg";
        if (mime.equals("image/png")) return "png";
        if (mime.equals("image/webp")) return "webp";
        if (mime.equals("image/gif")) return "gif";
        if (mime.equals("image/heic")) return "heic";
        if (mime.equals("image/heif")) return "heif";
        if (mime.equals("image/avif")) return "avif";
        return "img";
    }

    /** Compatibility fallback for older hooks that expose only a message ID and rendered text. */
    public static void captureIncomingMessage(String messageId, String text) {
        captureIncomingMessage(messageId, null, null, text, 0);
    }

    public static synchronized void recordUnsent(String messageId) {
        recordUnsent(messageId, System.currentTimeMillis());
    }

    public static synchronized void recordUnsent(String messageId, long unsentAt) {
        UnsentDiagnostics.record(appContext, "revoke.callback", "entered");
        if (messageId == null || messageId.isEmpty()) {
            UnsentDiagnostics.record(appContext, "revoke.skip", "missing_id");
            return;
        }
        if (!wouldUse("keep_unsent")) {
            UnsentDiagnostics.record(appContext, "revoke.skip", "disabled");
            return;
        }
        long eventTime = unsentAt > 0 ? unsentAt : System.currentTimeMillis();
        incrementUnsentCounter(UNSENT_REVOKE_HITS);
        SharedPreferences prefs = preferences;
        if (prefs == null) {
            UnsentDiagnostics.record(appContext, "revoke.skip", "no_preferences");
            return;
        }
        Set<String> ids = new HashSet<>(prefs.getStringSet(KEPT_UNSENT_KEY, Collections.emptySet()));
        if (ids.add(messageId)) prefs.edit().putStringSet(KEPT_UNSENT_KEY, ids).apply();
        Context context = appContext;
        if (context != null) {
            try {
                AntiUnsendStore.get(context).markUnsent(messageId, eventTime);
                incrementUnsentCounter(UNSENT_REVOKE_SAVED);
                UnsentDiagnostics.record(appContext, "revoke.save", "ok");
            } catch (RuntimeException error) {
                UnsentDiagnostics.record(appContext, "revoke.save", "db_error");
                hookFailedPrivately("keep_unsent", "Can't record unsent message history", error);
            }
        } else {
            UnsentDiagnostics.record(appContext, "revoke.skip", "no_context");
        }
        activeAt.put("keep_unsent", System.currentTimeMillis());
    }

    /** E2EE delete deltas can revoke more than one message in a single parser pass. */
    public static void recordUnsentIds(List<?> messageIds) {
        if (messageIds == null || messageIds.isEmpty() || !wouldUse("keep_unsent")) return;
        for (Object value : messageIds) {
            if (value instanceof String) recordUnsent((String) value);
        }
    }

    public static boolean isKeptUnsent(String messageId) {
        if (messageId == null) return false;
        SharedPreferences prefs = preferences;
        if (prefs == null) return false;
        return prefs.getStringSet(KEPT_UNSENT_KEY, Collections.emptySet()).contains(messageId);
    }

    /** Restore cached text only for a message already observed as unsent. */
    static String cachedUnsentText(String messageId) {
        Context context = appContext;
        if (context == null || messageId == null || messageId.isEmpty()) return null;
        try {
            return AntiUnsendStore.get(context).unsentText(messageId);
        } catch (RuntimeException error) {
            hookFailedPrivately("keep_unsent", "Can't restore cached unsent text", error);
            return null;
        }
    }

    /**
     * Render-path fallback for builds where this getter is the earliest verified plaintext surface.
     * Cache the text for local history, but never alter what Messenger renders.
     */
    public static String labelKeptUnsent(String text, String messageId) {
        if (text == null) {
            UnsentDiagnostics.record(appContext, "hook.render_text", "null");
            return null;
        }
        if (text.isEmpty()) {
            UnsentDiagnostics.record(appContext, "hook.render_text", "empty_text");
            return text;
        }
        boolean active = wouldUse("keep_unsent");
        UnsentDiagnostics.record(appContext, "hook.render_text", active ? "enabled" : "disabled");
        if (active) captureIncomingMessage(messageId, text);
        return text;
    }

    /** History-only mode never changes Messenger's own unsent state. */
    public static boolean suppressUnsent(boolean original, String messageId) {
        UnsentDiagnostics.record(appContext, "hook.delta_unsent", original ? "true" : "false");
        // This is Messenger's own per-message unsent predicate. Observe true values to mark
        // the local archive, but always return the original value so Messenger behaves normally.
        if (original) recordUnsent(messageId);
        return original;
    }

    /** Android's standard color emoji font, used when the phone can't say which font it draws emoji with. */
    static final String NOTO_EMOJI_FONT = "/system/fonts/NotoColorEmoji.ttf";
    /** Null asks Android which font it draws emoji with. Tests point this at a missing file. */
    static String systemEmojiFont;
    static android.graphics.Typeface systemEmoji;
    static boolean systemEmojiMissing;
    /** Which font the emoji typeface came from, for diagnostics and tests. */
    static String systemEmojiSource;
    public static android.graphics.Typeface systemEmojiTypeface() {
        // Checked before enabled(), so a font that failed to load doesn't count as a use.
        if (systemEmojiMissing || !enabled("use_system_emoji")) return null;
        if (systemEmoji != null) return systemEmoji;
        try {
            // Samsung, many other phones and emoji modules draw emoji with a font other than NotoColorEmoji.ttf (#25).
            android.graphics.Typeface shaped = systemEmojiFont == null ? shapedEmojiTypeface() : null;
            if (shaped != null) {
                systemEmoji = shaped;
            } else {
                String path = systemEmojiFont != null ? systemEmojiFont : NOTO_EMOJI_FONT;
                systemEmoji = android.graphics.Typeface.createFromFile(path);
                systemEmojiSource = path;
            }
        } catch (Exception error) {
            // The font file won't appear later, so Messenger's own emoji stay without retrying on every draw.
            systemEmojiMissing = true;
            hookFailed("use_system_emoji", "Can't load the system emoji font", error);
        }
        return systemEmoji;
    }

    /** Default emoji presentation, so Android picks its emoji font rather than a text symbol font. */
    static final String EMOJI_PROBE = "😀";

    /**
     * Android 12 and newer report the font they shape an emoji with, which is the phone's own emoji set. The typeface
     * keeps Android's usual fallback, so flags in a separate font still draw. Null means use the font file path instead.
     */
    static android.graphics.Typeface shapedEmojiTypeface() {
        if (android.os.Build.VERSION.SDK_INT < 31) return null;
        try {
            android.graphics.text.PositionedGlyphs glyphs = android.graphics.text.TextRunShaper.shapeTextRun(
                EMOJI_PROBE, 0, EMOJI_PROBE.length(), 0, EMOJI_PROBE.length(), 0f, 0f, false, new android.graphics.Paint());
            // Glyph 0 is the missing-glyph box: the phone has no emoji font, so Messenger's set should stay.
            if (glyphs.glyphCount() == 0 || glyphs.getGlyphId(0) == 0) return null;
            android.graphics.fonts.Font font = glyphs.getFont(0);
            java.io.File file = font.getFile();
            android.graphics.Typeface typeface;
            if (file != null && file.canRead()) {
                typeface = android.graphics.Typeface.createFromFile(file);
            } else {
                // Updated emoji fonts can live where the app can't open them; the shaped font is already loaded.
                typeface = new android.graphics.Typeface.CustomFallbackBuilder(
                    new android.graphics.fonts.FontFamily.Builder(font).build()).setSystemFallback("sans-serif").build();
            }
            systemEmojiSource = file != null ? file.getPath() : "shaped emoji font";
            return typeface;
        } catch (RuntimeException error) {
            android.util.Log.w("HushMessenger", "Can't find the phone's emoji font, using " + NOTO_EMOJI_FONT, error);
            return null;
        }
    }

    /** Null means return the exact original list. Only typed ad rows are removed. */
    public static List<?> filterInboxAds(List<?> items) {
        if (!enabled("ads") || items == null || items.isEmpty()) return null;
        List<Object> filtered = null;
        for (int index = 0; index < items.size(); index++) {
            Object item = items.get(index);
            boolean ad = false;
            for (Class<?> type = item == null ? null : item.getClass(); type != null; type = type.getSuperclass()) {
                if ("com.facebook.messaging.business.inboxads.common.InboxAdsItem".equals(type.getName())) { ad = true; break; }
            }
            if (ad && filtered == null) filtered = new ArrayList<>(items.subList(0, index));
            if (!ad && filtered != null) filtered.add(item);
        }
        return filtered;
    }

    /** Only the section renderer receives a copy. The captured native snapshot is never changed. */
    public static List<?> filterJoinedCommunityInboxRows(List<?> items, Object callback, Object filter) {
        return filterJoinedCommunityInboxRows(items, () -> isAllChatsFilter(filter) && HostScreens.isMainInboxScope(callback, filter),
            HostScreens::isJoinedCommunityRow);
    }

    // Every chip except Message Requests keeps the INBOX folder, so Channels and Unread would otherwise count as the main list.
    static boolean isAllChatsFilter(Object filter) {
        return filter instanceof Enum && "ALL".equals(((Enum<?>) filter).name());
    }

    static List<?> filterJoinedCommunityInboxRows(List<?> items, java.util.function.BooleanSupplier mainInbox,
            java.util.function.Predicate<Object> joined) {
        if (items == null || items.isEmpty() || !enabled("community_inbox")) return items;
        try {
            if (!mainInbox.getAsBoolean()) return items;
            List<Object> kept = null;
            for (int at = 0; at < items.size(); at++) {
                Object item = items.get(at);
                boolean hide = item != null && joined.test(item);
                if (hide && kept == null) kept = new ArrayList<>(items.subList(0, at));
                if (!hide && kept != null) kept.add(item);
            }
            return kept == null ? items : Collections.unmodifiableList(kept);
        } catch (RuntimeException | LinkageError error) {
            hookFailedPrivately("community_inbox", "Can't filter joined community chats", error);
            return items;
        }
    }

    private static final String AVATAR_TAB_EVENT = "com.facebook.xapp.messaging.composer.avatar.composertab.event.ActivateAvatarSticker";

    /** Null means keep Messenger's sticker keyboard tabs; otherwise the tabs without the avatar tab. */
    public static List<?> filterKeyboardTabs(List<?> tabs) {
        if (tabs == null || tabs.isEmpty() || !enabled("avatar_stickers")) return null;
        List<Object> kept = new ArrayList<>(tabs.size());
        for (Object tab : tabs) if (!opensAvatarTab(tab)) kept.add(tab);
        return kept.size() == tabs.size() ? null : kept;
    }

    /** Builds that fill the tab list inline pass it here before copying it; the list is theirs to change. */
    public static void removeAvatarTabs(Iterable<?> tabs) {
        if (!(tabs instanceof java.util.Collection) || !enabled("avatar_stickers")) return;
        try {
            ((java.util.Collection<?>) tabs).removeIf(Settings::opensAvatarTab);
        } catch (RuntimeException error) {
            hookFailed("avatar_stickers", "Can't filter the sticker keyboard tabs", error);
        }
    }

    // Some builds keep the tab's event directly on the item, others on a config object the item holds.
    static boolean opensAvatarTab(Object tab) {
        return holdsAvatarEvent(tab, 2);
    }

    private static boolean holdsAvatarEvent(Object value, int depth) {
        if (value == null || depth == 0) return false;
        for (java.lang.reflect.Field f : value.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            try {
                f.setAccessible(true);
                Object field = f.get(value);
                if (field == null) continue;
                if (AVATAR_TAB_EVENT.equals(field.getClass().getName())) return true;
                // Only Messenger's own obfuscated classes are walked into, never strings or collections.
                if (field.getClass().getName().startsWith("X.") && holdsAvatarEvent(field, depth - 1)) return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return false;
    }

    /** Keep non-web routes and Messenger's surrounding link handling intact. */
    public static boolean preferExternalBrowser(boolean original, Uri uri) {
        if (uri == null || !enabled("external_browser")) return original;
        String scheme = uri.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || original;
    }

    private static Object legacyDrawerKey;

    /** Replaced by the menu patch with constructors from the validated host build. */
    public static Object legacyDrawerSection(Context context) {
        return null;
    }

    /** Only the independent folder identity survives refreshes, never a context or a row. */
    public static synchronized Object cachedLegacyDrawerKey(Object proposed) {
        if (proposed == null) return null;
        if (legacyDrawerKey == null) legacyDrawerKey = proposed;
        return legacyDrawerKey.getClass() == proposed.getClass() ? legacyDrawerKey : null;
    }

    /** The legacy drawer can omit Settings entirely, so construct a separate native section. */
    @SuppressWarnings("unchecked")
    public static List addLegacyDrawerEntry(Object fragment, List sections) {
        if (fragment == null || sections == null) return sections;
        try {
            java.lang.reflect.Method getContext = fragment.getClass().getMethod("getContext");
            getContext.setAccessible(true);
            Object current = getContext.invoke(fragment);
            if (!(current instanceof Context)) return sections;
            Context context = (Context) current;
            Object section = legacyDrawerSection(context);
            if (section == null) return sections;

            Class<?> sectionClass = section.getClass();
            java.lang.reflect.Field rowsField = sectionClass.getDeclaredField("A06");
            if (rowsField.getType() != List.class) throw new IllegalArgumentException("Drawer rows changed");
            rowsField.setAccessible(true);
            Object rows = rowsField.get(section);
            if (!(rows instanceof List) || ((List) rows).size() != 1)
                throw new IllegalArgumentException("Drawer factory rows changed");
            Object row = ((List) rows).get(0);
            if (row == null) throw new IllegalArgumentException("Drawer factory row missing");
            Class<?> rowClass = row.getClass();
            java.lang.reflect.Field title = rowClass.getDeclaredField("A06");
            java.lang.reflect.Field owner = rowClass.getDeclaredField("A00");
            java.lang.reflect.Field key = rowClass.getDeclaredField("A03");
            java.lang.reflect.Field metadata = rowClass.getDeclaredField("A04");
            if (title.getType() != String.class || owner.getType() != Context.class
                    || !key.getType().getName().endsWith("DrawerFolderKey")
                    || !metadata.getType().getName().endsWith("HeterogeneousMap"))
                throw new IllegalArgumentException("Drawer row fields changed");
            title.setAccessible(true);
            owner.setAccessible(true);
            key.setAccessible(true);
            metadata.setAccessible(true);
            Object ownKey = key.get(row);
            if (!"HushMessenger".equals(title.get(row)) || owner.get(row) != context
                    || ownKey == null || !ownKey.getClass().getName().endsWith("SettingsFolderKey")
                    || !key.getType().isInstance(ownKey) || metadata.get(row) == null
                    || !metadata.getType().isInstance(metadata.get(row)))
                throw new IllegalArgumentException("Drawer factory values changed");

            boolean present = false;
            // Validate the complete input even when an earlier section already contains our row.
            for (Object originalSection : sections) {
                if (originalSection == null || originalSection.getClass() != sectionClass)
                    throw new IllegalArgumentException("Drawer section changed");
                Object originalRows = rowsField.get(originalSection);
                if (!(originalRows instanceof List)) throw new IllegalArgumentException("Drawer rows missing");
                for (Object originalRow : (List) originalRows) {
                    if (originalRow == null || originalRow.getClass() != rowClass)
                        throw new IllegalArgumentException("Drawer row changed");
                    Object originalKey = key.get(originalRow);
                    Object originalMetadata = metadata.get(originalRow);
                    Object originalTitle = title.get(originalRow);
                    if (originalKey == null || !key.getType().isInstance(originalKey)
                            || originalTitle == null || !(originalTitle instanceof String)
                            || (originalMetadata != null && !metadata.getType().isInstance(originalMetadata)))
                        throw new IllegalArgumentException("Drawer row values changed");
                    if ("HushMessenger".equals(originalTitle) && originalKey.getClass() == ownKey.getClass())
                        present = true;
                }
            }
            if (present) return sections;
            ArrayList result = new ArrayList(sections);
            result.add(section);
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            hookFailedPrivately("menu_row", "Legacy drawer entry unavailable", error);
            return sections;
        }
    }

    /** Puts a HushMessenger copy of the Menu tab's Settings folder row right after it (one title String per row). */
    @SuppressWarnings("unchecked")
    public static void addMenuSettingsEntry(ArrayList list) {
        try {
            if (list == null || list.isEmpty()) return;
            // Messenger 581 builds its QR code row into the same list, so find Settings by its folder key.
            int at = -1;
            for (int i = 0; i < list.size() && at < 0; i++) if (holdsSettingsKey(list.get(i))) at = i;
            if (at < 0) return;
            Object original = list.get(at);
            Object clone = shallowClone(original);
            if (clone == null) return;
            java.lang.reflect.Field title = null;
            for (java.lang.reflect.Field f : original.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getType() == String.class) {
                    if (title != null) return;
                    title = f;
                } else if (f.getType() == Integer.class) {
                    f.set(clone, null);
                } else if (f.getType().getName().endsWith("HeterogeneousMap")) {
                    // Folder metadata, such as Settings' unseen badge count, belongs to the Settings row.
                    Object metadata = f.get(original);
                    if (metadata == null) continue;
                    Object copy = shallowClone(metadata);
                    if (copy == null) return;
                    for (java.lang.reflect.Field m : copy.getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) || m.getType() != Map.class) continue;
                        m.setAccessible(true);
                        m.set(copy, new java.util.HashMap<>());
                    }
                    f.set(clone, copy);
                } else if (f.getType().getName().endsWith("DrawerFolderKey")) {
                    // Folder keys compare by identity, so a copy keeps this row from acting as Settings.
                    Object key = f.get(original);
                    Object copy = key == null ? null : shallowClone(key);
                    if (copy == null) return;
                    f.set(clone, copy);
                }
            }
            if (title == null) return;
            title.set(clone, "HushMessenger");
            list.add(at + 1, clone);
        } catch (Exception e) {
            hookFailed("menu_row", "addMenuSettingsEntry failed", e);
        }
    }

    private static boolean holdsSettingsKey(Object row) throws IllegalAccessException {
        if (row == null) return false;
        for (java.lang.reflect.Field f : row.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object value = f.get(row);
            if (value != null && value.getClass().getName().endsWith("SettingsFolderKey")) return true;
        }
        return false;
    }

    /** Opens settings for the HushMessenger folder row and returns null; other rows come back unchanged. */
    public static Object drawerFolderClicked(Object item) {
        if (item == null) return null;
        boolean recognized = false;
        Context context = null;
        try {
            boolean titled = false, settingsKey = false;
            for (java.lang.reflect.Field f : item.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object value = f.get(item);
                if (value instanceof Context) context = (Context) value;
                else if ("HushMessenger".equals(value)) titled = true;
                // Only the copy of the Settings row carries a Settings folder key with this title.
                else if (value != null && value.getClass().getName().endsWith("SettingsFolderKey")) settingsKey = true;
            }
            if (!titled || !settingsKey || context == null) return item;
            recognized = true;
            HostScreens.open(context, HostScreens.SETTINGS);
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            hookFailedPrivately("menu_row", "Opening the drawer entry failed", error);
            if (recognized) try {
                android.widget.Toast.makeText(context, new SettingsText(context).get("settings_open_failed"),
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (RuntimeException | LinkageError feedbackError) {
                hookFailedPrivately("menu_row", "Drawer entry feedback unavailable", feedbackError);
            }
            // Our independent row has no native dispatcher, including when Android rejects the launch.
            return recognized ? null : item;
        }
    }

    @SuppressWarnings("unchecked")
    public static java.util.List addMenuDrawerEntry(java.util.List list) {
        try {
            if (list == null || list.size() < 2) return list;
            for (Object item : list) {
                try {
                    java.lang.reflect.Field f = item.getClass().getDeclaredField("A05");
                    f.setAccessible(true);
                    if ("HushMessenger".equals(f.get(item))) return list;
                } catch (NoSuchFieldException ignored) {}
            }
            Object template = list.get(0);
            Object clone = shallowClone(template);
            if (clone == null) return list;
            java.lang.reflect.Field label = clone.getClass().getDeclaredField("A05");
            if (label.getType() != String.class) return list;
            label.setAccessible(true);
            label.set(clone, "HushMessenger");
            try {
                java.lang.reflect.Field longPress = clone.getClass().getDeclaredField("A03");
                longPress.setAccessible(true);
                longPress.set(clone, null);
            } catch (NoSuchFieldException ignored) {}
            java.util.ArrayList result = new java.util.ArrayList(list);
            result.add(clone);
            return result;
        } catch (Exception e) {
            hookFailed("menu_row", "addMenuDrawerEntry failed", e);
            return list;
        }
    }

    public static void handleMenuItemBound(Object viewHolder) {
        try {
            java.lang.reflect.Field textField = viewHolder.getClass().getDeclaredField("A06");
            textField.setAccessible(true);
            Object tv = textField.get(viewHolder);
            if (!(tv instanceof android.widget.TextView)) return;
            CharSequence text = ((android.widget.TextView) tv).getText();
            if (!"HushMessenger".equals(text != null ? text.toString() : null)) return;
            java.lang.reflect.Field viewField = null;
            for (Class<?> c = viewHolder.getClass(); c != null; c = c.getSuperclass()) {
                try { viewField = c.getDeclaredField("A0I"); break; }
                catch (NoSuchFieldException ignored) {}
            }
            if (viewField == null) return;
            viewField.setAccessible(true);
            android.view.View itemView = (android.view.View) viewField.get(viewHolder);
            if (itemView == null) return;
            itemView.setOnClickListener(v -> {
                try {
                    HostScreens.open(v.getContext(), HostScreens.SETTINGS);
                } catch (RuntimeException e) {
                    hookFailed("menu_row", "Opening settings failed", e);
                }
            });
        } catch (Exception e) {
            hookFailed("menu_row", "handleMenuItemBound failed", e);
        }
    }

    private static java.lang.reflect.Method allocateMethod;
    private static Object unsafeInstance;

    private static Object shallowClone(Object src) {
        try {
            if (allocateMethod == null) {
                Class<?> u = Class.forName("sun.misc.Unsafe");
                java.lang.reflect.Field f = u.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                unsafeInstance = f.get(null);
                allocateMethod = u.getMethod("allocateInstance", Class.class);
            }
            Object dst = allocateMethod.invoke(unsafeInstance, src.getClass());
            for (Class<?> c = src.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    f.set(dst, f.get(src));
                }
            }
            return dst;
        } catch (Exception e) {
            hookFailed("menu_row", "shallowClone failed", e);
            return null;
        }
    }

    private Settings() { }
}
