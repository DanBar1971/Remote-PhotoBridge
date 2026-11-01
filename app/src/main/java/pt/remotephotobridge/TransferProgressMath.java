package pt.remotephotobridge;

/** Deterministic, byte-weighted progress; 100% only after final acknowledgement. */
final class TransferProgressMath {
    private TransferProgressMath() {}

    static int percentage(long acknowledgedBytes, long totalBytes,
                          long inFlightBytes, int completed, int total,
                          boolean trulyFinished) {
        if (trulyFinished) return 100;
        double ratio;
        if (totalBytes > 0) {
            ratio = ((double) Math.max(0L, acknowledgedBytes)
                    + (double) Math.max(0L, inFlightBytes)) / totalBytes;
        } else if (total > 0) {
            ratio = (double) Math.max(0, completed) / total;
        } else {
            ratio = 0;
        }
        if (Double.isNaN(ratio)) ratio = 0;
        return Math.max(0, Math.min(99, (int) Math.floor(ratio * 100)));
    }
}
