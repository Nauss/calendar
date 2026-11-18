package fr.nauss.calendar.core;

import biweekly.ICalendar;
import biweekly.component.VAlarm;
import biweekly.component.VEvent;
import biweekly.component.Observance;
import biweekly.io.TimezoneAssignment;
import biweekly.io.text.ICalReader;
import biweekly.parameter.Related;
import biweekly.property.*;
import biweekly.util.Frequency;
import biweekly.util.ICalDate;
import biweekly.util.Recurrence;
import java.io.IOException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Pure JVM iCalendar adapter. No network, no global timezone changes, no raw RRULE parsing. */
public final class IcsCodec {
    private IcsCodec() {}
    public static final int MAX_ICS_CHARS = 1_000_000;
    public static final int MAX_OCCURRENCES = 4000;
    public static final class Draft {
        public String title = "", location = "", notes = "", zone = ZoneId.systemDefault().getId(), repeat = "NONE";
        public LocalDateTime start = LocalDateTime.now().withSecond(0).withNano(0);
        public LocalDateTime end = start.plusHours(1);
        public boolean allDay, floating;
        public int reminderMinutes = -1;
    }
    public static final class Info {
        public final String uid;
        public final Draft draft;
        public final boolean editable;
        public final String protection;
        public final boolean timeEditable, recurrenceEditable, reminderEditable, timezoneEditable;
        public final String timeProtection;
        Info(ICalendar cal, VEvent event, Draft draft) {
            uid=event.getUid().getValue(); this.draft=draft; protection=protection(cal,event); editable=protection.isEmpty();
            boolean embedded=!cal.getTimezoneInfo().getComponents().isEmpty();
            boolean complex=complexRecurrence(cal,event);
            recurrenceEditable=editable && !complex && !embedded;
            reminderEditable=editable && (event.getAlarms().isEmpty() || (event.getAlarms().size()==1 && simpleAlarm(event.getAlarms().get(0))));
            timeProtection=!editable?protection:complex?"RECURRENCE_EXCEPTIONS":embedded && event.getRecurrenceRule()!=null?"EMBEDDED_SERIES"
                :embedded && !matchingOriginalTimes(cal,event,draft)?"CUSTOM_TIMEZONE":"";
            timeEditable=timeProtection.isEmpty();
            timezoneEditable=timeEditable && !embedded;
        }
    }
    public static final class Occurrence {
        public final String uid, key, title, location, notes;
        public final Instant start, end;
        public final boolean allDay;
        public final int reminderMinutes;
        Occurrence(VEvent e, Instant start, Instant end, String key) {
            uid=e.getUid().getValue(); this.key=key; this.start=start; this.end=end;
            title=text(e.getSummary()); location=text(e.getLocation()); notes=text(e.getDescription());
            allDay=!e.getDateStart().getValue().hasTime(); reminderMinutes=reminder(e);
        }
    }
    private static String text(ValuedProperty<String> p) { return p==null || p.getValue()==null ? "" : p.getValue().replace("\r\n","\n"); }
    /** Public codes only: never expose parser messages or calendar fields in UI/logs. */
    public static final class UnsupportedException extends IllegalArgumentException {
        public final String reason;
        UnsupportedException(String reason) { super("Unsupported calendar data: "+safeReadReason(reason));this.reason=safeReadReason(reason); }
    }
    private static UnsupportedException invalid() {
        // Compiled source locations are diagnostic metadata, never imported values.
        for(StackTraceElement frame:new Throwable().getStackTrace()) {
            if(frame.getClassName().equals(IcsCodec.class.getName()) && !frame.getMethodName().equals("invalid") && frame.getLineNumber()>0)
                return new UnsupportedException("DATA_SITE_"+frame.getLineNumber());
        }
        return new UnsupportedException("DATA");
    }
    /** Allowlisted diagnostic categories only; never forward a parser message or field name. */
    public static String safeReadReason(String reason) {
        if(reason==null) return "DATA";
        return Set.of("DATA","LIMIT","RECURRENCE","TIMEZONE","RESPONSE","XML","PARTIAL","IDENTITY","METADATA","OFFSET_PRECISION").contains(reason)
            || reason.matches("PARSE_WARNING_[0-9]{1,3}|DATA_(?:(?:NULL|DATE|ARGUMENT|ARITHMETIC|UNSUPPORTED|MISSING|OTHER)_)?SITE_[0-9]{1,4}") ? reason : "DATA";
    }
    public static String readFailureReason(Exception failure) {
        if(failure instanceof UnsupportedException && !((UnsupportedException)failure).reason.equals("DATA")) return safeReadReason(((UnsupportedException)failure).reason);
        String kind=failure instanceof NullPointerException?"NULL":failure instanceof DateTimeException?"DATE"
            :failure instanceof IllegalArgumentException?"ARGUMENT":failure instanceof ArithmeticException?"ARITHMETIC"
            :failure instanceof UnsupportedOperationException?"UNSUPPORTED":failure instanceof NoSuchElementException?"MISSING":"OTHER";
        for(StackTraceElement frame:failure.getStackTrace()) {
            if((frame.getClassName().equals(IcsCodec.class.getName()) || frame.getClassName().startsWith(IcsCodec.class.getName()+"$")) && frame.getLineNumber()>0)
                return safeReadReason("DATA_"+kind+"_SITE_"+frame.getLineNumber());
        }
        return "DATA";
    }
    private static String writeCalendar(ICalendar calendar) {
        // Reconciliation must use the same lossless offset writer as editing.
        return biweekly.Biweekly.write(calendar).version(calendar.getVersion())
            .register(SecondPrecisionOffsets.from()).register(SecondPrecisionOffsets.to()).go();
    }
    private static ICalendar parse(String raw, ZoneId zone) {
        if(raw==null || raw.length()>MAX_ICS_CHARS || !raw.trim().endsWith("END:VCALENDAR")) throw invalid();
        try(ICalReader reader=new ICalReader(raw)) {
            reader.registerScribe(SecondPrecisionOffsets.from());
            reader.registerScribe(SecondPrecisionOffsets.to());
            reader.setDefaultTimezone(TimeZone.getTimeZone(zone));
            ICalendar cal=reader.readNext();
            // 37 resolves an IANA TZID; 43 uses an exactly matching embedded VTIMEZONE.
            // Neither falls back to the default timezone or drops data. Unknown zones
            // (5/38) must still fail closed; never strip a vendor prefix or guess IANA.
            for(var warning:reader.getWarnings()) {
                Integer code=warning.getCode();
                if(Integer.valueOf(37).equals(code) || Integer.valueOf(43).equals(code)) continue;
                if(Integer.valueOf(5).equals(code) || Integer.valueOf(38).equals(code)) throw new UnsupportedException("TIMEZONE");

                throw new UnsupportedException(code==null?"DATA":safeReadReason("PARSE_WARNING_"+code));
            }
            if(cal==null || cal.getEvents().isEmpty() || cal.getEvents().size()>1000 || reader.readNext()!=null) throw invalid();
            String uid=null; int masters=0; Set<String> ids=new HashSet<>();
            for(VEvent e:cal.getEvents()) {
                if(e.getUid()==null || e.getUid().getValue()==null || e.getUid().getValue().isEmpty()) throw invalid();
                if(uid==null) uid=e.getUid().getValue(); else if(!uid.equals(e.getUid().getValue())) throw invalid();
                if(e.getRecurrenceId()==null) masters++;
                else if(!ids.add(e.getRecurrenceId().getValue().toString())) throw invalid();
                if(e.getDateStart()==null && !(e.getRecurrenceId()!=null && cancelled(e))) throw invalid();
                if(e.getProperties(DateStart.class).size()>1 || e.getProperties(DateEnd.class).size()>1 || e.getProperties(Uid.class).size()!=1) throw invalid();
                if(e.getDateStart()!=null && e.getDateStart().getValue()==null) throw invalid();
                if(e.getDateEnd()!=null && (e.getDateEnd().getValue()==null || e.getDateEnd().getValue().hasTime()!=e.getDateStart().getValue().hasTime())) throw invalid();
            }
            if(masters>1) throw invalid();
            return cal;
        } catch(IOException | NullPointerException ex) { throw invalid(); }
    }
    private static VEvent master(ICalendar cal) {
        for(VEvent e:cal.getEvents()) if(e.getRecurrenceId()==null) return e;
        return cal.getEvents().get(0);
    }
    private static TimeZone timeZone(ICalendar cal, DateOrDateTimeProperty p, ZoneId display) {
        if(!p.getValue().hasTime() || cal.getTimezoneInfo().isFloating(p)) return TimeZone.getTimeZone(display);
        TimezoneAssignment a=cal.getTimezoneInfo().getTimezone(p);
        return a==null ? TimeZone.getTimeZone("UTC") : a.getTimeZone();
    }
    private static LocalDate rawDay(ICalDate d) {
        var raw=d.getRawComponents();
        return raw==null ? d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : LocalDate.of(raw.getYear(),raw.getMonth(),raw.getDate());
    }
    private static Instant instant(DateOrDateTimeProperty p, ZoneId display) {
        return p.getValue().hasTime() ? p.getValue().toInstant() : rawDay(p.getValue()).atStartOfDay(display).toInstant();
    }
    private static Instant eventEnd(ICalendar cal, VEvent e, Instant start, ZoneId display) {
        if(e.getDateEnd()!=null) return instant(e.getDateEnd(),display);
        if(e.getDuration()!=null) return durationEnd(cal,e,start,display);
        return !e.getDateStart().getValue().hasTime() ? start.atZone(display).plusDays(1).toInstant() : start;
    }
    private static long part(Integer value) { return value==null?0:value.longValue(); }
    private static Instant durationEnd(ICalendar cal,VEvent e,Instant start,ZoneId display) {
        var duration=e.getDuration().getValue();
        if(duration==null || duration.isPrior()) throw invalid();
        long days=part(duration.getWeeks())*7+part(duration.getDays());
        long seconds=part(duration.getHours())*3600+part(duration.getMinutes())*60+part(duration.getSeconds());
        // A one-off long absence costs one occurrence, not years of recurrence work.
        long limit=oneOff(cal,e)?366000:366;
        if(days>limit || seconds>limit*86400 || (!e.getDateStart().getValue().hasTime() && duration.hasTime())) throw invalid();
        // RFC 5545: nominal calendar days first, then elapsed sub-day units.
        // Calendar retains embedded VTIMEZONE rules, not just IANA zone IDs.
        Calendar local=Calendar.getInstance(timeZone(cal,e.getDateStart(),display));
        local.setTime(Date.from(start));local.add(Calendar.DATE,(int)days);
        return local.toInstant().plusSeconds(seconds);
    }
    private static String protection(ICalendar cal,VEvent e) {
        if(cal.getMethod()!=null || cal.getEvents().stream().anyMatch(v->v.getOrganizer()!=null || !v.getAttendees().isEmpty())) return "MEETING";
        if(!cal.getTodos().isEmpty() || !cal.getJournals().isEmpty() || !cal.getFreeBusies().isEmpty() || !cal.getExperimentalComponents().isEmpty() || !e.getExperimentalComponents().isEmpty()) return "COMPLEX_COMPONENT";
        if(e.getDateStart()==null) return "CANCELLED";
        return "";
    }
    private static boolean complexRecurrence(ICalendar cal,VEvent e) {
        return cal.getEvents().size()!=1 || e.getRecurrenceId()!=null || !e.getExceptionDates().isEmpty() || !e.getRecurrenceDates().isEmpty()
            || !e.getProperties(ExceptionRule.class).isEmpty() || e.getProperties(RecurrenceRule.class).size()>1
            || (e.getRecurrenceRule()!=null && !simple(e.getRecurrenceRule().getValue()));
    }
    private static LocalDateTime localTime(Instant instant,TimeZone zone) {
        Calendar c=Calendar.getInstance(zone);c.setTimeInMillis(instant.toEpochMilli());
        return LocalDateTime.of(c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH),c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),c.get(Calendar.SECOND));
    }
    // A TZID is only a label. Validate actual assignments at original AND requested instants.
    // This proves one-off endpoints, never equivalence of historical/future recurrence rules.
    private static boolean matchingOriginalTimes(ICalendar cal,VEvent e,Draft d) {
        if(d.allDay || d.floating || e.getDateEnd()==null || e.getDuration()!=null) return false;
        ZoneId zone=ZoneId.of(d.zone);
        for(DateOrDateTimeProperty p:List.of(e.getDateStart(),e.getDateEnd())) {
            TimeZone assigned=timeZone(cal,p,zone);
            if(!assigned.getID().equals(d.zone) || !localTime(p.getValue().toInstant(),assigned).equals(p.getValue().toInstant().atZone(zone).toLocalDateTime())) return false;
        }
        return true;
    }
    private static Date assignedDate(ICalendar cal,DateOrDateTimeProperty property,LocalDateTime target,ZoneId zone) {
        TimeZone assigned=timeZone(cal,property,zone);
        // Preserve an unchanged ambiguous endpoint's original choice of offset.
        if(localTime(property.getValue().toInstant(),assigned).equals(target)) return new Date(property.getValue().getTime());
        if(zone.getRules().getValidOffsets(target).size()!=1) throw invalid();
        Instant instant=target.atZone(zone).toInstant();
        if(!localTime(instant,assigned).equals(target)) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
        TimezoneAssignment assignment=cal.getTimezoneInfo().getTimezone(property);
        if(assignment!=null && !instant.equals(uniqueAssignedInstant(assignment,target))) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
        return Date.from(instant);
    }
    // Enumerate finite offsets from parsed rules, not guessed hour/minute samples.
    // Keep millisecond precision, including supported historical offset seconds.
    static Instant uniqueAssignedInstant(TimezoneAssignment assignment,LocalDateTime target) {
        var component=assignment.getComponent();
        if(component==null) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
        List<Observance> observances=new ArrayList<>(component.getStandardTimes());
        observances.addAll(component.getDaylightSavingsTime());
        if(observances.isEmpty()) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
        TimeZone assigned=assignment.getTimeZone();
        Set<Long> offsets=new HashSet<>();offsets.add((long)assigned.getRawOffset());
        for(Observance observance:observances) {
            if(observance.getProperties(TimezoneOffsetFrom.class).size()!=1 || observance.getProperties(TimezoneOffsetTo.class).size()!=1
                || observance.getTimezoneOffsetFrom()==null || observance.getTimezoneOffsetFrom().getValue()==null
                || observance.getTimezoneOffsetTo()==null || observance.getTimezoneOffsetTo().getValue()==null) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
            offsets.add(observance.getTimezoneOffsetFrom().getValue().getMillis());
            offsets.add(observance.getTimezoneOffsetTo().getValue().getMillis());
        }
        // ICalTimeZone's inherited getOffset(long) is not an inverse of retained
        // observance wall times. Expand those transitions with biweekly's recurrence
        // engine in each observance's fixed FROM offset; never parse RRULE ourselves.
        Instant latest=target.toInstant(ZoneOffset.UTC).minusMillis(Collections.min(offsets));
        NavigableMap<Instant,Long> transitions=new TreeMap<>();int steps=0;
        for(Observance observance:observances) {
            if(observance.getProperties(DateStart.class).size()!=1 || observance.getDateStart()==null || observance.getDateStart().getValue()==null
                || !observance.getDateStart().getValue().hasTime() || observance.getDateStart().getValue().getRawComponents()==null
                || !observance.getRecurrenceDates().isEmpty() || !observance.getExceptionDates().isEmpty()
                || !observance.getProperties(ExceptionRule.class).isEmpty() || observance.getProperties(RecurrenceRule.class).size()>1) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
            if(observance.getRecurrenceRule()!=null) {
                Recurrence rule=observance.getRecurrenceRule().getValue();
                if(rule==null || !Set.of(Frequency.YEARLY,Frequency.MONTHLY,Frequency.WEEKLY,Frequency.DAILY).contains(rule.getFrequency())
                    || !rule.getXRules().isEmpty() || rule.getByHour().size()>24 || rule.getByMinute().size()>1 || rule.getBySecond().size()>1) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
            }
            long from=observance.getTimezoneOffsetFrom().getValue().getMillis(),to=observance.getTimezoneOffsetTo().getValue().getMillis();
            Observance copy=observance.copy();
            Instant wall=biweekly.util.Google2445Utils.convertUtc(biweekly.util.Google2445Utils.convertFromRawComponents(observance.getDateStart().getValue())).toInstant();
            copy.setDateStart(new ICalDate(Date.from(wall.minusMillis(from)),true));
            var iterator=biweekly.util.Google2445Utils.getDateIterator(copy,new SimpleTimeZone(Math.toIntExact(from),"observance-offset"));
            while(iterator.hasNext()) {
                if(++steps>20000) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
                Instant transition=iterator.next().toInstant();
                if(transition.isAfter(latest)) break;
                Long previous=transitions.putIfAbsent(transition,to);
                if(previous!=null && previous!=to) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
            }
        }
        Instant unique=null;
        for(long offset:offsets) {
            Instant candidate=target.toInstant(ZoneOffset.UTC).minusMillis(offset);
            var active=transitions.floorEntry(candidate);
            // No proven preceding observance: do not invent a prehistory offset.
            if(active==null) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
            if(active.getValue()!=offset) continue;
            if(unique!=null) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
            unique=candidate;
        }
        if(unique==null || !localTime(unique,assigned).equals(target)) throw new IllegalArgumentException("CUSTOM_TIMEZONE");
        return unique;
    }
    private static boolean simple(Recurrence r) {
        return r!=null && Set.of(Frequency.DAILY,Frequency.WEEKLY,Frequency.MONTHLY,Frequency.YEARLY).contains(r.getFrequency())
            && (r.getInterval()==null || r.getInterval()==1) && r.getCount()==null && r.getUntil()==null
            && r.getByDay().isEmpty() && r.getByMonthDay().isEmpty() && r.getByMonth().isEmpty() && r.getByHour().isEmpty()
            && r.getByMinute().isEmpty() && r.getBySecond().isEmpty() && r.getByYearDay().isEmpty() && r.getByWeekNo().isEmpty()
            && r.getBySetPos().isEmpty() && r.getXRules().isEmpty() && r.getWorkweekStarts()==null;
    }
    private static boolean simpleAlarm(VAlarm a) {
        return a.getAction()!=null && a.getAction().isDisplay() && a.getTrigger()!=null && a.getTrigger().getDuration()!=null
            && (a.getTrigger().getRelated()==null || a.getTrigger().getRelated()==Related.START)
            && a.getTrigger().getDuration().toMillis()<=0 && a.getTrigger().getDuration().toMillis()>=-10080L*60000
            && a.getTrigger().getDuration().toMillis()%60000==0 && a.getRepeat()==null && a.getDuration()==null
            && a.getAttachments().isEmpty() && a.getExperimentalProperties().isEmpty();
    }
    private static int reminder(VEvent e) {
        if(e.getAlarms().size()!=1 || !simpleAlarm(e.getAlarms().get(0))) return -1;
        return (int)(-e.getAlarms().get(0).getTrigger().getDuration().toMillis()/60000);
    }
    public static Info inspect(String raw, ZoneId zone) {
        ICalendar cal=parse(raw,zone); VEvent e=master(cal); Draft d=new Draft();
        d.title=text(e.getSummary()); d.location=text(e.getLocation()); d.notes=text(e.getDescription());
        if(e.getDateStart()!=null) {
            d.allDay=!e.getDateStart().getValue().hasTime();
            d.floating=cal.getTimezoneInfo().isFloating(e.getDateStart());
            TimeZone tz=timeZone(cal,e.getDateStart(),zone);
            try { d.zone=tz.toZoneId().getId(); } catch(Exception ex) { d.zone=zone.getId(); }
            Instant start=instant(e.getDateStart(),zone);
            d.start=d.allDay?rawDay(e.getDateStart().getValue()).atStartOfDay():localTime(start,tz);
            d.end=d.allDay?eventEnd(cal,e,start,zone).atZone(zone).toLocalDateTime():localTime(eventEnd(cal,e,start,zone),tz);
        }
        d.repeat=e.getRecurrenceRule()==null?"NONE":e.getRecurrenceRule().getValue().getFrequency().name();
        d.reminderMinutes=reminder(e);
        return new Info(cal,e,d);
    }
    public static String save(String original, Draft d) {
        ZoneId zone=ZoneId.of(d.zone);
        if(d.title==null || d.title.length()>1000 || d.location.length()>4000 || d.notes.length()>100000
            || d.reminderMinutes < -1 || d.reminderMinutes>10080) throw invalid();
        ICalendar cal; VEvent e;
        if(original==null) {
            cal=new ICalendar(); cal.setProductId("-//Agenda//Calendar 0.1//EN"); e=new VEvent(); e.setUid(UUID.randomUUID()+"@agenda.local"); cal.addEvent(e);
        } else {
            cal=parse(original,zone); e=master(cal); if(!protection(cal,e).isEmpty()) throw new IllegalArgumentException("Protected resource: edit in the originating calendar client");
        }
        boolean wasFloating=original!=null && cal.getTimezoneInfo().isFloating(e.getDateStart());
        if(wasFloating && !d.floating && !d.allDay) throw new IllegalArgumentException("Floating semantics must be preserved");
        Info info=original==null?null:inspect(original,zone);
        Draft old=info==null?null:info.draft;
        boolean preserveTiming=old!=null && old.start.equals(d.start) && old.end.equals(d.end)
            && old.zone.equals(d.zone) && old.allDay==d.allDay && old.floating==d.floating;
        boolean changeRepeat=old==null || !old.repeat.equals(d.repeat);
        boolean changeReminder=old==null || old.reminderMinutes!=d.reminderMinutes;
        if(d.title.trim().isEmpty() && (old==null || !old.title.equals(d.title))) throw invalid();
        if(!preserveTiming && (!d.end.isAfter(d.start) || (info!=null && !info.timeEditable))) throw invalid();
        if(changeRepeat && ((info!=null && !info.recurrenceEditable) || !Set.of("NONE","DAILY","WEEKLY","MONTHLY","YEARLY").contains(d.repeat))) throw invalid();
        if(changeReminder && info!=null && !info.reminderEditable) throw invalid();
        boolean embedded=!cal.getTimezoneInfo().getComponents().isEmpty();
        if(!preserveTiming && embedded && (!old.zone.equals(d.zone) || old.allDay!=d.allDay || old.floating!=d.floating)) throw invalid();
        var startParameters=e.getDateStart()==null?null:new biweekly.parameter.ICalParameters(e.getDateStart().getParameters());
        var endParameters=e.getDateEnd()==null?null:new biweekly.parameter.ICalParameters(e.getDateEnd().getParameters());
        if(old==null || !old.title.equals(d.title)) { if(e.getSummary()==null) e.setSummary(d.title.trim()); else e.getSummary().setValue(d.title.trim()); }
        if(old==null || !old.location.equals(d.location)) { if(e.getLocation()==null) e.setLocation(d.location); else e.getLocation().setValue(d.location); }
        if(old==null || !old.notes.equals(d.notes)) { if(e.getDescription()==null) e.setDescription(d.notes); else e.getDescription().setValue(d.notes); }
        if(e.getDateTimeStamp()==null) e.setDateTimeStamp(new Date()); else e.getDateTimeStamp().setValue(new Date());
        if(e.getLastModified()==null) e.setLastModified(new Date()); else e.getLastModified().setValue(new Date());
        if(e.getSequence()==null) e.setSequence(0); else e.getSequence().setValue(e.getSequence().getValue()+1);
        if(!preserveTiming) {
        if(embedded) {
            Date start=assignedDate(cal,e.getDateStart(),d.start,zone),end=assignedDate(cal,e.getDateEnd(),d.end,zone);
            if(!end.after(start)) throw invalid();
            e.getDateStart().setValue(start,true);e.getDateEnd().setValue(end,true);
        } else {
        e.removeProperties(DurationProperty.class);
        if(d.allDay) {
            if(!d.end.toLocalDate().isAfter(d.start.toLocalDate())) throw invalid();
            e.setDateStart(new DateStart(new ICalDate(new biweekly.util.DateTimeComponents(d.start.getYear(),d.start.getMonthValue(),d.start.getDayOfMonth()),false)));
            e.setDateEnd(new DateEnd(new ICalDate(new biweekly.util.DateTimeComponents(d.end.getYear(),d.end.getMonthValue(),d.end.getDayOfMonth()),false)));
        } else if(d.floating) {
            e.setDateStart(new DateStart(floatingDate(d.start)));e.setDateEnd(new DateEnd(floatingDate(d.end)));
            cal.getTimezoneInfo().setFloating(e.getDateStart(),true);cal.getTimezoneInfo().setFloating(e.getDateEnd(),true);
        } else {
            // Reject a local time in a DST gap rather than silently moving the appointment.
            if(zone.getRules().getValidOffsets(d.start).size()!=1 || zone.getRules().getValidOffsets(d.end).size()!=1) throw invalid();
            e.setDateStart(Date.from(d.start.atZone(zone).toInstant())); e.setDateEnd(Date.from(d.end.atZone(zone).toInstant()));
            var assignment=new TimezoneAssignment(TimeZone.getTimeZone(zone),zone.getId());
            cal.getTimezoneInfo().setTimezone(e.getDateStart(),assignment); cal.getTimezoneInfo().setTimezone(e.getDateEnd(),assignment);
        }
        if(startParameters!=null) { startParameters.setTimezoneId(null);e.getDateStart().setParameters(startParameters); }
        if(endParameters!=null) { endParameters.setTimezoneId(null);e.getDateEnd().setParameters(endParameters); }
        }
        }
        if(changeRepeat) {
        if(d.repeat.equals("NONE")) e.removeProperties(RecurrenceRule.class);
        else if(e.getRecurrenceRule()==null) e.setRecurrenceRule(new Recurrence.Builder(Frequency.valueOf(d.repeat)).build());
        else e.getRecurrenceRule().setValue(new Recurrence.Builder(Frequency.valueOf(d.repeat)).build());
        }
        if(changeReminder) {
        if(d.reminderMinutes<0) e.removeComponents(VAlarm.class);
        else if(e.getAlarms().isEmpty()) e.addAlarm(VAlarm.display(new Trigger(biweekly.util.Duration.fromMillis(-d.reminderMinutes*60000L),Related.START),d.title));
        else if(reminder(e)!=d.reminderMinutes) {
            VAlarm a=e.getAlarms().get(0);Trigger trigger=new Trigger(biweekly.util.Duration.fromMillis(-d.reminderMinutes*60000L),Related.START);
            trigger.setParameters(new biweekly.parameter.ICalParameters(a.getTrigger().getParameters()));a.setTrigger(trigger);
        }
        }
        return writeCalendar(cal);
    }
    private static ICalDate floatingDate(LocalDateTime value) {
        return new ICalDate(new biweekly.util.DateTimeComponents(value.getYear(),value.getMonthValue(),value.getDayOfMonth(),value.getHour(),value.getMinute(),value.getSecond(),false),true);
    }
    private static boolean cancelled(VEvent e) { return e.getStatus()!=null && e.getStatus().isCancelled(); }
    private static boolean oneOff(ICalendar cal,VEvent e) {
        return cal.getEvents().size()==1 && e.getRecurrenceId()==null && e.getRecurrenceRule()==null
            && e.getRecurrenceDates().isEmpty() && e.getExceptionDates().isEmpty() && e.getProperties(ExceptionRule.class).isEmpty();
    }
    private static void requireSafeHourlyZone(ICalendar cal,VEvent event,Instant start,Instant hi,ZoneId display) {
        // biweekly's hourly iterator can normalize a DST gap into duplicate instants.
        // Keep the mature iterator for stable-offset ranges; do not invent a DST recurrence engine.
        if(!cal.getTimezoneInfo().getComponents().isEmpty()) throw new UnsupportedException("TIMEZONE");
        try {
            ZoneId zone=timeZone(cal,event.getDateStart(),display).toZoneId();
            var rules=zone.getRules();
            if(rules.getValidOffsets(LocalDateTime.ofInstant(start,zone)).size()!=1) throw new UnsupportedException("TIMEZONE");
            var transition=rules.nextTransition(start.minusSeconds(1));
            if(transition!=null && transition.getInstant().isBefore(hi)) throw new UnsupportedException("TIMEZONE");
        } catch(DateTimeException ex) { throw new UnsupportedException("TIMEZONE"); }
    }
    public static List<Occurrence> expand(String raw, LocalDate from, LocalDate until, ZoneId display) {
        if(!until.isAfter(from) || ChronoUnit.DAYS.between(from,until)>370) throw new IllegalArgumentException("Expansion window must be 1–370 days");
        ICalendar cal=parse(raw,display); VEvent main=master(cal);
        Instant lo=from.atStartOfDay(display).toInstant(), hi=until.atStartOfDay(display).toInstant();
        List<Occurrence> result=new ArrayList<>(); Map<Instant,VEvent> overrides=new HashMap<>();
        for(VEvent e:cal.getEvents()) {
            if(e.getRecurrenceId()!=null) {
                if(e.getRecurrenceId().getRange()!=null) throw new UnsupportedException("RECURRENCE");
                Instant id=instant(e.getRecurrenceId(),display); overrides.put(id,e);
                if(!cancelled(e)) add(result,e,instant(e.getDateStart(),display),eventEnd(cal,e,instant(e.getDateStart(),display),display),id.toString(),lo,hi);
            }
        }
        if(main.getRecurrenceId()==null && !cancelled(main)) {
            for(RecurrenceRule rule: main.getProperties(RecurrenceRule.class)) {
                Recurrence r=rule.getValue();
                if(!Set.of(Frequency.HOURLY,Frequency.DAILY,Frequency.WEEKLY,Frequency.MONTHLY,Frequency.YEARLY).contains(r.getFrequency()) || !r.getXRules().isEmpty()) throw new UnsupportedException("RECURRENCE");
                if(r.getFrequency()==Frequency.HOURLY && !main.getDateStart().getValue().hasTime()) throw invalid();
                if(r.getByHour().size()>24 || r.getByMinute().size()>1 || r.getBySecond().size()>1) throw new UnsupportedException("LIMIT");
            }
            if(!main.getProperties(ExceptionRule.class).isEmpty() || main.getRecurrenceDates().stream().anyMatch(r->!r.getPeriods().isEmpty())) throw new UnsupportedException("RECURRENCE");
            Instant start=instant(main.getDateStart(),display), end=eventEnd(cal,main,start,display);
            if(main.getProperties(RecurrenceRule.class).stream().anyMatch(r->r.getValue().getFrequency()==Frequency.HOURLY)) requireSafeHourlyZone(cal,main,start,hi,display);
            long duration=java.time.Duration.between(start,end).toMillis();
            if(duration<0) throw invalid();
            if(oneOff(cal,main)) {
                add(result,main,start,end,start.toString(),lo,hi);
                return result;
            }
            if(duration>366L*86400000) throw new UnsupportedException("LIMIT");
            boolean allDay=!main.getDateStart().getValue().hasTime();
            long days=allDay?ChronoUnit.DAYS.between(start.atZone(display).toLocalDate(),end.atZone(display).toLocalDate()):0;
            var iterator=main.getDateIterator(timeZone(cal,main.getDateStart(),display));
            // Iteration has a hard work budget, including historical instances. Fail visibly, never hang.
            int steps=0;
            while(iterator.hasNext()) {
                if(++steps>20000) throw new UnsupportedException("LIMIT");
                Date next=iterator.next();
                Instant occurrence=allDay ? next.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(display).toInstant() : next.toInstant();
                if(!occurrence.isBefore(hi)) break;
                if(overrides.containsKey(occurrence)) continue;
                Instant finish=main.getDuration()!=null?durationEnd(cal,main,occurrence,display):allDay?occurrence.atZone(display).plusDays(days).toInstant():occurrence.plusMillis(duration);
                add(result,main,occurrence,finish,occurrence.toString(),lo,hi);
            }
        }
        result.sort(Comparator.comparing(o->o.start)); return result;
    }
    private static void add(List<Occurrence> out,VEvent e,Instant start,Instant end,String key,Instant lo,Instant hi) {
        if(start.isBefore(hi) && (end.isAfter(lo) || (start.equals(end) && !start.isBefore(lo)))) {
            if(out.size()>=MAX_OCCURRENCES) throw new UnsupportedException("LIMIT");
            out.add(new Occurrence(e,start,end,key));
        }
    }
    /** Conservative lost-create-response reconciliation: no subset/UID-only comparisons. */
    public static boolean equivalent(String a,String b) {
        return writeCalendar(parse(a,ZoneOffset.UTC)).equals(writeCalendar(parse(b,ZoneOffset.UTC)));
    }
}
