package app.hushmessenger.extension;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class UnsentHistoryInsetsTest {
    @Test public void historyHeaderKeepsTwentyFourDpBelowTheSystemInset() {
        assertEquals(24, UnsentHistoryActivity.HISTORY_TOP_GAP_DP);
        assertEquals(96, UnsentHistoryActivity.historyContentTopPadding(24, 3.0f));
    }

    @Test public void historyHeaderKeepsItsGapWhenTheSystemInsetIsZero() {
        assertEquals(72, UnsentHistoryActivity.historyContentTopPadding(0, 3.0f));
    }

    @Test public void invalidNegativeInsetsDoNotPullTheHeaderAboveTheSafeArea() {
        assertEquals(72, UnsentHistoryActivity.historyContentTopPadding(-40, 3.0f));
    }
}
