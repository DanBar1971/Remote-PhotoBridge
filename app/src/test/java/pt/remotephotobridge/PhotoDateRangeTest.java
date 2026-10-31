package pt.remotephotobridge;

import org.junit.Test;
import java.util.Calendar;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class PhotoDateRangeTest {
    private Calendar date(int year, int month, int day, int hour) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Lisbon"));
        c.clear();
        c.set(year, month - 1, day, hour, 0, 0);
        return c;
    }
    @Test public void todayBeginsAtLocalMidnight() {
        Calendar now = date(2026, 10, 31, 18);
        Calendar midnight = date(2026, 10, 31, 0);
        assertEquals(midnight.getTimeInMillis(), PhotoDateRange.startMillis(1, now));
    }
    @Test public void lastThreeIncludesTodayAndPreviousTwoDays() {
        Calendar now = date(2026, 10, 31, 18);
        assertEquals(date(2026, 10, 29, 0).getTimeInMillis(),
                PhotoDateRange.startMillis(3, now));
        assertTrue(PhotoDateRange.within(date(2026, 10, 29, 0).getTimeInMillis(),
                PhotoDateRange.startMillis(3, now), now.getTimeInMillis()));
        assertFalse(PhotoDateRange.within(date(2026, 10, 28, 23).getTimeInMillis(),
                PhotoDateRange.startMillis(3, now), now.getTimeInMillis()));
    }
    @Test public void acrossMonthAndDstBoundary() {
        Calendar now = date(2026, 11, 2, 11);
        assertEquals(date(2026, 10, 27, 0).getTimeInMillis(),
                PhotoDateRange.startMillis(7, now));
    }
    @Test public void neverAllowAllGallery() {
        assertFalse(PhotoDateRange.supported(0));
        assertFalse(PhotoDateRange.supported(Integer.MAX_VALUE));
        try { PhotoDateRange.startMillis(0, date(2026, 10, 31, 10)); fail("should reject"); }
        catch (IllegalArgumentException expected) { }
    }
}
