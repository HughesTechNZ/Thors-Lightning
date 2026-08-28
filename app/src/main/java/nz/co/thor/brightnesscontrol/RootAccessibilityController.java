package nz.co.thor.brightnesscontrol;

import android.content.Context;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Small, root-only bridge for temporarily removing one accessibility service. */
final class RootAccessibilityController {
    private static final String KEY = "enabled_accessibility_services";
    private RootAccessibilityController() {}

    static boolean setSuspended(Context context, String component, boolean suspended) {
        if (component == null || component.length() == 0) return false;
        String current = read(context);
        if (current == null) return false;
        String next = remove(current, component);
        if (!suspended) {
            next = next.length() == 0 ? component : next + ":" + component;
        }
        return write(next);
    }

    static boolean setSuspended(Context context, java.util.Set<String> components, boolean suspended) {
        boolean ok = true;
        for (String component : components) ok &= setSuspended(context, component, suspended);
        return ok;
    }

    private static String remove(String value, String component) {
        StringBuilder out = new StringBuilder();
        for (String part : value.split(":")) {
            if (part.length() == 0 || part.equalsIgnoreCase(component)) continue;
            if (out.length() > 0) out.append(':');
            out.append(part);
        }
        return out.toString();
    }

    private static String read(Context context) {
        try {
            String value = Settings.Secure.getString(context.getContentResolver(), KEY);
            if (value != null) return value;
        } catch (RuntimeException ignored) { }
        return shell("settings get secure " + KEY);
    }

    private static boolean write(String value) {
        String escaped = value.replace("'", "'\\''");
        return shell("settings put secure " + KEY + " '" + escaped + "'") != null;
    }

    private static String shell(String command) {
        try {
            Process process;
            if (ShizukuSupport.available()) {
                java.lang.reflect.Method method = Class.forName("rikka.shizuku.Shizuku")
                        .getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                method.setAccessible(true);
                process = (Process) method.invoke(null, new Object[]{new String[]{"sh", "-c", command}, null, null});
            } else {
                process = new ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true).start();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            int exit = process.waitFor();
            return exit == 0 ? (line == null ? "" : line.trim()) : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
