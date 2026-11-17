package fr.nauss.calendar.core;

import java.io.IOException;
import java.util.List;

/** Crash-safe protocol: persist an immutable operation BEFORE any network mutation. */
public final class SyncEngine {
    public static final class Operation {
        public final String id,calendar,href,etag,ics,kind;
        public final boolean conflict;
        public Operation(String id,String calendar,String href,String etag,String ics,String kind,boolean conflict) {
            this.id=id;this.calendar=calendar;this.href=href;this.etag=etag;this.ics=ics;this.kind=kind;this.conflict=conflict;
        }
    }
    public interface Store {
        List<Operation> pending(String calendar);
        void markAttempted(String operationId);
        void acknowledge(Operation operation,DavClient.Resource resource);
        void fail(String operationId,String code);
        /** Must be transactional; preserve pending objects and reject identity changes. */
        void applySnapshot(String calendar,List<DavClient.Resource> completeSnapshot);
    }
    public interface Transport {
        DavClient.Resource put(String href,String etag,String ics) throws IOException;
        void delete(String href,String etag) throws IOException;
        List<DavClient.Resource> report(String calendar) throws IOException;
    }
    private final Store store; private final Transport transport;
    private String error="";
    public SyncEngine(Store store,Transport transport) { this.store=store;this.transport=transport; }
    public SyncEngine(Store store,DavClient client) {
        this(store,new Transport() {
            public DavClient.Resource put(String h,String e,String b) throws IOException { return client.put(h,e,b); }
            public void delete(String h,String e) throws IOException { client.delete(h,e); }
            public List<DavClient.Resource> report(String c) throws IOException { return client.report(c); }
        });
    }
    public String error() { return error; }
    public boolean sync(String calendar) {
        error="";
        for(Operation op:store.pending(calendar)) {
            if(op.conflict) { error="CONFLICT"; continue; }
            try {
                store.markAttempted(op.id);
                DavClient.Resource resource=null;
                if(op.kind.equals("DELETE")) transport.delete(op.href,op.etag);
                else if(op.kind.equals("PUT")) resource=transport.put(op.href,op.etag,op.ics);
                else throw new IllegalArgumentException("Unknown operation");
                store.acknowledge(op,resource);
            } catch(DavClient.ConflictException ex) { store.fail(op.id,"CONFLICT");error="CONFLICT"; }
              catch(DavClient.DavException ex) { error=ex.status==401 || ex.status==403 ? "AUTH" : "SERVER"; store.fail(op.id,error); return false; }
              catch(IOException | IllegalArgumentException ex) { store.fail(op.id,"NETWORK");error="NETWORK";return false; }
              catch(IllegalStateException ex) { error="RETRY";return false; }
        }
        try { store.applySnapshot(calendar,transport.report(calendar)); }
        catch(DavClient.DavException ex) { if(error.isEmpty()) error=ex.status==401||ex.status==403?"AUTH":"SERVER"; }
        catch(IOException | IllegalArgumentException | IllegalStateException ex) { if(error.isEmpty()) error="REPORT"; }
        return error.isEmpty();
    }
}
