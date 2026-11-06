package no.scienta.cfp;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TalkController {

    private final ConferenceService service;

    public TalkController(ConferenceService service) {
        this.service = service;
    }

    @PostMapping("/talks")
    @ResponseStatus(HttpStatus.CREATED)
    public TalkDto submit(@RequestBody TalkForm form) {
        return service.submitTalk(form);
    }

    @GetMapping("/talks/{id}")
    public TalkDto get(@PathVariable Long id) {
        return service.getTalk(id);
    }

    @GetMapping("/talks")
    public List<TalkDto> list(@RequestParam(required = false) String sort,
                              @RequestParam(required = false) Long speakerId) {
        return service.listTalks(sort, speakerId);
    }

    @PutMapping("/talks/{id}/ratings/{reviewerId}")
    public Map<String, Object> rate(@PathVariable Long id, @PathVariable Long reviewerId,
                                    @RequestBody Map<String, Object> body) {
        return service.rate(id, reviewerId, body);
    }
}
