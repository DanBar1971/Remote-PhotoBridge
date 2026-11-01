package pt.remotephotobridge;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.net.wifi.WifiManager;
import android.os.ParcelFileDescriptor;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionOptions;
import com.google.android.gms.nearby.connection.ConnectionType;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Same Google Nearby Connections transport as SaúdePrática, with a distinct
 * service ID so medical apps and televisions are NEVER discovery candidates.
 * Device roles, not radio ownership, determine data direction:
 * Principal: only receives image FILE payloads and persists to MediaStore.
 * Secundário: only lists/reads its gallery and sends selected photos.
 * No cloud, internet, SIM card or Google account is used in the transfer.
 * Google Play services must be available on both handsets.
 */
final class NearbyPhotoLink {
    interface Listener {
        void state(String message);
        void peers(Map<String, String> endpoints);
        void authenticate(String id, String name, String digits);
        void connected();
        void disconnected();
        /** Called only after an original photo is verified and inserted in Gallery. */
        void photoReceived(Uri galleryUri);
        /** Byte-weighted progress, updated while Nearby transmits actual bytes. */
        void transferProgress(boolean sending, int percent, int completed, int total, String description);
        /** Fired only after all sender acknowledgements / receiver import checks. */
        void transferFinished(boolean sending, int newPhotos, int duplicates, int total);
        void transferInterrupted(boolean sending, String message);
    }

    private static final String SERVICE = "pt.remotephotobridge.nearby.photos.v1";
    private static final Strategy STRATEGY = Strategy.P2P_POINT_TO_POINT;
    private static final long MAX_MEDIA_BYTES = 12L * 1024 * 1024 * 1024;
    private static final String PREFS = "photobridge_options";

    private int connectionType() {
        String chosen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("network_mode", TransferNetworkMode.STABLE);
        return TransferNetworkMode.allowDisruptiveUpgrade(chosen) ?
                ConnectionType.DISRUPTIVE : ConnectionType.NON_DISRUPTIVE;
    }

    private String wirelessState() {
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        return wifi == null ? "rádio Wi-Fi indisponível" :
                wifi.isWifiEnabled() ? "Wi-Fi ligado" : "Wi-Fi DESLIGADO";
    }

    private final Context context;
    private final ConnectionsClient connections;
    private final PhotoTransport photos;
    private final TransferDb db;
    private final Listener listener;
    private final TransferPowerGuard powerGuard;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String,String> endpoints = new HashMap<>();
    private final Map<String,JSONObject> offers = new HashMap<>();
    private final Map<Long,String> fileOffers = new HashMap<>();
    private final Map<Long,Payload> inboundFiles = new HashMap<>();
    private final Map<Long,Boolean> completed = new HashMap<>();
    private volatile boolean receivingMode;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean busy;
    private String endpoint;
    private List<PhotoTransport.Photo> queue = new ArrayList<>();
    private int position, saved, skipped;
    private long totalBytes, acknowledgedBytes, currentPayloadBytes;
    private long transferStartMillis, currentFileStartedMillis;
    private long activePayloadId = -1L;
    private int receivedCount, receivedNew, receivedDuplicates, receiverTotal;
    private long receiverExpectedBytes, receiverAcknowledgedBytes, receiverInFlightBytes;
    private long receiverCurrentFileSize;
    private String receiverCurrentFileName = "";
    private volatile boolean receiverSessionActive;
    private boolean peerSupportsVideo;
    private List<PhotoTransport.Photo> retrySelection = new ArrayList<>();
    private boolean pendingRetry;
    private int lastProgressPercent = -1;
    private int lastProgressDone = -1;
    private long lastProgressUiAtMillis;
    private volatile String activeOffer;
    private volatile PhotoTransport.Photo activePhoto;
    private ParcelFileDescriptor activeDescriptor;

    NearbyPhotoLink(Context context, PhotoTransport photos, TransferDb db, Listener listener) {
        this.context = context.getApplicationContext();
        this.connections = Nearby.getConnectionsClient(context);
        this.photos = photos;
        this.db = db;
        this.listener = listener;
        this.powerGuard = new TransferPowerGuard(context);
    }

