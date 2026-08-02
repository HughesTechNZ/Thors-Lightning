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
    private volatile int rootShellPid = -1;

    RootInputMonitor(Handler mainHandler, Listener listener) {
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    synchronized void start(int source) {
        stop();
        running = true;
        thread = new Thread(() -> readEvents(source), "ThorRootInput");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        int pid = rootShellPid;
        rootShellPid = -1;
        if (pid > 1) {
            try {
                new ProcessBuilder("su", "-c", "kill " + pid).start().waitFor();
            } catch (Exception ignored) {
                // The parent-watch loop is the fallback if explicit shutdown races.
            }
        }
        if (process != null) process.destroy();
        process = null;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void readEvents(int source) {
        String axis = source == Prefs.AXIS_RIGHT_STICK ? "ABS_RZ"
                : source == Prefs.AXIS_LEFT_STICK ? "ABS_Y" : "ABS_HAT0Y";
        try {
            int appPid = android.os.Process.myPid();
            String command = "echo THOR_PID:$$; "
                    // The Thor's controller is exposed as /dev/input/event9 on
                    // current firmware (event12 no longer exists).  Reading
                    // the old node silently produced no D-pad/stick events.
                    + "getevent -lt /dev/input/event9 & child=$!; "
                    + "trap 'kill $child 2>/dev/null' TERM EXIT; "
                    + "while kill -0 " + appPid + " 2>/dev/null; do sleep 1; done; "
                    + "kill $child 2>/dev/null; wait $child";
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.startsWith("THOR_PID:")) {
                    try {
                        rootShellPid = Integer.parseInt(line.substring("THOR_PID:".length()).trim());
                    } catch (NumberFormatException ignored) {
                        rootShellPid = -1;
                    }
                    continue;
                }
                if (!line.contains("EV_ABS") || !line.contains(axis)) continue;
                String[] parts = line.trim().split("\\s+");
                int raw = (int) Long.parseUnsignedLong(parts[parts.length - 1], 16);
                int direction;
                if (source == Prefs.AXIS_DPAD) {
                    direction = raw < 0 ? 1 : raw > 0 ? -1 : 0;
                } else {
                    direction = raw < -12000 ? 1 : raw > 12000 ? -1 : 0;
                }
                mainHandler.post(() -> listener.onDirection(direction));
            }
        } catch (Exception ignored) {
            if (running) mainHandler.post(listener::onStopped);
        } finally {
            running = false;
        }
    }
}
