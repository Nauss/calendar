package fr.nauss.calendar.core;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SyncEngineTest {
    static class Store implements SyncEngine.Store {
        List<SyncEngine.Operation> pending=new ArrayList<>(); boolean attempted,applied; String failure; int acknowledged;
        public List<SyncEngine.Operation> pending(String calendar) { return new ArrayList<>(pending); }
        public void markAttempted(String id) { attempted=true; }
        public void acknowledge(SyncEngine.Operation op,DavClient.Resource r) { pending.remove(op); acknowledged++; }
        public void fail(String id,String code) { failure=code; }
        public void applySnapshot(String calendar,List<DavClient.Resource> resources) { applied=true; }
    }
    @Test public void conflictRetainsPendingAndCache() {
        Store store=new Store(); store.pending.add(new SyncEngine.Operation("op","cal","href","\"1\"","raw","PUT",false));
        SyncEngine.Transport transport=new SyncEngine.Transport() {
            public DavClient.Resource put(String c,String h,String e,String b) throws java.io.IOException { assertTrue(store.attempted); throw new DavClient.ConflictException(); }
            public void delete(String c,String h,String e) {}
            public List<DavClient.Resource> report(String c) throws java.io.IOException { throw new java.io.IOException(); }
        };
        assertFalse(new SyncEngine(store,transport).sync("cal")); assertEquals(1,store.pending.size()); assertFalse(store.applied); assertEquals("CONFLICT",store.failure);
    }
    @Test public void acceptedCreateBeforeLostResponseRetriesSameOperation() {
        Store store=new Store(); var operation=new SyncEngine.Operation("durable-op","cal","same-href",null,"same-body","PUT",false); store.pending.add(operation);
        List<String> seen=new ArrayList<>();
        SyncEngine.Transport t=new SyncEngine.Transport() {
            public DavClient.Resource put(String c,String h,String e,String b) throws java.io.IOException { seen.add(h+":"+b); if(seen.size()==1) throw new java.io.IOException(); return null; }
            public void delete(String c,String h,String e) {}
            public List<DavClient.Resource> report(String c) { return List.of(); }
        };
        SyncEngine engine=new SyncEngine(store,t); assertFalse(engine.sync("cal")); assertEquals(1,store.pending.size());
        assertTrue(engine.sync("cal")); assertEquals(seen.get(0),seen.get(1)); assertEquals(1,store.acknowledged);
    }
    @Test public void reportFailureNeverAppliesEmptySnapshot() {
        Store store=new Store(); SyncEngine.Transport t=new SyncEngine.Transport() {
            public DavClient.Resource put(String c,String h,String e,String b) { throw new AssertionError(); }
            public void delete(String c,String h,String e) { throw new AssertionError(); }
            public List<DavClient.Resource> report(String c) throws java.io.IOException { throw new java.io.IOException("partial"); }
        };
        assertFalse(new SyncEngine(store,t).sync("cal")); assertFalse(store.applied);
    }
}
