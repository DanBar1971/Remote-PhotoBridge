package pt.remotephotobridge;

/** Prefer radio stability by default. Fast mode is an explicit user choice. */
final class TransferNetworkMode {
    static final String STABLE = "stable";
    static final String FAST = "fast";
    private TransferNetworkMode() {}
    static String normalize(String value) {
        return FAST.equals(value) ? FAST : STABLE;
    }
    static boolean allowDisruptiveUpgrade(String value) {
        return FAST.equals(normalize(value));
    }
    static String label(String value) {
        return allowDisruptiveUpgrade(value)
                ? "Velocidade máxima (pode desligar do router)"
                : "Estável (preservar ligação Wi-Fi)";
    }
}
