package pt.remotephotobridge;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RPB1: directional gallery transfer over Wi-Fi Direct TCP.
 * Principal only runs Server; Secondary only runs Client.
 * Authentication: ephemeral 6-digit PIN over WPA2-protected P2P network.
 * Files: SHA-256 advertised before bytes, so duplicates transfer zero bytes.
 */
final class PhotoTransport {
    interface Updates { void onEvent(String text); }
    private static final int PORT = 38988;
    private static final long MAX_PHOTO_BYTES = 2_000_000_000L;
    private static final int CHUNK = 64 * 1024;
    private final Context context;
    private final TransferDb db;
    private final Updates updates;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile ServerSocket server;
    private volatile boolean listening;
    private volatile boolean sending;

    PhotoTransport(Context c, TransferDb db, Updates updates) {
        context = c.getApplicationContext(); this.db = db; this.updates = updates;
    }

    synchronized void receive(InetAddress bindTo, String pin) {
        if (listening) return;
        listening = true;
        executor.execute(() -> {
            try (ServerSocket ss = new ServerSocket(PORT, 2, bindTo)) {
                server = ss;
                msg("Pronto para receber fotos. PIN: " + pin);
                while (listening) {
                    try (Socket socket = ss.accept()) {
                        socket.setSoTimeout(45_000);
                        handleIncoming(socket, pin);
                    } catch (SocketException closed) {
                        if (listening) msg("A ligação foi interrompida: " + closed.getMessage());
                    } catch (Exception err) {
                        if (listening) msg("Receção interrompida: " + err.getMessage());
                    }
                }
            } catch (Exception e) {
                if (listening) msg("Não foi possível abrir a receção: " + e.getMessage());
            } finally {
                listening = false;
                server = null;
            }
        });
    }

    synchronized void stopReceiving() {
        listening = false;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
    }

    boolean isSending() { return sending; }

