package com.example.shareclipboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final Pattern LOOPBACK_CHECK =
            Pattern.compile("(?i)://(127\\.0\\.0\\.1|localhost)([:/?#\\s]|$)");
    private static final Pattern LOOPBACK_HOST =
            Pattern.compile("(?i)(://)(127\\.0\\.0\\.1|localhost)(?=[:/?#\\s]|$)");

    private EditText editClipText;
    private Button btnShare;
    private Button btnPlay;
    private Button btnOpenVlc;
    private ListView listDevices;
    private TextView statusText;

    private NsdHelper nsdHelper;
    private ClipboardServer server;

    private final List<NsdServiceInfo> discoveredDevices = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private NsdServiceInfo selectedDevice = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        editClipText = findViewById(R.id.editClipText);
        btnShare = findViewById(R.id.btnShare);
        btnPlay = findViewById(R.id.btnPlay);
        btnOpenVlc = findViewById(R.id.btnOpenVlc);
        listDevices = findViewById(R.id.listDevices);
        statusText = findViewById(R.id.statusText);

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        listDevices.setAdapter(adapter);

        listDevices.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < discoveredDevices.size()) {
                selectedDevice = discoveredDevices.get(position);
                Toast.makeText(this, "Selected: " + selectedDevice.getServiceName(), Toast.LENGTH_SHORT).show();
            }
        });

        server = new ClipboardServer((text, senderAddress) ->
                runOnUiThread(() -> showIncomingDialog(text, senderAddress)));
        server.start(port -> runOnUiThread(() -> startNetworkServices(port)));

        btnShare.setOnClickListener(v -> shareClipboard());

        btnPlay.setOnClickListener(v -> {
            String link = editClipText.getText().toString().trim();
            if (link.isEmpty()) {
                Toast.makeText(this, "Paste a link in the box first", Toast.LENGTH_SHORT).show();
            } else {
                playInApp(link);
            }
        });

        btnOpenVlc.setOnClickListener(v -> {
            String link = editClipText.getText().toString().trim();
            if (link.isEmpty()) {
                Toast.makeText(this, "Paste a link in the box first", Toast.LENGTH_SHORT).show();
            } else {
                openInVlc(link);
            }
        });
    }

    private void startNetworkServices(int port) {
        if (isFinishing() || isDestroyed() || nsdHelper != null) {
            return;
        }
        String deviceName = Build.MODEL + "-" + (1000 + new Random().nextInt(9000));
        nsdHelper = new NsdHelper(
                this,
                info -> addDevice(info),
                info -> removeDevice(info)
        );
        nsdHelper.registerService(port, deviceName);
        nsdHelper.startDiscovery();
        statusText.setText("Running as " + deviceName + " — looking for nearby devices…");
    }

    private void addDevice(NsdServiceInfo info) {
        runOnUiThread(() -> {
            for (NsdServiceInfo existing : discoveredDevices) {
                if (existing.getServiceName().equals(info.getServiceName())) {
                    return;
                }
            }
            discoveredDevices.add(info);
            refreshList();
        });
    }

    private void removeDevice(NsdServiceInfo info) {
        runOnUiThread(() -> {
            String lostName = info.getServiceName();
            discoveredDevices.removeIf(d -> d.getServiceName().equals(lostName));
            if (selectedDevice != null && selectedDevice.getServiceName().equals(lostName)) {
                selectedDevice = null;
            }
            refreshList();
        });
    }

    private void refreshList() {
        adapter.clear();
        for (NsdServiceInfo device : discoveredDevices) {
            adapter.add(device.getServiceName());
        }
        adapter.notifyDataSetChanged();
        if (discoveredDevices.isEmpty()) {
            statusText.setText("No devices found yet — make sure the other phone has ClipShare open on the same Wi-Fi.");
        } else {
            statusText.setText(discoveredDevices.size() + " device(s) found.");
        }
    }

    private void shareClipboard() {
        final NsdServiceInfo target = selectedDevice;
        if (target == null) {
            Toast.makeText(this, "Pick a device from the list first", Toast.LENGTH_SHORT).show();
            return;
        }

        String textToSend = editClipText.getText().toString().trim();
        if (textToSend.isEmpty()) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence coerced = clip.getItemAt(0).coerceToText(this);
                if (coerced != null) {
                    textToSend = coerced.toString();
                }
            }
        }

        if (textToSend.isEmpty()) {
            Toast.makeText(this, "Nothing to share — type/paste something or copy it first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (containsLoopbackHost(textToSend)) {
            String localIp = getLocalIpAddress();
            if (localIp == null) {
                Toast.makeText(
                        this,
                        "That link points at 127.0.0.1/localhost and I couldn't determine this phone's Wi-Fi IP to fix it — the other device won't be able to reach it.",
                        Toast.LENGTH_LONG
                ).show();
            } else {
                String rewritten = rewriteLoopbackHost(textToSend, localIp);
                Toast.makeText(this, "Rewrote localhost link to use this phone's IP (" + localIp + ")", Toast.LENGTH_SHORT).show();
                textToSend = rewritten;
            }
        }

        InetAddress hostAddress = target.getHost();
        if (hostAddress == null || hostAddress.getHostAddress() == null) {
            Toast.makeText(this, "Could not resolve that device's address yet, try again", Toast.LENGTH_SHORT).show();
            return;
        }
        final String host = hostAddress.getHostAddress();
        final int port = target.getPort();
        final String payload = textToSend;

        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                writer.write(payload);
                writer.flush();
                socket.shutdownOutput();
                runOnUiThread(() -> Toast.makeText(this, "Sent to " + target.getServiceName(), Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "Send failed", e);
                runOnUiThread(() -> Toast.makeText(this, "Send failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private boolean containsLoopbackHost(String text) {
        return LOOPBACK_CHECK.matcher(text).find();
    }

    private String rewriteLoopbackHost(String text, String replacementIp) {
        Matcher matcher = LOOPBACK_HOST.matcher(text);
        return matcher.replaceAll("$1" + Matcher.quoteReplacement(replacementIp));
    }

    @SuppressWarnings("deprecation")
    private String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ipInt = wifiManager.getConnectionInfo().getIpAddress();
            if (ipInt != 0) {
                return String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        ipInt & 0xff,
                        (ipInt >> 8) & 0xff,
                        (ipInt >> 16) & 0xff,
                        (ipInt >> 24) & 0xff
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "WifiManager IP lookup failed", e);
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "NetworkInterface IP lookup failed", e);
        }
        return null;
    }

    private void showIncomingDialog(String text, String senderAddress) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Link from " + senderAddress)
                .setMessage(text)
                .setPositiveButton("Play", (dialog, which) -> {
                    editClipText.setText(text);
                    copyToClipboard(text);
                    playInApp(text);
                })
                .setNeutralButton("Open in VLC", (dialog, which) -> {
                    editClipText.setText(text);
                    copyToClipboard(text);
                    openInVlc(text);
                })
                .setNegativeButton("Copy", (dialog, which) -> {
                    editClipText.setText(text);
                    copyToClipboard(text);
                    Toast.makeText(this, "Copied to your clipboard", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("ClipShare", text));
    }

    private void playInApp(String link) {
        String trimmed = link.trim();
        String scheme = Uri.parse(trimmed).getScheme();
        boolean playable = scheme != null
                && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        if (!playable) {
            Toast.makeText(this, "The in-app player only supports http/https links", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(PlayerActivity.createIntent(this, trimmed));
    }

    private void openInVlc(String link) {
        VlcLauncher.open(this, link.trim());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nsdHelper != null) {
            nsdHelper.stopDiscovery();
            nsdHelper.unregisterService();
        }
        if (server != null) {
            server.stop();
        }
    }
}
