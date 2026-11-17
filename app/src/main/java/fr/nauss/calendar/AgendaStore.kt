package fr.nauss.calendar

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import fr.nauss.calendar.core.DavClient
import fr.nauss.calendar.core.IcsCodec
import fr.nauss.calendar.core.SyncEngine
import java.time.ZoneId
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl

data class CalendarRow(val id: String, val name: String, val color: String, val remote: Boolean, val writable: Boolean, val visible: Boolean, val enabled: Boolean)
data class EventRow(val id: String, val calendar: String, val href: String, val uid: String, val raw: String, val etag: String?, val deleted: Boolean, val pending: Boolean, val error: String, val attempted: Boolean)
fun contentVersion(raw: String): String = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)))
fun eventRevision(event: EventRow): String = contentVersion(event.raw+"\u0000"+event.etag.orEmpty())

/** Private database; transactional durable outbox. No Android Calendar Provider. */
class AgendaStore(context: Context, name: String = "agenda.db",private val onWidgetChange: ()->Unit = {}) : SQLiteOpenHelper(context, name, null, 1), SyncEngine.Store {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE calendars(id TEXT PRIMARY KEY,name TEXT NOT NULL,color TEXT NOT NULL,remote INTEGER NOT NULL,writable INTEGER NOT NULL,visible INTEGER NOT NULL,enabled INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY,calendar TEXT NOT NULL REFERENCES calendars(id),href TEXT NOT NULL,uid TEXT NOT NULL,raw TEXT NOT NULL,etag TEXT,deleted INTEGER NOT NULL DEFAULT 0, UNIQUE(calendar,href),UNIQUE(calendar,uid))")
        db.execSQL("CREATE TABLE outbox(id TEXT PRIMARY KEY,event TEXT NOT NULL UNIQUE REFERENCES events(id) ON DELETE CASCADE,calendar TEXT NOT NULL,href TEXT NOT NULL,etag TEXT,raw TEXT NOT NULL,kind TEXT NOT NULL,attempted INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL("INSERT INTO calendars VALUES('local','','#1A73E8',0,1,1,1)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("No destructive migration allowed") }
    private fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        pairs.forEach { (k,v) -> when(v) { null -> putNull(k); is String -> put(k,v); is Int -> put(k,v); is Long -> put(k,v); is Boolean -> put(k,if(v) 1 else 0); else -> error("Unsupported DB value") } }
    }
    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db=writableDatabase; db.beginTransaction();var committed=false
        try { val result=block(db); db.setTransactionSuccessful();committed=true; return result }
        finally { db.endTransaction();if(committed && !db.inTransaction()) notifyWidgets() }
    }
    // A widget scheduler failure must never turn a committed edit into a reported failed save.
    private fun notifyWidgets() { runCatching { onWidgetChange() } }
    @Synchronized fun meta(key: String, fallback: String = ""): String = readableDatabase.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else fallback }
    @Synchronized fun setMeta(key: String, value: String) {
        val changed=widgetMetadataInvalidates(key) && meta(key)!=value
        writableDatabase.insertWithOnConflict("metadata",null,values("key" to key,"value" to value),SQLiteDatabase.CONFLICT_REPLACE)
        if(changed) notifyWidgets()
    }
    @Synchronized fun calendars(): List<CalendarRow> = readableDatabase.rawQuery("SELECT * FROM calendars ORDER BY remote,name,id",null).use { c -> buildList { while(c.moveToNext()) add(CalendarRow(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)==1,c.getInt(4)==1,c.getInt(5)==1,c.getInt(6)==1)) } }
    @Synchronized fun calendar(id: String) = calendars().firstOrNull { it.id==id }
    @Synchronized fun visibility(id: String, visible: Boolean) { if(writableDatabase.update("calendars",values("visible" to visible),"id=?",arrayOf(id))>0) notifyWidgets() }
    @Synchronized fun addCalendars(list: List<DavClient.CalendarInfo>) = transaction { db ->
        list.forEach { c ->
            val v=values("name" to c.name,"color" to c.color,"remote" to true,"writable" to c.writable,"enabled" to true)
            if(db.update("calendars",v,"id=?",arrayOf(c.href))==0) db.insertOrThrow("calendars",null,v.apply { put("id",c.href);put("visible",1) })
        }
    }
    @Synchronized fun chooseCalendars(list: List<DavClient.CalendarInfo>) = transaction { db ->
        val selected=list.map { it.href }.toSet()
        calendars().filter { it.remote && it.id !in selected }.forEach { require(pending(it.id).isEmpty()) { "PENDING" }; db.update("calendars",values("enabled" to false),"id=?",arrayOf(it.id)) }
        addCalendars(list)
    }
    private val eventQuery="SELECT e.id,e.calendar,e.href,e.uid,e.raw,e.etag,e.deleted,o.id,o.error,o.attempted FROM events e LEFT JOIN outbox o ON o.event=e.id"
    private fun event(c: Cursor)=EventRow(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),if(c.isNull(5)) null else c.getString(5),c.getInt(6)==1,!c.isNull(7),if(c.isNull(8)) "" else c.getString(8),!c.isNull(9)&&c.getInt(9)==1)
    @Synchronized fun events(includeDeleted: Boolean = false): List<EventRow> = readableDatabase.rawQuery(eventQuery+(if(includeDeleted) "" else " WHERE e.deleted=0"),null).use { c -> buildList { while(c.moveToNext()) add(event(c)) } }
    /** Widget-only bounded cache read. The boolean must surface as a limited-preview notice. */
    @Synchronized fun widgetRecords(): Pair<List<EventRow>,Boolean> {
        val rows=mutableListOf<EventRow>();var chars=0;var limited=false
        readableDatabase.rawQuery("$eventQuery JOIN calendars c ON c.id=e.calendar WHERE e.deleted=0 AND c.visible=1 ORDER BY e.id LIMIT ${WidgetProjection.RECORD_LIMIT+1}",null).use { cursor ->
            while(cursor.moveToNext()) {
                val record=event(cursor)
                if(rows.size>=WidgetProjection.RECORD_LIMIT || chars+record.raw.length>4_000_000) { limited=true;break }
                rows.add(record);chars+=record.raw.length
            }
        }
        return rows to limited
    }
    @Synchronized fun event(id: String): EventRow? = readableDatabase.rawQuery("$eventQuery WHERE e.id=?",arrayOf(id)).use { if(it.moveToFirst()) event(it) else null }
    @Synchronized fun pendingCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox",null).use { it.moveToFirst();it.getInt(0) }
    @Synchronized fun committedSubmission(submissionId: String): String? = meta("submission:$submissionId").ifEmpty { null }
    @Synchronized fun save(id: String?, calendarId: String, draft: IcsCodec.Draft, expectedVersion: String? = null, submissionId: String? = null, expectedRevision: String? = null): String = transaction { db ->
        if(submissionId!=null) {
            require(id==null && submissionId.length==36 && UUID.fromString(submissionId).toString()==submissionId)
            committedSubmission(submissionId)?.let { return@transaction it }
        }
        val cal=calendar(calendarId) ?: error("MISSING_CALENDAR")
        check(cal.writable && cal.enabled) { "READ_ONLY" }
        val previous=id?.let { event(it) ?: error("MISSING_EVENT") }
        check(previous==null || if(expectedRevision!=null) expectedRevision==eventRevision(previous) else expectedVersion==contentVersion(previous.raw)) { "STALE_EDITOR" }
        check(previous==null || (previous.calendar==calendarId && !previous.deleted && !previous.attempted && previous.error!="CONFLICT")) { "PENDING" }
        check(!cal.remote || previous==null || ((previous.etag==null || DavClient.strongEtag(previous.etag)) && DavClient.isResourceHref(cal.id,previous.href))) { "READ_ONLY" }
        val raw=IcsCodec.save(previous?.raw,draft); val uid=IcsCodec.inspect(raw,ZoneId.systemDefault()).uid
        val eventId=id ?: UUID.randomUUID().toString()
        val href=previous?.href ?: if(cal.remote) cal.id.toHttpUrl().newBuilder().addPathSegment("${UUID.randomUUID()}.ics").build().toString() else eventId
        check(!cal.remote || DavClient.isResourceHref(cal.id,href)) { "READ_ONLY" }
        val v=values("calendar" to calendarId,"href" to href,"uid" to uid,"raw" to raw,"etag" to previous?.etag,"deleted" to false)
        if(previous==null) db.insertOrThrow("events",null,v.apply { put("id",eventId) }) else db.update("events",v,"id=?",arrayOf(eventId))
        if(cal.remote) queue(db,eventId,calendarId,href,previous?.etag,raw,"PUT")
        // Receipt and event/outbox commit together. Retain the receipt after deletion.
        if(submissionId!=null) db.insertOrThrow("metadata",null,values("key" to "submission:$submissionId","value" to eventId))
        eventId
    }
    private fun queue(db: SQLiteDatabase,id: String,calendar: String,href: String,etag: String?,raw: String,kind: String) {
        db.delete("outbox","event=?",arrayOf(id))
        db.insertOrThrow("outbox",null,values("id" to UUID.randomUUID().toString(),"event" to id,"calendar" to calendar,"href" to href,"etag" to etag,"raw" to raw,"kind" to kind,"attempted" to false,"error" to ""))
    }
    @Synchronized fun delete(id: String, expectedVersion: String? = null, expectedRevision: String? = null) = transaction { db ->
        val e=event(id) ?: return@transaction
        check(expectedVersion==null || expectedVersion==contentVersion(e.raw)) { "STALE_EDITOR" }
        check(expectedRevision==null || expectedRevision==eventRevision(e)) { "STALE_EDITOR" }
        val cal=calendar(e.calendar) ?: error("MISSING_CALENDAR")
        check(cal.writable && cal.enabled && !e.attempted && e.error!="CONFLICT") { "PENDING" }
        check(!cal.remote || DavClient.isResourceHref(cal.id,e.href)) { "READ_ONLY" }
        check(IcsCodec.inspect(e.raw,ZoneId.systemDefault()).editable) { "PROTECTED" }
        if(!cal.remote || (e.pending && e.etag==null)) db.delete("events","id=?",arrayOf(id))
        else { check(DavClient.strongEtag(e.etag)) { "READ_ONLY" }; db.update("events",values("deleted" to true),"id=?",arrayOf(id)); queue(db,id,e.calendar,e.href,e.etag,e.raw,"DELETE") }
    }
    @Synchronized override fun pending(calendar: String): List<SyncEngine.Operation> = readableDatabase.rawQuery("SELECT id,calendar,href,etag,raw,kind,error FROM outbox WHERE calendar=? ORDER BY rowid",arrayOf(calendar)).use { c -> buildList { while(c.moveToNext()) add(SyncEngine.Operation(c.getString(0),c.getString(1),c.getString(2),if(c.isNull(3)) null else c.getString(3),c.getString(4),c.getString(5),c.getString(6)=="CONFLICT")) } }
    @Synchronized override fun markAttempted(operationId: String) { check(writableDatabase.update("outbox",values("attempted" to true),"id=?",arrayOf(operationId))==1) { "STALE_OPERATION" } }
    @Synchronized override fun fail(operationId: String, code: String) { writableDatabase.update("outbox",values("error" to code),"id=?",arrayOf(operationId));notifyWidgets() }
    @Synchronized override fun acknowledge(operation: SyncEngine.Operation, resource: DavClient.Resource?) = transaction { db ->
        val id=db.rawQuery("SELECT event FROM outbox WHERE id=?",arrayOf(operation.id)).use { check(it.moveToFirst()); it.getString(0) }
        if(operation.kind=="DELETE") db.delete("events","id=?",arrayOf(id))
        else { requireNotNull(resource); check(resource.uid==event(id)!!.uid && resource.href==operation.href && DavClient.isResourceHref(operation.calendar,resource.href)); db.update("events",values("raw" to resource.ics,"etag" to resource.etag,"deleted" to false),"id=?",arrayOf(id)); db.delete("outbox","id=?",arrayOf(operation.id)) }
        Unit
    }
    @Synchronized override fun applySnapshot(calendar: String, completeSnapshot: List<DavClient.Resource>) = transaction { db ->
        val existing=events(true).filter { it.calendar==calendar }
        val byHref=existing.associateBy { it.href }; val byUid=existing.associateBy { it.uid }
        check(completeSnapshot.map { it.href }.toSet().size==completeSnapshot.size && completeSnapshot.map { it.uid }.toSet().size==completeSnapshot.size)
        completeSnapshot.forEach { r ->
            check(DavClient.isResourceHref(calendar,r.href) && DavClient.strongEtag(r.etag)) { "FOREIGN_RESOURCE" }
            check(byHref[r.href]?.uid?.let { it==r.uid } != false) { "UID_CHANGED" }
            check(byUid[r.uid]?.href?.let { it==r.href } != false) { "HREF_CHANGED" }
            val old=byHref[r.href]
            if(old?.pending!=true) {
                val v=values("calendar" to calendar,"href" to r.href,"uid" to r.uid,"raw" to r.ics,"etag" to r.etag,"deleted" to false)
                if(old==null) db.insertOrThrow("events",null,v.apply { put("id",UUID.randomUUID().toString()) }) else db.update("events",v,"id=?",arrayOf(old.id))
            }
        }
        // Never infer remote deletions while this collection has any unsent mutation.
        if(pending(calendar).isEmpty()) {
            val seen=completeSnapshot.map { it.href }.toSet()
            existing.filter { it.href !in seen }.forEach { db.delete("events","id=?",arrayOf(it.id)) }
        }
    }
    @Synchronized fun assertIdentityChangeAllowed(endpoint: String,user: String) {
        val same=meta("endpoint")==endpoint && meta("username")==user
        check(same || (pendingCount()==0 && calendars().none { it.remote })) { "ACCOUNT_LOCKED" }
    }
    @Synchronized fun setIdentity(endpoint: String,user: String) { assertIdentityChangeAllowed(endpoint,user);setMeta("endpoint",endpoint);setMeta("username",user) }
    /** Explicit recovery: keep the local version as an isolated local copy, then accept server state. */
    @Synchronized fun resolveWithLocalCopy(id: String,remote: DavClient.Resource?) = transaction { db ->
        val e=event(id) ?: error("MISSING_EVENT"); check(e.pending && e.error=="CONFLICT")
        val copy=UUID.randomUUID().toString(); val archive="recovered-$copy"
        db.insertOrThrow("calendars",null,values("id" to archive,"name" to "","color" to "#A142F4","remote" to false,"writable" to true,"visible" to true,"enabled" to true))
        db.insertOrThrow("events",null,values("id" to copy,"calendar" to archive,"href" to copy,"uid" to e.uid,"raw" to e.raw,"etag" to null,"deleted" to false))
        db.delete("outbox","event=?",arrayOf(id))
        if(remote==null) db.delete("events","id=?",arrayOf(id)) else {
            check(remote.href==e.href)
            db.update("events",values("raw" to remote.ics,"uid" to remote.uid,"etag" to remote.etag,"deleted" to false),"id=?",arrayOf(id))
        }
    }
    /** Only after explicit UI confirmation, never sends anything to the old/new server. */
    @Synchronized fun detachPreservingCache() = transaction { db ->
        check(pendingCount()==0) { "PENDING" }
        calendars().filter { it.remote }.forEach { cal ->
            val archive="archive-${UUID.randomUUID()}"
            db.insertOrThrow("calendars",null,values("id" to archive,"name" to cal.name,"color" to cal.color,"remote" to false,"writable" to true,"visible" to cal.visible,"enabled" to true))
            db.update("events",values("calendar" to archive,"etag" to null),"calendar=?",arrayOf(cal.id))
            db.delete("calendars","id=?",arrayOf(cal.id))
            if(meta("default_calendar")==cal.id) setMeta("default_calendar",archive)
        }
        setMeta("endpoint","");setMeta("username","");setMeta("sync_error","")
    }
}
