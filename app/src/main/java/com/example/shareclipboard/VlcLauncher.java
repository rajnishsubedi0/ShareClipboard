package com.example.shareclipboard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import androidx.appcompat.app.AlertDialog;

final class VlcLauncher {
    private static final String VLC_PACKAGE = "org.videolan.vlc";

    private VlcLauncher() {
    }

    static void open(Activity activity, String link) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(link), "video/*");
            intent.setPackage(VLC_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle("VLC not installed")
                    .setMessage("VLC for Android isn't installed on this device. Install it from the Play Store?")
                    .setPositiveButton("Install", (dialog, which) -> {
                        try {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + VLC_PACKAGE)));
                        } catch (ActivityNotFoundException e2) {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + VLC_PACKAGE)));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }
}
