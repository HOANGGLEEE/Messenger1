package app.hushmessenger.extension;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

/**
 * Small trampoline that picks a local audio file, then hands it to Messenger's own share/attachment flow.
 * Messenger remains responsible for upload, encryption, delivery and the recipient-side audio player.
 */
public final class MusicShareActivity extends Activity {
    private static final int PICK_AUDIO = 5817;
    private boolean pickerStarted;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null) openPicker();
    }

    private void openPicker() {
        if (pickerStarted) return;
        pickerStarted = true;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("audio/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(pick, PICK_AUDIO);
        } catch (ActivityNotFoundException missingDocumentProvider) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("audio/*");
                fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(fallback, PICK_AUDIO);
            } catch (RuntimeException error) {
                Toast.makeText(this, "Không tìm thấy trình chọn tệp âm thanh.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_AUDIO) return;
        if (resultCode != RESULT_OK || data == null) {
            finish();
            return;
        }
        Uri uri = data.getData();
        if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            uri = data.getClipData().getItemAt(0).getUri();
        }
        if (uri == null) {
            Toast.makeText(this, "Không đọc được tệp nhạc đã chọn.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Some document providers grant a temporary read permission only.
            }
            String name = displayName(uri);
            String mime = audioMime(uri, name);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mime);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, name);
            share.setClipData(ClipData.newUri(getContentResolver(), name, uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setPackage("com.facebook.orca");
            try {
                startActivity(share);
            } catch (ActivityNotFoundException noMessengerAudioRoute) {
                share.setPackage(null);
                startActivity(Intent.createChooser(share, "Gửi nhạc bằng Messenger"));
            }
        } catch (RuntimeException error) {
            Toast.makeText(this, "Messenger chưa mở được tệp nhạc này. Hãy thử tệp MP3, M4A hoặc WAV khác.", Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (RuntimeException ignored) {}
        return "music";
    }

    private String audioMime(Uri uri, String name) {
        String mime = getContentResolver().getType(uri);
        if (mime != null && mime.startsWith("audio/")) return mime;
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) return "audio/mp4";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".opus")) return "audio/opus";
        if (lower.endsWith(".aac")) return "audio/aac";
        return "audio/*";
    }
}
