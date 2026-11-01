package pt.remotephotobridge;

/** Pure selection logic for an explicit, persisted appearance preference. */
final class ThemeMode {
    static final String DARK = "dark";
    static final String LIGHT = "light";
    static final String SYSTEM = "system";
    private ThemeMode() { }

    static String normalize(String value) {
        if (DARK.equals(value) || LIGHT.equals(value) || SYSTEM.equals(value)) return value;
        // Existing installs have always used dark mode. Keep that initial look
        // until the user explicitly chooses another appearance.
        return DARK;
    }

    static boolean useDark(String value, boolean androidDark) {
        switch (normalize(value)) {
            case LIGHT: return false;
            case SYSTEM: return androidDark;
            default: return true;
        }
    }

    static String label(String value) {
        switch (normalize(value)) {
            case LIGHT: return "Claro";
            case SYSTEM: return "Automático (seguir o Android)";
            default: return "Escuro";
        }
    }
}
