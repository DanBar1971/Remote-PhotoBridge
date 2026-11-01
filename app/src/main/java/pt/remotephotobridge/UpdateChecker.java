package pt.remotephotobridge;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Read-only, user-initiated check for the latest non-prerelease APK. */
final class UpdateChecker {
    static final String API =
            "https://api.github.com/repos/DanBar1971/Remote-PhotoBridge/releases/latest";
    static final String RELEASE_PAGE =
            "https://github.com/DanBar1971/Remote-PhotoBridge/releases/latest";

    static final class Result {
        final boolean available;
        final String installed, latest, releaseUrl, apkUrl;

        Result(boolean available, String installed, String latest,
               String releaseUrl, String apkUrl) {
            this.available = available;
            this.installed = installed;
            this.latest = latest;
            this.releaseUrl = releaseUrl;
            this.apkUrl = apkUrl;
        }
    }

    static int compare(String a, String b) {
        int[] left = components(a), right = components(b);
        for (int i = 0; i < 3; i++) {
            if (left[i] < right[i]) return -1;
            if (left[i] > right[i]) return 1;
        }
        return 0;
    }

    private static int[] components(String version) {
        if (version == null || !version.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new IllegalArgumentException("Número de versão inválido.");
        String text = version.startsWith("v") ? version.substring(1) : version;
        String[] parts = text.split("\\.");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2])};
    }

    static Result check(Context context) throws Exception {
        PackageInfo packageInfo = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
        String installed = packageInfo.versionName;
        if (installed == null) throw new IOException("Não foi possível ler a versão instalada.");

        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "RemotePhotoBridge-Android");
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(9000);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK)
                throw new IOException(code == 403 ?
                        "GitHub temporariamente indisponível. Tenta mais tarde." :
                        "O GitHub devolveu o erro HTTP " + code + ".");
            JSONObject json;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                char[] chars = new char[4096];
                int count;
                while ((count = reader.read(chars)) != -1) {
                    if (body.length() + count > 500_000)
                        throw new IOException("Resposta do GitHub demasiado grande.");
                    body.append(chars, 0, count);
                }
                json = new JSONObject(body.toString());
            }
            if (json.optBoolean("draft") || json.optBoolean("prerelease"))
                throw new IOException("A última publicação ainda não é uma versão estável.");
            String latest = json.getString("tag_name").replaceFirst("^v", "");
            boolean isNew = compare(installed, latest) < 0;
            String url = "";
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) for (int i = 0; i < assets.length(); i++) {
                JSONObject item = assets.optJSONObject(i);
                if (item == null) continue;
                String asset = item.optString("browser_download_url");
                String name = item.optString("name");
                if (name.endsWith(".apk") &&
                        asset.startsWith("https://github.com/DanBar1971/Remote-PhotoBridge/releases/download/") &&
                        asset.endsWith(".apk")) {
                    url = asset;
                    break;
                }
            }
            return new Result(isNew, installed, latest, RELEASE_PAGE, url);
        } finally {
            connection.disconnect();
        }
    }
}
