package pt.remotephotobridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransferSizesTest {
    @Test public void formatMegabytesAndGigabytes() {
        assertEquals("19.0 MB", TransferSizes.format(19_000_000L));
        assertEquals("1.50 GB", TransferSizes.format(1_500_000_000L));
        assertEquals("200.0 KB", TransferSizes.format(200_000L));
    }
    @Test public void bytesAccountForCurrentFileAndRemaining() {
        assertEquals("Lote: 650.0 MB de 2.00 GB · faltam 1.35 GB",
                TransferSizes.batch(300_000_000L, 350_000_000L, 2_000_000_000L));
        assertEquals("Ficheiro atual: 150.0 MB de 500.0 MB · faltam 350.0 MB",
                TransferSizes.current(150_000_000L, 500_000_000L));
    }
    @Test public void duplicatesAreProcessedButNeverCountedTwice() {
        assertEquals(300L, TransferSizes.processed(200, 100, 300));
        assertEquals(300L, TransferSizes.processed(300, 100, 300));
        assertEquals("Lote: 300 B de 300 B · faltam 0 B",
                TransferSizes.batch(300, 100, 300));
    }
    @Test public void noNegativeValues() {
        assertEquals("0 B", TransferSizes.format(-10));
        assertEquals(0, TransferSizes.processed(-20, -10, 5));
        assertEquals("Ficheiro atual: 0 B de 0 B · faltam 0 B",
                TransferSizes.current(40, -1));
    }
}
