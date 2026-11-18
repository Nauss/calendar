package fr.nauss.calendar.core;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class UploadOnlyTest {
    @Test public void confirmedWritesSurviveDownloadFailureButResetForTheNextExecution() {
        var store=new SyncEngineTest.Store();store.pending.add(new SyncEngine.Operation("create","cal","new",null,"raw","PUT",false));
        var engine=new SyncEngine(store,new SyncEngine.Transport() {
            public DavClient.Resource put(String c,String h,String e,String b) { return null; }
            public void delete(String c,String h,String e) { throw new AssertionError(); }
            public List<DavClient.Resource> report(String c) throws java.io.IOException { throw new DavClient.DataException(); }
        });
        assertFalse(engine.sync("cal"));assertEquals("REPORT",engine.error());assertTrue(store.pending.isEmpty());assertEquals(1,engine.confirmedWrites());
        assertTrue(engine.upload("cal"));assertEquals(0,engine.confirmedWrites());
    }
    @Test public void offlineRetryKeepsTheSameOperationAndDrainedWakeUpsDoNothing() {
        var store=new SyncEngineTest.Store();var op=new SyncEngine.Operation("durable","cal","same",null,"same-body","PUT",false);store.pending.add(op);
        var calls=new ArrayList<String>();
        var engine=new SyncEngine(store,new SyncEngine.Transport() {
            public DavClient.Resource put(String c,String h,String e,String b) throws java.io.IOException { calls.add(h+":"+b);if(calls.size()==1) throw new java.io.IOException("offline fixture");return null; }
            public void delete(String c,String h,String e) { throw new AssertionError(); }
            public List<DavClient.Resource> report(String c) { throw new AssertionError("No periodic poll from upload"); }
        });
        assertFalse(engine.upload("cal"));assertSame(op,store.pending.get(0));assertEquals("NETWORK",engine.error());
        assertEquals(0,engine.confirmedWrites());
        assertTrue(engine.upload("cal"));assertEquals(1,engine.confirmedWrites());assertTrue(engine.upload("cal"));assertEquals(0,engine.confirmedWrites());assertEquals(2,calls.size());assertEquals(calls.get(0),calls.get(1));assertEquals(1,store.acknowledged);assertFalse(store.applied);
    }
    @Test public void conflictsRemainExplicitWithoutAnySnapshotOrMutation() {
        var store=new SyncEngineTest.Store();var op=new SyncEngine.Operation("conflict","cal","same","\"1\"","raw","PUT",true);store.pending.add(op);
        var engine=new SyncEngine(store,new SyncEngine.Transport() {
            public DavClient.Resource put(String c,String h,String e,String b) { throw new AssertionError(); }
            public void delete(String c,String h,String e) { throw new AssertionError(); }
            public List<DavClient.Resource> report(String c) { throw new AssertionError(); }
        });
        assertFalse(engine.upload("cal"));assertEquals("CONFLICT",engine.error());assertSame(op,store.pending.get(0));assertFalse(store.applied);
    }
    @Test public void changesAreUploadedWithoutDownloadingASnapshot() {
        var store=new SyncEngineTest.Store();
        store.pending.add(new SyncEngine.Operation("create","cal","new",null,"raw","PUT",false));
        store.pending.add(new SyncEngine.Operation("delete","cal","old","\"1\"","old-raw","DELETE",false));
        var sent=new ArrayList<String>();
        var engine=new SyncEngine(store,new SyncEngine.Transport() {
            public DavClient.Resource put(String c,String h,String e,String b) { sent.add("PUT");return null; }
            public void delete(String c,String h,String e) { sent.add("DELETE"); }
            public List<DavClient.Resource> report(String c) { throw new AssertionError("Upload must not poll the NAS"); }
        });
        assertTrue(engine.upload("cal"));assertEquals(List.of("PUT","DELETE"),sent);
        assertTrue(store.pending.isEmpty());assertEquals(2,store.acknowledged);assertFalse(store.applied);
    }
}
