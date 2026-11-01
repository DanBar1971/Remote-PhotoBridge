package pt.remotephotobridge;

import java.util.Locale;

/** User-readable decimal MB/GB and safe accounting of the selected media batch. */
final class TransferSizes {
    static final long MB = 1_000_000L;
    static final long GB = 1_000_000_000L;
    private TransferSizes() {}

    static String format(long bytes) {
        long value = Math.max(0L, bytes);
        if (value >= GB) return String.format(Locale.ROOT, "%.2f GB", value / (double) GB);
        if (value >= MB) return String.format(Locale.ROOT, "%.1f MB", value / (double) MB);
        if (value >= 1_000) return String.format(Locale.ROOT, "%.1f KB", value / 1_000.0);
        return value + " B";
    }

    /** Already acknowledged items count too, even when skipped as duplicates. */
    static long processed(long confirmedBytes, long inFlightBytes, long totalBytes) {
        long limit = Math.max(0L, totalBytes);
        long confirmed = Math.min(limit, Math.max(0L, confirmedBytes));
        long inflight = Math.min(limit - confirmed, Math.max(0L, inFlightBytes));
        return confirmed + inflight;
    }

    static String batch(long confirmed, long inFlight, long total) {
        long all = Math.max(0L, total);
        long processed = processed(confirmed, inFlight, all);
        return "Lote: " + format(processed) + " de " + format(all) +
                " · faltam " + format(all - processed);
    }

    static String current(long transferred, long total) {
        long all = Math.max(0L, total);
        long sent = Math.min(all, Math.max(0L, transferred));
        return "Ficheiro atual: " + format(sent) + " de " + format(all) +
                " · faltam " + format(all - sent);
    }
}
