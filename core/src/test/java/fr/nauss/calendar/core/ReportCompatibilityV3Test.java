package fr.nauss.calendar.core;

import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the real REPORT decoder with strings, without opening any socket. */
public class ReportCompatibilityV3Test {
    @Test public void scalarStatusMustBeOneCompleteStatusLine() throws Exception {
        for(String status:List.of("HTTP/1.1 200 OK&#10;HTTP/1.1 403 Forbidden","HTTP/1.1 200 OK&#13;&#10;HTTP/1.1 403 Forbidden","HTTP/1.1 200 O&#x7f;K","HTTP/1.1 200 OK\u2028HTTP/1.1 403 Forbidden")) {
            retained(envelope(row("a",good()).replace("HTTP/1.1 200 OK",status)),"REPORT");
        }
        for(String status:List.of("HTTP/1.1 200 OK","HTTP/1.1 200","HTTP/2 200 OK","HTTP/2 200")) {
            assertEquals(1,client.parseReport(CAL,envelope(row("a",good()).replace("HTTP/1.1 200 OK",status))).size());
        }
    }
    @Test public void excessiveXmlDepthIsAControlledReadFailureWithoutPruning() {
        String deep="<d:responsedescription xmlns:x='urn:fixture'>"+"<x:n>".repeat(20000)+"</x:n>".repeat(20000)+"</d:responsedescription>";
        retained(envelope(deep),"REPORT");
        retained(envelope(row("a",good())+deep),"REPORT");
    }
    @Test public void depthBudgetCountsElementsNotCommentsCdataOrText() throws Exception {
        String open="<d:responsedescription xmlns:x='urn:fixture'>",close="</d:responsedescription>";
        assertTrue(client.parseReport(CAL,envelope(open+"<x:n>".repeat(126)+"</x:n>".repeat(126)+close)).isEmpty());
        retained(envelope(open+"<x:n>".repeat(127)+"</x:n>".repeat(127)+close),"REPORT");
        assertTrue(client.parseReport(CAL,envelope(open+"<![CDATA["+"<x:n>".repeat(1000)+"]]><!-- <x:n> -->"+close)).isEmpty());
    }
    @Test public void partialMarkersAtAnyDepthNeverPruneCacheOrPendingOperations() {
        String valid=row("a",good());
        for(String marker:List.of("<d:limit-reached/>","<d:error/>","<c:limit-recurrence-set/>")) {
            for(String rows:List.of(valid+marker,
                valid.replace("</d:response>",marker+"</d:response>"),
                valid+"<d:responsedescription>"+marker+"</d:responsedescription>",
                valid.replace("</d:propstat>","<d:responsedescription>"+marker+"</d:responsedescription></d:propstat>"),
                valid.replace("</d:prop>","<d:displayname>"+marker+"</d:displayname></d:prop>"))) retained(envelope(rows),"REPORT");
        }
    }
    @Test public void structuralTextNeverBecomesAnAuthoritativeEmptySnapshot() throws Exception {
        for(String bad:List.of(envelope("not a report"),envelope("<![CDATA[not a report]]>"),
            envelope(row("a",good()).replace("<d:response>","<d:response>unexpected")),
            envelope(row("a",good()).replace("<d:propstat>","<d:propstat>unexpected")),
            envelope(row("a",good()).replace("<d:prop>","<d:prop>unexpected")))) retained(bad,"REPORT");
        assertTrue(client.parseReport(CAL,envelope(" \r\n\t")).isEmpty());
    }
    private static final String CAL="https://fixture.invalid/cal/";
    private final DavClient client=new DavClient(CAL,"fixture","synthetic",false);
    private String envelope(String rows) { return "<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'>"+rows+"</d:multistatus>"; }
    private String row(String name,String raw) {
        return "<d:response><d:href>/cal/"+name+".ics</d:href><d:propstat><d:prop><d:getetag>&quot;revision-1&quot;</d:getetag><c:calendar-data><![CDATA["+raw+"]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }
    private String good() { return IcsCodecTest.ics("DTSTART:20261101T090000Z\r\nSUMMARY:Ordinary fixture").replace("fixture-1","ordinary"); }
    @Test public void aValidLongOneOffNoLongerBlocksWholeReportAndRawIdentityIsUnchanged() throws Exception {
        // XML normalizes literal CRLF to LF before calendar-data reaches the codec.
        String longEvent=IcsCodecTest.ics("DTSTART;VALUE=DATE:20260101\r\nDURATION:P400D\r\nSUMMARY:Long absence fixture\r\nX-FIXTURE:must remain unchanged").replace("\r\n","\n");
        var result=client.parseReport(CAL,envelope(row("normal",good())+row("long",longEvent)));
        assertEquals(2,result.size());
        assertEquals(CAL+"long.ics",result.get(1).href);assertEquals("fixture-1",result.get(1).uid);
        assertEquals("\"revision-1\"",result.get(1).etag);assertEquals(longEvent,result.get(1).ics);
        var store=new MemoryStore();var engine=new SyncEngine(store,transport(envelope(row("normal",good())+row("long",longEvent))));
        assertTrue(engine.sync(CAL));assertEquals("",engine.error());assertTrue(store.applied);
    }
    private static class MemoryStore extends SyncEngineTest.Store {
        List<String> cache=new ArrayList<>(List.of("old-raw-must-survive","other-cached-event"));
        @Override public void applySnapshot(String calendar,List<DavClient.Resource> rows) { super.applySnapshot(calendar,rows);cache=rows.stream().map(r->r.ics).toList(); }
    }
    private SyncEngine.Transport transport(String xml) {
        return new SyncEngine.Transport() {
            public DavClient.Resource put(String c,String h,String e,String b) { throw new AssertionError("No mutation allowed"); }
            public void delete(String c,String h,String e) { throw new AssertionError("No mutation allowed"); }
            public List<DavClient.Resource> report(String c) throws IOException { return client.parseReport(c,xml); }
        };
    }
    private void retained(String xml,String error) {
        for(boolean pending:List.of(false,true)) {
            var store=new MemoryStore();var before=List.copyOf(store.cache);
            if(pending) store.pending.add(new SyncEngine.Operation("op",CAL,CAL+"pending.ics","\"1\"","pending-raw","DELETE",true));
            var pendingBefore=List.copyOf(store.pending);
            var engine=new SyncEngine(store,transport(xml));
            assertFalse(engine.sync(CAL));assertFalse(store.applied);assertEquals(before,store.cache);
            assertEquals(pending?"CONFLICT,"+error:error,engine.error());assertEquals(pending?1:0,store.pending.size());
            assertEquals(pendingBefore,store.pending);
            if(pending) { assertEquals("pending-raw",store.pending.get(0).ics);assertEquals("DELETE",store.pending.get(0).kind); }
        }
    }
    @Test public void ambiguousReportBindingsNeverPruneCacheOrPendingOperations() throws Exception {
        String valid=row("a",good()),status="<d:status>HTTP/1.1 200 OK</d:status>";
        String etag="<d:getetag>&quot;revision-1&quot;</d:getetag>";
        String data="<c:calendar-data><![CDATA["+good()+"]]></c:calendar-data>";
        var cases=new LinkedHashMap<String,String>();
        cases.put("duplicate propstat status",valid.replace(status,status+"<d:status>HTTP/1.1 403 Forbidden</d:status>"));
        cases.put("identical propstat status",valid.replace(status,status+status));
        cases.put("missing propstat status",valid.replace(status,""));
        cases.put("nested propstat status",valid.replace("200 OK","200 <d:status/>OK"));
        cases.put("duplicate response status",valid.replace("</d:response>",status+"<d:status>HTTP/1.1 403 Forbidden</d:status></d:response>"));
        cases.put("identical response status",valid.replace("</d:response>",status+status+"</d:response>"));
        cases.put("mixed success representation",valid.replace("</d:response>",status+"</d:response>"));
        cases.put("nested response status","<d:response><d:href>/cal/a.ics</d:href><d:status>HTTP/1.1 <d:status/>403 Forbidden</d:status></d:response>");
        cases.put("duplicate prop container",valid.replace("</d:prop>","</d:prop><d:prop/>"));
        cases.put("missing prop container",valid.replace("<d:prop>"+etag+data+"</d:prop>",""));
        for(String field:List.of(etag,data)) {
            cases.put("duplicate field "+field,valid.replace(field,field+field));
            cases.put("cross propstat duplicate "+field,valid.replace("</d:response>","<d:propstat><d:prop>"+field+"</d:prop>"+status+"</d:propstat></d:response>"));
            cases.put("missing field "+field,valid.replace(field,""));
        }
        cases.put("element in etag",valid.replace("revision-1","revision<d:displayname/>-1"));
        cases.put("element in data",valid.replace("</c:calendar-data>","<d:displayname/></c:calendar-data>"));
        cases.put("element in href",valid.replace("/cal/a.ics","/cal/<d:displayname/>a.ics"));
        for(String binding:List.of("<d:status>HTTP/1.1 403 Forbidden</d:status>","<d:prop/>","<d:href>/cal/b.ics</d:href>",etag,data,"<d:response/>","<d:propstat/>")) {
            cases.put("hidden response binding "+binding,valid.replace("</d:response>","<d:responsedescription>"+binding+"</d:responsedescription></d:response>"));
            cases.put("hidden propstat binding "+binding,valid.replace("</d:propstat>","<d:responsedescription>"+binding+"</d:responsedescription></d:propstat>"));
            cases.put("hidden property binding "+binding,valid.replace("</d:prop>","<d:displayname>"+binding+"</d:displayname></d:prop>"));
        }
        for(var entry:cases.entrySet()) {
            String xml=envelope(entry.getValue());
            try {
                retained(xml,"REPORT");
                var error=assertThrows(DavClient.DataException.class,()->client.parseReport(CAL,xml));
                assertEquals(new DavClient.DataException().getMessage(),error.getMessage());assertNull(error.getCause());
            } catch(AssertionError error) { throw new AssertionError(entry.getKey(),error); }
        }
    }
    @Test public void splitSuccessfulPropstatsPreserveTextCdataAndUnknownIcsProperties() throws Exception {
        String raw=good().replace("\r\n","\n").replace("END:VEVENT","X-UNKNOWN:preserved\nEND:VEVENT");
        String valid=row("a",raw).replace("</d:getetag>","</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop>")
            .replace("</d:response>","<d:responsedescription>standard metadata</d:responsedescription></d:response>");
        for(String value:List.of(valid,valid.replace("<![CDATA["+raw+"]]>",raw))) {
            var result=client.parseReport(CAL,envelope(value));
            assertEquals(1,result.size());assertEquals(raw,result.get(0).ics);assertEquals("\"revision-1\"",result.get(0).etag);
        }
    }
    @Test public void duplicateDirectHrefsNeverPruneCacheOrPendingOperations() {
        for(String second:List.of("b","a")) {
            String xml=envelope(row("a",good()).replace("</d:href>","</d:href><d:href>/cal/"+second+".ics</d:href>"));
            retained(xml,"REPORT");
            var error=assertThrows(DavClient.DataException.class,()->client.parseReport(CAL,xml));
            assertEquals(new DavClient.DataException().getMessage(),error.getMessage());assertNull(error.getCause());
        }
    }
    @Test public void missingDirectHrefNeverPrunesCacheOrPendingOperations() {
        String xml=envelope(row("a",good()).replace("<d:href>/cal/a.ics</d:href>",""));
        retained(xml,"REPORT");
        var error=assertThrows(DavClient.DataException.class,()->client.parseReport(CAL,xml));
        assertEquals(new DavClient.DataException().getMessage(),error.getMessage());assertNull(error.getCause());
    }
    @Test public void malformedOffsetSecondsStillFailClosedWithoutPruning() {
        // Sixty is not a valid UTC-offset second; never repair or silently round it.
        String unsupported=ImportedEditingTest.paris().replace("+0100","+010060");
        retained(envelope(row("normal",good())+row("unsupported",unsupported)),"REPORT");
    }
    @Test public void incompleteMalformedAndDuplicateSnapshotsNeverApplyPartialData() {
        retained(envelope(row("normal",good())+row("bad","not ICS")),"REPORT");
        retained(envelope(row("normal",good())+"<d:limit-reached/>"),"REPORT");
        retained(envelope(row("normal",good())+row("duplicate",good())),"REPORT");
        retained(envelope(row("normal",good())).replace("</d:multistatus>",""),"REPORT");
        retained(envelope(row("normal",good())).replace("<d:getetag>&quot;revision-1&quot;</d:getetag>",""),"REPORT");
    }
    @Test public void partialHttpAuthAndPermissionErrorsAreNotCalledNetworkOrConflicts() {
        for(String status:List.of("401 Unauthorized","403 Forbidden","412 Precondition Failed")) {
            String code=status.startsWith("401")?"AUTH":status.startsWith("403")?"PERMISSION":"REPORT";
            retained(envelope(row("normal",good())+"<d:response><d:href>/cal/rejected.ics</d:href><d:status>HTTP/1.1 "+status+"</d:status></d:response>"),code);
            retained(envelope(row("normal",good())+row("rejected",good()).replace("200 OK",status)),code);
        }
    }
    @Test public void publicErrorsNeverContainPrivateParserInput() {
        String raw="private-fixture-title-secret";
        var error=assertThrows(DavClient.DataException.class,()->client.parseReport(CAL,envelope(row("normal",good())+row("bad",raw))));
        assertFalse(error.toString().contains(raw));assertNull(error.getCause());
    }
    @Test public void malformedXmlNeverLogsResponseContent() {
        var previous=System.err;var captured=new java.io.ByteArrayOutputStream();
        try {
            System.setErr(new java.io.PrintStream(captured));
            assertThrows(DavClient.DataException.class,()->client.parseReport(CAL,"<d:multistatus xmlns:d='DAV:'><private-fixture-secret></d:multistatus>"));
        } finally { System.setErr(previous); }
        assertEquals("",captured.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
