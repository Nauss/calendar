package fr.nauss.calendar.core;

import okhttp3.mockwebserver.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Loopback only; opaque names must not weaken collection or URL boundaries. */
public class ResourcePathTest {
    private MockWebServer server;
    @Before public void setup() throws Exception { server=new MockWebServer();server.start(java.net.InetAddress.getByAddress("127.0.0.1",new byte[]{127,0,0,1}),0); }
    @After public void cleanup() throws Exception { server.shutdown(); }
    private String url(String path) { return server.url(path).newBuilder().host("127.0.0.1").build().toString(); }
    private DavClient client() { return new DavClient(url("/"),"fixture","synthetic-only",true); }
    private String xml(String rows) { return "<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'>"+rows+"</d:multistatus>"; }
    private String row(String href,String props) { return "<d:response><d:href>"+href+"</d:href><d:propstat><d:prop>"+props+"</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"; }
    private void respond(String body) { server.enqueue(new MockResponse().setResponseCode(207).setBody(body)); }
    String body() { return IcsCodecTest.ics("DTSTART:20261117T090000Z\r\nDTEND:20261117T100000Z\r\nSUMMARY:PATH FIXTURE"); }
    @Test public void opaqueResourceCanRoundTripWithConditionalWrites() throws Exception {
        String href=url("/cal/opaque-123");DavClient c=client();
        respond(xml(row("/cal/opaque-123","<d:getetag>&quot;1&quot;</d:getetag><c:calendar-data><![CDATA["+body()+"]]></c:calendar-data>")));
        assertEquals(href,c.report(url("/cal/")).get(0).href);server.takeRequest();
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("ETag","\"1\"").setBody(body()));
        assertEquals("fixture-1",c.get(url("/cal/"),href).uid);assertEquals("GET",server.takeRequest().getMethod());
        server.enqueue(new MockResponse().setResponseCode(204).setHeader("ETag","\"2\""));
        assertEquals(href,c.put(url("/cal/"),href,"\"1\"",body()).href);
        var put=server.takeRequest();assertEquals("/cal/opaque-123",put.getPath());assertEquals("\"1\"",put.getHeader("If-Match"));
    }
    @Test public void reportRejectsAmbiguousRawPathsBeforeNormalization() {
        for(String path:new String[]{"/cal/../cal/a", "/cal/%2e%2e/cal/a", "/cal/sub/../a", "/cal/%2fother", "/cal/%252fother", "/cal//a", "/cal/a/", "/other/a", "/cal-other/a", "/cal", "/cal/", "/cal/a?x=1", "/cal/a#x"}) {
            respond(xml(row(path,"<d:getetag>&quot;1&quot;</d:getetag><c:calendar-data><![CDATA["+body()+"]]></c:calendar-data>")));
            assertThrows(path,Exception.class,()->client().report(url("/cal/")));
        }
    }
    @Test public void allResourceOperationsRejectForeignAndAmbiguousPathsWithoutNetwork() {
        DavClient c=client();String cal=url("/cal/");
        for(String suffix:new String[]{"/cal/../cal/a", "/cal/%2e%2e/cal/a", "/cal/sub/../a", "/cal/%2fother", "/cal/%252fother", "/cal//a", "/cal/a/", "/other/a.ics", "/cal-other/a", "/cal", "/cal/", "/cal/a?x=1", "/cal/a#x"}) {
            String href=url("/")+suffix.substring(1); // Never normalize the malicious path in the test helper.
            assertFalse(href,DavClient.isResourceHref(cal,href));
            assertThrows(IllegalArgumentException.class,()->c.get(cal,href));
            assertThrows(IllegalArgumentException.class,()->c.put(cal,href,"\"1\"",body()));
            assertThrows(IllegalArgumentException.class,()->c.delete(cal,href,"\"1\""));
        }
        assertEquals(0,server.getRequestCount());
        assertTrue(DavClient.isResourceHref(cal,url("/cal/UPPER.ICS")));
        assertTrue(DavClient.isResourceHref(cal,url("/cal/opaque")));
    }
    @Test public void deleteChecksCalendarDataAndRevisionBeforeDestruction() throws Exception {
        DavClient c=client();String cal=url("/cal/"),href=url("/cal/opaque");
        server.enqueue(new MockResponse().setHeader("ETag","\"1\"").setBody("<html>A collection</html>"));
        assertThrows(java.io.IOException.class,()->c.delete(cal,href,"\"1\""));
        assertEquals("GET",server.takeRequest().getMethod());
        server.enqueue(new MockResponse().setHeader("ETag","\"2\"").setBody(body()));
        assertThrows(DavClient.ConflictException.class,()->c.delete(cal,href,"\"1\""));
        assertEquals("GET",server.takeRequest().getMethod());
        server.enqueue(new MockResponse().setHeader("ETag","\"2\"").setBody(body()));
        respond(xml(row("/cal/opaque","<d:resourcetype/>")));
        server.enqueue(new MockResponse().setResponseCode(204));c.delete(cal,href,"\"2\"");
        assertEquals("GET",server.takeRequest().getMethod());
        var proof=server.takeRequest();assertEquals("PROPFIND",proof.getMethod());assertEquals("0",proof.getHeader("Depth"));
        var deletion=server.takeRequest();assertEquals("DELETE",deletion.getMethod());assertEquals("\"2\"",deletion.getHeader("If-Match"));
        server.enqueue(new MockResponse().setResponseCode(404));c.delete(cal,href,"\"2\"");
        assertEquals("GET",server.takeRequest().getMethod());assertEquals(6,server.getRequestCount());
    }
    @Test public void syncRejectsForeignOperationAndRetainsPendingWithoutNetwork() {
        String cal=url("/cal/");
        for(String kind:new String[]{"PUT","DELETE"}) {
            var store=new SyncEngineTest.Store();
            var op=new SyncEngine.Operation("immutable-op",cal,url("/other/opaque"),"\"1\"",body(),kind,false);
            store.pending.add(op);
            assertFalse(new SyncEngine(store,client()).sync(cal));
            assertSame(op,store.pending.get(0));assertEquals(0,store.acknowledged);assertFalse(store.applied);
        }
        var store=new SyncEngineTest.Store();
        store.pending.add(new SyncEngine.Operation("wrong-calendar",url("/other/"),url("/other/opaque"),"\"1\"",body(),"PUT",false));
        assertFalse(new SyncEngine(store,client()).sync(cal));assertFalse(store.attempted);
        assertEquals(0,server.getRequestCount());
    }
    @Test public void editedEmbeddedEventSyncsToOpaqueIdentityWithoutLosingStructure() throws Exception {
        String cal=url("/cal/"),href=url("/cal/opaque");
        String original=ImportedEditingTest.paris();var draft=IcsCodec.inspect(original,java.time.ZoneOffset.UTC).draft;
        draft.title="MOVED SYNC FIXTURE";draft.start=java.time.LocalDateTime.of(2026,3,29,9,0);draft.end=draft.start.plusHours(1);
        String edited=IcsCodec.save(original,draft);
        var store=new SyncEngineTest.Store();store.pending.add(new SyncEngine.Operation("immutable-edit",cal,href,"\"1\"",edited,"PUT",false));
        server.enqueue(new MockResponse().setResponseCode(204).setHeader("ETag","\"2\""));
        respond(xml(row("/cal/opaque","<d:getetag>&quot;2&quot;</d:getetag><c:calendar-data><![CDATA["+edited+"]]></c:calendar-data>")));
        assertTrue(new SyncEngine(store,client()).sync(cal));assertTrue(store.attempted);assertTrue(store.applied);assertTrue(store.pending.isEmpty());
        var put=server.takeRequest();assertEquals("PUT",put.getMethod());assertEquals("/cal/opaque",put.getPath());assertEquals("\"1\"",put.getHeader("If-Match"));
        String sent=put.getBody().readUtf8();assertEquals(edited,sent);assertEquals("fixture-1",IcsCodec.inspect(sent,java.time.ZoneOffset.UTC).uid);
        assertEquals(ImportedEditingTest.parsed(original).getTimezoneInfo().getComponents().toString(),ImportedEditingTest.parsed(sent).getTimezoneInfo().getComponents().toString());
        assertEquals("REPORT",server.takeRequest().getMethod());
    }
}
