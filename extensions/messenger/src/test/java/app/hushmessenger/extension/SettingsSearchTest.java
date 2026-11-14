package app.hushmessenger.extension;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsSearchTest {
    @Test public void everyWordHasToMatchSomewhereInAnyOrderWithAccentsAndCaseIgnored() {
        List<String> terms = SettingsSearch.terms("  receipts   READ ");
        assertEquals(List.of("receipts", "read"), terms);
        assertTrue(SettingsSearch.matches(terms, "Hide read receipts"));
        // The words can come from different texts of the same row.
        assertTrue(SettingsSearch.matches(terms, "Hide read", "Opening a chat no longer sends receipts"));
        assertFalse(SettingsSearch.matches(terms, "Hide read", "Opening a chat"));
        assertTrue(SettingsSearch.matches(SettingsSearch.terms("cafe"), "Café"));
        assertTrue(SettingsSearch.matches(SettingsSearch.terms("CAFÉ"), "cafe"));
        assertTrue(SettingsSearch.matches(SettingsSearch.terms("ubersetzen"), "Übersetzen"));
    }

    @Test public void aMissingTextAddsNothing() {
        assertFalse(SettingsSearch.matches(SettingsSearch.terms("null"), "Title", null));
        assertFalse(SettingsSearch.matches(SettingsSearch.terms("x"), (CharSequence[]) null));
        assertEquals("", SettingsSearch.normalized(null));
    }

    @Test public void aBlankQueryMatchesEverything() {
        assertEquals(List.of(), SettingsSearch.terms("   "));
        assertEquals(List.of(), SettingsSearch.terms(null));
        assertTrue(SettingsSearch.matches(List.of(), "anything"));
        assertTrue(SettingsSearch.matches(List.of()));
    }
}
