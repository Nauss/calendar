package fr.nauss.calendar.core;

import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

/** Raw HTTP bytes exercise the decoding boundary before XML/ICS can hide malformed UTF-8. */
public class Utf8ResponseSafetyTest {
    private MockWebServer server;
    @Before public void setup() throws Exception {
        server=new MockWebServer();server.start(java.net.InetAddress.getByAddress("127.0.0.1",new byte[]{127,0,0,1}),0);
    }
    @After public void cleanup() throws Exception { server.shutdown(); }
    private String url(String path) { return server.url(path).newBuilder().host("127.0.0.1").build().toString(); }
    private String event(String title) { return IcsCodecTest.ics("DTSTART:20261117T090000Z\r\nSUMMARY:"+title); }
    private String report(String raw) {
        return "<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'><d:response><d:href>/cal/a.ics</d:href><d:propstat><d:prop><d:getetag>&quot;1&quot;</d:getetag><c:calendar-data><![CDATA["+raw+"]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
    }
    private Buffer poisoned(String text,byte[] malformed) {
        int marker=text.indexOf("ENCODING_MARKER");assertTrue(marker>=0);
        return new Buffer().writeUtf8(text.substring(0,marker)).write(malformed).writeUtf8(text.substring(marker+"ENCODING_MARKER".length()));
    }
    private static class Store extends SyncEngineTest.Store {
        List<String> cache=new ArrayList<>(List.of("cached raw one","cached raw two"));
        @Override public void applySnapshot(String c,List<DavClient.Resource> rows) { super.applySnapshot(c,rows);cache=rows.stream().map(r->r.ics).toList(); }
    }
    @Test public void malformedWireEncodingCannotReplaceCacheOrPendingMutations() throws Exception {
        for(byte[] bad:List.of(new byte[]{(byte)0xc3,0x28},new byte[]{(byte)0x80},new byte[]{(byte)0xed,(byte)0xa0,(byte)0x80})) {
            for(boolean pending:List.of(false,true)) {
                server.enqueue(new MockResponse().setResponseCode(207).setHeader("Content-Type","application/xml; charset=utf-8").setBody(poisoned(report(event("ENCODING_MARKER")),bad)));
                var store=new Store();var before=List.copyOf(store.cache);
                if(pending) store.pending.add(new SyncEngine.Operation("immutable",url("/cal/"),url("/cal/pending.ics"),"\"1\"",event("PENDING FIXTURE"),"PUT",true));
                var operations=List.copyOf(store.pending);
                var engine=new SyncEngine(store,new DavClient(url("/"),"fixture","synthetic-only",true));
                assertFalse("Invalid wire bytes must not become an authoritative snapshot",engine.sync(url("/cal/")));
                assertEquals(pending?"CONFLICT,REPORT":"REPORT",engine.error());assertFalse(store.applied);assertEquals(before,store.cache);
                assertEquals(operations,store.pending);assertEquals(0,store.acknowledged);
                assertEquals("REPORT",server.takeRequest().getMethod());
            }
        }
    }
    @Test public void malformedGetBodyIsADataErrorWithoutPrivateExceptionContent() {
        server.enqueue(new MockResponse().setHeader("ETag","\"1\"").setBody(poisoned(event("ENCODING_MARKER"),new byte[]{(byte)0xc3})));
        var client=new DavClient(url("/"),"fixture","synthetic-only",true);
        var error=assertThrows(DavClient.DataException.class,()->client.get(url("/cal/"),url("/cal/a.ics")));
        assertEquals(new DavClient.DataException().getMessage(),error.getMessage());assertNull(error.getCause());
    }
    @Test public void validUnicodeIncludingAnEncodedReplacementCharacterIsPreserved() throws Exception {
        String title="Été • 東京 🗓 \uFFFD";
        server.enqueue(new MockResponse().setResponseCode(207).setBody(report(event(title))));
        var rows=new DavClient(url("/"),"fixture","synthetic-only",true).report(url("/cal/"));
        assertEquals(1,rows.size());assertEquals(title,IcsCodec.inspect(rows.get(0).ics,java.time.ZoneOffset.UTC).draft.title);
    }
}
