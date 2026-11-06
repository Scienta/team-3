package no.scienta.cfp;

import java.util.List;
import java.util.Map;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/speakers")
public class SpeakerController {

    private final ConferenceService service;

    public SpeakerController(ConferenceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody SpeakerForm form) {
        return service.registerSpeaker(form);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return service.getSpeaker(id);
    }

    // used by the admin UI to fix typos
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        return service.updateSpeaker(id, body.get("name"), body.get("email"), body.get("bio"));
    }

    @GetMapping("/{id}/talks")
    public List<TalkDto> talks(@PathVariable Long id) {
        return service.getTalksForSpeaker(id);
    }

    // admin only, not in the API docs
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String q) {
        return service.searchSpeakers(q);
    }
}
