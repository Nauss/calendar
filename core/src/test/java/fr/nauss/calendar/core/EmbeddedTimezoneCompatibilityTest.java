package fr.nauss.calendar.core;

import java.time.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Legal vendor-qualified TZIDs must use their supplied definition, not a guessed IANA zone. */
public class EmbeddedTimezoneCompatibilityTest {
    private static final ZoneId UTC=ZoneOffset.UTC;
    private static String fixture() { return ImportedEditingTest.zoned("/fixture.invalid/Europe/Paris","+0530"); }
    @Test public void vendorQualifiedTimezoneWithItsOwnDefinitionIsReadable() {
        String raw=fixture();
        var occurrences=IcsCodec.expand(raw,LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),UTC);
        assertEquals(1,occurrences.size());
        assertEquals(Instant.parse("2026-11-17T03:30:00Z"),occurrences.get(0).start);
        assertEquals(Instant.parse("2026-11-17T04:30:00Z"),occurrences.get(0).end);
        var resource=new DavClient.Resource("https://fixture.invalid/cal/event","\"1\"",raw);
        assertEquals(raw,resource.ics);assertEquals("fixture-1",resource.uid);
    }
    @Test public void embeddedVendorTimezoneCanBeTextEditedWithoutRewritingTheRules() {
        String raw=fixture();var info=IcsCodec.inspect(raw,UTC);
        assertFalse(info.timeEditable);info.draft.title="Metadata only";
        String saved=IcsCodec.save(raw,info.draft);
        ImportedEditingTest.sameUneditedStructure(raw,saved);
        assertEquals(Instant.parse("2026-11-17T03:30:00Z"),IcsCodec.expand(saved,LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),UTC).get(0).start);
    }
    @Test public void vendorQualifiedTimezoneWithoutDefinitionMustNeverUseTheDisplayTimezone() {
        String raw=IcsCodecTest.ics("DTSTART;TZID=/fixture.invalid/Europe/Paris:20261117T090000");
        assertThrows(IcsCodec.UnsupportedException.class,()->IcsCodec.inspect(raw,UTC));
        assertThrows(IcsCodec.UnsupportedException.class,()->IcsCodec.inspect(raw,ZoneId.of("Asia/Tokyo")));
    }
    @Test public void completeReportWithVendorTimezoneNoLongerBlocksTheCalendar() throws Exception {
        String raw=fixture();
        String xml="<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'><d:response><d:href>/cal/vendor.ics</d:href><d:propstat><d:prop><d:getetag>&quot;1&quot;</d:getetag><c:calendar-data><![CDATA["+raw+"]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
        var client=new DavClient("https://fixture.invalid/cal/","fixture","synthetic",false);
        var rows=client.parseReport("https://fixture.invalid/cal/",xml);
        assertEquals(1,rows.size());assertEquals(raw.replace("\r\n","\n"),rows.get(0).ics);
    }
}
