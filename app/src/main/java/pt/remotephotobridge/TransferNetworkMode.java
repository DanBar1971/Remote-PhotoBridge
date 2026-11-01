package pt.remotephotobridge;

/** Nearby radio policies; never switch an active connection without consent. */
final class TransferNetworkMode {
    static final String STABLE = "stable";
    static final String BALANCED = "balanced";
    static final String FAST = "fast";
    private TransferNetworkMode() {}

    static String normalize(String value) {
        if (FAST.equals(value) || BALANCED.equals(value)) return value;
        // Existing users retain the stable policy unless they choose otherwise.
        return STABLE;
    }
    static String label(String value) {
        switch (normalize(value)) {
            case FAST: return "Velocidade máxima (pode desligar do router)";
            case BALANCED: return "Equilibrado (Wi-Fi rápido quando possível)";
            default: return "Estável (preservar ligação Wi-Fi)";
        }
    }
    static boolean allowDisruptiveUpgrade(String value) {
        return FAST.equals(normalize(value));
    }
    static boolean potentiallySlowForLargeVideos(String value) {
        return STABLE.equals(normalize(value));
    }
}
