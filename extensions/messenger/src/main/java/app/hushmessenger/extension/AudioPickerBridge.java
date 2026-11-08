package app.hushmessenger.extension;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Method;

/** Adds a small entry point to Messenger's native voice-message recording sheet. */
public final class AudioPickerBridge {
    private static final String TAG = "hush_music_picker_button";

    private AudioPickerBridge() { }

    public static void install(Object fragment, Object voiceMessageSender) {
        if (fragment == null || voiceMessageSender == null) return;
        try {
            Method getActivity = fragment.getClass().getMethod("getActivity");
            Activity activity = (Activity) getActivity.invoke(fragment);
            Method getDialog = fragment.getClass().getMethod("getDialog");
            Dialog dialog = (Dialog) getDialog.invoke(fragment);
            if (activity == null || dialog == null) return;
            Window window = dialog.getWindow();
            if (window == null) return;

            View decor = window.getDecorView();
            View content = decor.findViewById(android.R.id.content);
            ViewGroup root = content instanceof ViewGroup ? (ViewGroup) content
                    : (decor instanceof ViewGroup ? (ViewGroup) decor : null);
            if (root == null || root.findViewWithTag(TAG) != null) return;

            FrameLayout overlay = new FrameLayout(activity);
            overlay.setClipChildren(false);
            overlay.setClipToPadding(false);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            root.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            TextView button = new TextView(activity);
            button.setTag(TAG);
            button.setText("♫  Chọn nhạc");
            button.setTextColor(Color.WHITE);
            button.setTextSize(14);
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(activity, 18), 0, dp(activity, 18), 0);
            button.setContentDescription("Chọn tệp nhạc để gửi dưới dạng tin nhắn thoại");
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.rgb(24, 119, 242));
            background.setCornerRadius(dp(activity, 24));
            button.setBackground(background);
            button.setElevation(dp(activity, 7));
            button.setOnClickListener(view -> AudioPickerActivity.launch(activity, voiceMessageSender));

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 44),
                    Gravity.TOP | Gravity.END);
            params.topMargin = dp(activity, 54);
            params.rightMargin = dp(activity, 16);
            overlay.addView(button, params);
        } catch (Throwable error) {
            // A missing host view must not affect Messenger's original recording controls.
            android.util.Log.w("HushMessengerAudio", "Couldn't add the music picker", error);
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
