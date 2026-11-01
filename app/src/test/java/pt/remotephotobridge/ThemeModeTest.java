package pt.remotephotobridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThemeModeTest {
    @Test public void explicitLightAlwaysWins() {
        assertFalse(ThemeMode.useDark("light", true));
        assertFalse(ThemeMode.useDark("light", false));
    }
    @Test public void explicitDarkAlwaysWins() {
        assertTrue(ThemeMode.useDark("dark", true));
        assertTrue(ThemeMode.useDark("dark", false));
    }
    @Test public void systemFollowsAndroid() {
        assertTrue(ThemeMode.useDark("system", true));
        assertFalse(ThemeMode.useDark("system", false));
    }
    @Test public void existingInstallsStayDark() {
        assertEquals("dark", ThemeMode.normalize(null));
        assertEquals("dark", ThemeMode.normalize("unexpected"));
        assertTrue(ThemeMode.useDark(null, false));
    }
}
