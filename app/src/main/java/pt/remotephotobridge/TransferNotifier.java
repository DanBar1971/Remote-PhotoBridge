package pt.remotephotobridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** User-visible progress and final status, without needing mobile data. */
final class TransferNotifier {
    private static final String CHANNEL_ID = "photobridge_transfers";
    private static final int NOTIFICATION_ID = 4102;
    private final Context context;
    private final NotificationManager manager;
    private int lastPercent = -1;
    private int lastDone = -1;
    private boolean active;

    TransferNotifier(Context context) {
        this.context = context.getApplicationContext();
        manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Transferência de fotografias", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Progresso e conclusão de envio ou receção pela Remote PhotoBridge");
            manager.createNotificationChannel(channel);
        }
    }

    private boolean allowed() {
        return manager != null && (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED);
    }

    private Notification.Builder builder() {
        Intent launch = new Intent(context, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, 4102, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(tap)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
    }

    void progress(boolean sending, int percent, int completed, int total) {
        if (!allowed()) return;
        if (lastPercent == percent && lastDone == completed && active) return;
        lastPercent = percent;
        lastDone = completed;
        active = true;
        String title = sending ? "A enviar fotografias" : "A receber fotografias";
        String detail = completed + "/" + total + " fotografias · " + percent + "%";
        Notification note = builder()
                .setContentTitle(title).setContentText(detail)
                .setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(100, percent, false).build();
        manager.notify(NOTIFICATION_ID, note);
    }

    void finished(boolean sending, int newPhotos, int duplicates, int total) {
        if (!allowed()) return;
        active = false;
        lastPercent = 100;
        lastDone = total;
        String title = sending ? "Fotografias enviadas!" : "Fotografias recebidas!";
        String detail = sending
                ? "Concluído: " + total + " fotografias (" + newPhotos +
                    " novas; " + duplicates + " já existentes)."
                : "Na Galeria: " + newPhotos + " novas; " +
                    duplicates + " já existentes.";
        Notification note = builder()
                .setContentTitle(title)
                .setContentText(detail)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(false)
                .setAutoCancel(true)
                .setProgress(0, 0, false).build();
        manager.notify(NOTIFICATION_ID, note);
    }

    void failed(String reason) {
        if (!allowed()) return;
        active = false;
        lastPercent = -1;
        lastDone = -1;
        String message = reason == null ? "Confirma a ligação nos dois telemóveis." : reason;
        if (message.length() > 110) message = message.substring(0, 110);
        Notification note = builder()
                .setContentTitle("Transferência interrompida")
                .setContentText(message)
                .setCategory(Notification.CATEGORY_ERROR)
                .setOngoing(false)
                .setAutoCancel(true)
                .setProgress(0, 0, false).build();
        manager.notify(NOTIFICATION_ID, note);
    }
}
