package fr.nauss.calendar.core;

import org.junit.Test;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

public class IcsCodecTest {
    static final ZoneId UTC = ZoneId.of("UTC");
    static String ics(String body) { return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Fixture//EN\r\nBEGIN:VEVENT\r\nUID:fixture-1\r\nDTSTAMP:20260101T000000Z\r\n" + body + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"; }
    @Test public void utcAndExclusiveAllDayEnd() {
        var x = IcsCodec.expand(ics("DTSTART;VALUE=DATE:20261101\r\nDTEND;VALUE=DATE:20261103\r\nSUMMARY:Holiday"), LocalDate.of(2026,11,1), LocalDate.of(2026,11,5), UTC);
        assertEquals(1,x.size()); assertTrue(x.get(0).allDay);
        assertEquals(Instant.parse("2026-11-03T00:00:00Z"),x.get(0).end);
    }
    @Test public void rruleExdateAndOverride() {
        String base = ics("DTSTART:20261101T090000Z\r\nDTEND:20261101T100000Z\r\nRRULE:FREQ=DAILY;COUNT=4\r\nEXDATE:20261102T090000Z\r\nSUMMARY:Base");
        base = base.replace("END:VCALENDAR", "BEGIN:VEVENT\r\nUID:fixture-1\r\nRECURRENCE-ID:20261103T090000Z\r\nDTSTART:20261103T110000Z\r\nDTEND:20261103T120000Z\r\nSUMMARY:Moved\r\nEND:VEVENT\r\nEND:VCALENDAR");
        var x = IcsCodec.expand(base, LocalDate.of(2026,11,1),LocalDate.of(2026,11,6),UTC);
        assertEquals(3,x.size()); assertEquals("Moved",x.get(1).title);
        assertEquals(Instant.parse("2026-11-03T11:00:00Z"),x.get(1).start);
        assertTrue(IcsCodec.inspect(base,UTC).editable);assertFalse(IcsCodec.inspect(base,UTC).timeEditable);
    }
    @Test public void floatingTimeUsesDisplayZone() {
        var x = IcsCodec.expand(ics("DTSTART:20261101T090000\r\nDTEND:20261101T100000"),LocalDate.of(2026,11,1),LocalDate.of(2026,11,2),ZoneId.of("Europe/Paris"));
        assertEquals(Instant.parse("2026-11-01T08:00:00Z"), x.get(0).start);
    }
    @Test public void timezoneAcrossDst() {
        var x = IcsCodec.expand(ics("DTSTART;TZID=Europe/Paris:20261024T090000\r\nDTEND;TZID=Europe/Paris:20261024T100000\r\nRRULE:FREQ=DAILY;COUNT=3"),LocalDate.of(2026,10,24),LocalDate.of(2026,10,28),UTC);
        assertEquals(3,x.size()); assertEquals(Instant.parse("2026-10-25T08:00:00Z"),x.get(1).start);
    }
    @Test public void editPreservesUnknownPropertiesAndUid() {
        String original=ics("DTSTART:20261101T090000Z\r\nDTEND:20261101T100000Z\r\nX-PRIVATE-PROP;X-PARAM=value:retain me\r\nSUMMARY:Old");
        var d=IcsCodec.inspect(original,UTC).draft; d.title="Changed";
        String edited=IcsCodec.save(original,d);
        assertTrue(edited.contains("X-PRIVATE-PROP;X-PARAM=value:retain me"));
        assertEquals("fixture-1", IcsCodec.inspect(edited,UTC).uid);
        assertEquals("Changed",IcsCodec.inspect(edited,UTC).draft.title);
    }
    @Test public void meetingBlockedAndComplexSeriesAllowsOnlyPreservableFields() {
        String a=ics("DTSTART:20261101T090000Z\r\nORGANIZER:mailto:someone@example.invalid\r\nSUMMARY:Meeting");
        assertFalse(IcsCodec.inspect(a,UTC).editable);
        assertThrows(IllegalArgumentException.class,()->IcsCodec.save(a,new IcsCodec.Draft()));
        String b=ics("DTSTART:20261101T090000Z\r\nRRULE:FREQ=MONTHLY;BYDAY=2MO");
        assertTrue(IcsCodec.inspect(b,UTC).editable);assertFalse(IcsCodec.inspect(b,UTC).recurrenceEditable);assertFalse(IcsCodec.inspect(b,UTC).timeEditable);
    }
    @Test public void newRoundTripTimezoneReminderAndEscaping() {
        var d=new IcsCodec.Draft(); d.title="A, B; C\nD"; d.zone="Europe/Paris";
        d.start=LocalDateTime.of(2026,10,24,9,0); d.end=d.start.plusHours(1); d.repeat="DAILY"; d.reminderMinutes=10;
        String s=IcsCodec.save(null,d); var info=IcsCodec.inspect(s,UTC);
        assertEquals(d.title,info.draft.title); assertEquals(10,info.draft.reminderMinutes);
        var x=IcsCodec.expand(s,LocalDate.of(2026,10,24),LocalDate.of(2026,10,27),UTC);
        assertEquals(Instant.parse("2026-10-25T08:00:00Z"),x.get(1).start);
    }
    @Test public void malformedAndUnboundedWindowsFailClosed() {
        assertThrows(IllegalArgumentException.class,()->IcsCodec.inspect("not ICS",UTC));
        assertThrows(IllegalArgumentException.class,()->IcsCodec.inspect(ics("DTSTART:nonsense"),UTC));
        assertThrows(IllegalArgumentException.class,()->IcsCodec.expand(ics("DTSTART:20260101T000000Z\r\nRRULE:FREQ=SECONDLY"),LocalDate.of(2026,1,1),LocalDate.of(2026,1,4),UTC));
    }
}
