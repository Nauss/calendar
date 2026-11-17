package fr.nauss.calendar.core;

import biweekly.ICalendar;
import biweekly.component.VAlarm;
import biweekly.component.VEvent;
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
        Info(String uid, Draft draft, String protection) {
            this.uid=uid; this.draft=draft; this.protection=protection; editable=protection.isEmpty();
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
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid or unsupported iCalendar resource"); }
    private static ICalendar parse(String raw, ZoneId zone) {
        if(raw==null || raw.length()>MAX_ICS_CHARS || !raw.trim().endsWith("END:VCALENDAR")) throw invalid();
        try(ICalReader reader=new ICalReader(raw)) {
            reader.setDefaultTimezone(TimeZone.getTimeZone(zone));
            ICalendar cal=reader.readNext();
            // 37 is the well-understood IANA TZID without a leading slash; nothing was lost.
            if(reader.getWarnings().stream().anyMatch(w->!Integer.valueOf(37).equals(w.getCode()))) throw invalid();
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
        if(days>366 || seconds>366L*86400 || (!e.getDateStart().getValue().hasTime() && duration.hasTime())) throw invalid();
        // RFC 5545: nominal calendar days first, then elapsed sub-day units.
        // Calendar retains embedded VTIMEZONE rules, not just IANA zone IDs.
        Calendar local=Calendar.getInstance(timeZone(cal,e.getDateStart(),display));
        local.setTime(Date.from(start));local.add(Calendar.DATE,(int)days);
        return local.toInstant().plusSeconds(seconds);
    }
    private static String protection(ICalendar cal,VEvent e) {
        if(cal.getEvents().size()!=1 || e.getRecurrenceId()!=null || !e.getExceptionDates().isEmpty() || !e.getRecurrenceDates().isEmpty() || !e.getProperties(ExceptionRule.class).isEmpty()) return "RECURRENCE_EXCEPTIONS";
        if(e.getOrganizer()!=null || !e.getAttendees().isEmpty() || cal.getMethod()!=null) return "MEETING";
        if(!cal.getTodos().isEmpty() || !cal.getJournals().isEmpty() || !cal.getFreeBusies().isEmpty() || !cal.getExperimentalComponents().isEmpty() || !e.getExperimentalComponents().isEmpty()) return "COMPLEX_COMPONENT";
        if(e.getProperties(RecurrenceRule.class).size()>1) return "COMPLEX_RECURRENCE";
        if(e.getRecurrenceRule()!=null && !simple(e.getRecurrenceRule().getValue())) return "COMPLEX_RECURRENCE";
        if(e.getAlarms().size()>1 || (!e.getAlarms().isEmpty() && !simpleAlarm(e.getAlarms().get(0)))) return "COMPLEX_REMINDER";
        if(e.getDateStart()==null) return "CANCELLED";
        TimezoneAssignment a=cal.getTimezoneInfo().getTimezone(e.getDateStart());
        if(a!=null && a.getComponent()!=null) return "CUSTOM_TIMEZONE";
        return "";
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
            d.start=d.allDay?rawDay(e.getDateStart().getValue()).atStartOfDay():start.atZone(ZoneId.of(d.zone)).toLocalDateTime();
            d.end=eventEnd(cal,e,start,zone).atZone(d.allDay?zone:ZoneId.of(d.zone)).toLocalDateTime();
        }
        d.repeat=e.getRecurrenceRule()==null?"NONE":e.getRecurrenceRule().getValue().getFrequency().name();
        d.reminderMinutes=reminder(e);
        return new Info(e.getUid().getValue(),d,protection(cal,e));
    }
    public static String save(String original, Draft d) {
        ZoneId zone=ZoneId.of(d.zone);
        if(d.title==null || d.title.trim().isEmpty() || d.title.length()>1000 || d.location.length()>4000 || d.notes.length()>100000 || !d.end.isAfter(d.start)
            || d.reminderMinutes < -1 || d.reminderMinutes>10080 || !Set.of("NONE","DAILY","WEEKLY","MONTHLY","YEARLY").contains(d.repeat)) throw invalid();
        ICalendar cal; VEvent e;
        if(original==null) {
            cal=new ICalendar(); cal.setProductId("-//Agenda//Calendar 0.1//EN"); e=new VEvent(); e.setUid(UUID.randomUUID()+"@agenda.local"); cal.addEvent(e);
        } else {
            cal=parse(original,zone); e=master(cal); if(!protection(cal,e).isEmpty()) throw new IllegalArgumentException("Protected resource: edit in the originating calendar client");
        }
        boolean wasFloating=original!=null && cal.getTimezoneInfo().isFloating(e.getDateStart());
        if(wasFloating && !d.floating && !d.allDay) throw new IllegalArgumentException("Floating semantics must be preserved");
        Draft old=original==null?null:inspect(original,zone).draft;
        boolean preserveTiming=old!=null && old.start.equals(d.start) && old.end.equals(d.end)
            && old.zone.equals(d.zone) && old.allDay==d.allDay && old.floating==d.floating;
        var startParameters=e.getDateStart()==null?null:new biweekly.parameter.ICalParameters(e.getDateStart().getParameters());
        var endParameters=e.getDateEnd()==null?null:new biweekly.parameter.ICalParameters(e.getDateEnd().getParameters());
        if(e.getSummary()==null) e.setSummary(d.title.trim()); else e.getSummary().setValue(d.title.trim());
        if(e.getLocation()==null) e.setLocation(d.location); else e.getLocation().setValue(d.location);
        if(e.getDescription()==null) e.setDescription(d.notes); else e.getDescription().setValue(d.notes);
        e.setDateTimeStamp(new Date()); e.setLastModified(new Date());
        e.setSequence(e.getSequence()==null ? 0 : e.getSequence().getValue()+1);
        if(!preserveTiming) {
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
            if(zone.getRules().getValidOffsets(d.start).isEmpty() || zone.getRules().getValidOffsets(d.end).isEmpty()) throw invalid();
            e.setDateStart(Date.from(d.start.atZone(zone).toInstant())); e.setDateEnd(Date.from(d.end.atZone(zone).toInstant()));
            var assignment=new TimezoneAssignment(TimeZone.getTimeZone(zone),zone.getId());
            cal.getTimezoneInfo().setTimezone(e.getDateStart(),assignment); cal.getTimezoneInfo().setTimezone(e.getDateEnd(),assignment);
        }
        if(startParameters!=null) { startParameters.setTimezoneId(null);e.getDateStart().setParameters(startParameters); }
        if(endParameters!=null) { endParameters.setTimezoneId(null);e.getDateEnd().setParameters(endParameters); }
        }
        if(d.repeat.equals("NONE")) e.removeProperties(RecurrenceRule.class);
        else if(e.getRecurrenceRule()==null) e.setRecurrenceRule(new Recurrence.Builder(Frequency.valueOf(d.repeat)).build());
        else e.getRecurrenceRule().setValue(new Recurrence.Builder(Frequency.valueOf(d.repeat)).build());
        if(d.reminderMinutes<0) e.removeComponents(VAlarm.class);
        else if(e.getAlarms().isEmpty()) e.addAlarm(VAlarm.display(new Trigger(biweekly.util.Duration.fromMillis(-d.reminderMinutes*60000L),Related.START),d.title));
        else if(reminder(e)!=d.reminderMinutes) {
            VAlarm a=e.getAlarms().get(0);Trigger trigger=new Trigger(biweekly.util.Duration.fromMillis(-d.reminderMinutes*60000L),Related.START);
            trigger.setParameters(new biweekly.parameter.ICalParameters(a.getTrigger().getParameters()));a.setTrigger(trigger);
        }
        return cal.write();
    }
    private static ICalDate floatingDate(LocalDateTime value) {
        return new ICalDate(new biweekly.util.DateTimeComponents(value.getYear(),value.getMonthValue(),value.getDayOfMonth(),value.getHour(),value.getMinute(),value.getSecond(),false),true);
    }
    private static boolean cancelled(VEvent e) { return e.getStatus()!=null && e.getStatus().isCancelled(); }
    public static List<Occurrence> expand(String raw, LocalDate from, LocalDate until, ZoneId display) {
        if(!until.isAfter(from) || ChronoUnit.DAYS.between(from,until)>370) throw new IllegalArgumentException("Expansion window must be 1–370 days");
        ICalendar cal=parse(raw,display); VEvent main=master(cal);
        Instant lo=from.atStartOfDay(display).toInstant(), hi=until.atStartOfDay(display).toInstant();
        List<Occurrence> result=new ArrayList<>(); Map<Instant,VEvent> overrides=new HashMap<>();
        for(VEvent e:cal.getEvents()) {
            if(e.getRecurrenceId()!=null) {
                if(e.getRecurrenceId().getRange()!=null) throw new IllegalArgumentException("RANGE recurrence is not supported");
                Instant id=instant(e.getRecurrenceId(),display); overrides.put(id,e);
                if(!cancelled(e)) add(result,e,instant(e.getDateStart(),display),eventEnd(cal,e,instant(e.getDateStart(),display),display),id.toString(),lo,hi);
            }
        }
        if(main.getRecurrenceId()==null && !cancelled(main)) {
            for(RecurrenceRule rule: main.getProperties(RecurrenceRule.class)) {
                Recurrence r=rule.getValue();
                if(!Set.of(Frequency.DAILY,Frequency.WEEKLY,Frequency.MONTHLY,Frequency.YEARLY).contains(r.getFrequency()) || !r.getXRules().isEmpty()) throw new IllegalArgumentException("High-frequency recurrence is not supported");
                if(r.getByHour().size()>24 || r.getByMinute().size()>1 || r.getBySecond().size()>1) throw new IllegalArgumentException("Recurrence too dense");
            }
            if(!main.getProperties(ExceptionRule.class).isEmpty() || main.getRecurrenceDates().stream().anyMatch(r->!r.getPeriods().isEmpty())) throw new IllegalArgumentException("Complex recurrence dates are not supported");
            Instant start=instant(main.getDateStart(),display), end=eventEnd(cal,main,start,display);
            long duration=java.time.Duration.between(start,end).toMillis();
            if(duration<0 || duration>366L*86400000) throw invalid();
            boolean allDay=!main.getDateStart().getValue().hasTime();
            long days=allDay?ChronoUnit.DAYS.between(start.atZone(display).toLocalDate(),end.atZone(display).toLocalDate()):0;
            var iterator=main.getDateIterator(timeZone(cal,main.getDateStart(),display));
            // Iteration has a hard work budget, including historical instances. Fail visibly, never hang.
            int steps=0;
            while(iterator.hasNext()) {
                if(++steps>20000) throw new IllegalArgumentException("Recurrence expansion limit exceeded");
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
            if(out.size()>=MAX_OCCURRENCES) throw new IllegalArgumentException("Too many occurrences");
            out.add(new Occurrence(e,start,end,key));
        }
    }
    /** Conservative lost-create-response reconciliation: no subset/UID-only comparisons. */
    public static boolean equivalent(String a,String b) {
        return parse(a,ZoneOffset.UTC).write().equals(parse(b,ZoneOffset.UTC).write());
    }
}
