package fr.nauss.calendar.core;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import biweekly.property.*;
import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;

/** Synthetic imported resources only. Compare parsed structures, not line folding. */
public class ImportedEditingTest {
    static final ZoneId UTC=ZoneOffset.UTC;
    static String embedded(String id, String offset) {
        return "BEGIN:VTIMEZONE\r\nTZID:"+id+"\r\nX-FIXTURE:retain\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:"+offset+"\r\nTZOFFSETTO:"+offset+"\r\nTZNAME:Fixture\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n";
    }
    static String zoned(String id, String offset) {
        return IcsCodecTest.ics("DTSTART;TZID="+id+";X-DATE=keep:20261117T090000\r\nDTEND;TZID="+id+":20261117T100000\r\nSUMMARY;LANGUAGE=fr;X-TEXT=keep:Before\r\nLOCATION;X-LOCATION=keep:Here\r\nDESCRIPTION;ALTREP=\"https://fixture.invalid/notes\":Notes\r\nX-UNKNOWN;X-PARAM=keep:opaque")
            .replace("BEGIN:VEVENT",embedded(id,offset)+"BEGIN:VEVENT");
    }
    static ICalendar parsed(String raw) { return Biweekly.parse(raw).first(); }
    static String paris() {
        String timezone="BEGIN:VTIMEZONE\r\nTZID:Europe/Paris\r\nX-FIXTURE:retain\r\n"
            +"BEGIN:DAYLIGHT\r\nDTSTART:19700329T020000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nEND:DAYLIGHT\r\n"
            +"BEGIN:STANDARD\r\nDTSTART:19701025T030000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n";
        return zoned("Europe/Paris","+0100").replace(embedded("Europe/Paris","+0100"),timezone);
    }
    @Test public void commonEmbeddedIanaCanMoveAcrossBothDstTransitions() {
        for(String local:new String[]{"2026-03-29T09:00","2026-10-25T09:00"}) {
            String raw=paris();var d=IcsCodec.inspect(raw,UTC).draft;
            d.start=LocalDateTime.parse(local);d.end=d.start.plusHours(1);d.title="Moved";
            String saved=IcsCodec.save(raw,d);
            assertEquals(d.start,IcsCodec.inspect(saved,UTC).draft.start);
            assertEquals(d.end,IcsCodec.inspect(saved,UTC).draft.end);
            var occurrence=IcsCodec.expand(saved,d.start.toLocalDate(),d.start.toLocalDate().plusDays(1),UTC).get(0);
            assertEquals(d.start.atZone(ZoneId.of("Europe/Paris")).toInstant(),occurrence.start);
            assertEquals(d.end.atZone(ZoneId.of("Europe/Paris")).toInstant(),occurrence.end);
            assertEquals(parsed(raw).getTimezoneInfo().getComponents().toString(),parsed(saved).getTimezoneInfo().getComponents().toString());
            assertTrue(saved.contains("X-DATE=keep"));assertTrue(saved.contains("X-TEXT=keep"));
        }
    }
    @Test public void embeddedNameMatchingOnlyAtOriginCannotMoveIntoDifferentRules() {
        String raw=zoned("Europe/Paris","+0100");var d=IcsCodec.inspect(raw,UTC).draft;
        d.start=LocalDateTime.of(2026,7,1,9,0);d.end=d.start.plusHours(1);
        assertThrows(IllegalArgumentException.class,()->IcsCodec.save(raw,d));
    }
    @Test public void embeddedSeriesRulesAndAmbiguousLocalTimeAreNeverRewritten() {
        String series=paris().replace("END:VEVENT","RRULE:FREQ=WEEKLY\r\nEND:VEVENT");
        var d=IcsCodec.inspect(series,UTC).draft;d.title="Metadata";
        sameUneditedStructure(series,IcsCodec.save(series,d));
        d.repeat="DAILY";assertThrows(IllegalArgumentException.class,()->IcsCodec.save(series,d));
        d.repeat="WEEKLY";d.start=d.start.plusHours(1);d.end=d.end.plusHours(1);
        assertThrows(IllegalArgumentException.class,()->IcsCodec.save(series,d));
        for(String time:new String[]{"2026-03-29T02:30","2026-10-25T02:30"}) {
            var gap=IcsCodec.inspect(paris(),UTC).draft;gap.start=LocalDateTime.parse(time);gap.end=gap.start.plusHours(1);
            assertThrows(IllegalArgumentException.class,()->IcsCodec.save(paris(),gap));
        }
    }
    @Test public void embeddedOverlapMustBeUniqueEvenWhenPlatformWallTimeIsUnique() {
        String raw=paris().replace("BYMONTH=10;BYDAY=-1SU","BYMONTH=10;BYDAY=3SU");
        var info=IcsCodec.inspect(raw,UTC);assertTrue(info.timeEditable);
        info.draft.start=LocalDateTime.parse("2026-10-18T01:30");
        info.draft.end=LocalDateTime.parse("2026-10-18T02:30");
        assertEquals(1,ZoneId.of("Europe/Paris").getRules().getValidOffsets(info.draft.end).size());
        assertThrows(IllegalArgumentException.class,()->IcsCodec.save(raw,info.draft));
    }
    @Test public void unsupportedEmbeddedNonHourOffsetStillAllowsMetadataOnly() {
        // Non-hour minute offsets are losslessly supported. Nonzero seconds are
        // protected separately by RawOffsetPreservationTest, not normalized away.
        // The assigned biweekly timezone does not agree with IANA at this origin.
        String raw=paris().replace("BYMONTH=10;BYDAY=-1SU","BYMONTH=10;BYDAY=3SU")
            .replace("TZOFFSETFROM:+0100","TZOFFSETFROM:+0129")
            .replace("TZOFFSETTO:+0100","TZOFFSETTO:+0129")
            .replace("20261117T090000","20260717T090000").replace("20261117T100000","20260717T100000");
        var info=IcsCodec.inspect(raw,UTC);assertFalse(info.timeEditable);assertTrue(info.editable);
        info.draft.title="Metadata remains editable";
        String saved=IcsCodec.save(raw,info.draft);
        sameUneditedStructure(raw,saved);
        assertTrue(saved.contains("TZOFFSETFROM:+0129"));assertTrue(saved.contains("TZOFFSETTO:+0129"));
        info.draft.start=LocalDateTime.parse("2026-10-18T01:30");
        info.draft.end=LocalDateTime.parse("2026-10-18T02:29:50");
        assertThrows(IllegalArgumentException.class,()->IcsCodec.save(raw,info.draft));
    }
    @Test public void embeddedGapOnDifferentDateFromPlatformIsRejected() {
        String raw=paris().replace("BYMONTH=3;BYDAY=-1SU","BYMONTH=3;BYDAY=3SU");
        var d=IcsCodec.inspect(raw,UTC).draft;
        d.start=LocalDateTime.parse("2026-03-22T01:30");d.end=LocalDateTime.parse("2026-03-22T02:30");
        assertThrows(IllegalArgumentException.class,()->IcsCodec.save(raw,d));
    }
    @Test public void unchangedAmbiguousEndpointAndMetadataKeepOriginalInstant() {
        String raw=paris().replace("20261117T090000","20261025T023000").replace("20261117T100000","20261025T040000");
        var d=IcsCodec.inspect(raw,UTC).draft;d.title="Only metadata";
        sameUneditedStructure(raw,IcsCodec.save(raw,d));
        var original=parsed(raw).getEvents().get(0).getDateStart().getValue().toInstant();
        d.end=d.end.plusHours(1);
        assertEquals(original,parsed(IcsCodec.save(raw,d)).getEvents().get(0).getDateStart().getValue().toInstant());
    }
    static void sameUneditedStructure(String before,String after) {
        ICalendar a=parsed(before),b=parsed(after);
        VEvent x=a.getEvents().get(0),y=b.getEvents().get(0);
        for(Class<? extends ICalProperty> type:java.util.List.of(Summary.class,Location.class,Description.class,DateTimeStamp.class,LastModified.class,Sequence.class)) {
            x.removeProperties(type);y.removeProperties(type);
        }
        assertEquals(a.write(),b.write());
    }
    @Test public void embeddedIanaZoneAllowsMetadataWithoutReplacingRules() {
        String raw=zoned("Europe/Paris","+0100");
        var info=IcsCodec.inspect(raw,UTC);assertTrue(info.editable);
        info.draft.title="After";info.draft.location="Elsewhere";info.draft.notes="More notes";
        String saved=IcsCodec.save(raw,info.draft);sameUneditedStructure(raw,saved);
        assertTrue(saved.contains("X-TEXT=keep"));assertTrue(saved.contains("X-LOCATION=keep"));assertTrue(saved.contains("ALTREP="));
        assertEquals("After",IcsCodec.inspect(saved,UTC).draft.title);
    }
    @Test public void ianaNameIsNotProofOfEmbeddedRulesAndTimeChangesFailClosed() {
        String raw=zoned("Europe/Paris","+0700");
        var d=IcsCodec.inspect(raw,UTC).draft;d.title="Text only";
        String saved=IcsCodec.save(raw,d);sameUneditedStructure(raw,saved);
        assertEquals(Instant.parse("2026-11-17T02:00:00Z"),IcsCodec.expand(saved,LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),UTC).get(0).start);
        d.start=d.start.plusHours(1);d.end=d.end.plusHours(1);
        assertThrows(IllegalArgumentException.class,()->IcsCodec.save(raw,d));
    }
    @Test public void customZoneAndZeroDurationAreSafeForTextOnly() {
        for(String raw:new String[]{zoned("Fixture/Custom","+0530"),IcsCodecTest.ics("DTSTART:20261117T090000Z\r\nSUMMARY:Before"),IcsCodecTest.ics("DTSTART:20261117T090000Z\r\nDTEND:20261117T090000Z\r\nSUMMARY:Before")}) {
            var d=IcsCodec.inspect(raw,UTC).draft;d.title="After";
            sameUneditedStructure(raw,IcsCodec.save(raw,d));
        }
    }
    @Test public void advancedSeriesAndExceptionsRetainTheirEntireStructure() {
        String raw=IcsCodecTest.ics("DTSTART:20261117T090000Z\r\nDTEND:20261117T100000Z\r\nSUMMARY:Before\r\nRRULE;X-RULE=keep:FREQ=WEEKLY;INTERVAL=2;COUNT=8;BYDAY=TU,TH\r\nEXDATE:20261119T090000Z\r\nRDATE:20261120T090000Z")
            .replace("END:VCALENDAR","BEGIN:VEVENT\r\nUID:fixture-1\r\nRECURRENCE-ID:20261201T090000Z\r\nDTSTART:20261201T120000Z\r\nDTEND:20261201T130000Z\r\nSUMMARY:Exception\r\nX-EXCEPTION:keep\r\nEND:VEVENT\r\nEND:VCALENDAR");
        var d=IcsCodec.inspect(raw,UTC).draft;d.title="Series metadata";
        sameUneditedStructure(raw,IcsCodec.save(raw,d));
        d.repeat="NONE";assertThrows(IllegalArgumentException.class,()->IcsCodec.save(raw,d));
        d.repeat="WEEKLY";d.start=d.start.plusMinutes(30);assertThrows(IllegalArgumentException.class,()->IcsCodec.save(raw,d));
    }
    @Test public void complexAlarmsAreNotRemovedByUnchangedMinusOneSentinel() {
        String raw=IcsCodecTest.ics("DTSTART:20261117T090000Z\r\nSUMMARY:Before\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nDESCRIPTION:Keep\r\nX-ALARM:opaque\r\nEND:VALARM\r\nBEGIN:VALARM\r\nACTION:AUDIO\r\nTRIGGER:-PT5M\r\nEND:VALARM");
        var d=IcsCodec.inspect(raw,UTC).draft;assertEquals(-1,d.reminderMinutes);d.title="After";
        sameUneditedStructure(raw,IcsCodec.save(raw,d));
        d.reminderMinutes=15;assertThrows(IllegalArgumentException.class,()->IcsCodec.save(raw,d));
    }
    @Test public void simpleReminderCanChangeWithoutTouchingEmbeddedTimezone() {
        String raw=zoned("Europe/Paris","+0700").replace("END:VEVENT","BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;X-TRIGGER=keep:-PT10M\r\nDESCRIPTION;LANGUAGE=fr:Original reminder\r\nEND:VALARM\r\nEND:VEVENT");
        var d=IcsCodec.inspect(raw,UTC).draft;d.reminderMinutes=30;
        String saved=IcsCodec.save(raw,d);assertEquals(30,IcsCodec.inspect(saved,UTC).draft.reminderMinutes);
        assertTrue(saved.contains("X-TRIGGER=keep"));assertTrue(saved.contains("Original reminder"));
        assertTrue(saved.contains("TZOFFSETTO:+0700"));
    }
    @Test public void meetingRemainsBlockedAndOriginalUnmodified() {
        String raw=zoned("Europe/Paris","+0100").replace("END:VEVENT","ORGANIZER;CN=Fixture:mailto:owner@example.invalid\r\nATTENDEE;PARTSTAT=ACCEPTED:mailto:guest@example.invalid\r\nEND:VEVENT");
        assertFalse(IcsCodec.inspect(raw,UTC).editable);
        var d=IcsCodec.inspect(raw,UTC).draft;d.title="No";
        assertThrows(IllegalArgumentException.class,()->IcsCodec.save(raw,d));
    }
    @Test public void ordinaryTimeEditPreservesRecurrenceParametersAndAlarm() {
        String raw=IcsCodecTest.ics("DTSTART;TZID=Europe/Paris:20261117T090000\r\nDTEND;TZID=Europe/Paris:20261117T100000\r\nSUMMARY:Before\r\nRRULE;X-RULE=keep:FREQ=WEEKLY\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nDESCRIPTION:Do not rewrite\r\nEND:VALARM");
        var d=IcsCodec.inspect(raw,UTC).draft;d.start=d.start.plusHours(1);d.end=d.end.plusHours(1);
        String saved=IcsCodec.save(raw,d);assertEquals(d.start,IcsCodec.inspect(saved,UTC).draft.start);
        assertEquals(parsed(raw).getEvents().get(0).getRecurrenceRule(),parsed(saved).getEvents().get(0).getRecurrenceRule());
        assertEquals(parsed(raw).getEvents().get(0).getAlarms(),parsed(saved).getEvents().get(0).getAlarms());
    }
}
