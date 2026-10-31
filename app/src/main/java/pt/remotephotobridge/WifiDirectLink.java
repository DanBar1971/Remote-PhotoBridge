package pt.remotephotobridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Direct local Wi-Fi P2P, with no cloud or Google Play dependencies. */
final class WifiDirectLink {
    interface Listener {
        void message(String text);
        void peers(List<WifiP2pDevice> devices);
        void connected(WifiP2pInfo info);
    }

    private final Context context;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Listener listener;
    private boolean registered;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == null) return;
            switch (intent.getAction()) {
                case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                        listener.message("Wi-Fi Direct desligado. Liga o Wi-Fi nas definições.");
                    break;
                case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                    requestPeers();
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
        this.context = context;
        this.listener = listener;
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) throw new IllegalStateException("Este Android não disponibiliza Wi-Fi Direct.");
        channel = manager.initialize(context, context.getMainLooper(), () -> listener.message("Canal Wi-Fi Direct perdido. Reabre a aplicação."));
    }

    void start() {
        if (registered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, f);
        registered = true;
        checkConnection();
    }

    void stop() {
        if (registered) {
            try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
            registered = false;
        }
    }

    @SuppressLint("MissingPermission")
    void createReceiverGroup() {
        if (!hasWifiPermission()) { listener.message("Autoriza a permissão Wi-Fi primeiro."); return; }
        manager.createGroup(channel, action("Modo compatibilidade: grupo Wi-Fi Direct criado. Procura novamente no Secundário."));
    }

    @SuppressLint("MissingPermission")
    void discover() {
        if (!hasWifiPermission()) { listener.message("Autoriza a permissão Wi-Fi primeiro."); return; }
        listener.message("A procurar dispositivos Wi-Fi Direct próximos...");
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                listener.message("Pesquisa Wi-Fi Direct iniciada. Mantém a aplicação aberta nos dois telemóveis.");
                requestPeers();
            }
            @Override public void onFailure(int reason) {
                listener.message("Falhou a pesquisa Wi-Fi Direct: " + errorReason(reason)
                        + ". Confirma o Wi-Fi e a Localização ativos.");
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void requestPeers() {
        if (!hasWifiPermission()) return;
        manager.requestPeers(channel, list -> {
            Collection<WifiP2pDevice> all = list.getDeviceList();
            listener.peers(new ArrayList<>(all));
            listener.message("Dispositivos Wi-Fi Direct visíveis: " + all.size() + ".");
        });
    }

    @SuppressLint("MissingPermission")
    void connectTo(WifiP2pDevice device, boolean principal) {
        if (!hasWifiPermission()) { listener.message("Autoriza a permissão Wi-Fi primeiro."); return; }
        WifiP2pConfig cfg = new WifiP2pConfig();
        cfg.deviceAddress = device.deviceAddress;
        // The receiving phone MUST be the group owner to keep photo transfer one-way.
        // Wi-Fi P2P negotiates ownership; these are preference values, not guarantees.
        cfg.groupOwnerIntent = principal ? 15 : 0;
        listener.message("A ligar a " + device.deviceName + " (via Wi-Fi Direct)...");
        manager.connect(channel, cfg, action("Pedido de ligação enviado. Aceita o convite Wi-Fi Direct no outro telemóvel, se aparecer."));
    }

    @SuppressLint("MissingPermission")
    void checkConnection() {
        if (!hasWifiPermission()) return;
        manager.requestConnectionInfo(channel, info -> {
            if (info != null && info.groupFormed) listener.connected(info);
        });
    }

    @SuppressLint("MissingPermission")
    void removeGroup() {
        if (hasWifiPermission()) manager.removeGroup(channel, action("Grupo Wi-Fi Direct terminado."));
    }

    private boolean hasWifiPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                : context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String errorReason(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "não suportado";
            case WifiP2pManager.BUSY: return "Android ocupado (BUSY)";
            default: return "código " + reason;
        }
    }

    private WifiP2pManager.ActionListener action(String successMessage) {
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { listener.message(successMessage); checkConnection(); }
            @Override public void onFailure(int reason) { listener.message("Erro Wi-Fi Direct (" + errorReason(reason) + "). Confirma Wi-Fi e Localização ligados."); }
        };
    }
}
