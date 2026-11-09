package app.hushmessenger.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compact, searchable local history for messages Messenger reports as unsent. */
public final class UnsentHistoryActivity extends Activity {
    private static final int ALL = 0;
    private static final int MESSAGES = 1;
    private static final int IMAGES = 2;
    private static final int NOTIFICATIONS = 3;

    /** Breathing room between the status-bar safe area and the history header. */
    static final int HISTORY_TOP_GAP_DP = 24;

    static int historyContentTopPadding(int safeInsetTop, float density) {
        return Math.max(0, safeInsetTop) + Math.round(HISTORY_TOP_GAP_DP * Math.max(0f, density));
    }

    private SettingsText text;
    private SettingsUi ui;
    private LinearLayout list;
    private LinearLayout filterRow;
    private TextView status;
    private TextView summaryMessages;
    private TextView summaryImages;
    private TextView summaryNotifications;
    private TextView diagnostics;
    private TextView diagnosticsToggle;
    private EditText search;
    private int activeFilter = ALL;
    private boolean diagnosticsExpanded;
    private List<AntiUnsendStore.Entry> entries = new ArrayList<>();
    private List<AntiUnsendStore.NotificationEntry> notificationEntries = new ArrayList<>();
    private final Map<String, List<AntiUnsendStore.MediaEntry>> mediaByMessage = new HashMap<>();
    private int imageCount;

    @Override public void onCreate(Bundle state) {
        HostScreens.start(this);
        Settings.initialize(this);
        boolean light = Settings.preferences.getBoolean("light", false);
        setTheme(light ? android.R.style.Theme_Material_Light_NoActionBar : android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        text = new SettingsText(this);
        ui = new SettingsUi(this, light);
        setTitle(text.get("unsent_history_title"));

        getWindow().setStatusBarColor(ui.background);
        getWindow().setNavigationBarColor(ui.background);
        getWindow().getDecorView().setSystemUiVisibility(light
            ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        if (Build.VERSION.SDK_INT >= 30) {
            // targetSdk 36 enforces edge-to-edge. Request raw insets and apply them to our root.
            getWindow().setDecorFitsSystemWindows(false);
        }

        LinearLayout root = ui.column();
        root.setBackgroundColor(ui.background);
        root.setLayoutDirection(text.layoutDirection());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            // Use typed insets on Android 11+; legacy systemWindowInsetTop can be zero in
            // edge-to-edge windows on Android 15/16 even while the status bar overlays content.
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left;
                top = safe.top;
                right = safe.right;
                bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    top = Math.max(top, cutout.getSafeInsetTop());
                    right = Math.max(right, cutout.getSafeInsetRight());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
            }
            view.setPadding(ui.dp(16) + left, historyContentTopPadding(top, getResources().getDisplayMetrics().density),
                ui.dp(16) + right, ui.dp(16) + bottom);
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED
                : insets.consumeSystemWindowInsets().consumeDisplayCutout();
        });

        buildHeader(root);
        buildSummary(root);
        buildFilters(root);
        buildSearch(root);
        buildDiagnostics(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        list = ui.column();
        list.setTag("unsent_history_list");
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = ui.dp(8);
        root.addView(scroll, scrollParams);

        setContentView(root);
        // The listener is attached before the view enters the window; request a fresh dispatch
        // after attachment so the status-bar inset is applied on API 35/36 as well.
        root.requestApplyInsets();
    }

