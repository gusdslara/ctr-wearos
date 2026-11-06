package com.ctrnative;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Servidor HTTP embutido para transferência de ROM/assets via Wi-Fi com QR Code.
 */
public class RomInbox {

    private static final String TAG = "RomInbox";
    private static final int PORT = 8080;
    private static final long MAX_UPLOAD = 16L * 1024 * 1024;

    public interface Listener {
        void onServerReady(String url, Bitmap qrBitmap);
        void onProgress(int percent);
        void onFileReceived(File file);
        void onError(String msg);
    }

    private final Activity activity;
    private final File targetDir;
    private final Listener listener;
    private final Handler mainHandler;
    private final String lang;

    private ServerSocket server;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private String serverIp;
    private volatile boolean running = false;

    public RomInbox(Activity activity, File targetDir, Listener listener) {
        this.activity = activity;
        this.targetDir = targetDir;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.lang = Locale.getDefault().getLanguage();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        startServer();
        requestWifi();
    }

    public synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {}
            server = null;
        }
        if (wifiCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(wifiCallback);
            } catch (Exception ignored) {}
            wifiCallback = null;
        }
    }

    private void requestWifi() {
        connectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                checkAndUpdateIp();
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                checkAndUpdateIp();
            }

            @Override
            public void onLost(Network network) {
                serverIp = null;
            }
        };

        try {
            connectivityManager.requestNetwork(req, wifiCallback, 30000);
        } catch (Exception e) {
            Log.w(TAG, "requestNetwork error", e);
        }

        checkAndUpdateIp();
    }

    private void checkAndUpdateIp() {
        String ip = getLocalIpAddress();
        if (ip != null && !ip.equals(serverIp)) {
            serverIp = ip;
            final String url = "http://" + serverIp + ":" + PORT;
            final Bitmap qr = generateQrBitmap(url, 260);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.onServerReady(url, qr);
                    }
                }
            });
        }
    }

    private void startServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    server = new ServerSocket();
                    server.setReuseAddress(true);
                    server.bind(new InetSocketAddress(PORT));
                    Log.d(TAG, "Server running on port " + PORT);

                    while (running && server != null && !server.isClosed()) {
                        try {
                            Socket client = server.accept();
                            client.setSoTimeout(30000);
                            handleClient(client);
                        } catch (IOException e) {
                            // Conexão encerrada
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Server socket error", e);
                }
            }
        }, "CTR-RomInbox-Server").start();
    }

    private void handleClient(Socket client) {
        try (InputStream in = new BufferedInputStream(client.getInputStream(), 65536);
             OutputStream out = client.getOutputStream()) {

            String header = readHeader(in);
            if (header == null) return;

            String[] lines = header.split("\r\n");
            String[] firstLine = lines[0].split(" ");
            if (firstLine.length < 2) return;

            String method = firstLine[0];
            String path = firstLine[1];

            long contentLength = -1;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                    try {
                        contentLength = Long.parseLong(lines[i].substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {}
                }
            }

            if ("GET".equalsIgnoreCase(method) && (path.equals("/") || path.startsWith("/index"))) {
                byte[] html = Page.html(lang).getBytes("UTF-8");
                sendResponse(out, 200, "text/html; charset=utf-8", html);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/upload")) {
                if (contentLength <= 0 || contentLength > MAX_UPLOAD) {
                    sendResponse(out, 413, "application/json", json(false, Page.t(lang, "notRom")));
                } else {
                    File received = receiveFile(in, contentLength, path);
                    if (received != null) {
                        sendResponse(out, 200, "application/json", json(true, Page.t(lang, "okGame")));
                        notifyFileReceived(received);
                    } else {
                        sendResponse(out, 500, "application/json", json(false, Page.t(lang, "cut")));
                    }
                }
            } else {
                sendResponse(out, 404, "text/plain", "Not Found".getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "handleClient error", e);
        }
    }

    private File receiveFile(InputStream in, long totalBytes, String queryPath) {
        targetDir.mkdirs();
        File tmp = new File(targetDir, "upload.tmp");
        int lastPercent = 0;

        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[32768];
            long readSoFar = 0;

            while (readSoFar < totalBytes) {
                int toRead = (int) Math.min(buf.length, totalBytes - readSoFar);
                int n = in.read(buf, 0, toRead);
                if (n < 0) break;
                fos.write(buf, 0, n);
                readSoFar += n;

                int pct = (int) (readSoFar * 100 / totalBytes);
                if (pct != lastPercent) {
                    lastPercent = pct;
                    final int p = pct;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) listener.onProgress(p);
                        }
                    });
                }
            }

            if (readSoFar < totalBytes) {
                tmp.delete();
                return null;
            }
        } catch (IOException e) {
            tmp.delete();
            return null;
        }

        // Determinar destino com base no tipo de arquivo
        File dest;
        if (tmp.length() == 524288) { // ROM SNES sem cabeçalho (512KB)
            dest = new File(targetDir, "ctr-u.bin");
        } else {
            dest = new File(targetDir, "ctr-u.bin");
        }

        dest.delete();
        if (tmp.renameTo(dest)) {
            return dest;
        } else {
            tmp.delete();
            return null;
        }
    }

    private void notifyFileReceived(final File file) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onFileReceived(file);
                }
            }
        });
    }

    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int state = 0;
        while (baos.size() < 16384) {
            int c = in.read();
            if (c < 0) return null;
            baos.write(c);
            if ((state == 0 || state == 2) && c == '\r') state++;
            else if ((state == 1 || state == 3) && c == '\n') state++;
            else state = (c == '\r') ? 1 : 0;
            if (state == 4) return baos.toString("ISO-8859-1");
        }
        return null;
    }

    private static void sendResponse(OutputStream out, int code, String contentType, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    private static byte[] json(boolean ok, String msg) {
        String s = "{\"ok\":" + (ok ? "true" : "false") + ",\"msg\":\"" + msg.replace("\"", "\\\"") + "\"}";
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    private String getLocalIpAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || !nif.getName().startsWith("wlan")) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Bitmap generateQrBitmap(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            bmp.setPixels(pixels, 0, width, 0, 0, width, height);
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "QR generation failed", e);
            return null;
        }
    }
}
