package fr.nauss.calendar.core;

import org.junit.*;
import okhttp3.mockwebserver.*;
import java.util.*;
import static org.junit.Assert.*;

public class DavClientTest {
    MockWebServer server;
    @Before public void before() throws Exception { server=new MockWebServer(); server.start(java.net.InetAddress.getByAddress("127.0.0.1",new byte[]{127,0,0,1}),0); }
    @After public void after() throws Exception { server.shutdown(); }
    String url(String path) { return server.url(path).newBuilder().host("127.0.0.1").build().toString(); }
    DavClient client() { return new DavClient(url("/"),"fixture","test-only",true); }
    String xml(String rows) { return "<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'>"+rows+"</d:multistatus>"; }
    String row(String href,String props) { return "<d:response><d:href>"+href+"</d:href><d:propstat><d:prop>"+props+"</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"; }
    void respond(String x) { server.enqueue(new MockResponse().setResponseCode(207).setBody(x)); }
    @Test public void rejectUnsafeEndpoints() {
        for(String url:List.of("http://example.com/","https://user:pass@example.com/","https://example.com/?password=x","https://example.com/#fragment"))
            assertThrows(IllegalArgumentException.class,()->new DavClient(url,"a","b",true));
        assertThrows(IllegalArgumentException.class,()->new DavClient(server.url("/").toString(),"a","b",false));
    }
    @Test public void rootPrincipalHomeDiscoveryAndReadOnly() throws Exception {
        respond(xml(row("/","<d:current-user-principal><d:href>/p/</d:href></d:current-user-principal>")));
        respond(xml(row("/p/","<c:calendar-home-set><d:href>/home/</d:href></c:calendar-home-set>")));
        respond(xml(row("/home/cal/","<d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Fixture</d:displayname><d:current-user-privilege-set><d:privilege><d:read/></d:privilege></d:current-user-privilege-set>")));
        var found=client().discover(); assertEquals(1,found.size()); assertFalse(found.get(0).writable); assertEquals("Fixture",found.get(0).name);
    }
    @Test public void reportRejectsPartialForeignAndXxe() throws Exception {
        respond(xml("<d:response><d:href>/cal/a.ics</d:href><d:status>HTTP/1.1 500 ERROR</d:status></d:response>"));
        assertThrows(java.io.IOException.class,()->client().report(url("/cal/")));
        respond(xml(row("https://evil.invalid/a.ics","<d:getetag>&quot;1&quot;</d:getetag><c:calendar-data>bad</c:calendar-data>")));
        assertThrows(DavClient.DataException.class,()->client().report(url("/cal/")));
        respond("<!DOCTYPE d:multistatus [<!ENTITY x SYSTEM 'file:///nonexistent'>]>"+xml("&x;"));
        assertThrows(java.io.IOException.class,()->client().report(url("/cal/")));
    }
    @Test public void redirectsNeverFollow() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","https://evil.invalid/"));
        assertThrows(Exception.class,()->client().discover()); assertEquals(1,server.getRequestCount());
    }
    @Test public void createConditionalConflictAndReconciliation() throws Exception {
        String body=IcsCodecTest.ics("DTSTART:20261101T090000Z\r\nDTEND:20261101T100000Z\r\nSUMMARY:Fixture");
        server.enqueue(new MockResponse().setResponseCode(412));
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("ETag","\"2\"").setBody(body));
        var result=client().put(url("/cal/"),url("/cal/fixture.ics"),null,body);
        assertEquals("\"2\"",result.etag); assertEquals("*",server.takeRequest().getHeader("If-None-Match"));
        assertEquals("GET",server.takeRequest().getMethod());
        server.enqueue(new MockResponse().setResponseCode(412));
        assertThrows(DavClient.ConflictException.class,()->client().put(url("/cal/"),url("/cal/fixture.ics"),"\"1\"",body));
        assertEquals("\"1\"",server.takeRequest().getHeader("If-Match"));
    }
    @Test public void deleteRequiresStrongEtagAndCollectionRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,()->client().delete(url("/cal/"),url("/cal/a.ics"),null));
        assertThrows(IllegalArgumentException.class,()->client().delete(url("/cal/"),url("/cal/"),"\"1\""));
        assertThrows(IllegalArgumentException.class,()->client().delete(url("/cal/"),url("/cal/a.ics"),"W/\"1\""));
        server.enqueue(new MockResponse().setHeader("ETag","\"1\"").setBody(IcsCodecTest.ics("DTSTART:20261101T090000Z")));
        server.enqueue(new MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d='DAV:'><d:response><d:href>/cal/a.ics</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"));
        server.enqueue(new MockResponse().setResponseCode(204)); client().delete(url("/cal/"),url("/cal/a.ics"),"\"1\"");
        assertEquals("GET",server.takeRequest().getMethod());
        assertEquals("PROPFIND",server.takeRequest().getMethod());
        assertEquals("\"1\"",server.takeRequest().getHeader("If-Match"));
    }
}
