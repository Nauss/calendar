package fr.nauss.calendar.core;

import okhttp3.mockwebserver.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

/** A collection can export perfectly valid single-event ICS. GET alone cannot authorize DELETE. */
public class DeleteResourceTypeTest {
    private MockWebServer server;
    private final List<RecordedRequest> requests=new ArrayList<>();
    private String typeResponse;
    private int typeStatus=207;
    private String raw;
    @Before public void setup() throws Exception {
        raw=IcsCodecTest.ics("DTSTART:20261117T090000Z\r\nDTEND:20261117T100000Z\r\nSUMMARY:DELETE TYPE FIXTURE");
        server=new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                synchronized(requests) { requests.add(request); }
                return switch(request.getMethod()) {
                    case "GET" -> new MockResponse().setHeader("ETag","\"1\"").setBody(raw);
                    case "PROPFIND" -> new MockResponse().setResponseCode(typeStatus).setBody(typeResponse);
                    case "DELETE" -> new MockResponse().setResponseCode(204);
                    case "REPORT" -> new MockResponse().setResponseCode(207).setBody(envelope(""));
                    default -> new MockResponse().setResponseCode(405);
                };
            }
        });
        server.start(java.net.InetAddress.getByAddress("127.0.0.1",new byte[]{127,0,0,1}),0);
    }
    @After public void cleanup() throws Exception { server.shutdown(); }
    private String url(String path) { return server.url(path).newBuilder().host("127.0.0.1").build().toString(); }
    private String envelope(String rows) { return "<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'>"+rows+"</d:multistatus>"; }
    private String row(String href,String props,String status) {
        return "<d:response>"+(href==null?"":"<d:href>"+href+"</d:href>")+"<d:propstat><d:prop>"+props+"</d:prop>"+(status==null?"":"<d:status>"+status+"</d:status>")+"</d:propstat></d:response>";
    }
    private String response(String props) { return envelope(row("/cal/opaque",props,"HTTP/1.1 200 OK")); }
    private void assertRefusedWithoutLosingPending() {
        var store=new SyncEngineTest.Store();
        var op=new SyncEngine.Operation("immutable-delete",url("/cal/"),url("/cal/opaque"),"\"1\"",raw,"DELETE",false);
        store.pending.add(op);
        var engine=new SyncEngine(store,new DavClient(url("/"),"fixture","synthetic-only",true));
        assertFalse("Unproven DAV type must retain the deletion",engine.sync(op.calendar));
        assertSame(op,store.pending.get(0));assertEquals(raw,store.pending.get(0).ics);
        assertEquals(0,store.acknowledged);assertFalse(store.applied);assertNotNull(store.failure);
        assertEquals(List.of("GET","PROPFIND"),requests.stream().map(RecordedRequest::getMethod).toList());
        var proof=requests.get(1);
        assertEquals("/cal/opaque",proof.getPath());assertEquals("0",proof.getHeader("Depth"));
        assertEquals(requests.get(0).getHeader("Authorization"),proof.getHeader("Authorization"));
        assertNotNull(proof.getHeader("Authorization"));assertTrue(proof.getBody().readUtf8().contains("resourcetype"));
        requests.clear();
    }
    @Test public void collectionExportingOneEventIsNeverDeleted() {
        typeResponse=response("<d:resourcetype><d:collection/><c:calendar/></d:resourcetype>");
        assertRefusedWithoutLosingPending();
    }
    @Test public void unknownMissingMalformedForeignOrFailedTypeFailsClosed() {
        for(String bad:List.of(
            "not XML",envelope(""),response(""),response("<d:resourcetype>unknown</d:resourcetype>"),
            response("<d:resourcetype><c:calendar/></d:resourcetype>"),
            response("<d:resourcetype/><d:resourcetype/>"),
            envelope(row("/cal/opaque","<d:resourcetype/>",null)),
            envelope(row("/cal/opaque","<d:resourcetype/>","HTTP/1.1 404 Not Found")),
            envelope(row(null,"<d:resourcetype/>","HTTP/1.1 200 OK")),
            envelope(row("https://foreign.invalid/cal/opaque","<d:resourcetype/>","HTTP/1.1 200 OK")),
            envelope(row("/cal/other","<d:resourcetype/>","HTTP/1.1 200 OK")),
            envelope(row("/cal/opaque","<d:resourcetype/>","HTTP/1.1 200 OK")+row("/cal/other","<d:resourcetype/>","HTTP/1.1 200 OK")))) {
            typeResponse=bad;assertRefusedWithoutLosingPending();
        }
        typeResponse=response("<d:resourcetype/>");
        for(int status:new int[]{301,401,403,404,410,500}) { typeStatus=status;assertRefusedWithoutLosingPending(); }
    }
    @Test public void exactEmptyResourceTypeAllowsOnlyConditionalEventDelete() throws Exception {
        typeResponse=response("<d:resourcetype/>");
        new DavClient(url("/"),"fixture","synthetic-only",true).delete(url("/cal/"),url("/cal/opaque"),"\"1\"");
        assertEquals(List.of("GET","PROPFIND","DELETE"),requests.stream().map(RecordedRequest::getMethod).toList());
        assertEquals("/cal/opaque",requests.get(2).getPath());assertEquals("\"1\"",requests.get(2).getHeader("If-Match"));
    }
}
