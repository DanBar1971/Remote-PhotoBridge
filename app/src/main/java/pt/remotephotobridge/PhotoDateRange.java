package pt.remotephotobridge;

import java.util.Calendar;

/** Date windows are local-calendar dates, including today; never all photos. */
final class PhotoDateRange {
    private PhotoDateRange() { }
    static boolean supported(int days) {
        return days == 1 || days == 3 || days == 7 || days == 14 || days == 30;
    }
    static long startMillis(int days, Calendar now) {
        if (!supported(days)) throw new IllegalArgumentException("Período inválido.");
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
