package fr.nauss.calendar.core;

import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

public class RawOffsetPreservationTest {
    @Test public void foldedUnsupportedOffsetIsRejectedByThePropertyParser() {
        String raw=ImportedEditingTest.paris().replace("+0100","+0100\r\n 21");
        assertThrows(IllegalArgumentException.class,()->IcsCodec.inspect(raw,ZoneId.of("Europe/Paris")));
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
    @Test public void unsupportedOffsetSecondsCannotBeSilentlyLostOnTextEdit() {
        String raw=ImportedEditingTest.paris().replace("+0100","+010021");
        try {
            var draft=IcsCodec.inspect(raw,ZoneId.of("Europe/Paris")).draft;
            draft.title="Metadata edit must preserve the original timezone";
            String saved=IcsCodec.save(raw,draft);
            assertTrue("Retained UTC offset seconds must not be truncated",saved.contains("TZOFFSETTO:+010021"));
            assertTrue("Retained FROM offset seconds must not be truncated",saved.contains("TZOFFSETFROM:+010021"));
        } catch(IllegalArgumentException safeRejection) {
            // A parser limitation may be protected, but must never corrupt a write.
        }
    }
}
