package fr.nauss.calendar.core;

import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;

public class IcsSafetyTest {
    @Test public void allDayCreateRoundTripAndDstDates() {
        var d=new IcsCodec.Draft();d.title="All day";d.allDay=true;d.zone="Europe/Paris";d.start=LocalDateTime.of(2026,10,24,0,0);d.end=LocalDateTime.of(2026,10,27,0,0);d.repeat="YEARLY";
        String raw=IcsCodec.save(null,d);var info=IcsCodec.inspect(raw,ZoneId.of("America/New_York"));
        assertTrue(info.editable);assertEquals(d.start,info.draft.start);assertEquals(d.end,info.draft.end);
        var occurrences=IcsCodec.expand(raw,LocalDate.of(2026,10,23),LocalDate.of(2026,10,29),ZoneId.of("Europe/Paris"));
        assertEquals(1,occurrences.size());assertEquals(LocalDate.of(2026,10,27),occurrences.get(0).end.atZone(ZoneId.of("Europe/Paris")).toLocalDate());
    }
    @Test public void cancelledRecurrenceOverrideDoesNotAppear() {
        String raw=IcsCodecTest.ics("DTSTART:20261101T090000Z\r\nDTEND:20261101T100000Z\r\nRRULE:FREQ=DAILY;COUNT=3");
        raw=raw.replace("END:VCALENDAR","BEGIN:VEVENT\r\nUID:fixture-1\r\nRECURRENCE-ID:20261102T090000Z\r\nSTATUS:CANCELLED\r\nEND:VEVENT\r\nEND:VCALENDAR");
        assertEquals(2,IcsCodec.expand(raw,LocalDate.of(2026,11,1),LocalDate.of(2026,11,5),ZoneOffset.UTC).size());
    }
    @Test public void monthlyNthWeekdayAndFloatingExdateUseMatureIterator() {
        String raw=IcsCodecTest.ics("DTSTART:20261109T090000\r\nRRULE:FREQ=MONTHLY;BYDAY=2MO;COUNT=3\r\nEXDATE:20261214T090000");
        var out=IcsCodec.expand(raw,LocalDate.of(2026,11,1),LocalDate.of(2027,2,1),ZoneId.of("Europe/Paris"));
        assertEquals(2,out.size());assertEquals(Instant.parse("2027-01-11T08:00:00Z"),out.get(1).start);assertFalse(IcsCodec.inspect(raw,ZoneOffset.UTC).editable);
    }
    @Test public void titleOnlyEditPreservesFloatingSemanticsAndCustomParameters() {
        String raw=IcsCodecTest.ics("DTSTART:20261101T090000\r\nDTEND:20261101T100000\r\nSUMMARY;X-CUSTOM=keep:Old");
        var d=IcsCodec.inspect(raw,ZoneId.of("Europe/Paris")).draft;d.title="New";
        String updated=IcsCodec.save(raw,d);
        assertTrue(updated.contains("SUMMARY;X-CUSTOM=keep:New"));
        assertTrue(updated.contains("DTSTART:20261101T090000"));
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"),IcsCodec.expand(updated,LocalDate.of(2026,11,1),LocalDate.of(2026,11,2),ZoneId.of("America/New_York")).get(0).start);
    }
    @Test public void invalidDstGapAndReversedAllDayAreRejected() {
        var d=new IcsCodec.Draft();d.title="Gap";d.zone="Europe/Paris";d.start=LocalDateTime.of(2026,3,29,2,30);d.end=d.start.plusHours(1);
        assertThrows(IllegalArgumentException.class,()->IcsCodec.save(null,d));
        d.allDay=true;d.start=LocalDateTime.of(2026,11,1,0,0);d.end=d.start;
        assertThrows(IllegalArgumentException.class,()->IcsCodec.save(null,d));
    }
    @Test public void multiUidAndTruncationAreRejectedRatherThanDroppingContent() {
        String raw=IcsCodecTest.ics("DTSTART:20261101T090000Z");
        assertThrows(IllegalArgumentException.class,()->IcsCodec.inspect(raw.substring(0,raw.length()-12),ZoneOffset.UTC));
        String multi=raw.replace("END:VCALENDAR","BEGIN:VEVENT\r\nUID:another\r\nDTSTART:20261101T090000Z\r\nEND:VEVENT\r\nEND:VCALENDAR");
        assertThrows(IllegalArgumentException.class,()->IcsCodec.inspect(multi,ZoneOffset.UTC));
    }
}
