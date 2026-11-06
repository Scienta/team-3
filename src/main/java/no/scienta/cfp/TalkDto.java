package no.scienta.cfp;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * A talk as the API returns it. Also used internally all over the place.
 */
public class TalkDto {

    public Long id;
    public String title;
    public String format;
    public String level;
    public Long speakerId;
    public Integer durationMinutes;
    public String status;
    public Date submittedAt;
    public java.sql.Date submittedOn;  // the reporting tool wants a plain date
    public Double averageRating;
    public int ratingCount;
    public List<Long> coSpeakerIds = new ArrayList<>();  // other speakers added to the talk, in the order added; [] when none
}
