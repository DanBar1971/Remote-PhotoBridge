package pt.remotephotobridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;
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
import android.widget.EditText;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.ArrayList;
import java.util.Collections;
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
    private int BG = Color.rgb(14, 23, 38);
    private int CARD = Color.rgb(26, 39, 58);
    private int ACCENT = Color.rgb(52, 211, 191);
    private int TEXT = Color.rgb(235, 243, 249);
    private int MUTED = Color.rgb(164, 179, 197);
    private int BORDER = Color.rgb(43, 64, 82);
    private int SECONDARY_BG = Color.rgb(43, 62, 83);
    private int ACCENT_TEXT = Color.rgb(14, 23, 38);
    private static final String PRINCIPAL = "principal", SECONDARY = "secundario";
    private static final int PERMISSION_CODE = 2048;
    private boolean returningFromCamera;
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
    private Button galleryButton;
    private Uri lastReceivedGalleryUri;
    private boolean receiving;
    private String currentPage = "home";
    private UpdateChecker.Result latestUpdate;
    private String updateMessage = "Ainda não foi verificada a existência de atualizações.";
    private TextView updateStatusView;
    private Button updateDownloadButton;
    private boolean updateCheckedThisSession;
    private boolean updateChecking;
    private final StringBuilder log = new StringBuilder();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("photobridge_options", MODE_PRIVATE);
        applyAppearance();
        String previousReceived = prefs.getString("latest_gallery_photo", null);
        if (previousReceived != null) lastReceivedGalleryUri = Uri.parse(previousReceived);
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
        showHome();
    }

    /**
     * Apply the complete native Android theme AND custom widget palette.
     * Changing mode does not restart Nearby or recreate the Activity:
     * connection and in-progress receive state are preserved.
     */
    private void applyAppearance() {
        boolean systemDark = (getResources().getConfiguration().uiMode &
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = ThemeMode.useDark(prefs.getString("appearance", ThemeMode.DARK), systemDark);
        if (dark) {
            BG = Color.rgb(14, 23, 38);
            CARD = Color.rgb(26, 39, 58);
            ACCENT = Color.rgb(52, 211, 191);
            TEXT = Color.rgb(235, 243, 249);
            MUTED = Color.rgb(164, 179, 197);
            BORDER = Color.rgb(43, 64, 82);
            SECONDARY_BG = Color.rgb(43, 62, 83);
            ACCENT_TEXT = Color.rgb(14, 23, 38);
        } else {
            BG = Color.rgb(244, 247, 250);
            CARD = Color.WHITE;
            ACCENT = Color.rgb(8, 108, 102);
            TEXT = Color.rgb(22, 40, 58);
            MUTED = Color.rgb(78, 96, 113);
            BORDER = Color.rgb(200, 215, 225);
            SECONDARY_BG = Color.rgb(231, 238, 244);
            ACCENT_TEXT = Color.WHITE;
        }
        setTheme(dark ? R.style.AppTheme : R.style.AppTheme_Light);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? (flags & ~lightFlags) : (flags | lightFlags));
    }

    private void setAppearance(String mode) {
        if (nearby != null && nearby.isBusy()) {
            Toast.makeText(this, "Termina primeiro a transferência em curso.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString("appearance", ThemeMode.normalize(mode)).apply();
        applyAppearance();
        redrawCurrentPage();
    }

    private void redrawCurrentPage() {
        switch (currentPage) {
            case "sharing":
                renderSharing();
                if (nearby != null && nearby.isConnected()) connected();
                break;
            case "role": showRoleChooser(); break;
            case "options": showOptions(); break;
            case "about": showAbout(); break;
            case "support": showSupport(); break;
            default: showHome(); break;
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (prefs != null && ThemeMode.SYSTEM.equals(
                ThemeMode.normalize(prefs.getString("appearance", ThemeMode.DARK)))) {
            // Automatically switch when the Android light/dark preference changes.
            // Do not reinitialize Nearby or interrupt an ongoing transfer.
            applyAppearance();
            if (nearby == null || !nearby.isBusy()) redrawCurrentPage();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (returningFromCamera) {
            returningFromCamera = false;
            // A normal Camera app may save several photos and does not return
            // their URIs. Invite the user to sync by date; never auto-send.
            if (SECONDARY.equals(role)) getWindow().getDecorView().post(() ->
                    new AlertDialog.Builder(this)
                        .setTitle("Sincronizar fotografias?")
                        .setMessage("Se tiraste fotografias, a Câmara guardou-as na Galeria normal. Queres abrir Partilhar e sincronizar as fotografias de hoje?")
                        .setNegativeButton("Agora não", null)
                        .setPositiveButton("Ir para Partilhar", (dialog, which) -> renderSharing())
                        .show());
        }
    }

    @Override public void onBackPressed() {
        if ("home".equals(currentPage)) super.onBackPressed();
        else showHome();
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
        tag.setPadding(0, dp(7), 0, dp(18));
        root.addView(tag);
        return root;
    }

    private void showRoleChooser() {
        currentPage = "role";
        LinearLayout root = screen();
        root.addView(button("‹ VOLTAR ÀS OPÇÕES", false, this::showOptions));
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
        renderSharing();
    }

    private void renderSharing() {
        currentPage = "sharing";
        // Keep connection diagnostics even if this screen is opened again.
        // Nearby remains connected until the user explicitly disconnects.
        peersView = null;
        periodView = null;
        galleryButton = null;
        LinearLayout root = screen();
        root.addView(button("‹ MENU PRINCIPAL", false, this::showHome));
        LinearLayout roleCard = card(root);
        roleCard.addView(text(PRINCIPAL.equals(role) ? "TELEMÓVEL PRINCIPAL" : "TELEMÓVEL SECUNDÁRIO", 17, ACCENT, true));
        roleCard.addView(note(PRINCIPAL.equals(role) ?
                "Este telemóvel só recebe fotografias." :
                "Este telemóvel só envia fotografias do período selecionado."));
        roleCard.addView(note("Para alterar a função, abre Opções no menu principal."));

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
        galleryButton = button("ABRIR GALERIA DO TELEMÓVEL", false, this::openSystemGallery);
        galleryButton.setVisibility(prefs.getBoolean("received_gallery_photo", false) ? View.VISIBLE : View.GONE);
        card.addView(galleryButton);
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
        send.addView(note("Para tirar várias fotos com a Câmara normal, regressa ao menu principal. Depois escolhe Hoje para as sincronizar."));
        send.addView(note("Só serão enviadas fotografias do período indicado. A opção inicial é Hoje. Nunca envia toda a galeria."));
        ArrayAdapter<String> periods = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, PERIODS) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView item = (TextView) super.getView(position, convertView, parent);
                item.setTextColor(TEXT);
                item.setTextSize(17);
                item.setPadding(dp(10), dp(18), dp(10), dp(18));
                return item;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView item = (TextView) super.getDropDownView(position, convertView, parent);
                item.setTextColor(TEXT);
                item.setBackgroundColor(CARD);
                item.setPadding(dp(12), dp(12), dp(12), dp(12));
                return item;
            }
        };
        periods.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        periodView = new Spinner(this);
        periodView.setAdapter(periods);
        // Each time this screen opens start safely from TODAY, even if
        // the user chose a broader period during an earlier sync.
        periodView.setSelection(0);
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
        return DAYS[pos];
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
        if (PRINCIPAL.equals(role) && Build.VERSION.SDK_INT <= 28)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
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

    @Override public void photoReceived(Uri galleryUri) {
        if (!PRINCIPAL.equals(role)) return;
        runOnUiThread(() -> {
            if (galleryUri != null) {
                lastReceivedGalleryUri = galleryUri;
                prefs.edit().putString("latest_gallery_photo", galleryUri.toString()).apply();
            }
            prefs.edit().putBoolean("received_gallery_photo", true).apply();
            if (galleryButton != null) galleryButton.setVisibility(View.VISIBLE);
        });
    }

    @Override public void disconnected() {
        state("Ligação terminada. Podes voltar a iniciar o emparelhamento.");
    }

    /**
     * Open the received photo through the Android Gallery / installed image
     * viewer. Do not upload anything to Google Photos or external services.
     */

    private void showHome() {
        currentPage = "home";
        LinearLayout root = screen();
        root.addView(text("O que pretendes fazer?", 20, TEXT, true));
        root.addView(note("Acesso rápido à Câmara, Galeria e transferência de fotografias."));
        LinearLayout first = new LinearLayout(this);
        first.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout second = new LinearLayout(this);
        second.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(first);
        root.addView(second);
        menuTile(first, "📷", "CÂMARA", "Tirar várias fotografias", this::openSystemCamera);
        menuTile(first, "🖼", "GALERIA", "Ver as fotografias", this::openSystemGallery);
        menuTile(second, "⇄", "PARTILHAR", "Enviar ou receber", () -> {
            if (role == null) showRoleChooser();
            else renderSharing();
        });
        menuTile(second, "⚙", "OPÇÕES", "Função, acessos e ajuda", this::showOptions);
        root.addView(note("Sem Internet nem SIM para partilhar · A ligação Nearby continua disponível entre os dois telemóveis."));
        String state = role == null ? "Função ainda não escolhida" :
                PRINCIPAL.equals(role) ? "Função atual: Principal (só recebe)" :
                "Função atual: Secundário (só envia)";
        LinearLayout info = card(root);
        info.addView(text(state, 16, ACCENT, true));
        if (nearby != null && nearby.isConnected()) {
            info.addView(note("Ligação Nearby ativa. Abre Partilhar para sincronizar."));
        }
    }

    private void menuTile(LinearLayout parent, String symbol, String title,
                          String subtitle, Runnable action) {
        LinearLayout surface = new LinearLayout(this);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setGravity(Gravity.CENTER);
        surface.setPadding(dp(12), dp(15), dp(12), dp(15));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), BORDER);
        surface.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(92, 52, 211, 191)), bg, null));
        TextView pictogram = text(symbol, 32, ACCENT, true);
        pictogram.setGravity(Gravity.CENTER);
        surface.addView(pictogram);
        TextView caption = text(title, 17, TEXT, true);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(10), 0, dp(4));
        surface.addView(caption);
        TextView description = text(subtitle, 12, MUTED, false);
        description.setGravity(Gravity.CENTER);
        surface.addView(description);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, dp(166), 1);
        params.setMargins(dp(4), dp(6), dp(4), dp(6));
        parent.addView(surface, params);
        surface.setClickable(true);
        surface.setOnClickListener(v -> action.run());
    }

    /** Native camera owns its saves: DCIM/Camera, multi-shot, no app temp file. */
    private void openSystemCamera() {
        try {
            // Official Android intent opens the normal camera application,
            // not the one-shot capture API that had failed to save photos.
            startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
            returningFromCamera = true;
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, "Não foi encontrada uma aplicação Câmara neste telemóvel.",
                    Toast.LENGTH_LONG).show();
        } catch (SecurityException unavailable) {
            Toast.makeText(this, "A Câmara não pôde ser aberta. Confirma os acessos do Android.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Launch the GALLERY HOME, not ACTION_VIEW of the last received image. */
    private void openSystemGallery() {
        try {
            startActivity(Intent.makeMainSelectorActivity(
                    Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY));
        } catch (ActivityNotFoundException notRegistered) {
            try {
                Intent gallery = new Intent(Intent.ACTION_MAIN);
                gallery.addCategory(Intent.CATEGORY_APP_GALLERY);
                startActivity(gallery);
            } catch (ActivityNotFoundException fallback) {
                try {
                    Intent browse = new Intent(Intent.ACTION_VIEW);
                    browse.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
                    startActivity(browse);
                } catch (Exception noGallery) {
                    Toast.makeText(this, "Não foi encontrada uma aplicação Galeria.",
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void showOptions() {
        currentPage = "options";
        LinearLayout root = screen();
        root.addView(button("‹ MENU PRINCIPAL", false, this::showHome));
        root.addView(text("OPÇÕES", 23, TEXT, true));

        LinearLayout appearance = card(root);
        appearance.addView(text("ASPETO", 17, ACCENT, true));
        String selectedAppearance = ThemeMode.normalize(
                prefs.getString("appearance", ThemeMode.DARK));
        appearance.addView(note("Tema atual: " + ThemeMode.label(selectedAppearance)));
        appearance.addView(note("Escolhe as cores da aplicação. Em Automático acompanha o modo do Android."));
        appearance.addView(button("CLARO", ThemeMode.LIGHT.equals(selectedAppearance),
                () -> setAppearance(ThemeMode.LIGHT)));
        appearance.addView(button("ESCURO", ThemeMode.DARK.equals(selectedAppearance),
                () -> setAppearance(ThemeMode.DARK)));
        appearance.addView(button("AUTOMÁTICO · SEGUIR ANDROID", ThemeMode.SYSTEM.equals(selectedAppearance),
                () -> setAppearance(ThemeMode.SYSTEM)));

        LinearLayout updates = card(root);
        updates.addView(text("ATUALIZAÇÕES", 17, ACCENT, true));
        updates.addView(note("Versão instalada: " + BuildConfig.VERSION_NAME));
        updateStatusView = note(updateMessage);
        updates.addView(updateStatusView);
        updates.addView(button("PROCURAR ATUALIZAÇÕES", false, this::checkForUpdates));
        updateDownloadButton = button("DESCARREGAR ATUALIZAÇÃO", true, () -> {
            if (latestUpdate != null && latestUpdate.available) {
                // Opens the official GitHub APK in the user's browser. Android
                // installation always requires the user's own confirmation.
                openWebPage(latestUpdate.apkUrl.isEmpty()
                        ? latestUpdate.releaseUrl : latestUpdate.apkUrl);
            }
        });
        updateDownloadButton.setVisibility(latestUpdate != null && latestUpdate.available
                ? View.VISIBLE : View.GONE);
        updates.addView(updateDownloadButton);
        updates.addView(note("A verificação requer Internet apenas neste momento. As fotografias continuam a ser partilhadas sem Internet."));
        if (!updateCheckedThisSession) {
            updateCheckedThisSession = true;
            checkForUpdates();
        }

        LinearLayout functions = card(root);
        functions.addView(text("FUNÇÃO DESTE TELEMÓVEL", 17, ACCENT, true));
        functions.addView(note(role == null ? "Ainda não definida." :
                PRINCIPAL.equals(role) ? "Principal: só recebe fotografias." :
                "Secundário: só envia fotografias."));
        functions.addView(button("ESCOLHER PRINCIPAL / SECUNDÁRIO", false, () -> {
            if (nearby != null && nearby.isBusy()) {
                Toast.makeText(this, "Termina primeiro a transferência atual.",
                        Toast.LENGTH_LONG).show();
            } else showRoleChooser();
        }));

        LinearLayout access = card(root);
        access.addView(text("ACESSOS E PERMISSÕES", 17, ACCENT, true));
        access.addView(note("Câmara: aberta através da aplicação original do Android, sem pedir acesso direto à câmara."));
        boolean galleryGranted = Build.VERSION.SDK_INT >= 33
                ? checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean partial = Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        access.addView(note("Fotografias para enviar: " +
                (galleryGranted ? "Acesso permitido" : partial ? "Apenas fotografias selecionadas" : "Acesso não autorizado") +
                ". No Principal não é necessário ler todas as imagens para as receber."));
        access.addView(button("GERIR PERMISSÕES NO ANDROID", false, this::openAndroidAppSettings));
        access.addView(button("PEDIR ACESSOS PARA PARTILHAR", false,
                () -> withNearbyPermissions(() ->
                        Toast.makeText(this, "Permissões para Nearby confirmadas.", Toast.LENGTH_SHORT).show())));
        if (SECONDARY.equals(role))
            access.addView(button("PEDIR ACESSO ÀS FOTOGRAFIAS", false,
                    this::requestGalleryAccessOnly));

        LinearLayout about = card(root);
        about.addView(text("INFORMAÇÃO E APOIO", 17, ACCENT, true));
        about.addView(button("ACERCA DA REMOTE PHOTOBRIDGE", false, this::showAbout));
        about.addView(button("☕ OFERECER UM CAFÉ", false, this::showDonate));
        about.addView(button("✉ CONTACTAR SUPORTE", false, this::showSupport));
    }

    private void checkForUpdates() {
        if (updateChecking || executor == null || executor.isShutdown()) return;
        updateChecking = true;
        updateMessage = "A consultar a última versão estável no GitHub...";
        if (updateStatusView != null) updateStatusView.setText(updateMessage);
        executor.execute(() -> {
            try {
                UpdateChecker.Result result = UpdateChecker.check(this);
                runOnUiThread(() -> {
                    updateChecking = false;
                    latestUpdate = result;
                    if (result.available) {
                        updateMessage = "Nova versão disponível: " + result.latest +
                                " (instalada: " + result.installed + ").";
                    } else {
                        updateMessage = "A aplicação está atualizada (versão " +
                                result.installed + ").";
                    }
                    if (updateStatusView != null) updateStatusView.setText(updateMessage);
                    if (updateDownloadButton != null) updateDownloadButton.setVisibility(
                            result.available ? View.VISIBLE : View.GONE);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    updateChecking = false;
                    latestUpdate = null;
                    updateMessage = "Não foi possível verificar atualizações: " +
                            error.getMessage() +
                            ". Confirma a ligação à Internet e tenta novamente.";
                    if (updateStatusView != null) updateStatusView.setText(updateMessage);
                    if (updateDownloadButton != null)
                        updateDownloadButton.setVisibility(View.GONE);
                });
            }
        });
    }

    private void openAndroidAppSettings() {
        try {
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            settings.setData(Uri.parse("package:" + getPackageName()));
            startActivity(settings);
        } catch (Exception e) {
            Toast.makeText(this, "Abre Definições > Aplicações > Remote PhotoBridge > Permissões.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void requestGalleryAccessOnly() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (Build.VERSION.SDK_INT >= 34) permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        boolean ok = false;
        for (String permission : permissions)
            if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) ok = true;
        if (ok) {
            Toast.makeText(this, "Já tens acesso a fotografias. Podes alterar em Gerir permissões.",
                    Toast.LENGTH_LONG).show();
        } else askPermissions(permissions.toArray(new String[0]), () -> {
            Toast.makeText(this, "Acesso atualizado.", Toast.LENGTH_SHORT).show();
            showOptions();
        });
    }

    private void showAbout() {
        currentPage = "about";
        LinearLayout root = screen();
        root.addView(button("‹ OPÇÕES", false, this::showOptions));
        LinearLayout info = card(root);
        info.addView(text("REMOTE PHOTO BRIDGE", 22, ACCENT, true));
        info.addView(note("Versão 1.0.1 · Android"));
        info.addView(note("Autor: Daniel Barradas"));
        info.addView(note("Transfere fotografias entre Androids sem Internet através de Nearby Connections. O Principal só recebe, o Secundário só envia. Sem guardar fotos na nuvem."));
        info.addView(note("Suporte: daniel.barradas@gmail.com"));
        info.addView(button("VER REPOSITÓRIO NO GITHUB", false, () ->
                openWebPage("https://github.com/DanBar1971/Remote-PhotoBridge")));
        info.addView(button("☕ APOIAR O AUTOR", false, this::showDonate));
        info.addView(button("✉ CONTACTAR SUPORTE", false, this::showSupport));
    }

    private void openWebPage(String uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri))); }
        catch (Exception err) {
            Toast.makeText(this, "Não foi possível abrir o navegador.", Toast.LENGTH_LONG).show();
        }
    }

    private void showDonate() {
        // Exactly as in SaúdePrática: two separate buttons. On some Xiaomi
        // versions setMessage()+setItems() hides the choices entirely.
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPadding(dp(17), dp(5), dp(17), dp(8));
        choices.addView(note("O apoio é opcional. Escolhe o valor na página externa; nenhum pagamento é feito dentro da app."));
        AlertDialog popup = new AlertDialog.Builder(this)
                .setTitle("☕ Oferecer um cafezinho")
                .setView(choices)
                .setNegativeButton("Cancelar", null)
                .create();
        choices.addView(button("☕ BUY ME A COFFEE · PRIMEIRA OPÇÃO", true, () -> {
            popup.dismiss();
            openWebPage("https://buymeacoffee.com/danielbarr7");
        }));
        choices.addView(button("PAYPAL · ALTERNATIVA", false, () -> {
            popup.dismiss();
            openWebPage("https://www.paypal.com/paypalme/djjindungo");
        }));
        popup.show();
    }

    private void showSupport() {
        currentPage = "support";
        LinearLayout root = screen();
        root.addView(button("‹ OPÇÕES", false, this::showOptions));
        LinearLayout form = card(root);
        form.addView(text("CONTACTAR SUPORTE", 21, ACCENT, true));
        form.addView(note("Envia uma dúvida, sugestão ou erro ao mesmo email da SaúdePrática."));
        Spinner types = new Spinner(this);
        ArrayAdapter<String> options = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Dúvida", "Sugestão", "Comunicar erro"});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        types.setAdapter(options);
        form.addView(text("Tipo de contacto", 15, TEXT, true));
        form.addView(types);
        form.addView(text("Nome (opcional)", 15, TEXT, true));
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setTextColor(TEXT);
        form.addView(name);
        form.addView(text("Email para resposta (opcional)", 15, TEXT, true));
        EditText reply = new EditText(this);
        reply.setSingleLine(true);
        reply.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        reply.setTextColor(TEXT);
        form.addView(reply);
        form.addView(text("Mensagem", 15, TEXT, true));
        EditText content = new EditText(this);
        content.setMinLines(5);
        content.setGravity(Gravity.TOP);
        content.setTextColor(TEXT);
        content.setHint("Escreve a dúvida, sugestão ou problema...");
        content.setHintTextColor(MUTED);
        form.addView(content);
        form.addView(note("Não incluas códigos PIN nem informações pessoais sensíveis."));
        form.addView(button("PREPARAR EMAIL DE SUPORTE", true, () -> {
            String description = content.getText().toString().trim();
            String email = reply.getText().toString().trim();
            if (description.length() < 10) {
                content.setError("Escreve uma descrição com pelo menos 10 caracteres.");
                return;
            }
            if (!email.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                reply.setError("Email inválido.");
                return;
            }
            String subject = "Remote PhotoBridge · " + types.getSelectedItem();
            String body = "Nome: " + name.getText().toString().trim() +
                    "\nEmail para resposta: " + email +
                    "\nApp: Remote PhotoBridge 1.0.1" +
                    "\nAndroid: " + Build.VERSION.RELEASE + " (" + Build.MODEL + ")" +
                    "\n\n" + description;
            Intent send = new Intent(Intent.ACTION_SENDTO);
            send.setData(Uri.parse("mailto:daniel.barradas@gmail.com"));
            send.putExtra(Intent.EXTRA_EMAIL, new String[]{"daniel.barradas@gmail.com"});
            send.putExtra(Intent.EXTRA_SUBJECT, subject);
            send.putExtra(Intent.EXTRA_TEXT, body);
            try { startActivity(send); }
            catch (ActivityNotFoundException ex) {
                Toast.makeText(this, "Não foi encontrada uma aplicação de email. Escreve para daniel.barradas@gmail.com.",
                        Toast.LENGTH_LONG).show();
            }
        }));
        form.addView(note("O email só será enviado quando confirmares na aplicação de correio."));
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(17), dp(17), dp(17));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), BORDER);
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
        button.setTextColor(primary ? ACCENT_TEXT : TEXT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? ACCENT : SECONDARY_BG);
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
