package fr.nauss.calendar.core;

import biweekly.ICalDataType;
import biweekly.io.ParseContext;
import biweekly.io.WriteContext;
import biweekly.io.scribe.property.TimezoneOffsetFromScribe;
import biweekly.io.scribe.property.TimezoneOffsetToScribe;
import biweekly.parameter.ICalParameters;
import biweekly.property.TimezoneOffsetFrom;
import biweekly.property.TimezoneOffsetTo;
import biweekly.util.UtcOffset;
import java.util.Locale;

/** Property scribes only: biweekly still owns ICS unfolding, components and timezone rules. */
final class SecondPrecisionOffsets {
    private SecondPrecisionOffsets() { }
    private static UtcOffset read(String value) {
        if(value==null || !value.matches("[+-](?:[01][0-9]|2[0-3])[0-5][0-9](?:[0-5][0-9])?") || value.matches("-0000(?:00)?"))
            throw new IcsCodec.UnsupportedException("OFFSET_PRECISION");
        // Keep the library's minute parser; restore the optional seconds it otherwise drops.
        long millis=UtcOffset.parse(value.substring(0,5)).getMillis();
        long seconds=value.length()==7?Long.parseLong(value.substring(5)):0;
        return new UtcOffset(millis+(value.charAt(0)=='-'?-1:1)*seconds*1000);
    }
    private static String write(UtcOffset value) {
        if(value==null) return "";
        long millis=value.getMillis();
        if(millis<=-86400000L || millis>=86400000L || millis%1000!=0) throw new IcsCodec.UnsupportedException("OFFSET_PRECISION");
        long total=Math.abs(millis)/1000,seconds=total%60;
        return String.format(Locale.ROOT,"%s%02d%02d",millis<0?"-":"+",total/3600,total/60%60)
            +(seconds==0?"":String.format(Locale.ROOT,"%02d",seconds));
    }
    static TimezoneOffsetFromScribe from() {
        return new TimezoneOffsetFromScribe() {
            @Override protected TimezoneOffsetFrom _parseText(String value,ICalDataType type,ICalParameters parameters,ParseContext context) { return new TimezoneOffsetFrom(read(value)); }
            @Override protected String _writeText(TimezoneOffsetFrom property,WriteContext context) { return write(property.getValue()); }
        };
    }
    static TimezoneOffsetToScribe to() {
        return new TimezoneOffsetToScribe() {
            @Override protected TimezoneOffsetTo _parseText(String value,ICalDataType type,ICalParameters parameters,ParseContext context) { return new TimezoneOffsetTo(read(value)); }
            @Override protected String _writeText(TimezoneOffsetTo property,WriteContext context) { return write(property.getValue()); }
        };
    }
}
