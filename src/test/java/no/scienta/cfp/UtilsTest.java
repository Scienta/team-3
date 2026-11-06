package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class UtilsTest {

    @Test
    void isEmpty() {
        assertTrue(Utils.isEmpty(null));
        assertTrue(Utils.isEmpty(""));
        assertTrue(Utils.isEmpty("   "));
        assertFalse(Utils.isEmpty("a"));
    }

    @Test
    void isNumeric() {
        assertTrue(Utils.isNumeric("123"));
        assertFalse(Utils.isNumeric("12a"));
        assertFalse(Utils.isNumeric(""));
    }

    @Test
    void capitalize() {
        assertEquals("Workshop", Utils.capitalize("WORKSHOP"));
    }

    // Averages are shown with one decimal, halves up (Ratings spec: 4.25 shows as 4.3)
    @Test
    void roundsRatingToOneDecimal() {
        assertEquals(4.3, Utils.roundRating(4.25));
        assertEquals(3.7, Utils.roundRating(3.6666));
        assertEquals(4.5, Utils.roundRating(4.5));
        assertEquals(4.3, Utils.roundRating(4.3));
    }

    // Pinned to what Java 11 printed: newer Java (CLDR 42+) puts U+202F before AM/PM
    @Test
    void usShortHasAPlainSpaceBeforeAmPm() {
        assertEquals("11/6/26, 1:05 PM", Utils.usShort(new Date(1793970300000L)));  // 2026-11-06 13:05 UTC
        assertEquals("1/6/26, 12:05 AM", Utils.usShort(new Date(1767657900000L)));  // 2026-01-06 00:05 UTC
    }

    // Pinned to what Java 11 printed: newer Java writes "mars", "juni", "juli"
    @Test
    void norwegianDateKeepsTheOldMonthAbbreviations() {
        String[] expected = {"jan.", "feb.", "mar.", "apr.", "mai", "jun.", "jul.", "aug.", "sep.", "okt.", "nov.", "des."};
        for (int month = 0; month < 12; month++) {
            java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("Europe/Oslo"));
            c.clear();
            c.set(2026, month, 6, 12, 0);
            assertEquals("6. " + expected[month] + " 2026", Utils.norwegianDate(c.getTime()));
        }
    }

    @Disabled("flaky on Jenkins")
    @Test
    void nowIsCurrentTime() {
        SimpleDateFormat f = new SimpleDateFormat(Utils.DB_DATE_TIME);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertEquals(f.format(new Date()), Utils.now());
    }
}
