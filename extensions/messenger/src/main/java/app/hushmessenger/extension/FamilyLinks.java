package app.hushmessenger.extension;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Helper for the Threads and Instagram link part of "Restore screens on re-signed builds".
 *
 * <p>Messenger opens a Threads link, or an Instagram video link, by building a view intent for that
 * app and handing it to Meta's family launcher. That launcher starts only apps it finds signed with
 * Meta's keys, and Messenger ignores its answer. A Threads or Instagram the user patched and signed
 * themselves gets nothing at all: the app doesn't open, the browser doesn't either, and nothing is
 * logged. Once the launcher reports it started nothing, the same intent goes to Android the ordinary
 * way, and if no app takes it there, the link opens without a package, which is the browser.
 */
public final class FamilyLinks {

    private FamilyLinks() {}

    /** Called right after the family launcher, with its answer and the intent it was given. */
    public static void launched(boolean started, Context context, Intent intent) {
        if (started || context == null || intent == null) return;
        Intent view = new Intent(intent);
        if (!(context instanceof Activity)) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(view);
            return;
        } catch (Throwable error) {
            if (view.getPackage() == null) {
                Log.w("HushMessenger", "Can't open the link", error);
                return;
            }
            Log.w("HushMessenger", "Can't open the link in " + view.getPackage() + ", trying the browser", error);
        }
        view.setPackage(null);
        try {
            context.startActivity(view);
        } catch (Throwable error) {
            Log.w("HushMessenger", "Can't open the link", error);
        }
    }
}
