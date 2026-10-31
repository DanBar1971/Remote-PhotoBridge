package pt.remotephotobridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Asynchronous local Wi-Fi Direct discovery, pairing and disconnect with visible diagnostics. */
final class WifiDirectLink {
    interface Listener {
        void message(String text);
        void peers(List<WifiP2pDevice> devices);
        void connected(WifiP2pInfo info);
        void disconnected();
        void waitingForPeer();
    }

    private final Context context;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean registered;
    private boolean connecting;
    private boolean wasConnected;
    private boolean groupWaiting;
    private long monitoringUntil;
    private boolean monitorScheduled;
    private String tryingPeer = "";
    private final Runnable connectionPoll = new Runnable() {
        @Override public void run() {
            monitorScheduled = false;
            if (SystemClock.elapsedRealtime() > monitoringUntil) {
                if (connecting) listener.message("Sem confirmação do Android após 90 segundos. Consulta o estado da ligação; se continuar pendente, reinicia nos dois telemóveis.");
                // Do not clear connecting just because the platform has not completed the
                // negotiation. Android may be showing a Wi-Fi Direct confirmation dialog.
                return;
            }
            checkConnection();
            scheduleConnectionPoll();
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        listener.message("Wi-Fi Direct desligado. Ativa o Wi-Fi nas definições.");
                        if (wasConnected) { wasConnected = false; listener.disconnected(); }
                    }
                    break;
                case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                    if (!connecting) requestPeers();
                    break;
                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                    NetworkInfo network = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    listener.message("Evento Wi-Fi Direct: " +
                            (network == null ? "a verificar grupo" : network.getDetailedState().name()));
                    checkConnection();
                    break;
                case WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION:
                    int discovery = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                    if (discovery == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED)
                        listener.message("Descoberta Wi-Fi Direct ativa.");
                    break;
                case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
                    WifiP2pDevice local = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                    if (local != null)
                        listener.message("Estado deste Android no Wi-Fi Direct: " + peerStatus(local.status) + ".");
                    break;
            }
        }
    };

    WifiDirectLink(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) throw new IllegalStateException("Este Android não disponibiliza Wi-Fi Direct.");
        channel = manager.initialize(context, context.getMainLooper(),
                () -> listener.message("Canal Wi-Fi Direct perdido. Fecha e reabre a aplicação."));
        if (channel == null) throw new IllegalStateException("Não foi possível abrir o canal Wi-Fi Direct.");
    }

    void start() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter);
        registered = true;
        checkConnection();
    }

    void stop() {
        main.removeCallbacks(connectionPoll);
        monitorScheduled = false;
        monitoringUntil = 0;
        if (!registered) return;
        try { context.unregisterReceiver(receiver); }
        catch (IllegalArgumentException ignored) { }
        registered = false;
    }

    @SuppressLint("MissingPermission")
    void discover() {
        if (!hasWifiPermission()) { listener.message("Autoriza a permissão Dispositivos próximos primeiro."); return; }
        if (connecting) { listener.message("Já existe um pedido de ligação em curso. Aguarda ou desliga primeiro."); return; }
        listener.message("A procurar dispositivos Wi-Fi Direct próximos. No Principal, não é necessário encontrar o Secundário se este já encontrou o Principal.");
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                listener.message("Pesquisa iniciada. Toca no nome do outro telemóvel para ligar.");
                requestPeers();
            }
            @Override public void onFailure(int code) {
                listener.message("Erro ao pesquisar: " + explain(code) + ". Confirma Wi-Fi, Localização e permissões.");
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void requestPeers() {
        if (!hasWifiPermission()) return;
        manager.requestPeers(channel, available -> {
            if (connecting) return;
            Collection<WifiP2pDevice> all = available.getDeviceList();
            listener.peers(new ArrayList<>(all));
        });
    }

    @SuppressLint("MissingPermission")
    void connectTo(WifiP2pDevice device, boolean principal) {
        if (!hasWifiPermission()) { listener.message("Permissão Wi-Fi Direct em falta."); return; }
        if (connecting) { listener.message("Aguarda o pedido de ligação anterior ou usa Desligar/Reiniciar ligação."); return; }
        if (wasConnected) { listener.message("Já existe uma ligação. Usa Desligar antes de emparelhar outro dispositivo."); return; }
        connecting = true;
        tryingPeer = device.deviceName == null ? "Android" : device.deviceName;
        listener.message("A iniciar ligação a " + tryingPeer + ". Confirma o convite que aparecer no outro telemóvel.");
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.groupOwnerIntent = principal ? 15 : 0;
        config.wps.setup = WpsInfo.PBC;
        // Stop discovery before pairing. Concurrent discovery can cause BUSY on some OEMs.
        watchForConnection();
        manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { requestConnection(config); }
            @Override public void onFailure(int reason) {
                listener.message("Paragem da pesquisa: " + explain(reason) + ". A tentar ligação...");
                requestConnection(config);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void requestConnection(WifiP2pConfig config) {
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                listener.message("Pedido de emparelhamento aceite pelo sistema, mas ainda NÃO ligado. Confirma a janela Wi-Fi Direct no Principal.");
                watchForConnection();
                checkConnection();
            }
            @Override public void onFailure(int reason) {
                connecting = false;
                listener.message("Falha ao ligar a " + tryingPeer + ": " + explain(reason) + ". Usa Desligar/Reiniciar e tenta novamente.");
            }
        });
    }

    @SuppressLint("MissingPermission")
    void checkConnection() {
        if (!hasWifiPermission()) return;
        manager.requestConnectionInfo(channel, info -> {
            if (info != null && info.groupFormed) {
                if (!info.isGroupOwner) {
                    confirmedConnection(info);
                    return;
                }
                // createGroup() reports groupFormed=true before a second phone joins.
                // Only label the Principal "CONNECTED" once a real P2P client joins.
                manager.requestGroupInfo(channel, group -> {
                    if (group == null) { listener.message("Grupo Wi-Fi Direct ainda sem detalhes. A aguardar..."); return; }
                    if (!group.getClientList().isEmpty()) {
                        confirmedConnection(info);
                    } else if (!groupWaiting) {
                        groupWaiting = true;
                        listener.message("Grupo Wi-Fi Direct criado, mas ainda SEM Secundário ligado.");
                        listener.waitingForPeer();
                    }
                });
            } else if (wasConnected || groupWaiting) {
                wasConnected = false;
                groupWaiting = false;
                connecting = false;
                listener.message("A ligação Wi-Fi Direct foi desligada.");
                listener.disconnected();
            }
        });
    }

    private void confirmedConnection(WifiP2pInfo info) {
        boolean newlyConnected = !wasConnected || groupWaiting;
        wasConnected = true;
        groupWaiting = false;
        connecting = false;
        monitoringUntil = 0;
        main.removeCallbacks(connectionPoll);
        monitorScheduled = false;
        if (newlyConnected) {
            listener.message("Ligação Wi-Fi Direct CONFIRMADA pelo Android. Proprietário: " +
                    (info.isGroupOwner ? "Principal (este Android)" : "outro telemóvel") + ".");
            listener.connected(info);
        }
    }

    void watchForConnection() {
        monitoringUntil = SystemClock.elapsedRealtime() + 90_000L;
        scheduleConnectionPoll();
    }

    private void scheduleConnectionPoll() {
        if (monitorScheduled || monitoringUntil == 0) return;
        monitorScheduled = true;
        main.postDelayed(connectionPoll, 2_000L);
    }

    @SuppressLint("MissingPermission")
    void createReceiverGroup() {
        watchForConnection();
        if (!hasWifiPermission()) { listener.message("Permissão Wi-Fi Direct em falta."); return; }
        if (wasConnected) { listener.message("Já estás num grupo; desliga-o antes de criar outro."); return; }
        listener.message("Modo compatibilidade: a criar grupo com o Principal como proprietário...");
        manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { doCreateGroup(); }
            @Override public void onFailure(int code) { doCreateGroup(); }
        });
    }

    @SuppressLint("MissingPermission")
    private void doCreateGroup() {
        manager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                listener.message("Grupo do Principal criado. No Secundário pesquisa e seleciona este telemóvel.");
                checkConnection();
            }
            @Override public void onFailure(int code) {
                listener.message("Não foi possível criar grupo: " + explain(code) + ". Desliga e tenta novamente.");
            }
        });
    }

    @SuppressLint("MissingPermission")
    void disconnect() {
        connecting = false;
        groupWaiting = false;
        monitoringUntil = 0;
        main.removeCallbacks(connectionPoll);
        monitorScheduled = false;
        listener.message("A terminar ligação Wi-Fi Direct anterior...");
        if (!hasWifiPermission()) return;
        manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { removeGroupNow(); }
            @Override public void onFailure(int ignored) { removeGroupNow(); }
        });
    }

    @SuppressLint("MissingPermission")
    private void removeGroupNow() {
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                wasConnected = false;
                groupWaiting = false;
                listener.disconnected();
                listener.message("Ligação terminada. Podes voltar a procurar dispositivos.");
            }
            @Override public void onFailure(int code) {
                wasConnected = false;
                listener.disconnected();
                listener.message("Ligação reiniciada (" + explain(code) + "). Podes voltar a procurar.");
            }
        });
    }

    void removeGroup() { disconnect(); }

    private boolean hasWifiPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                : context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String peerStatus(int state) {
        switch (state) {
            case WifiP2pDevice.CONNECTED: return "ligado";
            case WifiP2pDevice.INVITED: return "convite pendente";
            case WifiP2pDevice.FAILED: return "erro de emparelhamento";
            case WifiP2pDevice.AVAILABLE: return "disponível";
            default: return "indisponível";
        }
    }

    private String explain(int code) {
        switch (code) {
            case WifiP2pManager.BUSY: return "Wi-Fi Direct ocupado (BUSY)";
            case WifiP2pManager.P2P_UNSUPPORTED: return "Wi-Fi Direct não suportado";
            default: return "código " + code;
        }
    }
}
