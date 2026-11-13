package app.hushmessenger.extension;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Initializes preferences before the main process executes UI hooks. Never exported. */
public final class SettingsProvider extends ContentProvider {
    static final String CALL_KEEP_UNSENT_ENABLED = "hush.get_keep_unsent_enabled";

    @Override public boolean onCreate() {
        Settings.initialize(getContext());
        HostScreens.start(getContext());
        // Brings the icon back after a repatch without the Menu row or a lost preference.
        SettingsActivity.syncDrawerIcon(getContext());
        return true;
    }
    /** Read-only IPC for Messenger's secondary receive process; no message content crosses processes. */
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (CALL_KEEP_UNSENT_ENABLED.equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean("enabled", Settings.keepUnsentEnabledLocally());
            return result;
        }
        return super.call(method, arg, extras);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
