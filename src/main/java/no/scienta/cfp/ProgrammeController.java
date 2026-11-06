package no.scienta.cfp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProgrammeController {

    private final ProgrammeService service;

    public ProgrammeController(ProgrammeService service) {
        this.service = service;
    }

    static final String CSV_HEADER = "slot,start,end,room,talkId,title,format,speakers";

    /** The whole programme for the website: one row per scheduled talk, earliest slot first, then by room. */
    @GetMapping("/programme")
    public List<ProgrammeRow> programme() {
        return service.export();
    }

    /**
     * The same rows for the print shop: RFC 4180 CSV, UTF-8, LF line endings, every line (the header too) ending
     * in "\n" and no blank line after the last. No `produces`: the Content-Type is set on the response itself, so
     * Spring skips content negotiation and a client asking for JSON still gets text/csv (spec), never a 406.
     */
    @GetMapping("/programme.csv")
    public ResponseEntity<byte[]> programmeCsv() {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (ProgrammeRow r : service.export()) {
            sb.append(csvField(r.slot)).append(',')
                    .append(csvField(r.start)).append(',')
                    .append(csvField(r.end)).append(',')
                    .append(csvField(r.room)).append(',')
                    .append(r.talkId).append(',')
                    .append(csvField(r.title)).append(',')
                    .append(csvField(r.format)).append(',')
                    .append(csvField(String.join("; ", r.speakers)))
                    .append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** RFC 4180: quote only when the field holds a comma, a quote or a line break; double any quotes inside. */
    static String csvField(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    @PutMapping("/programme/{room}/{slot}")
    public ProgrammeEntry place(@PathVariable String room, @PathVariable String slot,
                                @RequestBody(required = false) Map<String, Object> body) {
        return service.place(room, slot, body);
    }

    @GetMapping("/programme/{room}/{slot}")
    public ProgrammeEntry get(@PathVariable String room, @PathVariable String slot) {
        return service.get(room, slot);
    }

    @DeleteMapping("/programme/{room}/{slot}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String room, @PathVariable String slot) {
        service.remove(room, slot);
    }
}
