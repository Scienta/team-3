package no.scienta.cfp;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed venue: three rooms and the same five slots in every room. Nobody can add or change these,
 * so they live in code, not the database. A slot carries its wall-clock start/end (as on the signs,
 * HH:mm, 24-hour) and its length in minutes; a talk fits a slot when its duration is within that length.
 */
final class Venue {

    static final List<String> ROOMS = Arrays.asList("R1", "R2", "R3");
    static final List<String> SLOTS = Arrays.asList("S1", "S2", "S3", "S4", "S5");

    static final class SlotTime {
        final String start;
        final String end;
        final int minutes;

        SlotTime(String start, String end, int minutes) {
            this.start = start;
            this.end = end;
            this.minutes = minutes;
        }
    }

    private static final Map<String, SlotTime> TIMES = new LinkedHashMap<>();

    static {
        TIMES.put("S1", new SlotTime("09:00", "10:00", 60));
        TIMES.put("S2", new SlotTime("10:15", "11:15", 60));
        TIMES.put("S3", new SlotTime("11:30", "12:30", 60));
        TIMES.put("S4", new SlotTime("13:30", "15:30", 120));  // the long slot
        TIMES.put("S5", new SlotTime("15:45", "16:45", 60));
    }

    static boolean isRoom(String room) {
        return ROOMS.contains(room);
    }

    static boolean isSlot(String slot) {
        return SLOTS.contains(slot);
    }

    static SlotTime slot(String slot) {
        return TIMES.get(slot);
    }

    private Venue() {
    }
}
