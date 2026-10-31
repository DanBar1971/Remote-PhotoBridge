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
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
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
                monitoringUntil = 0;
                if (connecting && !wasConnected) {
                    connecting = false;
                    listener.message("Falha ao ligar: o Android não confirmou o grupo Wi-Fi Direct. Podes tocar novamente no outro telemóvel, ou tentar iniciar do lado oposto.");
                    if (hasWifiPermission())
                        manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                            @Override public void onSuccess() { }
                            @Override public void onFailure(int ignored) { }
                        });
                }
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
        diagnostic();
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
        listener.message("Descoberta Wi-Fi Direct ativa: os dois Androids devem estar visíveis. O Secundário é o único que inicia a ligação.");
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                listener.message("Pesquisa iniciada pelo Android. A procurar pares próximos (ainda não significa encontrados).");
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
        listener.message((principal ? "Principal" : "Secundário") +
                ": pedido de ligação a " + tryingPeer + ". Não carregues em ligar no outro telemóvel.");
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        // Photo receiver and P2P group owner are distinct roles. Prefer Principal
        // as group owner; either phone may initiate exactly one pairing request.
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
                listener.message("Android iniciou o pedido Wi-Fi Direct para " + tryingPeer + ". Ainda NÃO está ligado. Confirma o pedido no outro equipamento.");
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

    /**
     * Normal phone-to-phone setup. Do NOT create an autonomous P2P group
     * before the peer is discovered: createGroup() stops active discovery
     * and on certain Xiaomi/Redmi firmware prevents the other Android from
     * seeing this device. Role (receiver) is separate from P2P group owner.
     *
     * Clear any old autonomous group left by alpha6/alpha8 before scanning.
     */
    @SuppressLint("MissingPermission")
    void prepareReceiverDiscovery() {
        if (!hasWifiPermission()) {
            listener.message("Autoriza Dispositivos próximos / Localização para ativar Wi-Fi Direct.");
            return;
        }
        if (connecting) {
            listener.message("Existe um emparelhamento anterior pendente. A terminar antes da descoberta...");
        }
        connecting = false;
        monitoringUntil = 0;
        main.removeCallbacks(connectionPoll);
        monitorScheduled = false;
        manager.requestGroupInfo(channel, group -> {
            if (group == null) {
                listener.message("Principal: sem grupo antigo. A iniciar descoberta normal Wi-Fi Direct.");
                discover();
                return;
            }
            listener.message("Principal: a eliminar grupo anterior (" + group.getNetworkName() + ") antes de ficar visível.");
            manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { clearOldGroupAndDiscover(); }
                @Override public void onFailure(int reason) { clearOldGroupAndDiscover(); }
            });
        });
    }

    @SuppressLint("MissingPermission")
    private void clearOldGroupAndDiscover() {
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                resetLocalConnectionState();
                listener.message("Grupo antigo removido. Principal à procura do Secundário.");
                discover();
            }
            @Override public void onFailure(int reason) {
                resetLocalConnectionState();
                listener.message("Remover grupo antigo: " + explain(reason) +
                        ". A tentar descobrir o Secundário sem criar novo grupo.");
                discover();
            }
        });
    }

    private void resetLocalConnectionState() {
        wasConnected = false;
        groupWaiting = false;
        connecting = false;
        listener.disconnected();
    }

    @SuppressLint("MissingPermission")
    void createReceiverGroup() {
        watchForConnection();
        if (!hasWifiPermission()) { listener.message("Permissão Wi-Fi Direct em falta."); return; }
        if (wasConnected) { listener.message("Já estás num grupo; desliga-o antes de criar outro."); return; }
        listener.message("Principal: a criar grupo Wi-Fi Direct dedicado (o Secundário é o único que envia um pedido).");
        manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { doCreateGroup(); }
            @Override public void onFailure(int code) { doCreateGroup(); }
        });
    }

    @SuppressLint("MissingPermission")
    private void doCreateGroup() {
        manager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                listener.message("O Principal criou o grupo Wi-Fi Direct. No Secundário procura o nome do Principal e envia um pedido de ligação.");
                checkConnection();
                diagnostic();
            }
            @Override public void onFailure(int code) {
                listener.message("ERRO na criação do grupo do Principal: " + explain(code) + ". Toca em DESLIGAR/REINICIAR para limpar ligações anteriores.");
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

    /**
     * Diagnostic values come directly from Android's Wi-Fi Direct framework.
     * Seeing a peer in requestPeers does not mean a link has been negotiated.
     */
    @SuppressLint("MissingPermission")
    void diagnostic() {
        // Wi-Fi Direct has its own link; an infrastructure AP connection is
        // separate, but concurrent radio/AP operation is OEM-dependent.
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            listener.message("Rádio Wi-Fi ativo: " + (wm.isWifiEnabled() ? "sim" : "NÃO") + ".");
            try {
                WifiInfo current = wm.getConnectionInfo();
                if (current != null && current.getNetworkId() >= 0) {
                    int mhz = current.getFrequency();
                    String band = mhz >= 5925 ? "6 GHz" : mhz >= 4900 ? "5 GHz" :
                            mhz >= 2300 ? "2,4 GHz" : "frequência indisponível";
                    listener.message("Wi-Fi normal: ligado a um ponto de acesso (" +
                            band + "). Isto NÃO significa que Wi-Fi Direct esteja ligado.");
                } else {
                    listener.message("Wi-Fi normal: sem associação a um AP visível à aplicação (ou estado ocultado pelas permissões do Android).");
                }
            } catch (SecurityException e) {
                listener.message("Estado do Wi-Fi normal indisponível por permissões.");
            }
        }
        if (!hasWifiPermission()) {
            listener.message("Diagnóstico indisponível: falta autorização de Dispositivos próximos / Localização.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            manager.requestDeviceInfo(channel, device -> {
                if (device != null) listener.message("Este dispositivo Wi-Fi Direct: " +
                        device.deviceName + " | " + peerStatus(device.status));
                else listener.message("O Android não forneceu informações deste dispositivo.");
            });
        }
        manager.requestConnectionInfo(channel, info -> {
            if (info == null) {
                listener.message("Ligação Wi-Fi Direct: sem informação do Android.");
            } else if (!info.groupFormed) {
                listener.message("Ligação Wi-Fi Direct: sem grupo formado.");
            } else {
                listener.message("Ligação Wi-Fi Direct: grupo formado; proprietário=" +
                        (info.isGroupOwner ? "este telemóvel" : "outro telemóvel") + ".");
            }
        });
        manager.requestGroupInfo(channel, group -> {
            if (group == null) {
                listener.message("Grupo: nenhum grupo P2P disponível.");
            } else {
                String owner = group.getOwner() == null ? "indisponível" : group.getOwner().deviceName;
                listener.message("Grupo: " + group.getNetworkName() + " | proprietário: " +
                        owner + " | clientes ligados: " + group.getClientList().size() + ".");
                if (Build.VERSION.SDK_INT >= 29)
                    listener.message("Frequência do grupo Wi-Fi Direct: " + group.getFrequency() + " MHz.");
                for (WifiP2pDevice device : group.getClientList()) {
                    listener.message("Cliente: " + device.deviceName + " | " + peerStatus(device.status));
                }
            }
        });
        manager.requestPeers(channel, list -> {
            if (list == null) return;
            listener.message("Peers detetados pelo Android: " + list.getDeviceList().size() + ".");
            for (WifiP2pDevice peer : list.getDeviceList()) {
                listener.message("Peer: " + peer.deviceName + " | estado: " + peerStatus(peer.status));
            }
        });
    }

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