    void receive() {
        close();
        receivingMode = true;
        listener.state("Principal visível. No Secundário toca em Procurar.");
        connections.startAdvertising("PhotoBridge · " + Build.MODEL, SERVICE, lifecycle,
                new AdvertisingOptions.Builder().setStrategy(STRATEGY)
                        .setConnectionType(connectionType()).setLowPower(false).build())
                .addOnFailureListener(e -> listener.state("Falha ao anunciar Principal: " + e.getMessage()));
    }

    void search() {
        close();
        receivingMode = false;
        listener.state("A procurar Remote PhotoBridge por perto...");
        connections.startDiscovery(SERVICE, discovery,
                new DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
                .addOnFailureListener(e -> listener.state("Falha ao procurar: " + e.getMessage()));
    }

    boolean isBusy() { return busy || receiverSessionActive; }
    boolean isConnected() { return connected; }

    void connect(String id) {
        if (receivingMode || busy || connecting || connected) return;
        if (!endpoints.containsKey(id)) {
            listener.state("O telemóvel deixou de estar disponível. Repete a pesquisa.");
            return;
        }
        connecting = true;
        listener.state("A pedir ligação a " + endpoints.get(id) + "...");
        connections.requestConnection("PhotoBridge · " + Build.MODEL, id, lifecycle,
                        new ConnectionOptions.Builder()
                            .setConnectionType(connectionType()).setLowPower(false).build())
                .addOnFailureListener(e -> {
                    connecting = false;
                    listener.state("Falha de ligação: " + e.getMessage());
                });
    }

    void accept(String id) {
        connections.acceptConnection(id, payloads)
                .addOnFailureListener(e -> {
                    connecting = false;
                    listener.state("Falhou confirmação: " + e.getMessage());
                });
    }

    void reject(String id) {
        connections.rejectConnection(id);
        connecting = false;
        listener.state("Emparelhamento recusado.");
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override public void onEndpointFound(String id, DiscoveredEndpointInfo info) {
            if (connected) return;
            endpoints.put(id, info.getEndpointName());
            listener.peers(new HashMap<>(endpoints));
        }
        @Override public void onEndpointLost(String id) {
            // Keep the last discovered entry visible. Nearby discovery may
            // temporarily report LOST while negotiating the same endpoint.
            // The next user-initiated search clears the list.
            if (!connected) listener.state("Dispositivo temporariamente fora da pesquisa: " +
                    endpoints.getOrDefault(id, "Android") + ". Podes tentar ligar ou voltar a procurar.");
        }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override public void onConnectionInitiated(String id, ConnectionInfo info) {
            if (endpoint != null && !endpoint.equals(id)) {
                connections.rejectConnection(id);
                return;
            }
            endpoint = id;
            connecting = true;
            listener.authenticate(id, info.getEndpointName(), info.getAuthenticationDigits());
        }
        @Override public void onConnectionResult(String id, ConnectionResolution result) {
            connecting = false;
            if (result.getStatus().isSuccess() && id.equals(endpoint)) {
                connected = true;
                connections.stopAdvertising();
                connections.stopDiscovery();
                peerSupportsVideo = false;
                try { sendControl(json("hello").put("video", true).put("protocol", 2)); }
                catch (Exception ignored) { }
                listener.state("LIGADO com código de segurança confirmado.");
                listener.connected();
            } else {
                endpoint = null;
                listener.state("Emparelhamento não concluído: " + result.getStatus().getStatusCode());
            }
        }
        @Override public void onDisconnected(String id) {
            if (id.equals(endpoint)) {
                connected = false;
                connecting = false;
                if (busy || receiverSessionActive)
                    abort("Os telemóveis desligaram-se durante a transferência.");
                connected = false;
                busy = false;
                endpoint = null;
                releaseSenderFile();
                listener.state("Ligação interrompida (" + wirelessState() +
                        "). A procurar automaticamente o outro telemóvel...");
                listener.disconnected();
                // Restart the correct Nearby discovery role after an unexpected
                // disconnect. Android still asks for pairing confirmation; no
                // unattended or silent transfer is attempted.
                if (receivingMode) receive(); else search();
            }
        }
    };

    /** This method only runs on a Secondary after user confirms the date window. */
    void sendPhotos(List<PhotoTransport.Photo> selected) {
        if (receivingMode || !connected || endpoint == null) {
            listener.state("Liga ao Principal antes de enviar.");
            return;
        }
        if (busy) {
            listener.state("Existe uma transferência em curso.");
            return;
        }
        if (selected.isEmpty()) {
            listener.state("Não foram encontrados ficheiros no período escolhido.");
            return;
        }
        for (PhotoTransport.Photo file : selected) {
            if (file.mime.startsWith("video/") && !peerSupportsVideo) {
                listener.state("Para enviar vídeos, atualiza também o Principal para a versão 1.0.3 ou superior.");
                return;
            }
        }
        retrySelection = new ArrayList<>(selected);
        pendingRetry = true;
        busy = true;
        powerGuard.start();
        saved = skipped = position = 0;
        acknowledgedBytes = currentPayloadBytes = 0;
        transferStartMillis = SystemClock.elapsedRealtime();
        currentFileStartedMillis = 0L;
        activePayloadId = -1L;
        lastProgressPercent = lastProgressDone = -1;
        lastProgressUiAtMillis = 0L;
        queue = new ArrayList<>(selected);
        totalBytes = 0L;
        for (PhotoTransport.Photo p : queue) {
            if (p.size > 0 && Long.MAX_VALUE - totalBytes >= p.size)
                totalBytes += p.size;
        }
        listener.state("A preparar " + queue.size() + " ficheiros. " +
                "Modo rede: " + (connectionType() == ConnectionType.DISRUPTIVE ?
                "Velocidade máxima" : "Estável, preservar Wi-Fi") + ".");
        reportProgress(true, false, "A preparar envio...");
        try {
            sendControl(json("start").put("count", queue.size()).put("bytes", totalBytes));
            nextPhoto();
        } catch (Exception e) { abort("Não foi possível iniciar a transferência: " + e.getMessage()); }
    }

    private void nextPhoto() {
        if (!connected || !busy) return;
        if (position >= queue.size()) {
            int total = queue.size();
            // Complete means all selected images have been accepted or skipped
            // by the Principal, not merely queued for wireless transmission.
            try {
                sendControl(json("finish").put("count", total)
                        .put("saved", saved).put("skipped", skipped));
                reportProgress(true, true, "Envio concluído.");
                pendingRetry = false;
                retrySelection.clear();
                listener.transferFinished(true, saved, skipped, total);
                powerGuard.stop();
                listener.state("CONCLUÍDO: " + saved + " novas na galeria do Principal; " +
                        skipped + " já existentes (" + total + " selecionadas).");
            } catch (Exception e) {
                abort("Falhou a confirmação final: " + e.getMessage());
                return;
            } finally {
                busy = false;
                releaseSenderFile();
            }
            return;
        }
        final PhotoTransport.Photo p = queue.get(position);
        activePhoto = p;
        reportProgress(true, false, "A calcular SHA-256 (leitura local, sem envio)...");
        worker.execute(() -> {
            try {
                long hashStarted = SystemClock.elapsedRealtime();
                listener.state("A calcular SHA-256 de " + p.name +
                        " (" + (p.size / (1024 * 1024)) + " MB); ainda não está a enviar...");
                final String hash = photos.photoHash(p);
                long hashElapsed = Math.max(1, SystemClock.elapsedRealtime() - hashStarted);
                listener.state("Hash concluído em " + (hashElapsed / 1000) +
                        " s. A pedir confirmação de duplicado...");
                JSONObject offer = json("offer")
                        .put("id", UUID.randomUUID().toString())
                        .put("name", p.name)
                        .put("mime", p.mime)
                        .put("size", p.size)
                        .put("taken", p.dateTaken)
                        .put("hash", hash);
                activeOffer = offer.getString("id");
                activePhoto = p;
                listener.state("Preparado " + (position + 1) + "/" +
                        queue.size() + ": " + p.name);
                sendControl(offer);
            } catch (Exception e) {
                abort("Não foi possível ler a fotografia: " + e.getMessage());
            }
        });
    }

    private void sendFile(String offerId) {
        if (!busy || !connected || !offerId.equals(activeOffer) || activePhoto == null) return;
        worker.execute(() -> {
            try {
                activeDescriptor = context.getContentResolver().openFileDescriptor(activePhoto.uri, "r");
                if (activeDescriptor == null) throw new IOException("Ficheiro original indisponível.");
                Payload data = Payload.fromFile(activeDescriptor);
                activePayloadId = data.getId();
                currentPayloadBytes = 0L;
                currentFileStartedMillis = SystemClock.elapsedRealtime();
                sendControl(json("file").put("id", offerId).put("payload", data.getId()));
                connections.sendPayload(endpoint, data)
                        .addOnFailureListener(e -> abort("Envio de fotografia falhou: " + e.getMessage()));
                listener.state("A enviar " + (position + 1) + "/" + queue.size() +
                        " para o Principal...");
            } catch (Exception e) {
                abort("Não foi possível enviar fotografia: " + e.getMessage());
            }
        });
    }

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override public void onPayloadReceived(String id, Payload data) {
            if (!id.equals(endpoint)) return;
            if (data.getType() == Payload.Type.BYTES) {
                try { handleControl(new JSONObject(new String(data.asBytes(), StandardCharsets.UTF_8))); }
                catch (Exception e) { listener.state("Comando de transferência inválido: " + e.getMessage()); }
            } else if (data.getType() == Payload.Type.FILE && receivingMode) {
                inboundFiles.put(data.getId(), data);
                attemptImport(data.getId());
            }
        }
        @Override public void onPayloadTransferUpdate(String id, PayloadTransferUpdate update) {
            if (!id.equals(endpoint)) return;
            boolean currentSenderFile = !receivingMode && busy &&
                    activePayloadId == update.getPayloadId();
            boolean currentReceiverFile = receivingMode && receiverSessionActive &&
                    (inboundFiles.containsKey(update.getPayloadId()) ||
                     fileOffers.containsKey(update.getPayloadId()));
            if (update.getStatus() == PayloadTransferUpdate.Status.FAILURE ||
                    update.getStatus() == PayloadTransferUpdate.Status.CANCELED) {
                if (currentSenderFile || currentReceiverFile)
                    abort("Falha Nearby (" + update.getStatus() + ", " +
                            (update.getBytesTransferred() / 1048576L) + " MB enviados, " +
                            wirelessState() + "). Podes repetir sem duplicar.");
                return;
            }
            if (update.getStatus() == PayloadTransferUpdate.Status.IN_PROGRESS ||
                    update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {
                long transferred = Math.max(0L, update.getBytesTransferred());
                if (currentSenderFile) {
                    currentPayloadBytes = Math.min(activePhoto == null ? transferred :
                            activePhoto.size, transferred);
                    reportProgress(true, false, "A enviar ficheiro " +
                            (position + 1) + "/" + queue.size());
                } else if (currentReceiverFile) {
                    receiverInFlightBytes = Math.max(receiverInFlightBytes, transferred);
                    reportProgress(false, false, "A receber ficheiro " +
                            (receivedCount + 1) + "/" + receiverTotal);
                }
            }
            if (update.getStatus() == PayloadTransferUpdate.Status.SUCCESS && receivingMode &&
                    (inboundFiles.containsKey(update.getPayloadId()) ||
                     fileOffers.containsKey(update.getPayloadId()))) {
                completed.put(update.getPayloadId(), true);
                attemptImport(update.getPayloadId());
            }
        }
    };

    private void handleControl(JSONObject cmd) throws Exception {
        String type = cmd.getString("type");
        String id = cmd.optString("id", "");
        if ("hello".equals(type)) {
            peerSupportsVideo = cmd.optBoolean("video", false) &&
                    cmd.optInt("protocol", 0) >= 2;
            listener.state(peerSupportsVideo ?
                    "Dispositivo compatível com fotografias e vídeos." :
                    "Dispositivo compatível apenas com fotografias.");
        } else if ("start".equals(type) && receivingMode) {
            int count = cmd.getInt("count");
            long bytes = cmd.getLong("bytes");
            if (count < 1 || count > 200000 || bytes < 0)
                throw new IOException("Resumo de fotografias inválido.");
            receiverTotal = count;
            receiverExpectedBytes = bytes;
            receiverAcknowledgedBytes = receiverInFlightBytes = 0L;
            receiverCurrentFileSize = 0L;
            receiverCurrentFileName = "";
            receivedCount = receivedNew = receivedDuplicates = 0;
            receiverSessionActive = true;
            powerGuard.start();
            lastProgressPercent = lastProgressDone = -1;
            lastProgressUiAtMillis = 0L;
            listener.state("A iniciar receção de " + count + " ficheiros ("
                    + TransferSizes.format(bytes) + ")...");
            reportProgress(false, false, "A aguardar fotografias...");
        } else if ("offer".equals(type) && receivingMode) {
            String sha = cmd.getString("hash");
            long bytes = cmd.getLong("size");
            String mime = cmd.getString("mime");
            if (!sha.matches("[a-f0-9]{64}") || bytes < 1 || bytes > MAX_MEDIA_BYTES ||
                    !(mime.startsWith("image/") || (mime.startsWith("video/") && peerSupportsVideo)) ||
                    id.length() > 100 || id.isEmpty()) {
                listener.state("Recebidos metadados inválidos.");
                return;
            }
            receiverCurrentFileSize = bytes;
            receiverCurrentFileName = cmd.optString("name", "");
            receiverInFlightBytes = 0L;
            if (db.alreadyReceived(sha)) {
                sendControl(json("skip").put("id", id));
                receivedCount++;
                receivedDuplicates++;
                receiverAcknowledgedBytes += bytes;
                receiverInFlightBytes = 0L;
                receiverCurrentFileSize = 0L;
                receiverCurrentFileName = "";
                reportProgress(false, false, "Ficheiro já existente (não enviado novamente).");
            } else {
                offers.put(id, cmd);
                sendControl(json("need").put("id", id));
            }
        } else if ("skip".equals(type) && !receivingMode && id.equals(activeOffer)) {
            if (activePhoto != null) acknowledgedBytes += activePhoto.size;
            skipped++;
            currentPayloadBytes = 0L;
            reportProgress(true, false, "Fotografia já existente.");
            releaseSenderFile();
            position++;
            listener.state("Fotografia já guardada no Principal; ignorada.");
            nextPhoto();
        } else if ("need".equals(type) && !receivingMode && id.equals(activeOffer)) {
            sendFile(id);
        } else if ("file".equals(type) && receivingMode) {
            long payloadId = cmd.getLong("payload");
            if (!offers.containsKey(id)) return;
            fileOffers.put(payloadId, id);
            attemptImport(payloadId);
        } else if ("ack".equals(type) && !receivingMode && id.equals(activeOffer)) {
            boolean ok = cmd.getBoolean("ok");
            if (!ok) {
                abort("O Principal não conseguiu guardar a fotografia: " + cmd.optString("reason"));
                return;
            }
            if (cmd.optBoolean("saved", false)) saved++; else skipped++;
            if (activePhoto != null) acknowledgedBytes += activePhoto.size;
            currentPayloadBytes = 0L;
            reportProgress(true, false, "Fotografia confirmada pelo Principal.");
            releaseSenderFile();
            position++;
            nextPhoto();
        } else if ("finish".equals(type) && receivingMode) {
            int expected = cmd.optInt("count", -1);
            if (receiverSessionActive && expected == receiverTotal &&
                    receivedCount == receiverTotal &&
                    receivedNew + receivedDuplicates == receivedCount) {
                reportProgress(false, true, "Receção concluída.");
                listener.transferFinished(false, receivedNew, receivedDuplicates, receivedCount);
                listener.state("Receção terminada. Novas fotografias na Galeria: " +
                        receivedNew + "; repetidas: " + receivedDuplicates + ".");
            } else {
                listener.transferInterrupted(false,
                        "A receção terminou sem confirmar todas as fotografias.");
                listener.state("Receção incompleta: confirma os resultados no Secundário.");
            }
            receiverSessionActive = false;
            powerGuard.stop();
            busy = false;
        }
    }

    private void attemptImport(long payloadId) {
        if (!receivingMode || !Boolean.TRUE.equals(completed.get(payloadId))) return;
        String offerId = fileOffers.get(payloadId);
        Payload file = inboundFiles.get(payloadId);
        if (offerId == null || file == null) return;
        JSONObject metadata = offers.remove(offerId);
        fileOffers.remove(payloadId);
        inboundFiles.remove(payloadId);
        completed.remove(payloadId);
        if (metadata == null) return;
        worker.execute(() -> {
            boolean saved = false;
            String error = "";
            Uri temporary = null;
            try {
                temporary = file.asFile() == null ? null : file.asFile().asUri();
                if (temporary == null) throw new IOException("Nearby não forneceu o ficheiro recebido.");
                saved = photos.saveNearbyMedia(temporary, metadata.getString("name"),
                        metadata.getString("mime"), metadata.getLong("size"),
                        metadata.getLong("taken"), metadata.getString("hash"));
                if (saved) {
                    // Expose the Gallery action immediately, not only at the
                    // end of a potentially lengthy multi-photo transfer.
                    listener.photoReceived(photos.latestReceivedPhotoUri());
                }
                listener.state(saved ? (metadata.optString("mime").startsWith("video/") ?
                        "Vídeo guardado na Galeria do Principal." :
                        "Fotografia guardada na Galeria do Principal.") :
                        "Ficheiro repetido; não foi novamente guardado.");
            } catch (Exception e) {
                error = e.getMessage() == null ? "Falha ao importar na galeria" : e.getMessage();
                listener.state("Não foi possível guardar fotografia: " + error);
            }
            // Nearby's downloaded temporary FILE is not the gallery image.
            // Best-effort cleanup avoids duplicate copies in Downloads/.nearby.
            if (temporary != null) {
                try { context.getContentResolver().delete(temporary, null, null); }
                catch (Exception ignored) { }
            }
            try {
                // Count only fully verified (or already present) images.
                if (error.isEmpty()) {
                    receivedCount++;
                    if (saved) receivedNew++; else receivedDuplicates++;
                    receiverAcknowledgedBytes += metadata.optLong("size", 0L);
                    receiverInFlightBytes = 0L;
                    receiverCurrentFileSize = 0L;
                    receiverCurrentFileName = "";
                    reportProgress(false, false, "Ficheiro guardado e verificado.");
                }
                sendControl(json("ack").put("id", offerId).put("ok", error.isEmpty())
                        .put("saved", saved).put("reason", error));
            } catch (Exception ignored) { }
        });
    }

    private void reportProgress(boolean sender, boolean finished, String detail) {
        if (!sender && !receiverSessionActive && !finished) return;
        long done = sender ? acknowledgedBytes : receiverAcknowledgedBytes;
        long inflight = sender ? currentPayloadBytes : receiverInFlightBytes;
        long all = sender ? totalBytes : receiverExpectedBytes;
        int count = sender ? queue.size() : receiverTotal;
        int confirmed = sender ? saved + skipped : receivedCount;
        int percent = TransferProgressMath.percentage(
                done, all, inflight, confirmed, count, finished);
        powerGuard.pulse();
        long now = SystemClock.elapsedRealtime();
        // Nearby may report many byte updates within the same integer percent.
        // Refresh the MB totals at least every 400 ms, even when the percentage
        // has not changed (common during a single very large video).
        if (percent != lastProgressPercent || confirmed != lastProgressDone ||
                finished || now - lastProgressUiAtMillis >= 400L) {
            lastProgressPercent = percent;
            lastProgressDone = confirmed;
            lastProgressUiAtMillis = now;
            StringBuilder extra = new StringBuilder();
            extra.append("\n").append(TransferSizes.batch(done, inflight, all));
            long currentSize = 0L;
            String currentName = "";
            int currentIndex = sender ? position + 1 : receivedCount + 1;
            if (sender && activePhoto != null && position == confirmed && !finished) {
                currentSize = activePhoto.size;
                currentName = activePhoto.name;
            } else if (!sender && receiverCurrentFileSize > 0L && !finished) {
                currentSize = receiverCurrentFileSize;
                currentName = receiverCurrentFileName;
            }
            if (currentSize > 0L) {
                if (currentName.length() > 58)
                    currentName = currentName.substring(0, 55) + "...";
                extra.append("\n").append(currentIndex).append("/").append(count)
                        .append(": ").append(currentName);
                extra.append("\n").append(TransferSizes.current(inflight, currentSize));
            }
            if (sender && transferStartMillis > 0 && done + inflight > 0) {
                long elapsed = Math.max(1L, now - transferStartMillis);
                double speed = ((done + inflight) / 1048576.0) / (elapsed / 1000.0);
                extra.append(String.format(java.util.Locale.ROOT,
                        "\nMédia sessão: %.2f MiB/s", speed));
                if (currentFileStartedMillis > 0 && inflight > 0) {
                    long fileElapsed = Math.max(1L, now - currentFileStartedMillis);
                    double networkSpeed = (inflight / 1048576.0) / (fileElapsed / 1000.0);
                    extra.append(String.format(java.util.Locale.ROOT,
                            " · ficheiro: %.2f MiB/s", networkSpeed));
                }
            }
            listener.transferProgress(sender, percent, confirmed, count, detail + extra);
        }
    }

    private JSONObject json(String type) {
        JSONObject j = new JSONObject();
        try { j.put("type", type); } catch (Exception ignored) { }
        return j;
    }

    private void sendControl(JSONObject message) {
        if (!connected || endpoint == null) return;
        try {
            Payload bytes = Payload.fromBytes(message.toString().getBytes(StandardCharsets.UTF_8));
            connections.sendPayload(endpoint, bytes)
                    .addOnFailureListener(e -> abort("Falha de comunicação: " + e.getMessage()));
        } catch (Exception e) {
            abort("Falha de comunicação: " + e.getMessage());
        }
    }

    private void abort(String error) {
        boolean wasTransfer = busy || receiverSessionActive;
        boolean sending = !receivingMode;
        busy = false;
        receiverSessionActive = false;
        powerGuard.stop();
        releaseSenderFile();
        currentPayloadBytes = receiverInFlightBytes = 0L;
        if (wasTransfer) {
            if (sending && !retrySelection.isEmpty()) pendingRetry = true;
            listener.transferInterrupted(sending, error);
        }
        listener.state("TRANSFERÊNCIA PARADA: " + error);
    }

    private void releaseSenderFile() {
        if (activeDescriptor != null) {
            try { activeDescriptor.close(); } catch (IOException ignored) { }
            activeDescriptor = null;
        }
        activeOffer = null;
        activePhoto = null;
        activePayloadId = -1L;
        currentFileStartedMillis = 0L;
    }

    boolean canRetry() { return !receivingMode && pendingRetry && !retrySelection.isEmpty(); }

    void retryUnfinished() {
        if (!canRetry()) { listener.state("Não há transferência pendente para repetir."); return; }
        if (!connected) { listener.state("Liga novamente ao Principal; os ficheiros já recebidos serão ignorados."); return; }
        if (busy) { listener.state("Já está a transferir."); return; }
        sendPhotos(new ArrayList<>(retrySelection));
    }

    void close() {
        if (busy || receiverSessionActive)
            abort("A ligação foi terminada antes da confirmação final.");
        connections.stopAdvertising();
        connections.stopDiscovery();
        connections.stopAllEndpoints();
        connected = false;
        connecting = false;
        busy = false;
        endpoint = null;
        peerSupportsVideo = false;
        releaseSenderFile();
        powerGuard.stop();
        endpoints.clear();
        offers.clear();
        fileOffers.clear();
        inboundFiles.clear();
        completed.clear();
        listener.peers(new HashMap<>());
    }

    void shutdown() {
        close();
        worker.shutdownNow();
    }
}
