package pt.remotephotobridge;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Keep the CPU available only while actively sending/receiving, not while
 * idle or waiting for a peer. Does not force the Wi-Fi radio ON and does not
 * interfere with Nearby's choice of transport.
 */
final class TransferPowerGuard {
    private static final long HOLD_MS = 12 * 60 * 1000L;
    private static final long RENEW_MS = 8 * 60 * 1000L;
    private final PowerManager.WakeLock lock;
    private long nextRefresh;
    private boolean active;

    TransferPowerGuard(Context context) {
        PowerManager manager = (PowerManager)
                context.getSystemService(Context.POWER_SERVICE);
        lock = manager == null ? null : manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "RemotePhotoBridge:FileTransfer");
        if (lock != null) lock.setReferenceCounted(false);
    }

    synchronized void start() {
        active = true;
        refresh();
    }

    synchronized void pulse() {
        if (active && SystemClock.elapsedRealtime() >= nextRefresh) refresh();
    }

    private void refresh() {
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
            lock.acquire(HOLD_MS);
            nextRefresh = SystemClock.elapsedRealtime() + RENEW_MS;
        } catch (RuntimeException ignored) {
            // The transfer must never fail just because a manufacturer
            // disallows a wake lock.
        }
    }

    synchronized void stop() {
        active = false;
        nextRefresh = 0;
        if (lock != null && lock.isHeld()) {
            try { lock.release(); } catch (RuntimeException ignored) { }
        }
    }
}
