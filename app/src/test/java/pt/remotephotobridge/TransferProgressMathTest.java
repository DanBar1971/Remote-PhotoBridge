package pt.remotephotobridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransferProgressMathTest {
    @Test public void startsAtZero() {
        assertEquals(0, TransferProgressMath.percentage(0, 200, 0, 0, 2, false));
    }
    @Test public void followsActualTransferBytes() {
        assertEquals(75, TransferProgressMath.percentage(100, 200, 50, 1, 2, false));
    }
    @Test public void neverShowsCompleteBeforeFinalConfirmation() {
        assertEquals(99, TransferProgressMath.percentage(200, 200, 0, 2, 2, false));
        assertEquals(100, TransferProgressMath.percentage(200, 200, 0, 2, 2, true));
    }
    @Test public void handlesDuplicateOnlyBatch() {
        assertEquals(99, TransferProgressMath.percentage(500, 500, 0, 3, 3, false));
        assertEquals(100, TransferProgressMath.percentage(500, 500, 0, 3, 3, true));
    }
    @Test public void clampsInvalidAndOverreportedValues() {
        assertEquals(0, TransferProgressMath.percentage(-3, 100, -12, 0, 1, false));
        assertEquals(99, TransferProgressMath.percentage(500, 100, 200, 3, 1, false));
    }
}
