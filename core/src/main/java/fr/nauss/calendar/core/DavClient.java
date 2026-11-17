package fr.nauss.calendar.core;

import okhttp3.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One-origin, non-redirecting CalDAV transport. Never operates on calendar collections with PUT/DELETE. */
public final class DavClient {
    private static final String DAV="DAV:", CAL="urn:ietf:params:xml:ns:caldav";
    private static final MediaType XML=MediaType.get("application/xml; charset=utf-8"), ICS=MediaType.get("text/calendar; charset=utf-8");
    private static final int MAX_BODY=16_000_000;
    private final HttpUrl root;
    private final String authorization;
    private final OkHttpClient http;
    public static final class CalendarInfo {
        public final String href,name,color; public final boolean writable;
        public CalendarInfo(String href,String name,String color,boolean writable) { this.href=href;this.name=name;this.color=color;this.writable=writable; }
    }
    public static final class Resource {
        public final String href,etag,ics,uid;
        public Resource(String href,String etag,String ics) { this.href=href;this.etag=etag;this.ics=ics;this.uid=IcsCodec.inspect(ics,ZoneOffset.UTC).uid; }
    }
    public static class DavException extends IOException {
        public final int status;
        public DavException(int status) { super("CalDAV HTTP "+status); this.status=status; }
    }
    public static final class ConflictException extends DavException { public ConflictException() { super(412); } }
    public DavClient(String endpoint,String user,String password,boolean localHttp) {
        root=validateEndpoint(endpoint,localHttp);
        if(user.indexOf(':')>=0 || user.indexOf('\n')>=0 || user.indexOf('\r')>=0) throw new IllegalArgumentException("Invalid user name");
        authorization=Credentials.basic(user,password,StandardCharsets.UTF_8);
        http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).build();
    }
    public static HttpUrl validateEndpoint(String value,boolean localHttp) {
        if(value==null || !value.equals(value.trim()) || value.indexOf('\\')>=0 || !(value.startsWith("https://") || value.startsWith("http://")) || java.net.URI.create(value).getRawUserInfo()!=null) throw new IllegalArgumentException("Invalid endpoint");
        safeRawPath(value);
        HttpUrl u=HttpUrl.parse(value);
        if(u==null || !u.username().isEmpty() || !u.password().isEmpty() || u.query()!=null || u.fragment()!=null) throw new IllegalArgumentException("Endpoint must not contain credentials, query or fragment");
        // HTTP is limited to literal loopback/RFC1918 addresses. No DNS rebinding or public cleartext.
        if(!u.isHttps() && !(localHttp && u.scheme().equals("http") && privateLiteral(u.host()))) throw new IllegalArgumentException("HTTPS required; HTTP only for explicitly enabled private IP addresses");
        safePath(u); return u;
    }
    private static void safePath(HttpUrl url) {
        for(String part:url.pathSegments()) if(part.contains("/") || part.contains("\\") || part.contains("%") || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Ambiguous encoded path rejected");
    }
    private static void safeRawPath(String value) {
        String path=java.net.URI.create(value).getRawPath();
        if(path==null) throw new IllegalArgumentException("Missing path");
        for(String segment:path.split("/",-1)) {
            String dots=segment.replaceAll("(?i)%2e",".");
            if(dots.equals(".") || dots.equals("..")) throw new IllegalArgumentException("Dot path rejected before normalization");
        }
        if(path.contains("//")) throw new IllegalArgumentException("Empty path segment");
    }
    /** Exact immediate resource membership; extensions are not a DAV identity boundary. */
    public static boolean isResourceHref(String collection,String href) {
        try {
            HttpUrl c=validateEndpoint(collection,true),u=validateEndpoint(href,true);
            String path=c.encodedPath(),member=u.encodedPath();
            return c.scheme().equals(u.scheme()) && c.host().equals(u.host()) && c.port()==u.port()
                && path.endsWith("/") && member.startsWith(path) && member.length()>path.length()
                && !member.substring(path.length()).contains("/");
        } catch(IllegalArgumentException ex) { return false; }
    }
    private static boolean privateLiteral(String host) {
        if(host.equals("::1")) return true;
        String[] parts=host.split("\\.",-1); if(parts.length!=4) return false;
        int[] n=new int[4];
        try { for(int i=0;i<4;i++) { n[i]=Integer.parseInt(parts[i]); if(n[i]<0 || n[i]>255) return false; } } catch(NumberFormatException e) { return false; }
        return n[0]==127 || n[0]==10 || (n[0]==192 && n[1]==168) || (n[0]==172 && n[1]>=16 && n[1]<=31);
    }
    public String endpoint() { return root.toString(); }
    private HttpUrl resolve(String href) {
        if(href==null || href.indexOf('\\')>=0 || href.indexOf('\r')>=0 || href.indexOf('\n')>=0 || java.net.URI.create(href).getRawUserInfo()!=null) throw new IllegalArgumentException("Unsafe href");
        safeRawPath(href);
        HttpUrl u=root.resolve(href);
        if(u==null || !u.scheme().equals(root.scheme()) || !u.host().equals(root.host()) || u.port()!=root.port()
            || !u.username().isEmpty() || !u.password().isEmpty() || u.query()!=null || u.fragment()!=null) throw new IllegalArgumentException("Foreign or unsafe href rejected");
        safePath(u); return u;
    }
    public String childHref(String collection,String filename) {
        HttpUrl c=resolve(collection);
        if(!c.encodedPath().endsWith("/") || filename.isEmpty() || filename.contains("/") || filename.contains("\\") || filename.equals(".") || filename.equals("..")) throw new IllegalArgumentException("Invalid resource name");
        return c.newBuilder().addPathSegment(filename).build().toString();
    }
    private HttpUrl eventUrl(String collection,String href) {
        HttpUrl c=resolve(collection),u=resolve(href);
        if(!isResourceHref(c.toString(),u.toString())) throw new IllegalArgumentException("Not a resource in the selected calendar");
        return u;
    }
    private Request.Builder request(HttpUrl url) { return new Request.Builder().url(url).header("Authorization",authorization).header("Accept","application/xml, text/calendar").header("User-Agent","Agenda/0.1").header("Connection","close"); }
    private static String body(Response r) throws IOException {
        if(r.body()==null) throw new IOException("Empty server response");
        if(r.body().contentLength()>MAX_BODY) throw new IOException("Response too large");
        try(InputStream in=r.body().byteStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int n,total=0;
            while((n=in.read(buffer))!=-1) { total+=n; if(total>MAX_BODY) throw new IOException("Response too large"); out.write(buffer,0,n); }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static IOException failure(int code) { return code==412 ? new ConflictException() : new DavException(code); }
    private static void feature(DocumentBuilderFactory f,String name,boolean value) {
        try { f.setFeature(name,value); } catch(ParserConfigurationException ignored) { /* Android lacks some Xerces features. DTDs are rejected before parsing, resolver rejects all entities. */ }
    }
    private static Document xml(String raw) throws IOException {
        String upper=raw.toUpperCase(Locale.ROOT);
        if(upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) throw new IOException("DTD/entity rejected");
        try {
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true); f.setExpandEntityReferences(false);
            feature(f,"http://apache.org/xml/features/disallow-doctype-decl",true);
            feature(f,"http://xml.org/sax/features/external-general-entities",false);
            feature(f,"http://xml.org/sax/features/external-parameter-entities",false);
            feature(f,"http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
            DocumentBuilder b=f.newDocumentBuilder();
            b.setEntityResolver((publicId,systemId)->{ throw new SAXException("External entities rejected"); });
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() { @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; } @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; } });
            Document d=b.parse(new InputSource(new StringReader(raw)));
            if(!DAV.equals(d.getDocumentElement().getNamespaceURI()) || !"multistatus".equals(d.getDocumentElement().getLocalName())) throw new IOException("Not a multistatus response");
            return d;
        } catch(ParserConfigurationException | SAXException e) { throw new IOException("Malformed DAV response"); }
    }
    private static List<Element> children(Element p,String ns,String tag) {
        List<Element> out=new ArrayList<>();
        for(Node n=p.getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element && ns.equals(n.getNamespaceURI()) && tag.equals(n.getLocalName())) out.add((Element)n);
        return out;
    }
    private static Element first(Element p,String ns,String tag) { List<Element> list=children(p,ns,tag); return list.isEmpty()?null:list.get(0); }
    private static String text(Element p,String ns,String tag) { Element e=first(p,ns,tag); return e==null?null:e.getTextContent().trim(); }
    private static boolean contains(Element p,String ns,String tag) { return p!=null && p.getElementsByTagNameNS(ns,tag).getLength()>0; }
    private static boolean ok(String status) { return status!=null && (status.startsWith("HTTP/1.1 200 ") || status.startsWith("HTTP/2 200 ") || status.equals("HTTP/1.1 200")); }
    private List<Element> propfind(String href,String depth) throws IOException {
        String payload="<d:propfind xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav' xmlns:a='http://apple.com/ns/ical/'><d:prop><d:current-user-principal/><c:calendar-home-set/><d:resourcetype/><d:displayname/><d:current-user-privilege-set/><a:calendar-color/><c:supported-calendar-component-set/></d:prop></d:propfind>";
        try(Response r=http.newCall(request(resolve(href)).header("Depth",depth).method("PROPFIND",RequestBody.create(payload,XML)).build()).execute()) {
            if(r.code()!=207) throw failure(r.code());
            return children(xml(body(r)).getDocumentElement(),DAV,"response");
        }
    }
    private List<Element> successfulProps(Element response) throws IOException {
        String status=text(response,DAV,"status"); if(status!=null && !ok(status)) throw new IOException("Incomplete DAV response");
        List<Element> props=new ArrayList<>();
        for(Element ps:children(response,DAV,"propstat")) if(ok(text(ps,DAV,"status"))) {
            Element p=first(ps,DAV,"prop"); if(p!=null) props.add(p);
        }
        return props;
    }
    public List<CalendarInfo> discover() throws IOException {
        List<Element> rows=propfind(root.toString(),"0");
        List<CalendarInfo> direct=collections(rows); if(!direct.isEmpty()) return direct;
        String home=propertyHref(rows,CAL,"calendar-home-set");
        if(home==null) {
            String principal=propertyHref(rows,DAV,"current-user-principal");
            if(principal!=null) home=propertyHref(propfind(principal,"0"),CAL,"calendar-home-set");
        }
        // Also support a configured home-set URL without a principal property.
        return collections(propfind(home==null?root.toString():home,"1"));
    }
    private String propertyHref(List<Element> rows,String ns,String name) throws IOException {
        for(Element row:rows) {
            resolve(text(row,DAV,"href"));
            for(Element prop:successfulProps(row)) { Element p=first(prop,ns,name); if(p!=null) { String href=text(p,DAV,"href"); if(href!=null) return resolve(href).toString(); } }
        }
        return null;
    }
    private List<CalendarInfo> collections(List<Element> rows) throws IOException {
        List<CalendarInfo> out=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for(Element row:rows) {
            HttpUrl url=resolve(text(row,DAV,"href")); boolean calendar=false,writable=false,events=true; String name=null,color="#4285F4";
            for(Element prop:successfulProps(row)) {
                calendar|=contains(first(prop,DAV,"resourcetype"),CAL,"calendar");
                if(text(prop,DAV,"displayname")!=null) name=text(prop,DAV,"displayname");
                if(text(prop,"http://apple.com/ns/ical/","calendar-color")!=null) color=text(prop,"http://apple.com/ns/ical/","calendar-color");
                Element privileges=first(prop,DAV,"current-user-privilege-set");
                writable|=contains(privileges,DAV,"all") || contains(privileges,DAV,"write") || (contains(privileges,DAV,"write-content") && contains(privileges,DAV,"bind") && contains(privileges,DAV,"unbind"));
                Element set=first(prop,CAL,"supported-calendar-component-set");
                if(set!=null) { events=false; for(Element comp:children(set,CAL,"comp")) if("VEVENT".equals(comp.getAttribute("name"))) events=true; }
            }
            if(calendar && events) {
                if(!url.encodedPath().endsWith("/")) url=url.newBuilder().addPathSegment("").build();
                if(!seen.add(url.toString())) throw new IOException("Duplicate collection href");
                out.add(new CalendarInfo(url.toString(),name==null||name.isEmpty()?"CalDAV":name,color,writable));
            }
        }
        return out;
    }
    public List<Resource> report(String collection) throws IOException {
        HttpUrl c=resolve(collection); if(!c.encodedPath().endsWith("/")) throw new IllegalArgumentException("Collection URL must end in /");
        String payload="<c:calendar-query xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'><d:prop><d:getetag/><c:calendar-data/></d:prop><c:filter><c:comp-filter name='VCALENDAR'><c:comp-filter name='VEVENT'/></c:comp-filter></c:filter></c:calendar-query>";
        try(Response r=http.newCall(request(c).header("Depth","1").method("REPORT",RequestBody.create(payload,XML)).build()).execute()) {
            if(r.code()!=207) throw failure(r.code());
            Element document=xml(body(r)).getDocumentElement();
            for(Node child=document.getFirstChild();child!=null;child=child.getNextSibling()) {
                if(child instanceof Element && !(DAV.equals(child.getNamespaceURI()) && ("response".equals(child.getLocalName()) || "responsedescription".equals(child.getLocalName())))) throw new IOException("Unknown report envelope; cache retained");
            }
            if(contains(document,DAV,"error") || contains(document,CAL,"limit-recurrence-set")) throw new IOException("Partial report rejected");
            List<Resource> out=new ArrayList<>(); Set<String> hrefs=new HashSet<>(),uids=new HashSet<>();
            for(Element row:children(document,DAV,"response")) {
                HttpUrl url=resolve(text(row,DAV,"href"));
                if(!isResourceHref(c.toString(),url.toString())) throw new IOException("Foreign collection member");
                String etag=null,data=null;
                for(Element ps:children(row,DAV,"propstat")) if(!ok(text(ps,DAV,"status"))) throw new IOException("Partial report rejected");
                for(Element p:successfulProps(row)) {
                    if(text(p,DAV,"getetag")!=null) etag=text(p,DAV,"getetag");
                    Element value=first(p,CAL,"calendar-data"); if(value!=null) data=value.getTextContent();
                }
                if(etag==null || data==null || data.isEmpty() || !hrefs.add(url.toString()) || out.size()>=20000) throw new IOException("Incomplete report rejected");
                Resource resource;
                try { resource=new Resource(url.toString(),etag,data); } catch(IllegalArgumentException ex) { throw new IOException("Invalid calendar data; cache retained"); }
                if(!uids.add(resource.uid)) throw new IOException("Duplicate UID in collection; cache retained");
                out.add(resource);
            }
            return out;
        }
    }
    public static boolean strongEtag(String etag) {
        if(etag==null || etag.length()<2 || etag.length()>4096 || etag.charAt(0)!='"' || etag.charAt(etag.length()-1)!='"') return false;
        for(int i=1;i<etag.length()-1;i++) { char c=etag.charAt(i); if(c=='"' || c<0x21 || c==0x7f) return false; }
        return true;
    }
    private static void requireEtag(String etag) { if(!strongEtag(etag)) throw new IllegalArgumentException("Strong ETag required for mutation"); }
    public Resource get(String collection,String href) throws IOException {
        HttpUrl u=eventUrl(collection,href);
        try(Response r=http.newCall(request(u).get().build()).execute()) {
            if(r.code()!=200) throw failure(r.code());
            String etag=r.header("ETag"); requireEtag(etag);
            try { return new Resource(u.toString(),etag,body(r)); } catch(IllegalArgumentException ex) { throw new IOException("Invalid resource response"); }
        }
    }
    public Resource put(String collection,String href,String etag,String raw) throws IOException {
        HttpUrl u=eventUrl(collection,href); IcsCodec.inspect(raw,ZoneOffset.UTC);
        if(etag!=null) requireEtag(etag);
        Request q=request(u).header(etag==null?"If-None-Match":"If-Match",etag==null?"*":etag).put(RequestBody.create(raw,ICS)).build();
        try(Response r=http.newCall(q).execute()) {
            if(!(r.code()==412 && etag==null)) {
                if(r.code()!=200 && r.code()!=201 && r.code()!=204) throw failure(r.code());
                String next=r.header("ETag");
                if(strongEtag(next)) return new Resource(u.toString(),next,raw);
            }
        }
        Resource remote=get(collection,u.toString());
        if(!IcsCodec.equivalent(raw,remote.ics)) {
            // The server may have canonicalized a successful write. Keep pending until user reviews.
            throw new ConflictException();
        }
        return remote;
    }
    private void requireNonCollection(HttpUrl url) throws IOException {
        String payload="<d:propfind xmlns:d='DAV:'><d:prop><d:resourcetype/></d:prop></d:propfind>";
        try(Response r=http.newCall(request(url).header("Depth","0").method("PROPFIND",RequestBody.create(payload,XML)).build()).execute()) {
            if(r.code()!=207) throw failure(r.code());
            Element document=xml(body(r)).getDocumentElement();
            List<Element> rows=children(document,DAV,"response");
            if(rows.size()!=1 || contains(document,DAV,"error")) throw new IOException("Unproven resource type");
            Element row=rows.get(0);
            if(children(row,DAV,"href").size()!=1 || !url.equals(resolve(text(row,DAV,"href")))) throw new IOException("Resource type identity mismatch");
            String status=text(row,DAV,"status");
            if(status!=null && !ok(status)) throw new IOException("Failed resource type response");
            int types=0;
            for(Element ps:children(row,DAV,"propstat")) {
                if(children(ps,DAV,"status").size()!=1 || !ok(text(ps,DAV,"status")) || children(ps,DAV,"prop").size()!=1) throw new IOException("Incomplete resource type response");
                for(Element type:children(first(ps,DAV,"prop"),DAV,"resourcetype")) {
                    types++;
                    // Only an explicit empty DAV resourcetype proves a non-collection.
                    // Unknown types, nested elements and malformed text all fail closed.
                    if(!type.getTextContent().trim().isEmpty() || type.getElementsByTagName("*").getLength()!=0) throw new IOException("Not an ordinary event resource");
                }
            }
            if(types!=1) throw new IOException("Missing or ambiguous resource type");
        }
    }
    public void delete(String collection,String href,String etag) throws IOException {
        requireEtag(etag); HttpUrl u=eventUrl(collection,href);
        // A collection can export one-event ICS: verify both the revision and DAV type.
        Resource existing;
        try { existing=get(collection,href); }
        catch(DavException ex) { if(ex.status==404 || ex.status==410) return; throw ex; }
        if(!etag.equals(existing.etag)) throw new ConflictException();
        requireNonCollection(u);
        try(Response r=http.newCall(request(u).header("If-Match",etag).delete().build()).execute()) {
            if(r.code()!=200 && r.code()!=204 && r.code()!=404 && r.code()!=410) throw failure(r.code());
        }
    }
}
