package pt.remotephotobridge;

import java.util.Calendar;

/** Local-calendar date ranges, or an explicitly chosen entire gallery (zero). */
final class PhotoDateRange {
    /** Explicit user-selected historical gallery, not a default value. */
    static final int ALL_GALLERY = 0;
    private PhotoDateRange() { }
    static boolean isEntireGallery(int days) { return days == ALL_GALLERY; }
    static boolean supported(int days) {
        return days == ALL_GALLERY || days == 1 || days == 3 ||
                days == 7 || days == 14 || days == 30;
    }
    static long startMillis(int days, Calendar now) {
        if (!supported(days)) throw new IllegalArgumentException("Período inválido.");
        if (isEntireGallery(days)) return Long.MIN_VALUE;
        Calendar start = (Calendar) now.clone();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        start.add(Calendar.DAY_OF_YEAR, -(days - 1));
        return start.getTimeInMillis();
    }
    static boolean within(long capturedOrAddedMillis, long earliest, long latest) {
        return capturedOrAddedMillis >= earliest && capturedOrAddedMillis <= latest;
    }
}
