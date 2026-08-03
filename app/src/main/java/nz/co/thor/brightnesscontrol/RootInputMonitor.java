package nz.co.thor.brightnesscontrol;

import android.os.Handler;
import java.io.BufferedReader;
import java.io.InputStreamReader;

final class RootInputMonitor {
    interface Listener {
        void onDirection(int direction);
        void onStopped();
    }

    private final Handler mainHandler;
    private final Listener listener;
    private volatile boolean running;
    private Process process;
    private Thread thread;

    RootInputMonitor(Handler mainHandler, Listener listener) {
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    synchronized void start(int ignoredSource) {
        stop();
        running = true;
        thread = new Thread(this::readEvents, "ThorRootInput");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (process != null) process.destroy();
        if (process != null) process.destroyForcibly();
        process = null;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void readEvents() {
        int dpadX = 0, dpadY = 0, leftX = 0, leftY = 0, rightX = 0, rightY = 0;
        final long captureStart = System.currentTimeMillis();
        try {
            // Read the complete root input stream rather than relying on a
            // fixed event-node number. Firmware updates can renumber the
            // controller device; the axis token still identifies each source.
            process = new ProcessBuilder("su", "-c", "exec getevent -lt")
                    .redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (!line.contains("EV_ABS")) continue;
                // Match complete getevent axis tokens.  Substring matching can
                // misclassify HAT/other axis names as the left stick.
                String axis = "";
                String[] tokens = line.trim().split("\\s+");
                for (String token : tokens) {
                    if (token.equals("ABS_HAT0X") || token.equals("ABS_HAT0Y")
                            || token.equals("ABS_X") || token.equals("ABS_Y")
                            || token.equals("ABS_Z") || token.equals("ABS_RZ")) {
                        axis = token;
                        break;
                    }
                }
                int source = axis.startsWith("ABS_HAT0") ? Prefs.AXIS_DPAD
                        : (axis.equals("ABS_X") || axis.equals("ABS_Y")) ? Prefs.AXIS_LEFT_STICK
                        : (axis.equals("ABS_Z") || axis.equals("ABS_RZ")) ? Prefs.AXIS_RIGHT_STICK : -1;
                if (source < 0) continue;
                int raw = (int) Long.parseUnsignedLong(tokens[tokens.length - 1], 16);
                if (source == Prefs.AXIS_DPAD) {
                    if (axis.equals("ABS_HAT0X")) dpadX = raw; else dpadY = raw;
                    if (System.currentTimeMillis() - captureStart >= 300) emit(source, directionFor(source, dpadX, dpadY));
                } else if (source == Prefs.AXIS_LEFT_STICK) {
                    if (axis.equals("ABS_X")) leftX = raw; else leftY = raw;
                    if (System.currentTimeMillis() - captureStart >= 300) emit(source, directionFor(source, leftX, leftY));
                } else {
                    if (axis.equals("ABS_Z")) rightX = raw; else rightY = raw;
                    if (System.currentTimeMillis() - captureStart >= 300) emit(source, directionFor(source, rightX, rightY));
                }
            }
        } catch (Exception ignored) {
            if (running) mainHandler.post(listener::onStopped);
        } finally {
            running = false;
        }
    }

    private void emit(int source, int direction) {
        mainHandler.post(() -> listener.onDirection(direction == 0 ? 0 : source * 10 + direction));
    }

    private int directionFor(int source, int horizontal, int vertical) {
        if (source == Prefs.AXIS_DPAD) {
            if (horizontal < 0) return Prefs.ROOT_DIRECTION_LEFT;
            if (horizontal > 0) return Prefs.ROOT_DIRECTION_RIGHT;
            if (vertical < 0) return Prefs.ROOT_DIRECTION_UP;
            if (vertical > 0) return Prefs.ROOT_DIRECTION_DOWN;
            return 0;
        }

        // Use a forgiving dead-zone so slightly diagonal pushes still resolve
        // to the intended cardinal direction.
        int threshold = 8000;
        int horizontalMagnitude = Math.abs(horizontal);
        int verticalMagnitude = Math.abs(vertical);
        if (horizontalMagnitude < threshold && verticalMagnitude < threshold) return 0;
        if (horizontalMagnitude >= verticalMagnitude) {
            return horizontal < 0 ? Prefs.ROOT_DIRECTION_LEFT : Prefs.ROOT_DIRECTION_RIGHT;
        }
        return vertical < 0 ? Prefs.ROOT_DIRECTION_UP : Prefs.ROOT_DIRECTION_DOWN;
    }
}
