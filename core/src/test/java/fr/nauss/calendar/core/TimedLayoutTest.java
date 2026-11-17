package fr.nauss.calendar.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TimedLayoutTest {
    @Test public void adjacentShortEventsKeepRealIntervalsAndZeroLengthGetsOnlyOneMinute() {
        var out=TimedLayout.arrange(List.of(new TimedLayout.Span("a",570,600),new TimedLayout.Span("b",600,615),new TimedLayout.Span("c",615,615)));
        for(var placement:out) assertEquals(1,placement.columns);
        assertEquals(600,out.get(0).span.end);assertEquals(615,out.get(1).span.end);assertEquals(616,out.get(2).span.end);
    }
    @Test public void overlapGroupsShareColumnsAndTouchingEventsDoNotOverlap() {
        var result=TimedLayout.arrange(List.of(new TimedLayout.Span("a",540,600),new TimedLayout.Span("b",570,630),new TimedLayout.Span("c",630,660)));
        assertEquals(2,result.get(0).columns); assertEquals(2,result.get(1).columns); assertNotEquals(result.get(0).column,result.get(1).column);
        assertEquals(1,result.get(2).columns);
    }
    @Test public void transitiveOverlapUsesWholeGroupWidth() {
        var result=TimedLayout.arrange(List.of(new TimedLayout.Span("a",0,100),new TimedLayout.Span("b",30,40),new TimedLayout.Span("c",40,110),new TimedLayout.Span("d",80,120)));
        for(var p:result) assertEquals(3,p.columns);
    }
}
