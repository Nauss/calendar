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
        DavClient.Resource put(String calendar,String href,String etag,String ics) throws IOException;
        void delete(String calendar,String href,String etag) throws IOException;
        List<DavClient.Resource> report(String calendar) throws IOException;
    }
    private final Store store; private final Transport transport;
    private String error="";
    private String readError="";
    private int confirmedWrites;
    public SyncEngine(Store store,Transport transport) { this.store=store;this.transport=transport; }
    public SyncEngine(Store store,DavClient client) {
        this(store,new Transport() {
            public DavClient.Resource put(String c,String h,String e,String b) throws IOException { return client.put(c,h,e,b); }
            public void delete(String c,String h,String e) throws IOException { client.delete(c,h,e); }
            public List<DavClient.Resource> report(String c) throws IOException { return client.report(c); }
        });
    }
    public String error() { return error; }
    public String readError() { return readError; }
    /** Durable acknowledgements in this execution, even when a later download fails. */
    public int confirmedWrites() { return confirmedWrites; }
    private void recordReadError(Exception failure) {
        String reason=failure instanceof DavClient.DataException?((DavClient.DataException)failure).reason
            :failure instanceof IcsCodec.UnsupportedException?((IcsCodec.UnsupportedException)failure).reason:"DATA";
        readError=mergeErrors(readError,IcsCodec.safeReadReason(reason));
    }
    public static String mergeErrors(String first,String next) {
        var codes=new java.util.LinkedHashSet<String>();
        for(String value:List.of(first,next)) for(String code:value.split(",")) if(!code.isEmpty()) codes.add(code);
        return String.join(",",codes);
    }
    private void recordError(String code) { error=mergeErrors(error,code); }
    private static String httpError(DavClient.DavException ex) { return ex.status==401?"AUTH":ex.status==403?"PERMISSION":"SERVER"; }
    /** Drain durable changes only; do not turn every local edit into a NAS poll. */
    public boolean upload(String calendar) {
        error="";
        readError="";
        confirmedWrites=0;
        return pushPending(calendar) && error.isEmpty();
    }
    private boolean pushPending(String calendar) {
        for(Operation op:store.pending(calendar)) {
            if(op.conflict) { recordError("CONFLICT"); continue; }
            try {
                if(!op.calendar.equals(calendar)) throw new IllegalArgumentException("Foreign operation calendar");
                store.markAttempted(op.id);
                DavClient.Resource resource=null;
                if(op.kind.equals("DELETE")) transport.delete(calendar,op.href,op.etag);
                else if(op.kind.equals("PUT")) resource=transport.put(calendar,op.href,op.etag,op.ics);
                else throw new IllegalArgumentException("Unknown operation");
                store.acknowledge(op,resource);
                confirmedWrites++;
            } catch(DavClient.ConflictException ex) { store.fail(op.id,"CONFLICT");recordError("CONFLICT"); }
              catch(DavClient.DavException ex) { String code=httpError(ex);recordError(code);store.fail(op.id,code);return false; }
              catch(javax.net.ssl.SSLException ex) { store.fail(op.id,"TLS");recordError("TLS");return false; }
              catch(DavClient.DataException | IllegalArgumentException ex) { store.fail(op.id,"REPORT");recordError("REPORT");recordReadError(ex);return false; }
              catch(IOException ex) { store.fail(op.id,"NETWORK");recordError("NETWORK");return false; }
              catch(IllegalStateException ex) { recordError("RETRY");return false; }
        }
        return true;
    }
    public boolean sync(String calendar) {
        error="";
        readError="";
        confirmedWrites=0;
        if(!pushPending(calendar)) return false;
        try { store.applySnapshot(calendar,transport.report(calendar)); }
        catch(DavClient.DavException ex) { recordError(httpError(ex)); }
        catch(javax.net.ssl.SSLException ex) { recordError("TLS"); }
        catch(DavClient.DataException | IllegalArgumentException | IllegalStateException ex) { recordError("REPORT");recordReadError(ex); }
        catch(IOException ex) { recordError("NETWORK"); }
        return error.isEmpty();
    }
}
