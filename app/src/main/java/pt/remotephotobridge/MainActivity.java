package pt.remotephotobridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * One package, two immutable data directions:
 *   PRINCIPAL runs a receiver-only server.
 *   SECONDARY only scans the local gallery and sends to the principal.
 */
public final class MainActivity extends Activity implements WifiDirectLink.Listener {
    private static final int BG = Color.rgb(14, 23, 38);
    private static final int CARD = Color.rgb(26, 39, 58);
    private static final int ACCENT = Color.rgb(52, 211, 191);
    private static final int TEXT = Color.rgb(235, 243, 249);
    private static final int MUTED = Color.rgb(164, 179, 197);
    private static final String MAIN = "principal", SECONDARY = "secundario";
    private final SecureRandom random = new SecureRandom();
    private final StringBuilder log = new StringBuilder();
    private SharedPreferences prefs;
    private WifiDirectLink wifi;
    private PhotoTransport transport;
    private TransferDb database;
    private String role;
    private String pairingPin;
    private InetAddress principalAddress;
    private boolean accepting;
    private TextView logView, pinView, connectionView;
    private LinearLayout discoveredList;
    private String lastPeerSignature = "";
    private EditText pinEntry;
    private Runnable pendingPermissionAction;
    private String pendingPermissionType;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("role", MODE_PRIVATE);
        database = new TransferDb(this);
        transport = new PhotoTransport(this, database, this::message);
        role = prefs.getString("role", null);
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            new AlertDialog.Builder(this).setTitle("Wi-Fi Direct indisponível")
                    .setMessage("Este telemóvel não declara suporte Wi-Fi Direct.")
                    .setPositiveButton("OK", (d,w) -> finish()).setCancelable(false).show();
            return;
        }
        try { wifi = new WifiDirectLink(this, this); }
        catch (Exception e) { new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("OK", (d,w)->finish()).show(); return; }
        if (role == null) showRoleChooser();
        else render();
    }

    @Override protected void onStart() { super.onStart(); if (wifi != null) wifi.start(); }
    @Override protected void onStop() { if (wifi != null) wifi.stop(); super.onStop(); }
    @Override protected void onDestroy() {
        if (transport != null) transport.shutdown();
        if (database != null) database.close();
        super.onDestroy();
    }

    /**
     * Full-screen role selection. Some OEM Android alert dialogs can collapse the
     * setItems list when system display/font scaling is increased. Keep the two
     * actions as explicit touch targets in our own activity content instead.
     */
    private void showRoleChooser() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(32), dp(20), dp(36));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("REMOTE", 30, TEXT, true);
        root.addView(title);
        root.addView(text("PHOTO BRIDGE", 30, ACCENT, true));
        TextView heading = text("Escolhe a função deste telemóvel", 22, TEXT, true);
        heading.setPadding(0, dp(28), 0, dp(12));
        root.addView(heading);
        root.addView(sub("Esta escolha define o sentido da transferência. Podes alterá-la mais tarde nas opções da aplicação."));

        LinearLayout mainCard = card(root);
        mainCard.addView(text("TELEMÓVEL PRINCIPAL", 18, ACCENT, true));
        mainCard.addView(sub("Só RECEBE fotografias do secundário. Guarda tudo diretamente na galeria deste telemóvel."));
        Button selectMain = button("ESCOLHER PRINCIPAL · RECEBER", true, () -> selectRole(MAIN));
        selectMain.getLayoutParams().height = dp(68);
        mainCard.addView(selectMain);

        LinearLayout secondaryCard = card(root);
        secondaryCard.addView(text("TELEMÓVEL SECUNDÁRIO", 18, ACCENT, true));
        secondaryCard.addView(sub("Só ENVIA fotografias da sua galeria para o telemóvel Principal. Não precisa de cartão SIM nem de Internet."));
        Button selectSecondary = button("ESCOLHER SECUNDÁRIO · ENVIAR", true, () -> selectRole(SECONDARY));
        selectSecondary.getLayoutParams().height = dp(68);
        secondaryCard.addView(selectSecondary);

        root.addView(sub("Comunicação direta por Wi-Fi Direct. Nenhuma fotografia é enviada para a Internet."));
    }

    private void selectRole(String selectedRole) {
        if (!MAIN.equals(selectedRole) && !SECONDARY.equals(selectedRole)) return;
        role = selectedRole;
        prefs.edit().putString("role", role).apply();
        render();
    }

    private void askChangeRole() {
        if (transport.isSending()) {
            message("Aguarda o fim da transferência antes de mudar a função.");
            return;
        }
        transport.stopReceiving();
        wifi.removeGroup();
        accepting = false;
        principalAddress = null;
        pairingPin = null;
        role = null;
        prefs.edit().remove("role").apply();
        showRoleChooser();
    }

    private void render() {
        log.setLength(0);
        lastPeerSignature = "";
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(32), dp(20), dp(32));
        scroll.addView(content);
        setContentView(scroll);

        TextView title = text("REMOTE", 30, TEXT, true);
        content.addView(title);
        TextView brand = text("PHOTO BRIDGE", 30, ACCENT, true);
        content.addView(brand);
        TextView tagline = text("Fotografias sem Internet. Por Wi-Fi Direct.", 13, MUTED, false);
        tagline.setPadding(0, dp(8), 0, dp(24));
        content.addView(tagline);

        LinearLayout roleCard = card(content);
        TextView roleTitle = text(MAIN.equals(role) ? "▣   TELEMÓVEL PRINCIPAL" : "▣   TELEMÓVEL SECUNDÁRIO", 16, ACCENT, true);
        roleCard.addView(roleTitle);
        roleCard.addView(sub(MAIN.equals(role)
                ? "Este telemóvel só recebe fotografias e guarda-as na galeria."
                : "Este telemóvel só envia fotografias da galeria para o Principal."));
        Button change = button("Alterar função deste telemóvel", false, this::askChangeRole);
        roleCard.addView(change);

        // Connection feedback must be visible WITHOUT scrolling; previously all
        // connect/callback messages appeared below the device list.
        LinearLayout statusCard = card(content);
        statusCard.addView(text("ESTADO DA LIGAÇÃO", 16, ACCENT, true));
        connectionView = text("Não ligado. Escolhe o outro telemóvel na pesquisa.", 15, TEXT, true);
        connectionView.setPadding(0, dp(8), 0, dp(6));
        statusCard.addView(connectionView);
        logView = sub("Sem atividades nesta sessão.");
        logView.setTextIsSelectable(true);
        statusCard.addView(logView);
        statusCard.addView(button("RECOLHER DIAGNÓSTICO DO WI-FI", false, () -> ensureWifiPermission(() -> {
            message("A consultar Wi-Fi normal, rádio, grupo P2P e estado dos dispositivos...");
            wifi.diagnostic();
        })));
        statusCard.addView(button("COPIAR DIAGNÓSTICO DA LIGAÇÃO", false, () -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "Remote PhotoBridge - Diagnóstico", "Papel: " + role
                                + "\nEstado: " + connectionView.getText()
                                + "\n" + log.toString()));
                Toast.makeText(this, "Diagnóstico copiado. Podes colar aqui no chat.", Toast.LENGTH_LONG).show();
            }
        }));

        if (MAIN.equals(role)) renderPrincipal(content); else renderSecondary(content);
        content.addView(sub("Sem nuvem · Sem conta Google · Fotos em qualidade original"));
    }

    private void renderPrincipal(LinearLayout root) {
        LinearLayout card = card(root);
        card.addView(text("1. Preparar para receber", 18, TEXT, true));
        card.addView(sub("Ativa o Wi-Fi. Se necessário, ativa a Localização do Android para a descoberta de dispositivos."));
        card.addView(button("ATIVAR RECEÇÃO", true, () -> ensureReceiverPermissions(() -> {
            if (accepting) {
                message("Já ativaste a receção. Não cries outro grupo; usa DIAGNÓSTICO ou reinicia antes.");
                wifi.diagnostic();
                return;
            }
            accepting = true;
            pairingPin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
            pinView.setText(pairingPin);
            showConnectionStatus("RECEÇÃO ATIVA: descoberta de telemóveis Wi-Fi Direct. Ainda sem ligação.");
            message("Principal: a limpar grupos P2P antigos e a iniciar a descoberta Wi-Fi Direct normal.");
            wifi.prepareReceiverDiscovery();
        })));
        card.addView(sub("O Principal fica visível durante a pesquisa e aguarda. Os dois telemóveis podem procurar, mas SÓ o Secundário deve tocar em LIGAR AO PRINCIPAL. Não é preciso criar um grupo manualmente."));
        card.addView(button("REPETIR PESQUISA WI-FI DIRECT", false, () -> ensureWifiPermission(() -> {
            if (!accepting) {
                Toast.makeText(this, "Ativa primeiro a receção.", Toast.LENGTH_LONG).show();
                return;
            }
            wifi.discover();
            message("Principal: repetida pesquisa de dispositivos Wi-Fi Direct.");
        })));
        discoveredList = new LinearLayout(this);
        discoveredList.setOrientation(LinearLayout.VERTICAL);
        card.addView(discoveredList);
        card.addView(button("DIAGNÓSTICO DO GRUPO WI-FI DIRECT", false, () -> ensureWifiPermission(() -> {
            wifi.diagnostic();
            wifi.checkConnection();
        })));
        card.addView(sub("O rádio Wi-Fi deve estar ligado nos dois, mesmo sem ligação aos pontos de acesso. Se aparecer o nome do Secundário abaixo é sinal de que o Principal também o está a descobrir."));
        card.addView(button("VERIFICAR ESTADO DO EMPARELHAMENTO", false, () -> ensureWifiPermission(() -> {
            wifi.checkConnection();
            wifi.diagnostic();
            wifi.watchForConnection();
            message("A verificar se existe um grupo Wi-Fi Direct com cliente ligado...");
        })));
        card.addView(button("ABRIR DEFINIÇÕES WI-FI DO ANDROID", false, () -> {
            message("Vai às definições de Wi-Fi e verifica se aparece um pedido de Wi-Fi Direct pendente. Volta à app para confirmar o estado.");
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }));
        card.addView(button("DESLIGAR / REINICIAR LIGAÇÃO", false, () -> {
            accepting = false;
            pairingPin = null;
            pinView.setText("------");
            transport.stopReceiving();
            wifi.disconnect();
            showConnectionStatus("A reiniciar o Wi-Fi Direct. Quando terminar, volta a ATIVAR RECEÇÃO.");
            message("Reinício iniciado. Espera pela mensagem de desligado e só depois ativa a receção.");
        }));
        card.addView(button("PARAR DE RECEBER", false, () -> {
            accepting = false;
            pairingPin = null;
            transport.stopReceiving();
            wifi.disconnect();
            pinView.setText("------");
            showConnectionStatus("Receção desligada.");
            message("Receção parada.");
        }));
        card.addView(sub("PIN de emparelhamento (introduzir no secundário):"));
        pinView = text("------", 34, ACCENT, true);
        pinView.setGravity(Gravity.CENTER);
        pinView.setPadding(0, dp(16), 0, dp(16));
        card.addView(pinView);
        card.addView(sub("As fotos recebidas ficam na Galeria, em Pictures / Remote PhotoBridge. As originais continuam no secundário."));
    }

    private void renderSecondary(LinearLayout root) {
        LinearLayout peersCard = card(root);
        peersCard.addView(text("1. Encontrar o Principal", 18, TEXT, true));
        peersCard.addView(sub("SÓ ESTE SECUNDÁRIO INICIA A LIGAÇÃO. Ativa receção no Principal, espera que o grupo seja criado, procura aqui e toca em LIGAR AO PRINCIPAL uma única vez. Se estiverem em routers diferentes e não ligarem, testa ambos desligados do router, MAS com o Wi-Fi ligado."));
        peersCard.addView(button("PROCURAR DISPOSITIVOS", true, () -> ensureWifiPermission(() -> {
            wifi.discover();
            message("Se não aparecer, ativa a Localização e confirma o Wi-Fi em ambos.");
        })));
        discoveredList = new LinearLayout(this);
        discoveredList.setOrientation(LinearLayout.VERTICAL);
        peersCard.addView(discoveredList);
        peersCard.addView(button("VERIFICAR ESTADO DO EMPARELHAMENTO", false, () -> {
            wifi.checkConnection();
            wifi.watchForConnection();
            showConnectionStatus("A verificar se o Android estabeleceu a ligação Wi-Fi Direct...");
        }));
        peersCard.addView(button("DESLIGAR / REINICIAR LIGAÇÃO", false, () -> {
            principalAddress = null;
            wifi.disconnect();
            showConnectionStatus("Ligação reiniciada. Volta a procurar dispositivos.");
        }));

        LinearLayout syncCard = card(root);
        syncCard.addView(text("2. Enviar fotografias", 18, TEXT, true));
        syncCard.addView(sub("Só podes enviar depois de ambos mostrarem LIGADO. O PIN do Principal NÃO confirma por si só a ligação. Apenas são enviadas as fotografias autorizadas neste Android."));
        pinEntry = new EditText(this);
        pinEntry.setSingleLine(true);
        pinEntry.setHint("PIN de 6 dígitos do Principal");
        pinEntry.setHintTextColor(MUTED);
        pinEntry.setTextColor(TEXT);
        pinEntry.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinEntry.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        syncCard.addView(pinEntry);
        syncCard.addView(button("TESTAR COMUNICAÇÃO (SEM ENVIAR FOTOS)", false, () -> {
            if (principalAddress == null) {
                showConnectionStatus("TESTE IMPOSSÍVEL: os telemóveis ainda não estão ligados por Wi-Fi Direct.");
                Toast.makeText(this, "Liga primeiro os dois telemóveis.", Toast.LENGTH_LONG).show();
                wifi.diagnostic();
                return;
            }
            String pin = pinEntry.getText().toString().trim();
            if (!pin.matches("[0-9]{6}")) {
                Toast.makeText(this, "Introduz primeiro o PIN de seis dígitos do Principal.", Toast.LENGTH_LONG).show();
                return;
            }
            showConnectionStatus("A testar a comunicação com o Principal, sem transferir fotografias...");
            transport.testConnection(principalAddress, pin);
        }));
        syncCard.addView(button("SINCRONIZAR FOTOGRAFIAS", true, () -> {
            if (principalAddress == null) {
                showConnectionStatus("Ainda NÃO está ligado. Toca em LIGAR A: [nome do Principal] e aceita o convite.");
                Toast.makeText(this, "Primeiro liga os dois telemóveis por Wi-Fi Direct.", Toast.LENGTH_LONG).show();
                return;
            }
            String pin = pinEntry.getText().toString().trim();
            if (!pin.matches("[0-9]{6}")) {
                Toast.makeText(this, "Introduz o PIN de 6 dígitos mostrado no Principal.", Toast.LENGTH_LONG).show();
                return;
            }
            ensureGalleryPermission(() -> {
                if (!hasFullGalleryPermission()) message("Acesso parcial: só as fotografias autorizadas serão sincronizadas.");
                showConnectionStatus("Ligado. A enviar fotografias para o Principal...");
                Toast.makeText(this, "Sincronização iniciada", Toast.LENGTH_SHORT).show();
                transport.send(principalAddress, pin);
            });
        }));
        syncCard.addView(sub("Fotografias já recebidas são ignoradas por SHA-256, sem transmissão repetida dos ficheiros."));
    }

    private void ensureWifiPermission(Runnable action) {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
        ensurePermissions("wifi", new String[]{permission}, action);
    }

    private void ensureReceiverPermissions(Runnable action) {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ensurePermissions("receiver", new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, () -> ensureWifiPermission(action));
        } else ensureWifiPermission(action);
    }

    private void ensureGalleryPermission(Runnable action) {
        if (hasAnyGalleryPermission()) { action.run(); return; }
        if (Build.VERSION.SDK_INT >= 34) {
            ensurePermissions("gallery", new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, action);
        } else if (Build.VERSION.SDK_INT >= 33) {
            ensurePermissions("gallery", new String[]{Manifest.permission.READ_MEDIA_IMAGES}, action);
        } else {
            ensurePermissions("gallery", new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, action);
        }
    }

    private boolean hasFullGalleryPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAnyGalleryPermission() {
        return hasFullGalleryPermission() || Build.VERSION.SDK_INT >= 34 &&
                checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissions(String type, String[] permissions, Runnable action) {
        boolean allowed = true;
        for (String p : permissions) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) allowed = false;
        if (allowed) { action.run(); return; }
        pendingPermissionAction = action;
        pendingPermissionType = type;
        requestPermissions(permissions, 41);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != 41) return;
        boolean allowed = "gallery".equals(pendingPermissionType) ? hasAnyGalleryPermission()
                : "receiver".equals(pendingPermissionType)
                    ? checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    : hasWifiPermission();
        Runnable action = pendingPermissionAction;
        pendingPermissionAction = null;
        pendingPermissionType = null;
        if (allowed && action != null) action.run();
        else message("Permissão necessária não concedida. Abre as definições para permitir o acesso.");
    }

    private boolean hasWifiPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void message(String msg) {
        runOnUiThread(() -> {
            if (log.length() > 3_000) log.delete(0, Math.max(0, log.length() - 2_000));
            log.append("• ").append(msg).append('\n');
            if (logView != null) logView.setText(log.toString());
            if (msg.startsWith("TESTE OK:") || msg.startsWith("TESTE FALHOU:")) {
                if (connectionView != null) connectionView.setText(msg);
            } else if (msg.startsWith("TRANSFERÊNCIA INTERROMPIDA") || msg.startsWith("Não foi possível abrir a receção")) {
                if (connectionView != null) connectionView.setText(msg);
            } else if (msg.startsWith("CONCLUÍDO:") || msg.startsWith("RECEÇÃO CONCLUÍDA:")) {
                if (connectionView != null) connectionView.setText(msg);
            } else if (msg.startsWith("Evento Wi-Fi Direct:") ||
                    msg.startsWith("Pedido de emparelhamento aceite") ||
                    msg.startsWith("A ligação a ") || msg.startsWith("Falha ao ligar")) {
                if (connectionView != null) connectionView.setText(msg);
            } else if (msg.startsWith("Pronto para receber fotos")) {
                if (connectionView != null) connectionView.setText("RECEÇÃO PRONTA. PIN no ecrã. Aguarda o envio.");
            }
        });
    }

    @Override public void peers(List<WifiP2pDevice> devices) {
        runOnUiThread(() -> {
            // Never offer peer connection buttons on the Principal: it already
            // owns the Wi-Fi Direct group and simultaneous connect() calls race.
            if (role == null || discoveredList == null) return;
            // Refresh only when peers actually change. Recreating buttons on
            // every Wi-Fi peer broadcast can swallow touch-up and make taps fail.
            List<String> addresses = new ArrayList<>();
            for (WifiP2pDevice d : devices) addresses.add(d.deviceAddress + "/" + d.deviceName);
            Collections.sort(addresses);
            String signature = android.text.TextUtils.join(";", addresses);
            if (signature.equals(lastPeerSignature) && discoveredList.getChildCount() > 0) return;
            lastPeerSignature = signature;
            discoveredList.removeAllViews();
            if (devices.isEmpty()) {
                discoveredList.addView(sub("Nenhum outro telemóvel detetado nesta pesquisa. O rádio Wi-Fi e a Localização devem estar ligados; repete a pesquisa em ambos."));
                return;
            }
            discoveredList.addView(sub("Encontrados " + devices.size() + " dispositivos Wi-Fi Direct." +
                    (MAIN.equals(role) ? " Neste Principal não precisas tocar em ligar." : " Seleciona apenas o Principal, não a TV.")));
            for (WifiP2pDevice device : devices) {
                String name = device.deviceName == null || device.deviceName.isEmpty()
                        ? "Dispositivo Android" : device.deviceName;
                if (MAIN.equals(role)) {
                    discoveredList.addView(sub("Dispositivo detetado: " + name));
                } else {
                    discoveredList.addView(button("LIGAR AO PRINCIPAL: " + name, false, () -> ensureWifiPermission(() -> {
                        showConnectionStatus("A LIGAR AO PRINCIPAL: " + name + ". Aguarda a confirmação do Android no outro telemóvel.");
                        Toast.makeText(this, "Pedido de ligação enviado a " + name, Toast.LENGTH_LONG).show();
                        message("Secundário iniciou ligação ao " + name + ". Não carregues em ligar no Principal.");
                        wifi.connectTo(device);
                    })));
                }
            }
        });
    }

    @Override public void connected(WifiP2pInfo info) {
        runOnUiThread(() -> {
            if (MAIN.equals(role) && accepting && info.groupFormed && info.isGroupOwner) {
                showConnectionStatus("LIGADO. Principal pronto para receber fotografias. Introduz o PIN no Secundário.");
                // Android 14+ may anonymize groupOwnerAddress on the owner. Listen on
                // all local interfaces; the PIN gates incoming transfer requests.
                if (pairingPin != null) transport.receive(null, pairingPin);
            } else if (MAIN.equals(role) && accepting && info.groupFormed && !info.isGroupOwner) {
                showConnectionStatus("ERRO: o Principal não ficou como proprietário do grupo. Reinicia a ligação e liga a partir do Principal.");
                message("Este Principal não ficou como proprietário do grupo Wi-Fi Direct. Pára a receção e volta a tentar, ligando a partir do Principal.");
            } else if (SECONDARY.equals(role) && info.groupFormed && !info.isGroupOwner) {
                principalAddress = info.groupOwnerAddress;
                showConnectionStatus("LIGADO AO PRINCIPAL. Introduz o PIN e toca em SINCRONIZAR FOTOGRAFIAS.");
                message("Ligação Wi-Fi Direct estabelecida. Introduz o PIN e sincroniza.");
            } else if (SECONDARY.equals(role) && info.groupFormed && info.isGroupOwner) {
                showConnectionStatus("ERRO: o Secundário tornou-se proprietário do grupo. Reinicia e liga a partir do Principal.");
                message("O Secundário tornou-se proprietário. Usa DESLIGAR em ambos e inicia ligação no Principal.");
            }
        });
    }

    @Override public void waitingForPeer() {
        runOnUiThread(() -> {
            if (MAIN.equals(role) && accepting) {
                showConnectionStatus("GRUPO WI-FI DIRECT CRIADO, MAS SEM SECUNDÁRIO. Aguarda o convite/ligação do outro telemóvel.");
            }
        });
    }

    @Override public void disconnected() {
        runOnUiThread(() -> {
            principalAddress = null;
            transport.stopReceiving();
            if (MAIN.equals(role)) showConnectionStatus("Sem Secundário ligado. Ativa a receção após terminares qualquer reinício.");
            else showConnectionStatus("Sem ligação Wi-Fi Direct. Procura e liga novamente ao Principal.");
        });
    }

    private void showConnectionStatus(String state) {
        runOnUiThread(() -> {
            if (connectionView != null) connectionView.setText(state);
        });
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable b = new GradientDrawable();
        b.setColor(CARD);
        b.setCornerRadius(dp(18));
        b.setStroke(dp(1), Color.rgb(43, 64, 82));
        c.setBackground(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(16);
        root.addView(c, p);
        return c;
    }

    private Button button(String label, boolean prominent, Runnable click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(prominent ? BG : TEXT);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(prominent ? ACCENT : Color.rgb(43, 62, 83));
        bg.setCornerRadius(dp(12));
        // Visible press feedback, including on devices where a plain
        // GradientDrawable doesn't show that the button was touched.
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(100, 255, 255, 255)), bg, null));
        b.setClickable(true);
        b.setMinHeight(dp(52));
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(4);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            click.run();
        });
        return b;
    }

    private TextView sub(String value) { TextView t = text(value, 13, MUTED, false); t.setPadding(0, dp(10), 0, dp(4)); return t; }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private int dp(float dp) { return (int) (getResources().getDisplayMetrics().density * dp + 0.5f); }
}
