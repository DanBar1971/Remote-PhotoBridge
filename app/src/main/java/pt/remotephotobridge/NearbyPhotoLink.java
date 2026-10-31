package pt.remotephotobridge;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
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
    }

    private static final String SERVICE = "pt.remotephotobridge.nearby.photos.v1";
    private static final Strategy STRATEGY = Strategy.P2P_POINT_TO_POINT;
    private final Context context;
    private final ConnectionsClient connections;
    private final PhotoTransport photos;
    private final TransferDb db;
    private final Listener listener;
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
    private volatile String activeOffer;
    private volatile PhotoTransport.Photo activePhoto;
    private ParcelFileDescriptor activeDescriptor;

    NearbyPhotoLink(Context context, PhotoTransport photos, TransferDb db, Listener listener) {
        this.context = context.getApplicationContext();
        this.connections = Nearby.getConnectionsClient(context);
        this.photos = photos;
        this.db = db;
        this.listener = listener;
    }

    void receive() {
        close();
        receivingMode = true;
        listener.state("Principal visível. No Secundário toca em Procurar.");
        connections.startAdvertising("PhotoBridge · " + Build.MODEL, SERVICE, lifecycle,
                new AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
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

    boolean isBusy() { return busy; }
    boolean isConnected() { return connected; }

    void connect(String id) {
        if (receivingMode || busy || connecting || connected) return;
        if (!endpoints.containsKey(id)) {
            listener.state("O telemóvel deixou de estar disponível. Repete a pesquisa.");
            return;
        }
        connecting = true;
        listener.state("A pedir ligação a " + endpoints.get(id) + "...");
        connections.requestConnection("PhotoBridge · " + Build.MODEL, id, lifecycle)
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
                busy = false;
                endpoint = null;
                releaseSenderFile();
                listener.state("Ligação terminada. Volta a procurar ou a receber.");
                listener.disconnected();
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
            listener.state("Não há fotografias no período escolhido.");
            return;
        }
        busy = true;
        saved = skipped = position = 0;
        queue = new ArrayList<>(selected);
        listener.state("A preparar " + queue.size() + " fotografias do período selecionado...");
        nextPhoto();
    }

    private void nextPhoto() {
        if (!connected || !busy) return;
        if (position >= queue.size()) {
            int total = queue.size();
            busy = false;
            releaseSenderFile();
            listener.state("CONCLUÍDO: " + saved + " novas na galeria do Principal; " +
                    skipped + " já existentes (" + total + " selecionadas).");
            try {
                sendControl(json("finish").put("count", total)
                        .put("saved", saved).put("skipped", skipped));
            } catch (Exception e) {
                listener.state("A sincronização terminou, mas falhou o resumo: " + e.getMessage());
            }
            return;
        }
        final PhotoTransport.Photo p = queue.get(position);
        worker.execute(() -> {
            try {
                final String hash = photos.photoHash(p);
                JSONObject offer = json("offer")
                        .put("id", UUID.randomUUID().toString())
                        .put("name", p.name)
                        .put("mime", p.mime)
                        .put("size", p.size)
                        .put("taken", p.dateTaken)
                        .put("hash", hash);
                activeOffer = offer.getString("id");
                activePhoto = p;
                listener.state("A verificar " + (position + 1) + "/" + queue.size() + ": " + p.name);
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
            if (update.getStatus() == PayloadTransferUpdate.Status.ERROR ||
                    update.getStatus() == PayloadTransferUpdate.Status.CANCELED) {
                abort("Ficheiro interrompido. Podes repetir sem duplicar fotografias.");
                return;
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
        if ("offer".equals(type) && receivingMode) {
            String sha = cmd.getString("hash");
            long bytes = cmd.getLong("size");
            String mime = cmd.getString("mime");
            if (!sha.matches("[a-f0-9]{64}") || bytes < 1 || bytes > 2_000_000_000L ||
                    !mime.startsWith("image/") || id.length() > 100 || id.isEmpty()) {
                listener.state("Recebidos metadados inválidos.");
                return;
            }
            if (db.alreadyReceived(sha)) {
                sendControl(json("skip").put("id", id));
            } else {
                offers.put(id, cmd);
                sendControl(json("need").put("id", id));
            }
        } else if ("skip".equals(type) && !receivingMode && id.equals(activeOffer)) {
            skipped++;
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
            releaseSenderFile();
            position++;
            nextPhoto();
        } else if ("finish".equals(type) && receivingMode) {
            listener.state("Receção terminada. Novas fotografias na Galeria: " +
                    cmd.optInt("saved", 0) + "; repetidas: " + cmd.optInt("skipped", 0) + ".");
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
                saved = photos.saveNearbyPhoto(temporary, metadata.getString("name"),
                        metadata.getString("mime"), metadata.getLong("size"),
                        metadata.getLong("taken"), metadata.getString("hash"));
                listener.state(saved ? "Fotografia guardada na Galeria do Principal." :
                        "Fotografia repetida; não foi novamente guardada.");
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
                sendControl(json("ack").put("id", offerId).put("ok", error.isEmpty())
                        .put("saved", saved).put("reason", error));
            } catch (Exception ignored) { }
        });
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
        busy = false;
        releaseSenderFile();
        listener.state("TRANSFERÊNCIA PARADA: " + error);
    }

    private void releaseSenderFile() {
        if (activeDescriptor != null) {
            try { activeDescriptor.close(); } catch (IOException ignored) { }
            activeDescriptor = null;
        }
        activeOffer = null;
        activePhoto = null;
    }

    void close() {
        connections.stopAdvertising();
        connections.stopDiscovery();
        connections.stopAllEndpoints();
        connected = false;
        connecting = false;
        busy = false;
        endpoint = null;
        releaseSenderFile();
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
