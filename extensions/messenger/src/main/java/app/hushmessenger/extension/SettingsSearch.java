package app.hushmessenger.extension;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * How Find a control matches a row: every word typed has to appear somewhere in the row's texts, in any order, with
 * accents and case ignored. "cafe" finds "Café", and "receipts read" finds "Hide read receipts". Kept apart from the
 * screen so the matching can be tested without one.
 */
final class SettingsSearch {
    private SettingsSearch() { }

    /** Text as the search compares it: accents taken off, lower case in no particular language. */
    static String normalized(CharSequence value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    /** The words of [query], normalized. None for a blank query. */
    static List<String> terms(CharSequence query) {
        String text = normalized(query).trim();
        if (text.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(Arrays.asList(text.split("\\s+")));
    }

    /** Whether every word of [terms] is in one of [texts]. No words asks for nothing, so it matches. A null text adds nothing. */
    static boolean matches(List<String> terms, CharSequence... texts) {
        if (terms.isEmpty()) return true;
        String[] normalized = new String[texts == null ? 0 : texts.length];
        for (int i = 0; i < normalized.length; i++) normalized[i] = normalized(texts[i]);
        for (String term : terms) {
            boolean found = false;
            for (String text : normalized) {
                if (text.contains(term)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }
}
