package no.scienta.cfp;

import java.util.ArrayList;
import java.util.List;

/** One scheduled talk in the programme export (GET /programme and /programme.csv). */
public class ProgrammeRow {

    public String slot;
    public String start;
    public String end;
    public String room;
    public Long talkId;
    public String title;
    public String format;
    public List<String> speakers = new ArrayList<>();  // main speaker first, then co-speakers in the order added

    long mainSpeakerId;  // package-private: used to fill speakers, not serialised (Jackson sees public fields only)
}
