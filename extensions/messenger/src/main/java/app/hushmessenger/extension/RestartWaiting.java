package app.hushmessenger.extension;

import android.content.SharedPreferences;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a restart would apply, counted for the restart row at the top of settings. A control that holds its first
 * answer for the run counts while its saved switch, Pause or safe mode says otherwise (Settings.heldUntilRestart).
 * The bubble mode and the font file, size and position are read only as Messenger starts, so each counts while it
 * differs from what it was when settings first opened in this run. Changing one back takes it off the count.
 */
final class RestartWaiting {
    private static final Map<String, Object> started = new HashMap<>();
    /** The store the start values came from. Another one is another install's, or another test's. */
    private static SharedPreferences source;

    private RestartWaiting() { }

    /** Keeps [now] as the start values, unless this run already has them. */
    static synchronized void baseline(SharedPreferences prefs, Map<String, Object> now) {
        if (prefs != source) {
            started.clear();
            source = prefs;
        }
        for (Map.Entry<String, Object> choice : now.entrySet()) started.putIfAbsent(choice.getKey(), choice.getValue());
    }

    /**
     * How many changes wait: the [controls] still giving a held answer, plus each of [now] that differs from its start
     * value. A choice left out of [now] doesn't count, as when its control is off and a restart wouldn't use it.
     */
    static synchronized int count(SharedPreferences prefs, Collection<String> controls, Map<String, Object> now) {
        int count = 0;
        for (String key : controls) if (Settings.heldUntilRestart(key) != null) count++;
        if (prefs != source) return count;
        for (Map.Entry<String, Object> choice : now.entrySet()) {
            if (started.containsKey(choice.getKey()) && !Objects.equals(started.get(choice.getKey()), choice.getValue())) count++;
        }
        return count;
    }
}
