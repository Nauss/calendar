package fr.nauss.calendar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarContrastTest {
    private fun contrast(background: Color, foreground: Color): Float {
        val a=background.luminance();val b=foreground.luminance()
        return (maxOf(a,b)+0.05f)/(minOf(a,b)+0.05f)
    }
    @Test fun mediumLuminanceCalendarColorsHaveReadableText() {
        listOf(Color(0xFF42A5F5),Color(0xFFFF80AB),Color(0xFF66BB6A),Color(0xFF3F6CB5)).forEach { background ->
            assertTrue("Insufficient event text contrast on $background",contrast(background,calendarEventTextColor(background))>=4.5f)
        }
    }
    @Test fun grayscaleAndRgbSamplesKeepNormalTextContrast() {
        for(gray in 0..255) {
            val background=Color(gray,gray,gray)
            assertTrue("Gray $gray",contrast(background,calendarEventTextColor(background))>=4.5f)
        }
        for(red in 0..255 step 17) for(green in 0..255 step 17) for(blue in 0..255 step 17) {
            val background=Color(red,green,blue)
            assertTrue("RGB $red,$green,$blue",contrast(background,calendarEventTextColor(background))>=4.5f)
        }
    }
}
