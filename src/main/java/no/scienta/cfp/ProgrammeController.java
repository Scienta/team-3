package no.scienta.cfp;

import java.util.Map;

import org.springframework.http.HttpStatus;
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
