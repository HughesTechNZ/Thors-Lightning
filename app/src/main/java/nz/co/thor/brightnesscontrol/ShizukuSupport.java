package nz.co.thor.brightnesscontrol;

import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;

/** Reports whether Shizuku is available and authorized for this app. */
final class ShizukuSupport {
    private ShizukuSupport() { }

    static boolean available() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String status() {
        try {
            if (!Shizuku.pingBinder()) return "Shizuku unavailable";
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "Shizuku permission needed";
            }
            return Shizuku.getUid() == 0 ? "Shizuku (root)" : "Shizuku (ADB)";
        } catch (Throwable ignored) {
            return "Shizuku unavailable";
        }
    }

    static boolean runningWithoutPermission() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void requestPermission() {
        try {
            Shizuku.requestPermission(1001);
        } catch (Throwable ignored) {
            // Shizuku may stop between detection and the request.
        }
    }
}
