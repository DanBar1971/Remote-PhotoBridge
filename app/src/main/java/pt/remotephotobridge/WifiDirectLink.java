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
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

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
    }

    private final Context context;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean registered;
    private boolean connecting;
    private boolean wasConnected;
    private String tryingPeer = "";
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
                    checkConnection();
                    break;
                case WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION:
                    int discovery = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                    if (discovery == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED)
                        listener.message("Descoberta Wi-Fi Direct ativa.");
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
        if (!registered) return;
        try { context.unregisterReceiver(receiver); }
        catch (IllegalArgumentException ignored) { }
        registered = false;
    }

    @SuppressLint("MissingPermission")
    void discover() {
        if (!hasWifiPermission()) { listener.message("Autoriza a permissão Dispositivos próximos primeiro."); return; }
        if (connecting) { listener.message("Já existe um pedido de ligação em curso. Aguarda ou desliga primeiro."); return; }
        listener.message("A procurar dispositivos Wi-Fi Direct próximos...");
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
                listener.message("Pedido enviado. Aguarda autorização Wi-Fi Direct no outro telemóvel (ainda não está ligado).");
                main.postDelayed(() -> {
                    if (!connecting) return;
                    checkConnection();
                    listener.message("A ligação a " + tryingPeer + " ainda não foi confirmada. Aceita o convite no outro telemóvel; se falhar usa Desligar/Reiniciar.");
                    connecting = false;
                }, 30000);
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
                boolean newConnection = !wasConnected;
                wasConnected = true;
                connecting = false;
                if (newConnection) listener.message("Ligação Wi-Fi Direct estabelecida. Proprietário do grupo: " + (info.isGroupOwner ? "este telemóvel" : "outro telemóvel") + ".");
                listener.connected(info);
            } else if (wasConnected) {
                wasConnected = false;
                connecting = false;
                listener.message("A ligação Wi-Fi Direct foi desligada.");
                listener.disconnected();
            }
        });
    }

    @SuppressLint("MissingPermission")
    void createReceiverGroup() {
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

    private String explain(int code) {
        switch (code) {
            case WifiP2pManager.BUSY: return "Wi-Fi Direct ocupado (BUSY)";
            case WifiP2pManager.P2P_UNSUPPORTED: return "Wi-Fi Direct não suportado";
            default: return "código " + code;
        }
    }
}
