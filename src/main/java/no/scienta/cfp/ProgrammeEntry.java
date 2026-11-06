package no.scienta.cfp;

/** One room+slot of the programme, as the API returns it. */
public class ProgrammeEntry {

    public String room;
    public String slot;
    public String start;
    public String end;
    public Long talkId;

    public ProgrammeEntry(String room, String slot, String start, String end, long talkId) {
        this.room = room;
        this.slot = slot;
        this.start = start;
        this.end = end;
        this.talkId = talkId;
    }
}
