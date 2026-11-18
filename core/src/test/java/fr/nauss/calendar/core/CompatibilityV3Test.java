package fr.nauss.calendar.core;

import java.time.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic fixtures only; no server or account is used. */
public class CompatibilityV3Test {
    private static final LocalDate DAY=LocalDate.of(2026,11,1);
    private static final ZoneId UTC=ZoneOffset.UTC;
    @Test public void modestHourlySeriesExpandsAndKeepsImportedTimingProtected() {
        String raw=IcsCodecTest.ics("DTSTART:20261101T090000Z\r\nDURATION:PT30M\r\nRRULE:FREQ=HOURLY;INTERVAL=3;COUNT=4\r\nSUMMARY:Hourly fixture");
        var out=IcsCodec.expand(raw,DAY,DAY.plusDays(1),UTC);
        assertEquals(4,out.size());assertEquals(Instant.parse("2026-11-01T18:00:00Z"),out.get(3).start);
        assertEquals(Instant.parse("2026-11-01T18:30:00Z"),out.get(3).end);
        var info=IcsCodec.inspect(raw,UTC);assertFalse(info.timeEditable);assertFalse(info.recurrenceEditable);
        info.draft.start=info.draft.start.plusHours(1);
        assertThrows(IllegalArgumentException.class,()->IcsCodec.save(raw,info.draft));
    }
    @Test public void hourlyExdateAndMovedInstanceKeepOriginalOccurrenceIdentity() {
        String raw=IcsCodecTest.ics("DTSTART:20261101T090000Z\r\nDURATION:PT15M\r\nRRULE:FREQ=HOURLY;COUNT=4\r\nEXDATE:20261101T100000Z");
        raw=raw.replace("END:VCALENDAR","BEGIN:VEVENT\r\nUID:fixture-1\r\nRECURRENCE-ID:20261101T110000Z\r\nDTSTART:20261101T113000Z\r\nDURATION:PT15M\r\nEND:VEVENT\r\nEND:VCALENDAR");
        var out=IcsCodec.expand(raw,DAY,DAY.plusDays(1),UTC);
        assertEquals(3,out.size());assertEquals("2026-11-01T11:00:00Z",out.get(1).key);
        assertEquals(Instant.parse("2026-11-01T11:30:00Z"),out.get(1).start);
    }
    @Test public void hourlyFloatingSeriesUsesDisplayZone() {
        String raw=IcsCodecTest.ics("DTSTART:20261101T090000\r\nRRULE:FREQ=HOURLY;INTERVAL=6;COUNT=2");
        var out=IcsCodec.expand(raw,DAY,DAY.plusDays(1),ZoneId.of("Europe/Paris"));
        assertEquals(2,out.size());assertEquals(Instant.parse("2026-11-01T08:00:00Z"),out.get(0).start);
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"),out.get(1).start);
    }
    @Test public void hourlyBudgetsRemainFailClosed() {
        assertThrows(IllegalArgumentException.class,()->IcsCodec.expand(IcsCodecTest.ics("DTSTART:20260101T000000Z\r\nRRULE:FREQ=HOURLY"),LocalDate.of(2026,1,1),LocalDate.of(2027,1,1),UTC));
        assertThrows(IllegalArgumentException.class,()->IcsCodec.expand(IcsCodecTest.ics("DTSTART:20000101T000000Z\r\nRRULE:FREQ=HOURLY"),DAY,DAY.plusDays(1),UTC));
        assertThrows(IllegalArgumentException.class,()->IcsCodec.expand(IcsCodecTest.ics("DTSTART:20261101T090000Z\r\nRRULE:FREQ=HOURLY;BYMINUTE=0,30"),DAY,DAY.plusDays(1),UTC));
    }
    @Test public void hourlyAcrossDstFailsVisiblyRatherThanReturningDuplicateOrShiftedTimes() {
        String spring=IcsCodecTest.ics("DTSTART;TZID=Europe/Paris:20260329T003000\r\nDURATION:PT15M\r\nRRULE:FREQ=HOURLY;COUNT=4");
        assertEquals("TIMEZONE",assertThrows(IcsCodec.UnsupportedException.class,()->IcsCodec.expand(spring,LocalDate.of(2026,3,28),LocalDate.of(2026,3,30),UTC)).reason);
        String autumn=IcsCodecTest.ics("DTSTART;TZID=Europe/Paris:20261025T003000\r\nRRULE:FREQ=HOURLY;COUNT=4");
        assertEquals("TIMEZONE",assertThrows(IcsCodec.UnsupportedException.class,()->IcsCodec.expand(autumn,LocalDate.of(2026,10,24),LocalDate.of(2026,10,26),UTC)).reason);
        String floating=spring.replace(";TZID=Europe/Paris","");
        assertThrows(IcsCodec.UnsupportedException.class,()->IcsCodec.expand(floating,LocalDate.of(2026,3,28),LocalDate.of(2026,3,30),ZoneId.of("Europe/Paris")));
    }
    @Test public void hourlyIanaTimezoneWithoutTransitionIsSupported() {
        String raw=IcsCodecTest.ics("DTSTART;TZID=Europe/Paris:20261101T090000\r\nRRULE:FREQ=HOURLY;COUNT=2");
        var out=IcsCodec.expand(raw,DAY,DAY.plusDays(1),UTC);
        assertEquals(java.util.List.of("2026-11-01T08:00:00Z","2026-11-01T09:00:00Z"),out.stream().map(o->o.start.toString()).toList());
    }
    @Test public void textOnlyHourlyEditPreservesUidAndOccurrences() {
        String raw=IcsCodecTest.ics("DTSTART:20261101T090000Z\r\nDURATION:PT30M\r\nRRULE:FREQ=HOURLY;INTERVAL=3;COUNT=4\r\nX-FIXTURE:keep\r\nSUMMARY:Old");
        var info=IcsCodec.inspect(raw,UTC);info.draft.title="New fixture title";
        String saved=IcsCodec.save(raw,info.draft);
        assertEquals(info.uid,IcsCodec.inspect(saved,UTC).uid);assertTrue(saved.contains("X-FIXTURE:keep"));
        assertEquals(IcsCodec.expand(raw,DAY,DAY.plusDays(1),UTC).stream().map(o->o.start.toString()+o.end+o.key).toList(),IcsCodec.expand(saved,DAY,DAY.plusDays(1),UTC).stream().map(o->o.start.toString()+o.end+o.key).toList());
    }
    @Test public void validLongOneOffDurationNoLongerBlocksResourceAdmission() {
        String raw=IcsCodecTest.ics("DTSTART;VALUE=DATE:20260101\r\nDURATION:P400D\r\nSUMMARY:Long absence fixture");
        var resource=new DavClient.Resource("https://fixture.invalid/cal/long.ics","\"1\"",raw);
        assertEquals(raw,resource.ics);assertEquals("fixture-1",resource.uid);
        var out=IcsCodec.expand(raw,DAY,DAY.plusDays(1),UTC);
        assertEquals(1,out.size());assertEquals(LocalDate.of(2026,1,1).plusDays(400).atStartOfDay(UTC).toInstant(),out.get(0).end);
    }
    @Test public void longRecurringDurationRemainsBounded() {
        String raw=IcsCodecTest.ics("DTSTART;VALUE=DATE:20260101\r\nDURATION:P400D\r\nRRULE:FREQ=YEARLY");
        assertThrows(IllegalArgumentException.class,()->IcsCodec.expand(raw,DAY,DAY.plusDays(1),UTC));
    }
}
