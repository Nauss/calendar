package fr.nauss.calendar.core;

import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

public class RawOffsetPreservationTest {
    @Test public void foldedSecondOffsetIsParsedAndPreservedByThePropertyParser() {
        String raw=ImportedEditingTest.paris().replace("+0100","+0100\r\n 21");
        var info=IcsCodec.inspect(raw,ZoneId.of("Europe/Paris"));
        info.draft.title="Folded seconds";
        String saved=IcsCodec.save(raw,info.draft);
        assertTrue(saved.contains("TZOFFSETFROM:+010021"));assertTrue(saved.contains("TZOFFSETTO:+010021"));
        assertEquals(java.time.Instant.parse("2026-11-17T07:59:39Z"),IcsCodec.expand(saved,java.time.LocalDate.of(2026,11,17),java.time.LocalDate.of(2026,11,18),java.time.ZoneOffset.UTC).get(0).start);
    }
    @Test public void explicitZeroSecondsRemainLosslessAndEditable() {
        String raw=ImportedEditingTest.paris().replace("+0100","+010000").replace("+0200","+020000");
        var info=IcsCodec.inspect(raw,ZoneId.of("Europe/Paris"));
        assertTrue(info.timeEditable);
        info.draft.title="Safe zero seconds";
        String saved=IcsCodec.save(raw,info.draft);
        assertEquals(info.draft.start,IcsCodec.inspect(saved,ZoneId.of("Europe/Paris")).draft.start);
        assertEquals("Safe zero seconds",IcsCodec.inspect(saved,ZoneId.of("Europe/Paris")).draft.title);
    }
    @Test public void offsetSecondsCannotBeSilentlyLostOnTextEdit() {
        String raw=ImportedEditingTest.paris().replace("+0100","+010021");
        var draft=IcsCodec.inspect(raw,ZoneId.of("Europe/Paris")).draft;
        draft.title="Metadata edit must preserve the original timezone";
        String saved=IcsCodec.save(raw,draft);
        assertTrue("Retained UTC offset seconds must not be truncated",saved.contains("TZOFFSETTO:+010021"));
        assertTrue("Retained FROM offset seconds must not be truncated",saved.contains("TZOFFSETFROM:+010021"));
    }
}
