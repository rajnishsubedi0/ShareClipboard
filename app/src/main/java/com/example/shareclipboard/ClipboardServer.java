package com.example.shareclipboard;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

public class ClipboardServer {

    public interface OnTextReceived {
        void onTextReceived(String text, String senderAddress);
    }

    private static final String TAG = "ClipboardServer";
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_CHARS = 1000000;

    private final OnTextReceived onTextReceived;
    private volatile int port = 0;
    private volatile ServerSocket serverSocket;
    private volatile boolean running = false;

    public ClipboardServer(OnTextReceived onTextReceived) {
        this.onTextReceived = onTextReceived;
    }

    public int getPort() {
        return port;
    }

    public void start(final IntConsumer onStarted) {
        running = true;
        new Thread(() -> {
            try {
                ServerSocket socket = new ServerSocket(0);
                serverSocket = socket;
                if (!running) {
                    socket.close();
                    return;
                }
                port = socket.getLocalPort();
                if (onStarted != null) {
                    onStarted.accept(port);
                }
                while (running) {
                    Socket client = socket.accept();
                    handleClient(client);
                }
            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "Server stopped unexpectedly", e);
                }
            }
        }).start();
    }

    private void handleClient(final Socket client) {
        new Thread(() -> {
            try (Socket socket = client) {
                socket.setSoTimeout(READ_TIMEOUT_MS);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder builder = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    builder.append(buffer, 0, read);
                    if (builder.length() > MAX_CHARS) {
                        Log.w(TAG, "Dropped oversized message");
                        return;
                    }
                }
                String senderAddress = "unknown";
                InetAddress address = socket.getInetAddress();
                if (address != null && address.getHostAddress() != null) {
                    senderAddress = address.getHostAddress();
                }
                onTextReceived.onTextReceived(builder.toString(), senderAddress);
            } catch (Exception e) {
                Log.e(TAG, "Client handling failed", e);
            }
        }).start();
    }

    public void stop() {
        running = false;
        try {
            ServerSocket socket = serverSocket;
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }
}
