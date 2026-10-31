package pt.remotephotobridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User flow copied from the working SaúdePrática Nearby synchronization:
 * Principal advertises (only receive), Secundário discovers and sends.
 * Authentication digits must be verified on BOTH phones.
 * Date selection lives only on the Secondary. Default: TODAY, never all photos.
 */
public final class MainActivity extends Activity implements NearbyPhotoLink.Listener {
    private static final int BG = Color.rgb(14, 23, 38);
    private static final int CARD = Color.rgb(26, 39, 58);
    private static final int ACCENT = Color.rgb(52, 211, 191);
    private static final int TEXT = Color.rgb(235, 243, 249);
    private static final int MUTED = Color.rgb(164, 179, 197);
    private static final String PRINCIPAL = "principal", SECONDARY = "secundario";
    private static final int PERMISSION_CODE = 2048;
    private static final int[] DAYS = {1, 3, 7, 14, 30};
    private static final String[] PERIODS = {
            "Hoje", "Últimos 3 dias (inclui hoje)", "Últimos 7 dias (inclui hoje)",
            "Últimos 14 dias (inclui hoje)", "Últimos 30 dias (inclui hoje)"
    };

    private SharedPreferences prefs;
    private NearbyPhotoLink nearby;
    private PhotoTransport photos;
    private TransferDb db;
    private ExecutorService executor;
    private String role;
    private Runnable afterPermission;
    private TextView statusView, detailsView;
    private LinearLayout peersView;
    private Spinner periodView;
    private boolean receiving;
    private final StringBuilder log = new StringBuilder();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("photobridge_options", MODE_PRIVATE);
        role = getSharedPreferences("role", MODE_PRIVATE).getString("role", null);
        db = new TransferDb(this);
        photos = new PhotoTransport(this, db, this::state);
        executor = Executors.newSingleThreadExecutor();
        int services = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
        if (services != ConnectionResult.SUCCESS) {
            new AlertDialog.Builder(this).setTitle("Google Play Services indisponíveis")
                .setMessage("Este sistema precisa dos serviços Google Play para utilizar a mesma ligação Nearby da SaúdePrática. Não precisa de Internet durante a transferência.")
                .setPositiveButton("OK", (dialog, which) -> finish()).show();
            return;
        }
        nearby = new NearbyPhotoLink(this, photos, db, this);
        if (PRINCIPAL.equals(role) || SECONDARY.equals(role)) render();
        else showRoleChooser();
    }

    @Override protected void onDestroy() {
        if (nearby != null) nearby.shutdown();
        if (photos != null) photos.shutdown();
        if (db != null) db.close();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout screen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(36));
        scroll.addView(root);
        setContentView(scroll);
        root.addView(text("REMOTE", 29, TEXT, true));
        root.addView(text("PHOTO BRIDGE", 29, ACCENT, true));
        TextView tag = text("Fotografias sem Internet · Ligação Nearby", 13, MUTED, false);
        tag.setPadding(0, dp(7), 0, dp(24));
        root.addView(tag);
        return root;
    }

    private void showRoleChooser() {
        LinearLayout root = screen();
        root.addView(text("Qual é a função deste telemóvel?", 20, TEXT, true));
        LinearLayout mainCard = card(root);
        mainCard.addView(text("PRINCIPAL", 19, ACCENT, true));
        mainCard.addView(note("Recebe fotografias e guarda-as na Galeria. Nunca envia fotografias."));
        mainCard.addView(button("PRINCIPAL · SÓ RECEBER", true, () -> selectRole(PRINCIPAL)));
        LinearLayout secondaryCard = card(root);
        secondaryCard.addView(text("SECUNDÁRIO", 19, ACCENT, true));
        secondaryCard.addView(note("Só envia as fotografias que escolheres pelo período. Conserva os originais."));
        secondaryCard.addView(button("SECUNDÁRIO · SÓ ENVIAR", true, () -> selectRole(SECONDARY)));
        root.addView(note("Não necessita de SIM, Internet nem do mesmo router. Bluetooth e Wi-Fi devem estar ativos."));
    }

    private void selectRole(String chosen) {
        role = chosen;
        getSharedPreferences("role", MODE_PRIVATE).edit().putString("role", role).apply();
        receiving = false;
        if (nearby != null) nearby.close();
        render();
    }

    private void render() {
        log.setLength(0);
        peersView = null;
        periodView = null;
        LinearLayout root = screen();
        LinearLayout roleCard = card(root);
        roleCard.addView(text(PRINCIPAL.equals(role) ? "TELEMÓVEL PRINCIPAL" : "TELEMÓVEL SECUNDÁRIO", 17, ACCENT, true));
        roleCard.addView(note(PRINCIPAL.equals(role) ?
                "Este telemóvel só recebe fotografias." :
                "Este telemóvel só envia fotografias do período selecionado."));
        roleCard.addView(button("Alterar função", false, () -> {
            if (nearby != null && nearby.isBusy()) {
                state("Aguarda pelo fim do envio antes de alterar a função.");
                return;
            }
            new AlertDialog.Builder(this).setTitle("Alterar função?")
                    .setMessage("A ligação atual será terminada.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Alterar", (d,w) -> {
                        if (nearby != null) nearby.close();
                        getSharedPreferences("role", MODE_PRIVATE).edit().remove("role").apply();
                        role = null;
                        showRoleChooser();
                    }).show();
        }));

        LinearLayout stateCard = card(root);
        stateCard.addView(text("ESTADO DA LIGAÇÃO", 14, ACCENT, true));
        statusView = text("Não ligado. Segue os passos abaixo.", 16, TEXT, true);
        stateCard.addView(statusView);

        if (PRINCIPAL.equals(role)) renderPrincipal(root);
        else renderSecondary(root);

        LinearLayout advanced = card(root);
        LinearLayout diagnostics = new LinearLayout(this);
        diagnostics.setOrientation(LinearLayout.VERTICAL);
        diagnostics.setVisibility(View.GONE);
        advanced.addView(button("DIAGNÓSTICO E OPÇÕES  ▾", false, () ->
                diagnostics.setVisibility(diagnostics.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        advanced.addView(diagnostics);
        diagnostics.addView(button("TERMINAR LIGAÇÃO", false, () -> {
            if (nearby != null) nearby.close();
            receiving = false;
            statusView.setText("Ligação terminada. Podes começar novamente.");
        }));
        diagnostics.addView(button("COPIAR DIAGNÓSTICO", false, () -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("PhotoBridge", "Papel: " + role + "\n" + log));
                Toast.makeText(this, "Diagnóstico copiado.", Toast.LENGTH_SHORT).show();
            }
        }));
        detailsView = note("Sem atividades nesta sessão.");
        detailsView.setTextIsSelectable(true);
        diagnostics.addView(detailsView);
        root.addView(note("Fotografias em qualidade original · Sem envio para a Internet"));
    }

    private void renderPrincipal(LinearLayout root) {
        LinearLayout card = card(root);
        card.addView(text("1. PREPARAR RECEÇÃO", 18, TEXT, true));
        card.addView(note("Toca em RECEBER e deixa este ecrã aberto. O outro telemóvel encontra este Principal."));
        card.addView(button("RECEBER FOTOGRAFIAS", true, () -> withNearbyPermissions(() -> {
            if (nearby == null) return;
            receiving = true;
            nearby.receive();
        })));
        card.addView(note("2. Confirma no ecrã o mesmo código de segurança apresentado no Secundário."));
        card.addView(note("3. As fotografias recebidas são guardadas em Galeria → Pictures / Remote PhotoBridge. Os originais ficam no Secundário."));
    }

    private void renderSecondary(LinearLayout root) {
        LinearLayout card = card(root);
        card.addView(text("1. LIGAR AO PRINCIPAL", 18, TEXT, true));
        card.addView(note("No Principal toca em RECEBER. Depois procura-o aqui; só aparecem telemóveis a executar Remote PhotoBridge."));
        card.addView(button("PROCURAR PRINCIPAL", true, () -> withNearbyPermissions(() -> {
            if (nearby != null) nearby.search();
        })));
        peersView = new LinearLayout(this);
        peersView.setOrientation(LinearLayout.VERTICAL);
        card.addView(peersView);

        LinearLayout send = card(root);
        send.addView(text("2. ESCOLHER FOTOGRAFIAS", 18, TEXT, true));
        send.addView(note("Só serão enviadas fotografias do período indicado. A opção inicial é Hoje. Nunca envia toda a galeria."));
        ArrayAdapter<String> periods = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, PERIODS) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView item = (TextView) super.getView(position, convertView, parent);
                item.setTextColor(TEXT);
                item.setTextSize(17);
                item.setPadding(dp(10), dp(18), dp(10), dp(18));
                return item;
            }
        };
        periods.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        periodView = new Spinner(this);
        periodView.setAdapter(periods);
        int storedDays = prefs.getInt("days", 1);
        int savedIndex = 0;
        for (int i = 0; i < DAYS.length; i++) if (DAYS[i] == storedDays) savedIndex = i;
        periodView.setSelection(savedIndex);
        send.addView(periodView);
        send.addView(button("VER FOTOS DESTE PERÍODO", false, () ->
                withGalleryPermission(false)));
        send.addView(button("SINCRONIZAR FOTOGRAFIAS", true, () ->
                withGalleryPermission(true)));
        send.addView(note("Antes de enviar, a aplicação mostra quantas fotografias encontrou. As fotos antigas ficam de fora e as já recebidas não são duplicadas."));
    }

    private int selectedDays() {
        if (periodView == null) return 1;
        int pos = periodView.getSelectedItemPosition();
        if (pos < 0 || pos >= DAYS.length) return 1;
        int days = DAYS[pos];
        prefs.edit().putInt("days", days).apply();
        return days;
    }

    private void withGalleryPermission(boolean send) {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (Build.VERSION.SDK_INT >= 34)
                permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        boolean granted = false;
        for (String p : permissions) if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) granted = true;
        if (granted) preview(send);
        else askPermissions(permissions.toArray(new String[0]), () -> preview(send));
    }

    private void preview(boolean send) {
        int days = selectedDays();
        state("A contar fotografias do período selecionado...");
        executor.execute(() -> {
            try {
                List<PhotoTransport.Photo> selection = photos.getPhotos(days);
                long total = 0;
                for (PhotoTransport.Photo photo : selection) total += photo.size;
                final long bytes = total;
                runOnUiThread(() -> {
                    String summary = selection.size() + " fotografias (" +
                            String.format(Locale.getDefault(), "%.1f", bytes / 1048576.0) +
                            " MB) em: " + PERIODS[periodIndex(days)] + ".";
                    state(summary);
                    if (!send || selection.isEmpty()) return;
                    if (nearby == null || !nearby.isConnected()) {
                        state("Escolhe primeiro o Principal e confirma o código de segurança.");
                        return;
                    }
                    new AlertDialog.Builder(this)
                        .setTitle("Confirmar envio?")
                        .setMessage(summary + "\n\nSó estas fotografias serão enviadas. Os originais não serão apagados.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Enviar " + selection.size(), (d,w) -> nearby.sendPhotos(selection))
                        .show();
                });
            } catch (Exception e) {
                state("Erro a consultar Galeria: " + e.getMessage());
            }
        });
    }

    private int periodIndex(int days) {
        for (int i = 0; i < DAYS.length; i++) if (DAYS[i] == days) return i;
        return 0;
    }

    private void withNearbyPermissions(Runnable action) {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        boolean all = true;
        for (String p : permissions) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) all = false;
        if (all) action.run(); else askPermissions(permissions.toArray(new String[0]), action);
    }

    private void askPermissions(String[] requested, Runnable action) {
        afterPermission = action;
        requestPermissions(requested, PERMISSION_CODE);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != PERMISSION_CODE) return;
        Runnable next = afterPermission;
        afterPermission = null;
        if (next == null) return;
        boolean gallery = Arrays.asList(permissions).contains(Manifest.permission.READ_MEDIA_IMAGES)
                || Arrays.asList(permissions).contains(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (gallery) {
            boolean partial = false;
            for (int i = 0; i < permissions.length; i++) {
                if (grants.length > i && grants[i] == PackageManager.PERMISSION_GRANTED) partial = true;
            }
            if (partial) next.run();
            else state("Não foi dado acesso às fotografias. A autorização é necessária para enviar.");
        } else {
            boolean all = grants.length == permissions.length;
            for (int i = 0; i < permissions.length; i++) if (grants[i] != PackageManager.PERMISSION_GRANTED) all = false;
            if (all) next.run();
            else state("Nearby requer Bluetooth, Wi-Fi e Localização precisa. Autoriza as permissões nas definições.");
        }
    }

    @Override public void state(String message) {
        runOnUiThread(() -> {
            if (statusView != null) statusView.setText(message);
            if (log.length() > 9000) log.delete(0, log.length() - 6000);
            log.append("• ").append(message).append("\n");
            if (detailsView != null) detailsView.setText(log);
        });
    }

    @Override public void peers(Map<String,String> endpoints) {
        runOnUiThread(() -> {
            if (peersView == null) return;
            peersView.removeAllViews();
            if (endpoints.isEmpty()) {
                peersView.addView(note("A procurar telemóveis com Remote PhotoBridge..."));
                return;
            }
            for (Map.Entry<String,String> peer : endpoints.entrySet()) {
                peersView.addView(button("LIGAR A: " + peer.getValue(), false, () -> nearby.connect(peer.getKey())));
            }
        });
    }

    @Override public void authenticate(String id, String name, String digits) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
            .setTitle("Confirmar ligação segura")
            .setMessage("Telemóvel: " + name + "\n\nCÓDIGO: " + digits +
                    "\n\nConfirma que os dígitos são IGUAIS nos dois telemóveis.")
            .setCancelable(false)
            .setPositiveButton("Os códigos coincidem", (dialog, which) -> nearby.accept(id))
            .setNegativeButton("Recusar", (dialog, which) -> nearby.reject(id))
            .show());
    }

    @Override public void connected() {
        state(PRINCIPAL.equals(role) ?
                "LIGADO. Principal pronto para receber e guardar fotografias na Galeria." :
                "LIGADO. Escolhe o período e sincroniza as fotografias.");
    }

    @Override public void disconnected() {
        state("Ligação terminada. Podes voltar a iniciar o emparelhamento.");
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(17), dp(17), dp(17));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(43, 64, 82));
        card.setBackground(background);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(16);
        root.addView(card, p);
        return card;
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? BG : TEXT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? ACCENT : Color.rgb(43, 62, 83));
        background.setCornerRadius(dp(12));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(90, 255, 255, 255)), background, null));
        button.setMinHeight(dp(54));
        button.setPadding(dp(12), dp(14), dp(12), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        p.bottomMargin = dp(7);
        button.setLayoutParams(p);
        button.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            action.run();
        });
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private TextView note(String value) {
        TextView view = text(value, 15, MUTED, false);
        view.setPadding(0, dp(9), 0, dp(9));
        return view;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
