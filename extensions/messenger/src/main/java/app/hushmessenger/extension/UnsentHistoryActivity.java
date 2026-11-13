package app.hushmessenger.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Local-only history for messages observed by Keep unsent messages. */
public final class UnsentHistoryActivity extends Activity {
    private SettingsText text;
    private SettingsUi ui;
    private LinearLayout list;
    private TextView diagnostics;
    private String pendingDebugReport;
    private static final int DEBUG_EXPORT_REQUEST = 7412;

    @Override public void onCreate(Bundle state) {
        HostScreens.start(this);
        Settings.initialize(this);
        boolean light = Settings.preferences.getBoolean("light", false);
        setTheme(light ? android.R.style.Theme_Material_Light_NoActionBar : android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        text = new SettingsText(this);
        ui = new SettingsUi(this, light);
        setTitle(text.get("unsent_history_title"));

        LinearLayout root = ui.column();
        root.setBackgroundColor(ui.background);
        root.setPadding(ui.dp(20), ui.dp(32), ui.dp(20), ui.dp(24));
        root.setLayoutDirection(text.layoutDirection());

        LinearLayout header = ui.row();
        Button back = ui.button(text.get("unsent_history_back"));
        back.setTag("unsent_history_back");
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = ui.text(text.get("unsent_history_title"), 24, ui.text, true);
        title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(ui.dp(12));
        header.addView(title, titleParams);
        ui.add(root, header, 0);

        TextView help = ui.text(text.get("unsent_history_help"), 14, ui.muted, false);
        help.setTag("unsent_history_help");
        ui.add(root, help, 14);

        diagnostics = ui.text("", 13, ui.muted, false);
        diagnostics.setTag("unsent_history_diagnostics");
        ui.add(root, diagnostics, 10);

        Button exportDebug = ui.button(text.get("unsent_history_export_debug"));
        exportDebug.setTag("unsent_history_export_debug");
        exportDebug.setOnClickListener(view -> exportDebugReport());
        ui.add(root, exportDebug, 10);

        Button clear = ui.button(text.get("unsent_history_clear"));
        clear.setTag("unsent_history_clear");
        clear.setOnClickListener(view -> confirmClear());
        ui.add(root, clear, 14);

        ScrollView scroll = new ScrollView(this);
        list = ui.column();
        list.setTag("unsent_history_list");
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        diagnostics.setText(text.get("unsent_history_diagnostics",
            Settings.unsentCaptureHits(), Settings.unsentCaptureSaved(),
            Settings.unsentRevokeHits(), Settings.unsentRevokeSaved()) + "\n" +
            text.get("unsent_history_notification_diagnostics",
                Settings.unsentNotificationHits(), Settings.unsentNotificationSaved()) + "\n" +
            UnsentDiagnostics.summary(this));
        list.removeAllViews();
        List<AntiUnsendStore.Entry> entries;
        List<AntiUnsendStore.NotificationEntry> notificationEntries;
        try {
            AntiUnsendStore store = AntiUnsendStore.get(this);
            entries = store.listUnsent(500);
            notificationEntries = store.listNotificationSnapshots(30);
            UnsentDiagnostics.record(this, "history.read", "ok");
        } catch (RuntimeException error) {
            UnsentDiagnostics.record(this, "history.read", "db_error");
            Settings.hookFailedPrivately("keep_unsent", "Can't read unsent history", error);
            TextView failed = ui.text(text.get("unsent_history_read_failed"), 14, ui.muted, false);
            failed.setTag("unsent_history_error");
            ui.add(list, failed, 20);
            return;
        }

        DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);

        TextView confirmedTitle = ui.text(text.get("unsent_history_confirmed_section"), 17, ui.text, true);
        confirmedTitle.setAccessibilityHeading(true);
        ui.add(list, confirmedTitle, 20);
        if (entries.isEmpty()) {
            TextView empty = ui.text(text.get("unsent_history_empty"), 15, ui.muted, false);
            empty.setTag("unsent_history_empty");
            ui.add(list, empty, 10);
        } else {
            for (AntiUnsendStore.Entry entry : entries) {
                LinearLayout card = ui.panel();
                String sender = entry.senderId == null || entry.senderId.isEmpty()
                    ? text.get("unsent_history_unknown_sender") : entry.senderId;
                ui.add(card, ui.text(text.get("unsent_history_sender", sender), 13, ui.muted, true), 0);
                TextView body = ui.text(entry.text == null || entry.text.isEmpty()
                    ? text.get("unsent_history_missing_text") : entry.text, 16, ui.text, false);
                body.setTextIsSelectable(true);
                ui.add(card, body, 8);
                ui.add(card, ui.text(text.get("unsent_history_received_at",
                    dates.format(new Date(entry.receivedAt))), 12, ui.muted, false), 10);
                ui.add(card, ui.text(text.get("unsent_history_unsent_at",
                    dates.format(new Date(entry.unsentAt))), 12, ui.muted, false), 4);
                ui.add(list, card, 12);
            }
        }

        TextView fallbackTitle = ui.text(text.get("unsent_history_notification_section"), 17, ui.text, true);
        fallbackTitle.setAccessibilityHeading(true);
        ui.add(list, fallbackTitle, 24);
        ui.add(list, ui.text(text.get("unsent_history_notification_help"), 13, ui.muted, false), 8);
        if (notificationEntries.isEmpty()) {
            TextView empty = ui.text(text.get("unsent_history_notification_empty"), 15, ui.muted, false);
            empty.setTag("unsent_history_notification_empty");
            ui.add(list, empty, 10);
        } else {
            for (AntiUnsendStore.NotificationEntry entry : notificationEntries) {
                LinearLayout card = ui.panel();
                String sender = entry.title == null || entry.title.isEmpty()
                    ? text.get("unsent_history_unknown_sender") : entry.title;
                ui.add(card, ui.text(text.get("unsent_history_sender", sender), 13, ui.muted, true), 0);
                TextView body = ui.text(entry.text, 16, ui.text, false);
                body.setTextIsSelectable(true);
                ui.add(card, body, 8);
                ui.add(card, ui.text(text.get("unsent_history_notification_at",
                    dates.format(new Date(entry.postedAt))), 12, ui.muted, false), 10);
                ui.add(list, card, 12);
            }
        }
    }

    private void exportDebugReport() {
        pendingDebugReport = UnsentDiagnostics.report(this);
        UnsentDiagnostics.record(this, "diagnostics.export", "started");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "HushMessenger-Unsend-Diagnostics.json");
        try {
            startActivityForResult(intent, DEBUG_EXPORT_REQUEST);
        } catch (RuntimeException error) {
            UnsentDiagnostics.record(this, "diagnostics.export", "failed");
            Settings.hookFailedPrivately("keep_unsent", "Can't start diagnostics export", error);
            Toast.makeText(this, text.get("unsent_history_debug_failed"), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != DEBUG_EXPORT_REQUEST || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null || pendingDebugReport == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new java.io.IOException("No output stream");
            output.write(pendingDebugReport.getBytes(StandardCharsets.UTF_8));
            output.flush();
            UnsentDiagnostics.record(this, "diagnostics.export", "saved");
            Toast.makeText(this, text.get("unsent_history_debug_saved"), Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            UnsentDiagnostics.record(this, "diagnostics.export", "failed");
            Settings.hookFailedPrivately("keep_unsent", "Can't write diagnostics report", error);
            Toast.makeText(this, text.get("unsent_history_debug_failed"), Toast.LENGTH_LONG).show();
        } finally {
            pendingDebugReport = null;
        }
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
