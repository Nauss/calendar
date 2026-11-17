package fr.nauss.calendar.core;

import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;

public class NominalDurationTest {
    private final ZoneId paris=ZoneId.of("Europe/Paris");
    private String event(String start,String duration,String extra) {
        return IcsCodecTest.ics("DTSTART;TZID=Europe/Paris:"+start+"\r\nDURATION:"+duration+"\r\nSUMMARY:Before\r\n"+extra);
    }
    @Test public void nominalDaysAndWeeksCrossBothDstTransitions() {
        for(String[] c:new String[][] {
            {"20261024T090000","P1D","2026-10-25T09:00"},
            {"20260328T090000","P1D","2026-03-29T09:00"},
            {"20261024T090000","P1W","2026-10-31T09:00"},
            {"20260328T090000","P1W","2026-04-04T09:00"},
            {"20261024T090000","P1DT2H","2026-10-25T11:00"},
            {"20260328T090000","P1DT2H","2026-03-29T11:00"}}) {
            String raw=event(c[0],c[1],"");
            assertEquals(c[1],LocalDateTime.parse(c[2]),IcsCodec.inspect(raw,ZoneOffset.UTC).draft.end);
            var out=IcsCodec.expand(raw,LocalDate.of(2026,1,1),LocalDate.of(2027,1,1),paris);
            assertEquals(LocalDateTime.parse(c[2]),out.get(0).end.atZone(paris).toLocalDateTime());
        }
    }
    @Test public void recurringNominalDurationRecomputedForEachOccurrence() {
        for(String start:new String[]{"20261023T090000","20260327T090000"}) {
            String raw=event(start,"P1D","RRULE:FREQ=DAILY;COUNT=4");
            var out=IcsCodec.expand(raw,LocalDate.of(2026,1,1),LocalDate.of(2027,1,1),paris);
            assertEquals(4,out.size());
            for(var o:out) assertEquals(o.start.atZone(paris).plusDays(1),o.end.atZone(paris));
        }
    }
    @Test public void titleOnlyEditPreservesDurationAndRecurrenceSemantics() {
        String raw=event("20261024T090000","P1D","RRULE:FREQ=DAILY");
        var info=IcsCodec.inspect(raw,paris);assertTrue(info.editable);
        info.draft.title="After";
        String saved=IcsCodec.save(raw,info.draft);
        assertTrue(saved,saved.contains("DURATION:P1D"));assertFalse(saved,saved.contains("DTEND"));
        var before=IcsCodec.expand(raw,LocalDate.of(2026,10,24),LocalDate.of(2026,10,28),paris);
        var after=IcsCodec.expand(saved,LocalDate.of(2026,10,24),LocalDate.of(2026,10,28),paris);
        assertEquals(before.size(),after.size());
        for(int i=0;i<before.size();i++) { assertEquals(before.get(i).start,after.get(i).start);assertEquals(before.get(i).end,after.get(i).end); }
    }
    @Test public void elapsedHoursRemainElapsedAndTimeEditsAreExplicit() {
        String raw=event("20261024T090000","PT24H","");
        assertEquals(LocalDateTime.parse("2026-10-25T08:00"),IcsCodec.inspect(raw,paris).draft.end);
        var d=IcsCodec.inspect(raw,paris).draft;d.end=d.end.plusHours(2);
        String saved=IcsCodec.save(raw,d);assertFalse(saved.contains("DURATION:"));assertEquals(d.end,IcsCodec.inspect(saved,paris).draft.end);
    }
}
