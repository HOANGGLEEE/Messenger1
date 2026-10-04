package app.hushmessenger.extension;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Local-only history for messages observed by Keep unsent messages. */
public final class UnsentHistoryActivity extends Activity {
    private SettingsText text;
    private SettingsUi ui;
    private LinearLayout list;

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
        list.removeAllViews();
        List<AntiUnsendStore.Entry> entries;
        try {
            entries = AntiUnsendStore.get(this).listUnsent(500);
        } catch (RuntimeException error) {
            Settings.hookFailedPrivately("keep_unsent", "Can't read unsent history", error);
            TextView failed = ui.text(text.get("unsent_history_read_failed"), 14, ui.muted, false);
            failed.setTag("unsent_history_error");
            ui.add(list, failed, 20);
            return;
        }

        if (entries.isEmpty()) {
            TextView empty = ui.text(text.get("unsent_history_empty"), 15, ui.muted, false);
            empty.setTag("unsent_history_empty");
            ui.add(list, empty, 20);
            return;
        }

        DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (AntiUnsendStore.Entry entry : entries) {
            LinearLayout card = ui.panel();
            TextView body = ui.text(entry.text == null || entry.text.isEmpty()
                ? text.get("unsent_history_missing_text") : entry.text, 16, ui.text, false);
            body.setTextIsSelectable(true);
            ui.add(card, body, 0);
            ui.add(card, ui.text(text.get("unsent_history_unsent_at",
                dates.format(new Date(entry.unsentAt))), 12, ui.muted, false), 10);
            ui.add(list, card, 12);
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
            .setTitle(text.get("unsent_history_clear"))
            .setMessage(text.get("unsent_history_clear_confirm"))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(text.get("unsent_history_clear"), (dialog, which) -> {
                try {
                    AntiUnsendStore.get(this).clearUnsentHistory();
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
