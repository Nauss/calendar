package fr.nauss.calendar.core;

import okhttp3.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One-origin, non-redirecting CalDAV transport. Never operates on calendar collections with PUT/DELETE. */
public final class DavClient {
    private static final String DAV="DAV:", CAL="urn:ietf:params:xml:ns:caldav";
    private static final MediaType XML=MediaType.get("application/xml; charset=utf-8"), ICS=MediaType.get("text/calendar; charset=utf-8");
    private static final int MAX_BODY=16_000_000;
    private static final int MAX_XML_DEPTH=128;
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
    /** A received response cannot safely replace cache; distinct from a connection failure. */
    public static final class DataException extends IOException {
        public final String reason;
        public DataException() { this("RESPONSE"); }
        public DataException(String reason) { super("Incomplete or unsupported calendar response; cache retained: "+IcsCodec.safeReadReason(reason));this.reason=IcsCodec.safeReadReason(reason); }
    }
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
        if(r.body()==null) throw new DataException();
        if(r.body().contentLength()>MAX_BODY) throw new DataException();
        try(InputStream in=r.body().byteStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int n,total=0;
            while((n=in.read(buffer))!=-1) { total+=n; if(total>MAX_BODY) throw new DataException(); out.write(buffer,0,n); }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();
        } catch(CharacterCodingException e) { throw new DataException(); }
    }
    private static IOException failure(int code) { return code==412 ? new ConflictException() : new DavException(code); }
    private static void feature(DocumentBuilderFactory f,String name,boolean value) {
        try { f.setFeature(name,value); } catch(ParserConfigurationException ignored) { /* Android lacks some Xerces features. DTDs are rejected before parsing, resolver rejects all entities. */ }
    }
    private static void checkXmlDepth(String raw) throws ParserConfigurationException,SAXException,IOException {
        // SAX is available on both Android and JVM; reject depth before allocating a DOM.
        SAXParserFactory f=SAXParserFactory.newInstance(); f.setNamespaceAware(true);
        var reader=f.newSAXParser().getXMLReader();
        DefaultHandler handler=new DefaultHandler() {
            private int depth;
            @Override public void startElement(String uri,String localName,String qName,org.xml.sax.Attributes attributes) throws SAXException {
                if(++depth>MAX_XML_DEPTH) throw new SAXException("XML depth exceeded");
            }
            @Override public void endElement(String uri,String localName,String qName) { depth--; }
            @Override public InputSource resolveEntity(String publicId,String systemId) throws SAXException { throw new SAXException("External entities rejected"); }
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        };
        reader.setContentHandler(handler); reader.setEntityResolver(handler); reader.setErrorHandler(handler);
        reader.parse(new InputSource(new StringReader(raw)));
    }
    private static Document xml(String raw) throws IOException {
        String upper=raw.toUpperCase(Locale.ROOT);
        if(upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) throw new DataException();
        try {
            checkXmlDepth(raw);
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true); f.setExpandEntityReferences(false);
            feature(f,"http://apache.org/xml/features/disallow-doctype-decl",true);
            feature(f,"http://xml.org/sax/features/external-general-entities",false);
            feature(f,"http://xml.org/sax/features/external-parameter-entities",false);
            feature(f,"http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
            DocumentBuilder b=f.newDocumentBuilder();
            b.setEntityResolver((publicId,systemId)->{ throw new SAXException("External entities rejected"); });
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() { @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; } @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; } });
            Document d=b.parse(new InputSource(new StringReader(raw)));
            if(!DAV.equals(d.getDocumentElement().getNamespaceURI()) || !"multistatus".equals(d.getDocumentElement().getLocalName())) throw new DataException();
            return d;
        } catch(ParserConfigurationException | SAXException e) { throw new DataException(); }
    }
    private static List<Element> children(Element p,String ns,String tag) {
        List<Element> out=new ArrayList<>();
        for(Node n=p.getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element && ns.equals(n.getNamespaceURI()) && tag.equals(n.getLocalName())) out.add((Element)n);
        return out;
    }
    private static Element first(Element p,String ns,String tag) { List<Element> list=children(p,ns,tag); return list.isEmpty()?null:list.get(0); }
    private static String text(Element p,String ns,String tag) { Element e=first(p,ns,tag); return e==null?null:DAV.equals(ns) && "status".equals(tag)?e.getTextContent():e.getTextContent().trim(); }
    private static boolean contains(Element p,String ns,String tag) { return p!=null && p.getElementsByTagNameNS(ns,tag).getLength()>0; }
    private static int statusCode(String status) {
        if(status==null) return -1;
        int start;
        if(status.startsWith("HTTP/1.1 ") || status.startsWith("HTTP/1.0 ")) start=9;
        else if(status.startsWith("HTTP/2 ")) start=7;
        else return -1;
        if(status.length()<start+3) return -1;
        int code=0;
        for(int i=start;i<start+3;i++) {
            char c=status.charAt(i);
            if(c<'0' || c>'9') return -1;
            code=code*10+c-'0';
        }
        if(status.length()>start+3 && status.charAt(start+3)!=' ') return -1;
        // Validate the entire reason, without trimming away trailing controls.
        // HTTP reason text is printable ASCII/obs-text, never another status line.
        for(int i=start+3;i<status.length();i++) {
            char c=status.charAt(i);
            if(c<0x20 || (c>=0x7f && c<=0x9f) || c>0xff) return -1;
        }
        return code;
    }
    private static boolean ok(String status) { return statusCode(status)==200; }
    private static IOException failedStatus(String status) {
        int code=statusCode(status);
        if(code==401 || code==403) return new DavException(code);
        return new DataException();
    }
    private List<Element> propfind(String href,String depth) throws IOException {
        String payload="<d:propfind xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav' xmlns:a='http://apple.com/ns/ical/'><d:prop><d:current-user-principal/><c:calendar-home-set/><d:resourcetype/><d:displayname/><d:current-user-privilege-set/><a:calendar-color/><c:supported-calendar-component-set/></d:prop></d:propfind>";
        try(Response r=http.newCall(request(resolve(href)).header("Depth",depth).method("PROPFIND",RequestBody.create(payload,XML)).build()).execute()) {
            if(r.code()!=207) throw failure(r.code());
            return children(xml(body(r)).getDocumentElement(),DAV,"response");
        }
    }
    private List<Element> successfulProps(Element response) throws IOException {
        String status=text(response,DAV,"status"); if(status!=null && !ok(status)) throw failedStatus(status);
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
            return parseReport(collection,body(r));
        }
    }
    // Snapshot/type-proof validation: discovery keeps separate property semantics.
    private static String reportScalar(Element value) throws DataException {
        for(Node n=value.getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element) throw new DataException();
        return value.getTextContent();
    }
    private static void reportMetadata(Element value) throws DataException {
        Node n=value;
        while(n!=null) {
            if(n instanceof Element && ((DAV.equals(n.getNamespaceURI()) && Set.of("href","status","propstat","prop","response","getetag").contains(n.getLocalName()))
                || (CAL.equals(n.getNamespaceURI()) && "calendar-data".equals(n.getLocalName())))) throw new DataException();
            if(n.getFirstChild()!=null) n=n.getFirstChild();
            else {
                while(n!=value && n.getNextSibling()==null) n=n.getParentNode();
                n=n==value?null:n.getNextSibling();
            }
        }
    }
    private static void reportChildren(Element parent,String... allowed) throws DataException {
        requireElementContent(parent);
        for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element) {
            if(!DAV.equals(n.getNamespaceURI()) || !Arrays.asList(allowed).contains(n.getLocalName())) reportMetadata((Element)n);
        }
    }
    private static void requireElementContent(Element parent) throws DataException {
        for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())
            if((n.getNodeType()==Node.TEXT_NODE || n.getNodeType()==Node.CDATA_SECTION_NODE) && !n.getNodeValue().trim().isEmpty()) throw new DataException();
    }
    private static List<Element> reportProps(Element row) throws IOException {
        reportChildren(row,"href","status","propstat");
        var statuses=children(row,DAV,"status");
        var propstats=children(row,DAV,"propstat");
        if(statuses.size()>1) throw new DataException();
        String responseStatus=statuses.isEmpty()?null:reportScalar(statuses.get(0));
        List<Element> props=new ArrayList<>();
        List<String> propStatuses=new ArrayList<>();
        for(Element ps:propstats) {
            reportChildren(ps,"status","prop");
            var codes=children(ps,DAV,"status");var containers=children(ps,DAV,"prop");
            if(codes.size()!=1 || containers.size()!=1) throw new DataException();
            propStatuses.add(reportScalar(codes.get(0)));
            Element prop=containers.get(0);
            requireElementContent(prop);
            for(Node n=prop.getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element) {
                if((DAV.equals(n.getNamespaceURI()) && "getetag".equals(n.getLocalName())) || (CAL.equals(n.getNamespaceURI()) && "calendar-data".equals(n.getLocalName()))) reportScalar((Element)n);
                else reportMetadata((Element)n);
            }
            props.add(prop);
        }
        if(responseStatus!=null) {
            if(!ok(responseStatus)) throw failedStatus(responseStatus);
            // A resource success is represented by propstat, never also by response/status.
            throw new DataException();
        }
        for(String status:propStatuses) if(!ok(status)) throw failedStatus(status);
        return props;
    }
    private static void reportEnvelope(Element document) throws DataException {
        requireElementContent(document);
        if(contains(document,DAV,"error") || contains(document,DAV,"limit-reached") || contains(document,CAL,"limit-recurrence-set")) throw new DataException();
        for(Node child=document.getFirstChild();child!=null;child=child.getNextSibling()) {
            if(child instanceof Element && !(DAV.equals(child.getNamespaceURI()) && ("response".equals(child.getLocalName()) || "responsedescription".equals(child.getLocalName())))) throw new DataException();
            if(child instanceof Element && "responsedescription".equals(child.getLocalName())) reportMetadata((Element)child);
        }
    }
    /** Same decoder as REPORT; package-private for offline synthetic regression tests. */
    List<Resource> parseReport(String collection,String raw) throws IOException {
        HttpUrl c=resolve(collection);
        try {
            Element document=xml(raw).getDocumentElement();
            reportEnvelope(document);

            List<Resource> out=new ArrayList<>(); Set<String> hrefs=new HashSet<>(),uids=new HashSet<>();
            for(Element row:children(document,DAV,"response")) {
                if(children(row,DAV,"href").size()!=1) throw new DataException();
                reportScalar(children(row,DAV,"href").get(0));
                HttpUrl url=resolve(text(row,DAV,"href"));
                if(!isResourceHref(c.toString(),url.toString())) throw new DataException();
                String etag=null,data=null;
                int etags=0,values=0;
                for(Element p:reportProps(row)) {
                    for(Element value:children(p,DAV,"getetag")) { etags++;etag=reportScalar(value).trim(); }
                    for(Element value:children(p,CAL,"calendar-data")) { values++;data=reportScalar(value); }
                }
                if(etags!=1 || values!=1) throw new DataException();
                if(etag==null || data==null || data.isEmpty() || !hrefs.add(url.toString()) || out.size()>=20000) throw new DataException();
                Resource resource;
                try { resource=new Resource(url.toString(),etag,data); }
                catch(IcsCodec.UnsupportedException ex) { throw new DataException(ex.reason); }
                catch(IllegalArgumentException ex) { throw new DataException("DATA"); }
                if(!uids.add(resource.uid)) throw new DataException();
                out.add(resource);
            }
            return out;
        } catch(IllegalArgumentException ex) { throw new DataException(); }
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
            try { return new Resource(u.toString(),etag,body(r)); }
            catch(IcsCodec.UnsupportedException ex) { throw new DataException(ex.reason); }
            catch(IllegalArgumentException ex) { throw new DataException(); }
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
            reportEnvelope(document);
            List<Element> rows=children(document,DAV,"response");
            if(rows.size()!=1) throw new DataException();
            Element row=rows.get(0);
            if(children(row,DAV,"href").size()!=1 || !url.equals(resolve(text(row,DAV,"href")))) throw new DataException();
            reportScalar(children(row,DAV,"href").get(0));
            int types=0;
            for(Element prop:reportProps(row)) {
                for(Element type:children(prop,DAV,"resourcetype")) {
                    types++;
                    // Only an explicit empty DAV resourcetype proves a non-collection.
                    // Unknown types, nested elements and malformed text all fail closed.
                    if(!type.getTextContent().trim().isEmpty() || type.getElementsByTagName("*").getLength()!=0) throw new DataException();
                }
            }
            if(types!=1 || document.getElementsByTagNameNS(DAV,"resourcetype").getLength()!=1) throw new DataException();
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
