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
import java.util.LinkedHashMap;
import java.util.Map;
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
    // A single empty requestPeers() result must not remove a device the user
    // was about to tap. Keep the last known peers until role change/reset.
    private final Map<String, WifiP2pDevice> knownPeers = new LinkedHashMap<>();
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
    // Keep the P2P receiver registered while the Android system displays its
    // pairing authorization dialog: onStop may otherwise lose the connection
    // broadcast. Unregister only when the activity is destroyed.
    @Override protected void onStop() { super.onStop(); }
    @Override protected void onDestroy() {
        if (wifi != null) wifi.stop();
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
        knownPeers.clear();
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

        // Show the connection state first. Advanced diagnostics stay collapsed.
        LinearLayout statusCard = card(content);
        statusCard.addView(text("LIGAÇÃO WI-FI DIRECT", 15, ACCENT, true));
        connectionView = text("Ainda não ligado aos dois telemóveis.", 16, TEXT, true);
        connectionView.setPadding(0, dp(8), 0, dp(6));
        statusCard.addView(connectionView);

        if (MAIN.equals(role)) renderPrincipal(content); else renderSecondary(content);

        LinearLayout advancedCard = card(content);
        LinearLayout advancedContent = new LinearLayout(this);
        advancedContent.setOrientation(LinearLayout.VERTICAL);
        advancedContent.setVisibility(View.GONE);
        advancedCard.addView(button("DETALHES E DIAGNÓSTICO  ▾", false, () ->
            advancedContent.setVisibility(
                advancedContent.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        advancedCard.addView(advancedContent);
        advancedContent.addView(button("RECOLHER DIAGNÓSTICO", false, () -> ensureWifiPermission(() -> wifi.diagnostic())));
        advancedContent.addView(button("COPIAR DIAGNÓSTICO", false, () -> {
            wifi.diagnostic();
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "PhotoBridge diagnóstico", "Papel: " + role +
                    "\nEstado: " + connectionView.getText() + "\n" + log));
                Toast.makeText(this, "Diagnóstico copiado.", Toast.LENGTH_SHORT).show();
            }
        }));
        advancedContent.addView(button("DESLIGAR / REINICIAR LIGAÇÃO", false, () -> {
            transport.stopReceiving();
            principalAddress = null;
            wifi.disconnect();
            if (MAIN.equals(role)) {
                accepting = false;
                pairingPin = null;
                if (pinView != null) pinView.setText("------");
            }
            showConnectionStatus("Ligação terminada. Volta a ativar e procurar.");
            message("Ligação reiniciada manualmente.");
        }));
        logView = sub("Sem atividades.");
        logView.setTextIsSelectable(true);
        advancedContent.addView(logView);
        content.addView(sub("Sem nuvem · Sem conta Google · Fotos em qualidade original"));
    }

    private void renderPrincipal(LinearLayout root) {
        LinearLayout card = card(root);
        card.addView(text("RECEBER FOTOGRAFIAS", 19, TEXT, true));
        card.addView(sub("Ativa a receção. Quando aparecer o Secundário, podes tocar no seu nome para ligar. Ou deixa o Secundário iniciar a ligação. Apenas UM dos dois deve tocar em LIGAR."));

        card.addView(button("ATIVAR RECEÇÃO E PROCURAR", true, () -> ensureReceiverPermissions(() -> {
            if (!accepting) {
                accepting = true;
                pairingPin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
                pinView.setText(pairingPin);
                showConnectionStatus("Receção ativa. À procura do outro telemóvel...");
                wifi.prepareReceiverDiscovery();
                wifi.watchForConnection();
            } else {
                wifi.discover();
                showConnectionStatus("Receção ativa. A atualizar a lista de telemóveis...");
            }
        })));
        discoveredList = new LinearLayout(this);
        discoveredList.setOrientation(LinearLayout.VERTICAL);
        card.addView(discoveredList);
        card.addView(sub("Se o Secundário já vê este Principal, podes iniciar a ligação apenas no Secundário. O Principal não precisa de ter o nome na lista."));
        card.addView(sub("CÓDIGO PARA AUTORIZAR FOTOGRAFIAS:"));
        pinView = text("------", 32, ACCENT, true);
        pinView.setGravity(Gravity.CENTER);
        pinView.setPadding(0, dp(14), 0, dp(14));
        card.addView(pinView);
        card.addView(button("PARAR RECEÇÃO", false, () -> {
            accepting = false;
            pairingPin = null;
            transport.stopReceiving();
            wifi.disconnect();
            pinView.setText("------");
            showConnectionStatus("Receção parada.");
        }));
        card.addView(sub("As fotografias recebidas ficam na galeria. Os originais não são apagados no Secundário."));
    }

    private void renderSecondary(LinearLayout root) {
        LinearLayout peersCard = card(root);
        peersCard.addView(text("ENVIAR FOTOGRAFIAS", 19, TEXT, true));
        peersCard.addView(sub("No Principal ativa a receção. Depois procura aqui e toca no nome do Principal para ligar. Não carregues em LIGAR nos dois telemóveis."));
        peersCard.addView(button("PROCURAR TELEMÓVEL PRINCIPAL", true, () -> ensureWifiPermission(() -> {
            wifi.discover();
            showConnectionStatus("A procurar o Principal por Wi-Fi Direct...");
        })));
        discoveredList = new LinearLayout(this);
        discoveredList.setOrientation(LinearLayout.VERTICAL);
        peersCard.addView(discoveredList);

        LinearLayout syncCard = card(root);
        syncCard.addView(text("SINCRONIZAR FOTOGRAFIAS", 18, TEXT, true));
        syncCard.addView(sub("Depois de surgir LIGADO, introduz o código mostrado no Principal."));
        pinEntry = new EditText(this);
        pinEntry.setSingleLine(true);
        pinEntry.setHint("Código de 6 dígitos do Principal");
        pinEntry.setHintTextColor(MUTED);
        pinEntry.setTextColor(TEXT);
        pinEntry.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinEntry.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        syncCard.addView(pinEntry);
        syncCard.addView(button("ENVIAR FOTOGRAFIAS", true, () -> {
            if (principalAddress == null) {
                showConnectionStatus("Ainda não há ligação Wi-Fi Direct. Escolhe o Principal acima.");
                Toast.makeText(this, "Primeiro liga os dois telemóveis.", Toast.LENGTH_LONG).show();
                return;
            }
            String pin = pinEntry.getText().toString().trim();
            if (!pin.matches("[0-9]{6}")) {
                Toast.makeText(this, "Introduz o código de 6 dígitos.", Toast.LENGTH_LONG).show();
                return;
            }
            ensureGalleryPermission(() -> {
                showConnectionStatus("A transferir fotografias para o Principal...");
                transport.send(principalAddress, pin);
            });
        }));
        syncCard.addView(sub("Apenas fotografias novas; sem Internet e sem eliminar originais."));
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
            if (role == null || discoveredList == null) return;
            // Android sometimes sends an empty interim list while scanning.
            // Never wipe a recently visible phone, especially during touch-down.
            if (devices != null) {
                for (WifiP2pDevice d : devices) {
                    if (d.deviceAddress != null && !d.deviceAddress.isEmpty())
                        knownPeers.put(d.deviceAddress, d);
                }
            }
            List<String> addresses = new ArrayList<>();
            for (WifiP2pDevice d : knownPeers.values())
                addresses.add(d.deviceAddress + "/" + d.deviceName);
            Collections.sort(addresses);
            String signature = android.text.TextUtils.join(";", addresses);
            if (signature.equals(lastPeerSignature) && discoveredList.getChildCount() > 0) return;
            lastPeerSignature = signature;
            discoveredList.removeAllViews();
            if (knownPeers.isEmpty()) {
                discoveredList.addView(sub("À procura do outro telemóvel…"));
                return;
            }
            discoveredList.addView(sub("Escolhe o outro telemóvel (só um lado deve iniciar a ligação):"));
            int visiblePhones = 0;
            for (WifiP2pDevice d : knownPeers.values()) {
                String name = d.deviceName == null || d.deviceName.isEmpty()
                        ? "Android próximo" : d.deviceName;
                // TVs and smart displays are peers too, but they cannot run
                // this app. Keep them out of the one-tap phone pairing list.
                if (name.startsWith("[TV]") || name.startsWith("DIRECT-") && name.toLowerCase(Locale.ROOT).contains("tv"))
                    continue;
                visiblePhones++;
                final boolean isPrincipal = MAIN.equals(role);
                discoveredList.addView(button("LIGAR: " + name, false, () -> ensureWifiPermission(() -> {
                    if (isPrincipal && !accepting) {
                        Toast.makeText(this, "Primeiro ativa a receção neste Principal.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showConnectionStatus("A ligar a " + name + " por Wi-Fi Direct…");
                    message("Pedido de ligação para " + name + ". Não toques em LIGAR no outro telemóvel.");
                    wifi.connectTo(d, isPrincipal);
                })));
            }
            if (visiblePhones == 0)
                discoveredList.addView(sub("Só foram detetados equipamentos que não são Androids compatíveis (por exemplo, a TV). Repete a pesquisa."));
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
                showConnectionStatus("Ligação criada, mas o Android atribuiu a rede ao Secundário. Recomeça e liga a partir do Principal.");
                message("Grupo ligado, mas este Principal não é proprietário. Liga a partir do Principal, que pede prioridade de proprietário.");
            } else if (SECONDARY.equals(role) && info.groupFormed && !info.isGroupOwner) {
                principalAddress = info.groupOwnerAddress;
                showConnectionStatus("LIGADO AO PRINCIPAL. Introduz o PIN e toca em SINCRONIZAR FOTOGRAFIAS.");
                message("Ligação Wi-Fi Direct estabelecida. Introduz o PIN e sincroniza.");
            } else if (SECONDARY.equals(role) && info.groupFormed && info.isGroupOwner) {
                showConnectionStatus("Grupo formado, mas este Secundário é proprietário. No próximo emparelhamento, inicia a ligação a partir do Principal.");
                message("Negociação atribuiu proprietário ao Secundário. A transferência requer o Principal como proprietário.");
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
