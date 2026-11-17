package fr.nauss.calendar.core;

import java.util.*;

/** Stable greedy interval partitioning; each connected overlap group shares its maximum column count. */
public final class TimedLayout {
    private TimedLayout() {}
    public static final class Span {
        public final String id; public final int start,end;
        public Span(String id,int start,int end) { this.id=id;this.start=start;this.end=Math.max(start+1,end); }
    }
    public static final class Placement {
        public final Span span; public final int column,columns;
        Placement(Span span,int column,int columns) { this.span=span;this.column=column;this.columns=columns; }
    }
    public static List<Placement> arrange(List<Span> input) {
        List<Span> sorted=new ArrayList<>(input); sorted.sort(Comparator.comparingInt((Span s)->s.start).thenComparingInt(s->s.end).thenComparing(s->s.id));
        List<Placement> result=new ArrayList<>(); List<Span> group=new ArrayList<>(); int end=-1;
        for(Span span:sorted) {
            if(!group.isEmpty() && span.start>=end) { layout(group,result);group.clear();end=-1; }
            group.add(span);end=Math.max(end,span.end);
        }
        layout(group,result);return result;
    }
    private static void layout(List<Span> group,List<Placement> out) {
        List<Integer> ends=new ArrayList<>(),columns=new ArrayList<>();
        for(Span span:group) {
            int column=0;while(column<ends.size() && ends.get(column)>span.start) column++;
            if(column==ends.size()) ends.add(span.end); else ends.set(column,span.end);
            columns.add(column);
        }
        for(int i=0;i<group.size();i++) out.add(new Placement(group.get(i),columns.get(i),ends.size()));
    }
}
