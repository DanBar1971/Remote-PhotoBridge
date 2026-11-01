package pt.remotephotobridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateCheckerTest {
    @Test public void detectsNewMinorVersion() {
        assertTrue(UpdateChecker.compare("1.0.0", "1.0.1") < 0);
    }
    @Test public void detectsNewMajorVersion() {
        assertTrue(UpdateChecker.compare("1.9.99", "2.0.0") < 0);
    }
    @Test public void equalOrOlderIsNotNew() {
        assertEquals(0, UpdateChecker.compare("1.0.1", "v1.0.1"));
        assertTrue(UpdateChecker.compare("1.0.2", "v1.0.1") > 0);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsMaliciousVersionText() {
        UpdateChecker.compare("1.0.0", "1.0.2-beta");
    }
}
