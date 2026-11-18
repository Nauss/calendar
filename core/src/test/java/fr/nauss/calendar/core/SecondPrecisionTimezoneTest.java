package fr.nauss.calendar.core;

import java.time.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Seconds in embedded offsets must be read and written exactly, not rounded or rejected. */
public class SecondPrecisionTimezoneTest {
    private static final ZoneId UTC=ZoneOffset.UTC;
    private static final LocalDate DAY=LocalDate.of(2026,11,17);
    @Test public void positiveSecondOffsetControlsTheActualOccurrenceInstant() {
        String raw=ImportedEditingTest.zoned("Second-Precision-Fixture","+010021");
        var result=IcsCodec.expand(raw,DAY,DAY.plusDays(1),UTC);
        assertEquals(1,result.size());assertEquals(Instant.parse("2026-11-17T07:59:39Z"),result.get(0).start);
        assertEquals(Instant.parse("2026-11-17T08:59:39Z"),result.get(0).end);
        assertEquals(raw,new DavClient.Resource("https://fixture.invalid/cal/seconds.ics","\"1\"",raw).ics);
    }
    @Test public void metadataEditRetainsRawSecondsAndReconciliationDistinguishesRounding() {
        String raw=ImportedEditingTest.zoned("Second-Precision-Fixture","-000021");
        var info=IcsCodec.inspect(raw,UTC);assertTrue(info.editable);assertFalse(info.timeEditable);
        info.draft.title="Changed metadata";String saved=IcsCodec.save(raw,info.draft);
        assertTrue(saved.contains("TZOFFSETFROM:-000021"));assertTrue(saved.contains("TZOFFSETTO:-000021"));
        assertTrue(saved.contains("TZID:Second-Precision-Fixture"));assertTrue(saved.contains("UID:fixture-1"));
        assertEquals(Instant.parse("2026-11-17T09:00:21Z"),IcsCodec.expand(saved,DAY,DAY.plusDays(1),UTC).get(0).start);
        assertFalse(IcsCodec.equivalent(raw,raw.replace("-000021","+0000")));
    }
    @Test public void historicalSecondOffsetsDoNotBlockModernDatesOrDisappearDuringAnEdit() {
        String raw=ImportedEditingTest.paris().replace("X-FIXTURE:retain\r\nBEGIN:DAYLIGHT",
            "X-FIXTURE:retain\r\nBEGIN:STANDARD\r\nDTSTART:18910101T000000\r\nTZOFFSETFROM:+000921\r\nTZOFFSETTO:+000921\r\nEND:STANDARD\r\nBEGIN:STANDARD\r\nDTSTART:19110311T000000\r\nTZOFFSETFROM:+000921\r\nTZOFFSETTO:+0000\r\nEND:STANDARD\r\nBEGIN:DAYLIGHT");
        assertEquals(Instant.parse("2026-11-17T08:00:00Z"),IcsCodec.expand(raw,DAY,DAY.plusDays(1),UTC).get(0).start);
        var draft=IcsCodec.inspect(raw,UTC).draft;draft.title="Modern event, old timezone history";
        String saved=IcsCodec.save(raw,draft);assertTrue(saved.contains("TZOFFSETTO:+000921"));assertTrue(saved.contains("TZOFFSETFROM:+000921"));
        assertEquals(Instant.parse("2026-11-17T08:00:00Z"),IcsCodec.expand(saved,DAY,DAY.plusDays(1),UTC).get(0).start);
    }
    @Test public void recurringInstancesRetainSecondPrecisionAcrossADaylightTransition() {
        String raw=ImportedEditingTest.paris().replace("+0100","+010021").replace("+0200","+020021")
            .replace("20261117T090000","20261024T090000").replace("20261117T100000","20261024T100000")
            .replace("END:VEVENT","RRULE:FREQ=DAILY;COUNT=3\r\nEND:VEVENT");
        var result=IcsCodec.expand(raw,LocalDate.of(2026,10,24),LocalDate.of(2026,10,27),UTC);
        assertEquals(java.util.List.of("2026-10-24T06:59:39Z","2026-10-25T07:59:39Z","2026-10-26T07:59:39Z"),result.stream().map(o->o.start.toString()).toList());
        var draft=IcsCodec.inspect(raw,UTC).draft;draft.title="Precision recurrence";
        assertEquals(result.stream().map(o->o.start).toList(),IcsCodec.expand(IcsCodec.save(raw,draft),LocalDate.of(2026,10,24),LocalDate.of(2026,10,27),UTC).stream().map(o->o.start).toList());
    }
    @Test public void malformedOffsetsAreNeverRepairedOrDefaulted() {
        for(String value:new String[]{"+240000","+006000","+000060","-0000","-000000","010021","+01:00:21","+0100210","+0100\\nprivate"}) {
            var failure=assertThrows(IcsCodec.UnsupportedException.class,()->IcsCodec.inspect(ImportedEditingTest.zoned("Precision-Fixture",value),UTC));
            assertEquals("OFFSET_PRECISION",failure.reason);
        }
    }
    @Test public void reportWithValidSecondOffsetsIsAppliedAsACompleteSnapshot() throws Exception {
        String raw=ImportedEditingTest.zoned("Second-Precision-Fixture","+010021");
        String xml="<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'><d:response><d:href>/cal/seconds.ics</d:href><d:propstat><d:prop><d:getetag>&quot;1&quot;</d:getetag><c:calendar-data><![CDATA["+raw+"]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
        var client=new DavClient("https://fixture.invalid/cal/","fixture","synthetic",false);
        var result=client.parseReport("https://fixture.invalid/cal/",xml);assertEquals(1,result.size());
        assertEquals(raw.replace("\r\n","\n"),result.get(0).ics);assertTrue(result.get(0).ics.contains("TZOFFSETTO:+010021"));
    }
}