    void send(InetAddress principal, String pin) {
        if (sending) { msg("Já está a transferir fotografias."); return; }
        sending = true;
        // Client uses a separate thread: receiver's accept loop is not blocked.
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(principal, PORT), 12_000);
                socket.setSoTimeout(45_000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out.writeUTF("RPB1");
                out.writeUTF(pin);
                out.flush();
                if (!"READY".equals(in.readUTF())) throw new IOException("PIN incorreto ou ligação não autorizada.");
                List<Photo> photos = getPhotos();
                msg("Foram encontradas " + photos.size() + " fotografias acessíveis.");
                out.writeInt(photos.size());
                out.flush();
                int copied = 0, skipped = 0;
                int index = 0;
                for (Photo photo : photos) {
                    index++;
                    String hash = photoHash(photo);
                    out.writeUTF(photo.name);
                    out.writeUTF(photo.mime);
                    out.writeLong(photo.size);
                    out.writeLong(photo.dateTaken);
                    out.writeUTF(hash);
                    out.flush();
                    String action = in.readUTF();
                    if ("SKIP".equals(action)) {
                        skipped++;
                        msg("(" + index + "/" + photos.size() + ") Já existe: " + photo.name);
                        continue;
                    }
                    if (!"SEND".equals(action)) throw new IOException("Resposta inválida do principal.");
                    try (InputStream src = context.getContentResolver().openInputStream(photo.uri)) {
                        if (src == null) throw new IOException("Fotografia inacessível: " + photo.name);
                        copyExactly(src, out, photo.size, null);
                        out.flush();
                    }
                    String result = in.readUTF();
                    if (!"SAVED".equals(result)) throw new IOException("Receção rejeitou " + photo.name + ": " + result);
                    copied++;
                    msg("(" + index + "/" + photos.size() + ") Transferida: " + photo.name);
                }
                msg("CONCLUÍDO: " + copied + " fotografias novas; " + skipped + " já existentes.");
            } catch (Exception e) {
                msg("TRANSFERÊNCIA INTERROMPIDA: " + e.getMessage() + ". Podes repetir, sem duplicar fotos.");
            } finally {
                sending = false;
            }
        }, "PhotoBridge-Sender").start();
    }

    /**
     * Check the real Wi-Fi Direct TCP path and the PIN without touching the
     * gallery or transmitting any photographs.
     */
    void testConnection(InetAddress principal, String pin) {
        if (sending) {
            msg("Não é possível testar durante uma transferência em curso.");
            return;
        }
        new Thread(() -> {
            msg("TESTE: a tentar comunicar com o Principal via Wi-Fi Direct...");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(principal, PORT), 10_000);
                socket.setSoTimeout(12_000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out.writeUTF("RPB1");
                out.writeUTF(pin);
                out.flush();
                if (!"READY".equals(in.readUTF())) {
                    throw new IOException("Ligação estabelecida, mas PIN não autorizado.");
                }
                out.writeInt(0); // Zero photographs: pure communication test.
                out.flush();
                msg("TESTE OK: canal Wi-Fi Direct, TCP e PIN confirmados. Já podes sincronizar fotografias.");
            } catch (Exception e) {
                msg("TESTE FALHOU: " + e.getMessage() +
                        ". Se os dois ecrãs indicam LIGADO, copia o diagnóstico de ambos.");
            }
        }, "PhotoBridge-ConnectionTest").start();
    }

    void shutdown() {
        stopReceiving();
        executor.shutdownNow();
        // An already-running send completes or fails on its socket's own timeout.
    }

    private void handleIncoming(Socket socket, String expectedPin) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        if (!"RPB1".equals(in.readUTF()) || !expectedPin.equals(in.readUTF())) {
            out.writeUTF("DENIED"); out.flush();
            msg("Ligação recusada: PIN incorreto.");
            return;
        }
        out.writeUTF("READY"); out.flush();
        int count = in.readInt();
        if (count < 0 || count > 200_000) throw new IOException("Quantidade de fotografias inválida.");
        int saved = 0, duplicates = 0;
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            String mime = in.readUTF();
            long length = in.readLong();
            long taken = in.readLong();
            String expectedHash = in.readUTF();
            if (length < 1 || length > MAX_PHOTO_BYTES || !mime.startsWith("image/") ||
                    !expectedHash.matches("[a-f0-9]{64}")) {
                throw new IOException("Metadados da imagem inválidos.");
            }
            if (db.alreadyReceived(expectedHash)) {
                out.writeUTF("SKIP"); out.flush(); duplicates++;
                continue;
            }
            Destination destination = new Destination(name, mime, taken, expectedHash);
            out.writeUTF("SEND"); out.flush();
            boolean good = false;
            try {
                MessageDigest digest = digest();
                copyExactly(in, destination.output, length, digest);
                destination.output.flush();
                good = expectedHash.equals(hex(digest.digest()));
                destination.finish(good);
                if (good) db.markReceived(expectedHash);
            } finally {
                // Deletes incomplete/invalid pending images on interruption.
                destination.closeIfIncomplete();
            }
            out.writeUTF(good ? "SAVED" : "HASH_ERROR"); out.flush();
            if (good) {
                saved++;
                msg("Recebida (" + (i + 1) + "/" + count + "): " + name);
            }
        }
        msg("RECEÇÃO CONCLUÍDA: " + saved + " novas fotos; " + duplicates + " repetidas ignoradas.");
    }

    private String photoHash(Photo p) throws IOException {
        String existing = db.knownSourceHash(p.id, p.size, p.modified);
        if (existing != null) return existing;
        MessageDigest digest = digest();
        try (InputStream in = context.getContentResolver().openInputStream(p.uri)) {
            if (in == null) throw new IOException("Não foi possível abrir " + p.name);
            copyExactly(in, null, p.size, digest);
        }
        String hash = hex(digest.digest());
        db.saveSourceHash(p.id, p.size, p.modified, hash);
        return hash;
    }

    private List<Photo> getPhotos() {
        List<Photo> photos = new ArrayList<>();
        String[] columns = {
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE, MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED
        };
        ContentResolver cr = context.getContentResolver();
        try (Cursor cur = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, null, null,
                MediaStore.Images.Media.DATE_TAKEN + " ASC")) {
            if (cur == null) return photos;
            while (cur.moveToNext()) {
                long id = cur.getLong(0), size = cur.getLong(2);
                String mime = cur.getString(3);
                if (size <= 0 || size > MAX_PHOTO_BYTES || mime == null || !mime.startsWith("image/")) continue;
                String filename = cur.getString(1);
                if (filename == null || filename.trim().isEmpty()) filename = "Foto_" + id + ".jpg";
                long date = cur.isNull(4) ? 0 : cur.getLong(4);
                long modified = cur.isNull(5) ? 0 : cur.getLong(5);
                photos.add(new Photo(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        filename, mime, size, date, modified));
            }
        }
        return photos;
    }

    private static class Photo {
        long id, size, dateTaken, modified;
        Uri uri;
        String name, mime;
        Photo(long id, Uri uri, String name, String mime, long size, long dateTaken, long modified) {
            this.id=id; this.uri=uri; this.name=name; this.mime=mime; this.size=size;
            this.dateTaken=dateTaken; this.modified=modified;
        }
    }

    private final class Destination {
        final Uri uri;
        final File file;
        final OutputStream output;
        boolean finalized;
        Destination(String originalName, String mime, long dateTaken, String hash) throws IOException {
            String clean = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (clean.length() > 90) clean = clean.substring(clean.length() - 90);
            if (clean.length() < 3) clean = "Foto.jpg";
            // Prevent filename collisions; keep camera's original name in the filename.
            String unique = hash.substring(0, 10) + "_" + clean;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, unique);
                values.put(MediaStore.Images.Media.MIME_TYPE, mime);
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Remote PhotoBridge");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                if (dateTaken > 0) values.put(MediaStore.Images.Media.DATE_TAKEN, dateTaken);
                Uri created = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (created == null) throw new IOException("Falhou a criação do ficheiro na galeria.");
                uri = created;
                file = null;
                OutputStream tmp = context.getContentResolver().openOutputStream(uri, "w");
                if (tmp == null) {
                    context.getContentResolver().delete(uri, null, null);
                    throw new IOException("Não foi possível escrever a fotografia.");
                }
                output = tmp;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Remote PhotoBridge");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Não foi possível criar a pasta das fotos.");
                file = new File(dir, unique);
                uri = null;
                output = new FileOutputStream(file);
            }
        }

        void finish(boolean accept) throws IOException {
            output.close();
            if (accept) {
                if (uri != null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.IS_PENDING, 0);
                    if (context.getContentResolver().update(uri, v, null, null) < 1)
                        throw new IOException("Não foi possível tornar visível a fotografia.");
                } else MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
                finalized = true;
            } else closeIfIncomplete();
        }

        void closeIfIncomplete() {
            if (finalized) return;
            try { output.close(); } catch (Exception ignored) { }
            if (uri != null) context.getContentResolver().delete(uri, null, null);
            if (file != null) file.delete();
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }

    private static void copyExactly(InputStream in, OutputStream out, long bytes, MessageDigest hash) throws IOException {
        byte[] block = new byte[CHUNK];
        long remaining = bytes;
        while (remaining > 0) {
            int n = in.read(block, 0, (int) Math.min((long)block.length, remaining));
            if (n < 0) throw new IOException("O ficheiro terminou antes do tamanho anunciado.");
            if (n == 0) continue;
            if (out != null) out.write(block, 0, n);
            if (hash != null) hash.update(block, 0, n);
            remaining -= n;
        }
    }

    private void msg(String text) { updates.onEvent(text); }
}
