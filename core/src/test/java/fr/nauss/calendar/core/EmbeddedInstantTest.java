package fr.nauss.calendar.core;

import biweekly.io.TimezoneAssignment;
import biweekly.util.UtcOffset;
import java.time.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class EmbeddedInstantTest {
    private static TimezoneAssignment assignment(int raw,int daylight) {
        // Exercise the candidate enumeration independently of biweekly's text parser,
        // which truncates the optional seconds in a UTC-OFFSET in version 0.6.8.
        var component=ImportedEditingTest.parsed(ImportedEditingTest.paris().replace("BYMONTH=10;BYDAY=-1SU","BYMONTH=10;BYDAY=3SU")).getTimezoneInfo().getComponents().iterator().next();
        var standard=component.getStandardTimes().get(0);
        standard.setTimezoneOffsetFrom(new UtcOffset((long)raw+daylight));standard.setTimezoneOffsetTo(new UtcOffset(raw));
        var summer=component.getDaylightSavingsTime().get(0);
        summer.setTimezoneOffsetFrom(new UtcOffset(raw));summer.setTimezoneOffsetTo(new UtcOffset((long)raw+daylight));
        var zone=new SimpleTimeZone(raw,"Synthetic/Seconds",Calendar.MARCH,-1,Calendar.SUNDAY,7200000,
            Calendar.OCTOBER,3,Calendar.SUNDAY,10800000,daylight);
        return new TimezoneAssignment(zone,component);
    }
    @Test public void arbitrarySecondOffsetsAndNonHourOverlapAreExact() {
        for(int raw:new int[]{561000,-12645000,3600000}) {
            var assignment=assignment(raw,1815000);
            var before=LocalDateTime.parse("2026-10-18T02:29:44");
            assertEquals(before.toInstant(ZoneOffset.UTC).minusMillis((long)raw+1815000),IcsCodec.uniqueAssignedInstant(assignment,before));
            assertThrows(IllegalArgumentException.class,()->IcsCodec.uniqueAssignedInstant(assignment,LocalDateTime.parse("2026-10-18T02:29:45")));
            assertThrows(IllegalArgumentException.class,()->IcsCodec.uniqueAssignedInstant(assignment,LocalDateTime.parse("2026-10-18T02:59:59")));
            var after=LocalDateTime.parse("2026-10-18T03:00:00");
            assertEquals(after.toInstant(ZoneOffset.UTC).minusMillis(raw),IcsCodec.uniqueAssignedInstant(assignment,after));
        }
    }
    @Test public void nonHourGapIsRejectedWithoutSampling() {
        var assignment=assignment(561000,1815000);
        assertThrows(IllegalArgumentException.class,()->IcsCodec.uniqueAssignedInstant(assignment,LocalDateTime.parse("2026-03-29T02:30:14")));
        var after=LocalDateTime.parse("2026-03-29T02:30:15");
        assertEquals(after.toInstant(ZoneOffset.UTC).minusMillis(2376000),IcsCodec.uniqueAssignedInstant(assignment,after));
    }
    @Test public void incompleteObservancesCannotEstablishUniqueness() {
        var assignment=assignment(3600000,3600000);
        assignment.getComponent().getStandardTimes().get(0).removeProperties(biweekly.property.TimezoneOffsetFrom.class);
        assertThrows(IllegalArgumentException.class,()->IcsCodec.uniqueAssignedInstant(assignment,LocalDateTime.parse("2026-11-17T09:00")));
    }
}
