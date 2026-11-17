package fr.nauss.calendar.core;

import org.junit.*;
import okhttp3.mockwebserver.*;
import static org.junit.Assert.*;
import java.util.*;

public class DavSafetyTest {
    MockWebServer server; DavClient client;
    @Before public void setup() throws Exception { server=new MockWebServer();server.start();client=new DavClient(url("/"),"fixture","synthetic",true); }
    @After public void cleanup() throws Exception { server.shutdown(); }
    String url(String path) { return server.url(path).newBuilder().host("127.0.0.1").build().toString(); }
    String resource(String href,String uid) {
        String raw=IcsCodecTest.ics("DTSTART:20261101T090000Z\r\nSUMMARY:Fixture").replace("fixture-1",uid);
        return "<d:response><d:href>"+href+"</d:href><d:propstat><d:prop><d:getetag>&quot;1&quot;</d:getetag><c:calendar-data><![CDATA["+raw+"]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }
    void report(String rows) { server.enqueue(new MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'>"+rows+"</d:multistatus>")); }
    @Test public void etagCannotInjectWildcardOrMultipleConditions() {
        for(String value:List.of("\"a\", *, \"b\"","\"a\"\"b\"","\"a\u0001b\"","W/\"a\"","*")) assertFalse(value,DavClient.strongEtag(value));
        assertTrue(DavClient.strongEtag("\"abc-123\""));
    }
    @Test public void percentEncodedPathTraversalCannotEscapeCollection() {
        report(resource("/cal/a%2F..%2Foutside.ics","fixture-1"));
        assertThrows(Exception.class,()->client.report(url("/cal/")));
    }
    @Test public void emptyErrorEnvelopeNeverMeansEmptyCalendar() {
        report("<d:responsedescription>truncated response</d:responsedescription><d:limit-reached/>");
        assertThrows(java.io.IOException.class,()->client.report(url("/cal/")));
    }
    @Test public void duplicateUidOrHrefRejectsWholeSnapshot() {
        report(resource("/cal/a.ics","same")+resource("/cal/b.ics","same"));
        assertThrows(java.io.IOException.class,()->client.report(url("/cal/")));
        report(resource("/cal/a.ics","one")+resource("/cal/a.ics","two"));
        assertThrows(java.io.IOException.class,()->client.report(url("/cal/")));
    }
    @Test public void foreignHrefNeverReceivesCredentials() {
        assertThrows(IllegalArgumentException.class,()->client.get("http://localhost:"+server.getPort()+"/cal/a.ics"));
        assertThrows(IllegalArgumentException.class,()->client.get(url("/cal/a.ics")+"?token=bad"));
        assertEquals(0,server.getRequestCount());
    }
    @Test public void createConflictDoesNotReconcileByUidAlone() {
        String original=IcsCodecTest.ics("DTSTART:20261101T090000Z\r\nSUMMARY:Original");
        server.enqueue(new MockResponse().setResponseCode(412));server.enqueue(new MockResponse().setHeader("ETag","\"2\"").setBody(original.replace("SUMMARY:Original","SUMMARY:Remote changed")));
        assertThrows(DavClient.ConflictException.class,()->client.put(url("/cal/a.ics"),null,original));
        assertEquals(2,server.getRequestCount());
    }
    @Test public void directCalendarDiscoveryAndNamespacedColor() throws Exception {
        report("<d:response><d:href>/cal/</d:href><d:propstat><d:prop><d:resourcetype><c:calendar/></d:resourcetype><d:displayname>Direct</d:displayname><a:calendar-color xmlns:a='http://apple.com/ns/ical/'>#FF5500FF</a:calendar-color><d:current-user-privilege-set><d:privilege><d:write/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>");
        var found=client.discover();assertEquals(1,found.size());assertEquals("#FF5500FF",found.get(0).color);assertTrue(found.get(0).writable);assertEquals(1,server.getRequestCount());
    }
}
