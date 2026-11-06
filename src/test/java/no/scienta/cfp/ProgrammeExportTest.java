package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Programme export: GET /programme (JSON) and GET /programme.csv, one row per scheduled talk in programme
 * order, with the talk and its speakers as they are now. Runs the real app like {@link SchedulingTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProgrammeExportTest {

    private static final String HEADER = "slot,start,end,room,talkId,title,format,speakers\n";

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    // One database for the class: empty the programme before each test.
    @BeforeEach
    void clearProgramme() throws Exception {
        for (String room : Venue.ROOMS) {
            for (String slot : Venue.SLOTS) {
                send("DELETE", "/programme/" + room + "/" + slot, null);
            }
        }
    }

    // R7: an empty programme is an empty list, or a CSV with just the header
    @Test
    void emptyProgrammeIsEmptyListAndHeaderOnlyCsv() throws Exception {
        HttpResponse<String> json = send("GET", "/programme", null);
        assertEquals(200, json.statusCode());
        assertEquals("[]", json.body().trim());

        HttpResponse<byte[]> csv = csv(null);
        assertEquals(200, csv.statusCode());
        assertEquals(HEADER, text(csv));
    }

    // R1, R2: one row per scheduled talk, earliest start first, then by room; JSON and CSV in the same order
    @Test
    void rowsInProgrammeOrderInBothViews() throws Exception {
        long s1r3 = acceptedTalk("Late room", "PRESENTATION");
        long s1r1 = acceptedTalk("Early room", "LIGHTNING_10");
        long s4r2 = acceptedTalk("Workshop", "WORKSHOP");
        long s2r1 = acceptedTalk("Second slot", "LIGHTNING_20");
        long unscheduled = acceptedTalk("Not placed", "PRESENTATION");
        // placed out of order on purpose
        place("R2", "S4", s4r2);
        place("R3", "S1", s1r3);
        place("R1", "S2", s2r1);
        place("R1", "S1", s1r1);

        List<Map<String, Object>> rows = rows();
        assertEquals(4, rows.size(), "only scheduled talks, one row each");
        assertEquals(List.of("S1/R1", "S1/R3", "S2/R1", "S4/R2"), slotRooms(rows));
        assertEquals(List.of(s1r1, s1r3, s2r1, s4r2), talkIds(rows));
        assertFalse(talkIds(rows).contains(unscheduled));

        String[] lines = text(csv(null)).split("\n");
        assertEquals(5, lines.length);
        for (int i = 0; i < 4; i++) {
            assertTrue(lines[i + 1].startsWith(rows.get(i).get("slot") + ","), lines[i + 1]);
            assertEquals(String.valueOf(talkIds(rows).get(i)), lines[i + 1].split(",")[4]);
        }
    }

    // R3: each row has slot, start/end as in scheduling, room, talk id, title and format
    @Test
    void rowCarriesSlotTimesRoomAndTalk() throws Exception {
        long talk = acceptedTalk("Plain title", "WORKSHOP");
        place("R3", "S4", talk);

        Map<String, Object> row = rows().get(0);
        assertEquals("S4", row.get("slot"));
        assertEquals("13:30", row.get("start"));
        assertEquals("15:30", row.get("end"));
        assertEquals("R3", row.get("room"));
        assertEquals(talk, ((Number) row.get("talkId")).longValue());
        assertEquals("Plain title", row.get("title"));
        assertEquals("WORKSHOP", row.get("format"));
        assertEquals(List.of("talkId", "title", "format", "speakers", "slot", "start", "end", "room").stream().sorted().toList(),
                row.keySet().stream().sorted().toList(), "exactly the ProgrammeRow fields, nothing internal");

        assertEquals(HEADER + "S4,13:30,15:30,R3," + talk + ",Plain title,WORKSHOP," + speakerName(row) + "\n",
                text(csv(null)));
    }

    // Slot times for every slot match the scheduling table (09:00, not 9:00)
    @Test
    void everySlotHasItsSignTimes() throws Exception {
        String[][] slots = {{"S1", "09:00", "10:00"}, {"S2", "10:15", "11:15"}, {"S3", "11:30", "12:30"},
                {"S4", "13:30", "15:30"}, {"S5", "15:45", "16:45"}};
        for (String[] s : slots) {
            place("R1", s[0], acceptedTalk("T " + s[0], "LIGHTNING_10"));
        }
        List<Map<String, Object>> rows = rows();
        for (int i = 0; i < slots.length; i++) {
            assertEquals(slots[i][0], rows.get(i).get("slot"));
            assertEquals(slots[i][1], rows.get(i).get("start"));
            assertEquals(slots[i][2], rows.get(i).get("end"));
        }
    }

    // R4: speakers by name, main speaker first, then co-speakers in the order added; "; " in the CSV
    @Test
    void speakersMainFirstThenCoSpeakersInOrderAdded() throws Exception {
        long grace = newSpeaker("Grace Hopper");
        long ada = newSpeaker("Ada Lovelace");
        long barbara = newSpeaker("Barbara Liskov");
        long talk = acceptedTalk("Pair talk", "PRESENTATION", grace);
        addCoSpeaker(talk, barbara);
        addCoSpeaker(talk, ada);
        place("R1", "S1", talk);

        assertEquals(List.of("Grace Hopper", "Barbara Liskov", "Ada Lovelace"), rows().get(0).get("speakers"));
        assertTrue(text(csv(null)).endsWith(",Grace Hopper; Barbara Liskov; Ada Lovelace\n"));
    }

    // R3 "as they are right now": renames, co-speaker changes and withdrawal after scheduling show up
    @Test
    void showsTalkAndSpeakersAsTheyAreNow() throws Exception {
        long main = newSpeaker("Old Name");
        long co = newSpeaker("Co Speaker");
        long talk = acceptedTalk("Live", "PRESENTATION", main);
        place("R2", "S3", talk);

        rename(main, "New Name");
        addCoSpeaker(talk, co);
        assertEquals(List.of("New Name", "Co Speaker"), rows().get(0).get("speakers"));

        send("DELETE", "/talks/" + talk + "/co-speakers/" + co, null);
        assertEquals(List.of("New Name"), rows().get(0).get("speakers"));

        // the programme stays as it is when a talk changes (scheduling spec), so a withdrawn talk is still a row
        HttpResponse<String> w = send("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + main + "}");
        assertEquals(200, w.statusCode(), w.body());
        assertEquals(List.of(talk), talkIds(rows()));
    }

    // A talk taken out of the programme is no longer exported
    @Test
    void takenOutTalkDisappears() throws Exception {
        long talk = acceptedTalk("Gone", "PRESENTATION");
        place("R1", "S5", talk);
        assertEquals(1, rows().size());
        send("DELETE", "/programme/R1/S5", null);
        assertEquals(0, rows().size());
        assertEquals(HEADER, text(csv(null)));
    }

    // R5/R6: RFC 4180 quoting only when needed, quotes doubled; commas in names quote the speakers field
    @Test
    void csvQuotesOnlyWhenNeeded() throws Exception {
        long speaker = newSpeaker("Hopper, Grace");
        long talk = acceptedTalk("Kotlin, \"really\" fast", "PRESENTATION", speaker);
        long plain = acceptedTalk("Just words; and semicolons", "LIGHTNING_20");
        place("R1", "S1", talk);
        place("R2", "S1", plain);

        String[] lines = text(csv(null)).split("\n");
        assertEquals("S1,09:00,10:00,R1," + talk + ",\"Kotlin, \"\"really\"\" fast\",PRESENTATION,\"Hopper, Grace\"", lines[1]);
        assertTrue(lines[2].startsWith("S1,09:00,10:00,R2," + plain + ",Just words; and semicolons,LIGHTNING_20,"), lines[2]);
        assertFalse(lines[2].contains("\""), "no quotes when nothing needs them: " + lines[2]);
    }

    // R6: UTF-8, text/csv even when the client asks for JSON, LF endings, every line ends in \n, no blank last line
    @Test
    void csvIsUtf8TextCsvWithUnixLineEndings() throws Exception {
        long speaker = newSpeaker("Ærlig Øyvind Åsen");
        place("R1", "S1", acceptedTalk("Blåbærsyltetøy 🫐", "PRESENTATION", speaker));

        for (String accept : new String[] {null, "application/json", "text/csv", "*/*"}) {
            HttpResponse<byte[]> res = csv(accept);
            assertEquals(200, res.statusCode(), "Accept: " + accept);
            String type = res.headers().firstValue("Content-Type").orElse("");
            assertTrue(type.startsWith("text/csv"), "Accept " + accept + " -> " + type);
            String body = text(res);
            assertTrue(body.startsWith(HEADER));
            assertTrue(body.contains("Blåbærsyltetøy 🫐") && body.contains("Ærlig Øyvind Åsen"), body);
            assertFalse(body.contains("\r"), "LF only");
            assertTrue(body.endsWith("\n") && !body.endsWith("\n\n"), "ends in exactly one newline");
        }
    }

    // ---- helpers ----

    private List<Map<String, Object>> rows() throws Exception {
        HttpResponse<String> res = send("GET", "/programme", null);
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = om.readValue(res.body(), List.class);
        return rows;
    }

    private static List<String> slotRooms(List<Map<String, Object>> rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get("slot") + "/" + r.get("room")));
        return out;
    }

    private static List<Long> talkIds(List<Map<String, Object>> rows) {
        List<Long> out = new ArrayList<>();
        rows.forEach(r -> out.add(((Number) r.get("talkId")).longValue()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String speakerName(Map<String, Object> row) {
        return ((List<String>) row.get("speakers")).get(0);
    }

    private HttpResponse<byte[]> csv(String accept) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/programme.csv")).GET();
        if (accept != null) {
            b.header("Accept", accept);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> res) {
        return new String(res.body(), StandardCharsets.UTF_8);
    }

    private long newSpeaker(String name) throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":" + om.writeValueAsString(name) + ",\"email\":\"p-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        return id(res.body());
    }

    private void rename(long speakerId, String name) throws Exception {
        HttpResponse<String> current = send("GET", "/speakers/" + speakerId, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> s = om.readValue(current.body(), Map.class);
        HttpResponse<String> res = send("PUT", "/speakers/" + speakerId,
                "{\"name\":" + om.writeValueAsString(name) + ",\"email\":\"" + s.get("email") + "\"}");
        assertEquals(200, res.statusCode(), res.body());
    }

    private long acceptedTalk(String title, String format) throws Exception {
        return acceptedTalk(title, format, newSpeaker("Speaker " + UUID.randomUUID().toString().substring(0, 8)));
    }

    private long acceptedTalk(String title, String format, long speakerId) throws Exception {
        HttpResponse<String> res = send("POST", "/talks", "{\"title\":" + om.writeValueAsString(title)
                + ",\"format\":\"" + format + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}");
        assertEquals(201, res.statusCode(), res.body());
        long talk = id(res.body());
        assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/1", "{\"score\":4}").statusCode());
        assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/2", "{\"score\":5}").statusCode());
        HttpResponse<String> acc = send("PUT", "/talks/" + talk + "/status", "{\"status\":\"ACCEPTED\"}");
        assertEquals(200, acc.statusCode(), acc.body());
        return talk;
    }

    private void addCoSpeaker(long talkId, long speakerId) throws Exception {
        HttpResponse<String> res = send("PUT", "/talks/" + talkId + "/co-speakers/" + speakerId, null);
        assertEquals(200, res.statusCode(), res.body());
    }

    private void place(String room, String slot, long talkId) throws Exception {
        HttpResponse<String> res = send("PUT", "/programme/" + room + "/" + slot, "{\"talkId\":" + talkId + "}");
        assertEquals(200, res.statusCode(), room + "/" + slot + ": " + res.body());
    }

    @SuppressWarnings("unchecked")
    private long id(String body) throws Exception {
        return ((Number) om.readValue(body, Map.class).get("id")).longValue();
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
