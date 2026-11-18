package fr.nauss.calendar.core;

import java.time.ZoneOffset;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Failure diagnostics must describe a category, never echo an imported value. */
public class ReadFailureDiagnosticsTest {
    @Test public void invalidOffsetPrecisionHasADedicatedNonPrivateCategory() {
        String raw=ImportedEditingTest.zoned("Precision-Fixture","+010061");
        var failure=assertThrows(IcsCodec.UnsupportedException.class,()->IcsCodec.inspect(raw,ZoneOffset.UTC));
        assertEquals("OFFSET_PRECISION",failure.reason);assertNull(failure.getCause());
    }
    @Test public void unexpectedExceptionDiagnosticsContainOnlyAnAllowlistedKindAndCoreSite() {
        var failure=new IllegalArgumentException("private-fixture-title-and-password");
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("biweekly.private-input","private-title","private.ics",22),new StackTraceElement(IcsCodec.class.getName(),"expand","IcsCodec.java",417)});
        assertEquals("DATA_ARGUMENT_SITE_417",IcsCodec.readFailureReason(failure));
        assertEquals("DATA_ARGUMENT_SITE_417",IcsCodec.safeReadReason(IcsCodec.readFailureReason(failure)));
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("private.user.Class","private-title","private.ics",22)});
        assertEquals("DATA",IcsCodec.readFailureReason(failure));
        assertEquals("DATA",IcsCodec.safeReadReason("DATA_PRIVATE_SITE_417"));
    }
    @Test public void structuralFailureCarriesOnlyItsCompiledSourceSite() {
        var failure=assertThrows(IcsCodec.UnsupportedException.class,()->IcsCodec.inspect("private-fixture-input",ZoneOffset.UTC));
        assertTrue("Expected a private source-site code, got "+failure.reason,failure.reason.matches("DATA_SITE_[0-9]{1,4}"));
        assertEquals(failure.reason,IcsCodec.safeReadReason(failure.reason));
        assertNull(failure.getCause());assertFalse(failure.toString().contains("private-fixture-input"));
        assertEquals("DATA",IcsCodec.safeReadReason("DATA_SITE_10000"));
        assertEquals("DATA",IcsCodec.safeReadReason("DATA_SITE_12\nsecret"));
    }
    @Test public void parserFailureHasANumericCodeWithoutThePrivateInput() {
        String secret="private-fixture-title-password";
        var error=assertThrows(IcsCodec.UnsupportedException.class,()->IcsCodec.inspect(IcsCodecTest.ics("DTSTART:"+secret),ZoneOffset.UTC));
        assertTrue("Expected a numeric parser warning, got "+error.reason,error.reason.matches("PARSE_WARNING_[0-9]{1,3}"));
        assertFalse(error.toString().contains(secret));assertNull(error.getCause());
    }
    @Test public void unknownTimezoneNeverFallsBackSilentlyAndHasASpecificCategory() {
        var error=assertThrows(IcsCodec.UnsupportedException.class,()->IcsCodec.inspect(IcsCodecTest.ics("DTSTART;TZID=Unknown-Fixture-Zone:20261101T090000"),ZoneOffset.UTC));
        assertEquals("TIMEZONE",error.reason);
        assertFalse(error.toString().contains("Unknown-Fixture-Zone"));
    }
    @Test public void reportAndSyncKeepOnlyTheReadCategoryAndRetainThePreviousSnapshot() {
        var client=new DavClient("https://fixture.invalid/cal/","fixture","synthetic",false);
        String raw=IcsCodecTest.ics("DTSTART;TZID=Unknown-Fixture-Zone:20261101T090000\r\nSUMMARY:private-fixture-title");
        String xml="<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'><d:response><d:href>/cal/one.ics</d:href><d:propstat><d:prop><d:getetag>&quot;1&quot;</d:getetag><c:calendar-data><![CDATA["+raw+"]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
        var failure=assertThrows(DavClient.DataException.class,()->client.parseReport("https://fixture.invalid/cal/",xml));
        assertEquals("TIMEZONE",failure.reason);assertNull(failure.getCause());assertFalse(failure.toString().contains("private-fixture-title"));
        var store=new SyncEngineTest.Store();
        var engine=new SyncEngine(store,new SyncEngine.Transport() {
            public DavClient.Resource put(String c,String h,String e,String body) { throw new AssertionError("No writes"); }
            public void delete(String c,String h,String e) { throw new AssertionError("No writes"); }
            public List<DavClient.Resource> report(String c) throws java.io.IOException { throw failure; }
        });
        assertFalse(engine.sync("cal"));assertEquals("REPORT",engine.error());assertEquals("TIMEZONE",engine.readError());assertFalse(store.applied);
    }
    @Test public void arbitraryExceptionValuesCannotBecomeDiagnosticCodes() {
        for(String reason:new String[]{"private-fixture-title","https://private.invalid/secret","TIMEZONE\nsecret=1","PARSE_WARNING_1000"}) {
            var failure=new DavClient.DataException(reason);
            assertEquals("DATA",failure.reason);assertFalse(failure.toString().contains(reason));assertNull(failure.getCause());
            assertEquals("DATA",new IcsCodec.UnsupportedException(reason).reason);
        }
    }
    @Test public void directGetKeepsTheSamePrivateReadCategoryAsReport() throws Exception {
        var server=new okhttp3.mockwebserver.MockWebServer();
        server.start(java.net.InetAddress.getByAddress("127.0.0.1",new byte[]{127,0,0,1}),0);
        try {
            String root=server.url("/cal/").newBuilder().host("127.0.0.1").build().toString();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("ETag","\"1\"").setBody(IcsCodecTest.ics("DTSTART;TZID=Unknown-Fixture-Zone:20261101T090000")));
            var client=new DavClient(root,"fixture","synthetic",true);
            var failure=assertThrows(DavClient.DataException.class,()->client.get(root,root+"one.ics"));
            assertEquals("TIMEZONE",failure.reason);assertNull(failure.getCause());assertFalse(failure.toString().contains("Unknown-Fixture-Zone"));
        } finally { server.shutdown(); }
    }
}
