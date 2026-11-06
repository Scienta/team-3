package no.scienta.cfp;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Misc helpers.
 */
public class Utils {

    public static final String DB_DATE_TIME = "yyyy-MM-dd HH:mm:ss";

    public static final int MAX_TALKS = 5;

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    public static String today() {
        return new SimpleDateFormat("dd.MM.yyyy").format(new Date());
    }

    public static String now() {
        SimpleDateFormat f = new SimpleDateFormat(DB_DATE_TIME);
        f.setTimeZone(UTC);
        return f.format(new Date());
    }

    public static Date parse(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(DB_DATE_TIME);
            f.setTimeZone(UTC);
            return f.parse(s);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Epoch millis to a date-only java.sql.Date (UTC day).
     */
    public static java.sql.Date toSqlDate(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(UTC);
        return java.sql.Date.valueOf(f.format(new Date(millis)));
    }

    /**
     * "11/4/26, 1:05 PM", what the reviewer UI shows.
     */
    public static String usShort(Date d) {
        DateFormat f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.US);
        f.setTimeZone(UTC);
        return f.format(d);
    }

    /**
     * "4. nov. 2026" for the committee spreadsheet.
     */
    public static String norwegianDate(Date d) {
        DateFormat f = DateFormat.getDateInstance(DateFormat.MEDIUM, new Locale("nb", "NO"));
        f.setTimeZone(TimeZone.getTimeZone("Europe/Oslo"));
        return f.format(d);
    }

    /**
     * Makes a value safe for the semicolon CSV export.
     */
    public static String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(";") || s.contains("\"")) {
            return "\"" + s.replace("\"", "'") + "\"";
        }
        return s;
    }

    public static boolean isValidEmail(String email) {
        return email != null && email.contains("@") && email.indexOf('@') < email.lastIndexOf('.');
    }

    public static boolean isEmpty(String s) {
        return s == null || s.trim().length() == 0;
    }

    public static boolean isNumeric(String s) {
        if (isEmpty(s)) {
            return false;
        }
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rounds a rating average to one decimal.
     */
    public static double roundRating(double avg) {
        return Math.floor(avg * 10) / 10.0;
    }

    public static String capitalize(String s) {
        if (isEmpty(s)) {
            return s;
        }
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    // public static String slug(String title) {
    //     return title.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    // }
}