    private void buildHeader(LinearLayout root) {
        LinearLayout header = ui.row();
        Button back = ui.button("‹");
        back.setTag("unsent_history_back");
        back.setTextSize(24);
        back.setContentDescription(text.get("unsent_history_back"));
        back.setMinWidth(ui.dp(42));
        back.setMinimumWidth(ui.dp(42));
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));

        LinearLayout titles = ui.column();
        TextView title = ui.text(text.get("unsent_history_title"), ui.largeText ? 19 : 21, ui.text, true);
        title.setMaxLines(1);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setAccessibilityHeading(true);
        ui.add(titles, title, 0);
        ui.add(titles, ui.text(text.get("unsent_history_subtitle"), 12, ui.muted, false), 4);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(ui.dp(8));
        header.addView(titles, titleParams);

        Button clear = ui.button(text.get("unsent_history_clear_short"));
        clear.setTag("unsent_history_clear");
        clear.setTextSize(13);
        clear.setMinHeight(ui.dp(40));
        clear.setMinimumHeight(ui.dp(40));
        clear.setOnClickListener(view -> confirmClear());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-2, ui.dp(40));
        clearParams.setMarginStart(ui.dp(6));
        header.addView(clear, clearParams);
        ui.add(root, header, 0);

        status = ui.text("", 11, ui.accent, true);
        status.setTag("unsent_history_status");
        status.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
        status.setBackground(ui.shape(ui.infoSurface, ui.infoBorder, 20));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        statusParams.topMargin = ui.dp(10);
        root.addView(status, statusParams);
    }

    private void buildSummary(LinearLayout root) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        summaryMessages = addSummaryCard(row, "0", text.get("unsent_history_summary_messages"));
        summaryImages = addSummaryCard(row, "0", text.get("unsent_history_summary_images"));
        summaryNotifications = addSummaryCard(row, "0", text.get("unsent_history_summary_notifications"));
        ui.add(root, row, 12);
    }

    private TextView addSummaryCard(LinearLayout parent, String value, String label) {
        LinearLayout card = ui.column();
        card.setPadding(ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10));
        card.setBackground(ui.shape(ui.surface, ui.line, 14));
        TextView number = ui.text(value, 20, ui.text, true);
        card.addView(number);
        TextView caption = ui.text(label, 11, ui.muted, false);
        caption.setMaxLines(2);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-1, -2);
        captionParams.topMargin = ui.dp(3);
        card.addView(caption, captionParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMarginEnd(ui.dp(6));
        parent.addView(card, params);
        return number;
    }

    private void buildFilters(LinearLayout root) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        filterRow = ui.row();
        scroll.addView(filterRow, new HorizontalScrollView.LayoutParams(-2, -2));
        ui.add(root, scroll, 12);
    }

    private void buildSearch(LinearLayout root) {
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(ui.text);
        search.setHintTextColor(ui.muted);
        search.setHint(text.get("unsent_history_search_hint"));
        search.setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9));
        search.setBackground(ui.shape(ui.surface, ui.line, 12));
        search.setCompoundDrawablePadding(ui.dp(8));
        ui.add(root, search, 10);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderList(); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void buildDiagnostics(LinearLayout root) {
        diagnosticsToggle = ui.text(text.get("unsent_history_diagnostics_toggle") + "  ⌄", 12, ui.muted, true);
        diagnosticsToggle.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(4));
        diagnosticsToggle.setTag("unsent_history_diagnostics_toggle");
        diagnosticsToggle.setOnClickListener(view -> {
            diagnosticsExpanded = !diagnosticsExpanded;
            diagnostics.setVisibility(diagnosticsExpanded ? View.VISIBLE : View.GONE);
            diagnosticsToggle.setText(text.get("unsent_history_diagnostics_toggle") +
                (diagnosticsExpanded ? "  ⌃" : "  ⌄"));
        });
        ui.add(root, diagnosticsToggle, 6);
        diagnostics = ui.text("", 11, ui.muted, false);
        diagnostics.setTag("unsent_history_diagnostics");
        diagnostics.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        diagnostics.setBackground(ui.shape(ui.infoSurface, ui.infoBorder, 10));
        diagnostics.setVisibility(View.GONE);
        ui.add(root, diagnostics, 4);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        try {
            AntiUnsendStore store = AntiUnsendStore.get(this);
            entries = store.listUnsent(500);
            notificationEntries = store.listNotificationSnapshots(30);
            mediaByMessage.clear();
            imageCount = 0;
            for (AntiUnsendStore.Entry entry : entries) {
                List<AntiUnsendStore.MediaEntry> images = store.listUnsentMedia(entry.messageId);
                List<AntiUnsendStore.MediaEntry> readable = new ArrayList<>();
                if (images != null) for (AntiUnsendStore.MediaEntry image : images) {
                    File file = new File(image.localPath);
                    if (file.isFile() && file.canRead() && file.length() > 0) readable.add(image);
                }
                if (!readable.isEmpty()) {
                    mediaByMessage.put(entry.messageId, readable);
                    imageCount += readable.size();
                }
            }
            updateSummary();
            updateFilters();
            renderList();
            updateDiagnostics();
        } catch (RuntimeException error) {
            Settings.hookFailedPrivately("keep_unsent", "Can't read unsent history", error);
            list.removeAllViews();
            addEmptyState(text.get("unsent_history_read_failed"), text.get("unsent_history_read_failed_help"));
        }
    }

    private void updateSummary() {
        summaryMessages.setText(Integer.toString(entries.size()));
        summaryImages.setText(Integer.toString(imageCount));
        summaryNotifications.setText(Integer.toString(notificationEntries.size()));
        boolean active = Settings.keepUnsent();
        boolean modelSeen = Settings.unsentModelHookHits() > 0 || Settings.unsentCaptureHits() > 0;
        boolean parserEntered = Settings.unsentModelParserEntries() > 0;
        String state = !active ? "unsent_history_inactive" :
            modelSeen ? "unsent_history_active" :
            parserEntered ? "unsent_history_active_parser_waiting" : "unsent_history_active_waiting";
        boolean healthy = active && modelSeen;
        status.setText(text.get(state));
        status.setTextColor(healthy ? (ui.background == 0xff000000 ? 0xff83d6a8 : 0xff18794e) : ui.warning);
        status.setBackground(ui.shape(healthy
            ? (ui.background == 0xff000000 ? 0xff123325 : 0xffe5f5ec)
            : ui.warningSurface, ui.line, 20));
    }

    private void updateFilters() {
        filterRow.removeAllViews();
        addFilterChip(text.get("unsent_history_filter_all"), ALL);
        addFilterChip(text.get("unsent_history_filter_messages"), MESSAGES);
        addFilterChip(text.get("unsent_history_filter_images"), IMAGES);
        addFilterChip(text.get("unsent_history_filter_notifications"), NOTIFICATIONS);
    }

    private void addFilterChip(String label, int filter) {
        TextView chip = ui.text(label, 13, activeFilter == filter ? ui.selectedText : ui.muted, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9));
        chip.setBackground(ui.shape(activeFilter == filter ? ui.accent : ui.surface,
            activeFilter == filter ? ui.accent : ui.line, 22));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setTag("unsent_history_filter_" + filter);
        chip.setOnClickListener(view -> {
            activeFilter = filter;
            updateFilters();
            renderList();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginEnd(ui.dp(7));
        filterRow.addView(chip, params);
    }

    private void updateDiagnostics() {
        diagnostics.setText(
            text.get("unsent_history_parser_diagnostics",
                Settings.unsentModelParserEntries(), Settings.unsentModelHookHits()) + "\n" +
            text.get("unsent_history_model_diagnostics",
                Settings.unsentModelHookHits(), Settings.unsentModelIdMissing()) + "\n" +
            text.get("unsent_history_diagnostics",
                Settings.unsentCaptureHits(), Settings.unsentCaptureSaved(),
                Settings.unsentRevokeHits(), Settings.unsentRevokeSaved()) + "\n" +
            text.get("unsent_history_notification_diagnostics",
                Settings.unsentNotificationHits(), Settings.unsentNotificationSaved()) + "\n" +
            text.get("unsent_history_callback_diagnostics",
                Settings.unsentMediaCallbackHits(), Settings.unsentMediaHits(), Settings.unsentMediaSaved()));
    }

    private void renderList() {
        if (list == null) return;
        list.removeAllViews();
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

        if (activeFilter == ALL || activeFilter == MESSAGES || activeFilter == IMAGES) {
            List<AntiUnsendStore.Entry> visible = new ArrayList<>();
            for (AntiUnsendStore.Entry entry : entries) {
                List<AntiUnsendStore.MediaEntry> images = mediaByMessage.get(entry.messageId);
                if (activeFilter == IMAGES && (images == null || images.isEmpty())) continue;
                if (matches(query, entry.senderId, entry.text)) visible.add(entry);
            }
            if (activeFilter == ALL || activeFilter == MESSAGES || activeFilter == IMAGES) {
                String heading = activeFilter == IMAGES
                    ? text.get("unsent_history_filter_images")
                    : text.get("unsent_history_confirmed_section");
                addSectionHeading(heading, visible.size());
                if (visible.isEmpty()) {
                    String message = activeFilter == IMAGES
                        ? text.get("unsent_history_images_empty")
                        : text.get("unsent_history_empty");
                    addEmptyState(message, text.get("unsent_history_empty_help"));
                } else {
                    for (AntiUnsendStore.Entry entry : visible) addMessageCard(entry, dates);
                }
            }
        }

        if (activeFilter == ALL || activeFilter == NOTIFICATIONS) {
            List<AntiUnsendStore.NotificationEntry> visible = new ArrayList<>();
            for (AntiUnsendStore.NotificationEntry entry : notificationEntries) {
                if (matches(query, entry.title, entry.text)) visible.add(entry);
            }
            if (activeFilter == ALL) {
                ui.add(list, ui.text(text.get("unsent_history_notification_section"), 14, ui.text, true), 20);
                ui.add(list, ui.text(text.get("unsent_history_notification_help_short"), 11, ui.muted, false), 5);
            } else {
                addSectionHeading(text.get("unsent_history_filter_notifications"), visible.size());
            }
            if (visible.isEmpty()) {
                if (activeFilter == NOTIFICATIONS) {
                    addEmptyState(text.get("unsent_history_notification_empty"), text.get("unsent_history_notification_empty_help"));
                }
            } else {
                for (AntiUnsendStore.NotificationEntry entry : visible) addNotificationCard(entry, dates);
            }
        }
        if (list.getChildCount() == 0) addEmptyState(text.get("unsent_history_empty"), text.get("unsent_history_empty_help"));
    }

    private boolean matches(String query, String sender, String body) {
        if (query.isEmpty()) return true;
        String first = sender == null ? "" : sender.toLowerCase(Locale.ROOT);
        String second = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return first.contains(query) || second.contains(query);
    }

    private void addSectionHeading(String label, int count) {
        LinearLayout row = ui.row();
        TextView heading = ui.text(label, 14, ui.text, true);
        row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = ui.text(Integer.toString(count), 12, ui.muted, true);
        row.addView(amount);
        ui.add(list, row, 10);
    }

    private void addEmptyState(String title, String subtitle) {
        LinearLayout card = ui.column();
        card.setGravity(Gravity.CENTER);
        card.setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(20));
        card.setBackground(ui.shape(ui.surface, ui.line, 16));
        TextView icon = ui.text("▤", 28, ui.accent, true);
        icon.setGravity(Gravity.CENTER);
        ui.add(card, icon, 0);
        TextView main = ui.text(title, 14, ui.text, true);
        main.setGravity(Gravity.CENTER);
        ui.add(card, main, 8);
        TextView detail = ui.text(subtitle, 12, ui.muted, false);
        detail.setGravity(Gravity.CENTER);
        ui.add(card, detail, 5);
        ui.add(list, card, 10);
    }

    private void addMessageCard(AntiUnsendStore.Entry entry, DateFormat dates) {
        LinearLayout card = ui.column();
        card.setPadding(ui.dp(14), ui.dp(13), ui.dp(14), ui.dp(12));
        card.setBackground(ui.shape(ui.surface, ui.line, 16));

        LinearLayout top = ui.row();
        String sender = entry.senderId == null || entry.senderId.isEmpty()
            ? text.get("unsent_history_unknown_sender") : text.get("unsent_history_sender", entry.senderId);
        TextView senderView = ui.text(sender, 12, ui.muted, true);
        senderView.setMaxLines(1);
        senderView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(senderView, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = ui.text(text.get("unsent_history_confirmed_badge"), 10, ui.background == 0xff000000
            ? 0xff83d6a8 : 0xff18794e, true);
        badge.setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5));
        badge.setBackground(ui.shape(ui.background == 0xff000000 ? 0xff123325 : 0xffe5f5ec, 0, 18));
        top.addView(badge);
        card.addView(top);

        String bodyText = entry.text == null || entry.text.isEmpty()
            ? text.get("unsent_history_missing_text") : entry.text;
        TextView body = ui.text(bodyText, 15, ui.text, false);
        body.setTextIsSelectable(true);
        ui.add(card, body, 9);

        List<AntiUnsendStore.MediaEntry> images = mediaByMessage.get(entry.messageId);
        if (images != null && !images.isEmpty()) addSavedImages(card, images);

        String when = dates.format(new Date(entry.unsentAt > 0 ? entry.unsentAt : entry.receivedAt));
        ui.add(card, ui.text(text.get("unsent_history_unsent_at", when), 11, ui.muted, false), 10);
        ui.add(list, card, 9);
    }

    private void addNotificationCard(AntiUnsendStore.NotificationEntry entry, DateFormat dates) {
        LinearLayout card = ui.column();
        card.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(11));
        card.setBackground(ui.shape(ui.surface, ui.line, 16));
        LinearLayout top = ui.row();
        String sender = entry.title == null || entry.title.isEmpty()
            ? text.get("unsent_history_unknown_sender") : entry.title;
        TextView senderView = ui.text(sender, 13, ui.text, true);
        top.addView(senderView, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = ui.text(text.get("unsent_history_notification_badge"), 9, ui.warning, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(ui.dp(7), ui.dp(5), ui.dp(7), ui.dp(5));
        badge.setBackground(ui.shape(ui.warningSurface, ui.line, 18));
        top.addView(badge);
        card.addView(top);
        TextView body = ui.text(entry.text == null ? "" : entry.text, 14, ui.text, false);
        body.setTextIsSelectable(true);
        ui.add(card, body, 8);
        ui.add(card, ui.text(text.get("unsent_history_notification_at",
            dates.format(new Date(entry.postedAt))), 11, ui.muted, false), 8);
        ui.add(list, card, 9);
    }

    private void addSavedImages(LinearLayout card, List<AntiUnsendStore.MediaEntry> images) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = ui.row();
        int shown = 0;
        for (AntiUnsendStore.MediaEntry entry : images) {
            if (shown >= 10) break;
            File file = new File(entry.localPath);
            Bitmap bitmap = decodeSampled(file, ui.dp(116), ui.dp(132));
            if (bitmap == null) continue;
            ImageView image = new ImageView(this);
            image.setTag("unsent_history_image");
            image.setImageBitmap(bitmap);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(ui.shape(ui.line, ui.line, 12));
            image.setClipToOutline(true);
            image.setContentDescription(text.get("unsent_history_image_description"));
            image.setOnClickListener(view -> showImage(file));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(116), ui.dp(132));
            params.setMarginEnd(ui.dp(8));
            row.addView(image, params);
            shown++;
        }
        if (shown == 0) return;
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        ui.add(card, scroll, 10);
    }

    private Bitmap decodeSampled(File file, int width, int height) {
        if (!file.isFile() || !file.canRead()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= width &&
                bounds.outHeight / (sample * 2) >= height) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void showImage(File file) {
        Bitmap bitmap = decodeSampled(file,
            getResources().getDisplayMetrics().widthPixels,
            getResources().getDisplayMetrics().heightPixels);
        if (bitmap == null) {
            Toast.makeText(this, text.get("unsent_history_image_unavailable"), Toast.LENGTH_SHORT).show();
            return;
        }
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        image.setMaxHeight(getResources().getDisplayMetrics().heightPixels - ui.dp(100));
        image.setBackgroundColor(0xff101318);
        new AlertDialog.Builder(this).setView(image)
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
            .setTitle(text.get("unsent_history_clear"))
            .setMessage(text.get("unsent_history_clear_confirm"))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(text.get("unsent_history_clear"), (dialog, which) -> {
                try {
                    AntiUnsendStore.get(this).clearAllHistory();
                    refresh();
                    Toast.makeText(this, text.get("unsent_history_cleared"), Toast.LENGTH_SHORT).show();
                } catch (RuntimeException error) {
                    Settings.hookFailedPrivately("keep_unsent", "Can't clear unsent history", error);
                    Toast.makeText(this, text.get("unsent_history_clear_failed"), Toast.LENGTH_LONG).show();
                }
            })
            .show();
    }
}
