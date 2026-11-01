package pt.remotephotobridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransferNetworkModeTest {
    @Test public void existingUsersGetStableRadio() {
        assertEquals("stable", TransferNetworkMode.normalize(null));
        assertFalse(TransferNetworkMode.allowDisruptiveUpgrade("unexpected"));
    }
    @Test public void fastNeedsExplicitChoice() {
        assertTrue(TransferNetworkMode.allowDisruptiveUpgrade("fast"));
        assertFalse(TransferNetworkMode.allowDisruptiveUpgrade("stable"));
    }
}
