package fr.nauss.calendar.core;

import java.io.IOException;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;
import static org.junit.Assert.*;

public class SyncFailureV3Test {
    private SyncEngine.Transport failing(IOException failure,boolean mutation) {
        return new SyncEngine.Transport() {
            public DavClient.Resource put(String c,String h,String e,String b) throws IOException { throw failure; }
            public void delete(String c,String h,String e) throws IOException { throw failure; }
            public List<DavClient.Resource> report(String c) throws IOException { if(!mutation) throw failure;return List.of(); }
        };
    }
    private void check(IOException failure,String code,boolean mutation) {
        var store=new SyncEngineTest.Store();
        if(mutation) store.pending.add(new SyncEngine.Operation("op","cal","href","\"1\"","raw","DELETE",false));
        var engine=new SyncEngine(store,failing(failure,mutation));
        assertFalse(engine.sync("cal"));assertEquals(code,engine.error());assertFalse(store.applied);
        if(mutation) { assertEquals(1,store.pending.size());assertEquals(code,store.failure);assertTrue(store.attempted); }
    }
    @Test public void httpAuthenticationAndPermissionsRemainDistinct() {
        for(boolean mutation:List.of(false,true)) {
            check(new DavClient.DavException(401),"AUTH",mutation);
            check(new DavClient.DavException(403),"PERMISSION",mutation);
            check(new DavClient.DavException(503),"SERVER",mutation);
        }
    }
    @Test public void reportAndMutationDistinguishTlsFromNetwork() {
        for(boolean mutation:List.of(false,true)) {
            check(new SSLHandshakeException("synthetic private detail"),"TLS",mutation);
            check(new IOException("synthetic private detail"),"NETWORK",mutation);
        }
    }
    @Test public void conflictDoesNotHideReportFailureOrClearPending() {
        var store=new SyncEngineTest.Store();
        store.pending.add(new SyncEngine.Operation("op","cal","href","\"1\"","raw","PUT",true));
        var engine=new SyncEngine(store,failing(new IOException("synthetic private detail"),false));
        assertFalse(engine.sync("cal"));assertEquals("CONFLICT,NETWORK",engine.error());
        assertEquals(1,store.pending.size());assertFalse(store.applied);assertFalse(store.attempted);
    }
    @Test public void report412IsNotAnEventEtagConflict() { check(new DavClient.ConflictException(),"SERVER",false); }
}
