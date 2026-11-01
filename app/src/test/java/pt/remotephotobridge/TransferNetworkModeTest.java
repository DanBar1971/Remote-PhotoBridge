package pt.remotephotobridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransferNetworkModeTest {
    @Test public void previousPreferenceStaysStable() {
        assertEquals("stable", TransferNetworkMode.normalize(null));
        assertEquals("stable", TransferNetworkMode.normalize("unexpected"));
        assertFalse(TransferNetworkMode.allowDisruptiveUpgrade("stable"));
    }
    @Test public void balancedCanUseExistingWifiWithoutForcingFullSpeedPolicy() {
        assertEquals("balanced", TransferNetworkMode.normalize("balanced"));
        assertFalse(TransferNetworkMode.allowDisruptiveUpgrade("balanced"));
        assertFalse(TransferNetworkMode.potentiallySlowForLargeVideos("balanced"));
    }
    @Test public void fastIsOptIn() {
        assertEquals("fast", TransferNetworkMode.normalize("fast"));
        assertTrue(TransferNetworkMode.allowDisruptiveUpgrade("fast"));
        assertFalse(TransferNetworkMode.potentiallySlowForLargeVideos("fast"));
    }
    @Test public void onlyStableTriggersSlowModeWarning() {
        assertTrue(TransferNetworkMode.potentiallySlowForLargeVideos(null));
    }
}
