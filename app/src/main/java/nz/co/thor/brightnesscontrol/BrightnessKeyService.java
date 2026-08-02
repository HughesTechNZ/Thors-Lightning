package nz.co.thor.brightnesscontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BrightnessKeyService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean modifierDown;
    private boolean volumeUpDown;
    private boolean volumeDownDown;
    private int activeDirection;
    private int cachedBottomBrightness = -1;
    private final ExecutorService displayExecutor = Executors.newSingleThreadExecutor();
    private RootInputMonitor rootInput;
    private boolean rootMonitorEnabled;
    private int rootMonitorSource = -1;
    private final SharedPreferences.OnSharedPreferenceChangeListener rootPreferenceListener =
            (sharedPreferences, key) -> {
                if (Prefs.ROOT_AXES.equals(key) || Prefs.AXIS_SOURCE.equals(key)) {
                    refreshRootMonitor(sharedPreferences);
                }
            };

    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            if (modifierDown && activeDirection != 0 && isRemappingEnabled()) {
                int holdStep = Prefs.get(BrightnessKeyService.this).getInt(Prefs.HOLD_STEP,
                        Prefs.get(BrightnessKeyService.this).getInt(Prefs.STEP, 5));
                adjustBrightness(activeDirection, holdStep);
                handler.postDelayed(this, repeatDelay());
            }
        }
    };

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        SharedPreferences prefs = Prefs.get(this);
        refreshRootMonitor(prefs);
        if (prefs.getBoolean(Prefs.CAPTURING, false)) {
            return false;
        }

        int keyCode = event.getKeyCode();
        int modifier = prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1);
        int upKey = prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP);
        int downKey = prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean reserveModifier = prefs.getBoolean(Prefs.CONSUME_MODIFIER, false);

        if (keyCode == modifier) {
            modifierDown = down;
            if (down) suspendConflictingService();
            else restoreConflictingService();
            if (!down) {
                stopRepeating();
            }
            return isRemappingEnabled() && reserveModifier;
        }

        if (keyCode == upKey) {
            volumeUpDown = down;
        } else if (keyCode == downKey) {
            volumeDownDown = down;
        } else {
            return false;
        }

        boolean enabled = isRemappingEnabled();
        if (!modifierDown || !enabled || !isScreenInteractive()) {
            // Leave ordinary volume presses alone so other volume-control apps
            // can continue to receive them. Volume is consumed only when the
            // modifier is held and this service is actively remapping it.
            return false;
        }

        if (volumeUpDown && volumeDownDown) {
            emergencyDisable();
            return true;
        }

        if (down) {
            int direction = keyCode == upKey ? 1 : -1;
            if (event.getRepeatCount() == 0) {
                int pressStep = Prefs.get(this).getInt(Prefs.PRESS_STEP,
                        Prefs.get(this).getInt(Prefs.STEP, 5));
                adjustBrightness(direction, pressStep);
                activeDirection = direction;
                handler.removeCallbacks(repeater);
                handler.postDelayed(repeater, 450);
            }
        } else if ((keyCode == upKey && activeDirection > 0)
                || (keyCode == downKey && activeDirection < 0)) {
            stopRepeating();
        }
        return true;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        restoreConflictingService();
        rootInput = new RootInputMonitor(handler, new RootInputMonitor.Listener() {
            @Override public void onDirection(int direction) {
                handleRootDirection(direction);
            }
            @Override public void onStopped() {
                rootMonitorEnabled = false;
                stopRepeating();
            }
        });
        SharedPreferences prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(rootPreferenceListener);
        refreshRootMonitor(prefs);
    }

    private void refreshRootMonitor(SharedPreferences prefs) {
        if (rootInput == null) return;
        boolean enabled = prefs.getBoolean(Prefs.ROOT_AXES, false);
        int source = prefs.getInt(Prefs.AXIS_SOURCE, Prefs.AXIS_DPAD);
        if (enabled == rootMonitorEnabled && (!enabled || source == rootMonitorSource)) return;
        rootInput.stop();
        rootMonitorEnabled = enabled;
        rootMonitorSource = source;
        if (enabled) rootInput.start(source);
    }

    private void handleRootDirection(int direction) {
        if (!modifierDown || !isRemappingEnabled() || !isScreenInteractive()) {
            if (direction == 0) stopRepeating();
            return;
        }
        if (direction == 0) {
            stopRepeating();
        } else if (activeDirection != direction) {
            int pressStep = Prefs.get(this).getInt(Prefs.PRESS_STEP,
                    Prefs.get(this).getInt(Prefs.STEP, 5));
            adjustBrightness(direction, pressStep);
            activeDirection = direction;
            handler.removeCallbacks(repeater);
            handler.postDelayed(repeater, 450);
        }
    }

    private boolean isRemappingEnabled() {
        return Prefs.get(this).getBoolean(Prefs.ENABLED, true);
    }

    private boolean isScreenInteractive() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power != null && power.isInteractive();
    }

    private int repeatDelay() {
        return Prefs.get(this).getInt(Prefs.REPEAT_DELAY, 180);
    }

    private void adjustBrightness(int direction, int stepPercent) {
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, "Allow Modify system settings for Thor’s Lightning", Toast.LENGTH_LONG).show();
            stopRepeating();
            return;
        }

        try {
            int target = Prefs.get(this).getInt(Prefs.TARGET, Prefs.TARGET_BOTH);

            if (target == Prefs.TARGET_BOTH || target == Prefs.TARGET_TOP) {
                int currentTop = readSystemInt(Settings.System.SCREEN_BRIGHTNESS, 128);
                int topDelta = Math.max(1, Math.round(255f * stepPercent / 100f));
                int nextTop = clamp(currentTop + direction * topDelta, 1, 255);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, nextTop);
            }

            if (target == Prefs.TARGET_BOTH || target == Prefs.TARGET_BOTTOM) {
            int currentBottom = readBottomBrightness();
            int bottomDelta = Math.max(1, Math.round(255f * stepPercent / 100f));
            int nextBottom = clamp(currentBottom + direction * bottomDelta, 1, 255);
            cachedBottomBrightness = nextBottom;
            setAynDisplayBrightness(4, nextBottom);
            }
        } catch (RuntimeException exception) {
            Toast.makeText(this, "Brightness change failed safely", Toast.LENGTH_LONG).show();
            stopRepeating();
        }
    }

    private int readSystemInt(String key, int fallback) {
        try {
            return Settings.System.getInt(getContentResolver(), key);
        } catch (Settings.SettingNotFoundException e) {
            return fallback;
        }
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int readBottomBrightness() {
        if (cachedBottomBrightness >= 0) {
            return cachedBottomBrightness;
        }
        int stored;
        try {
            stored = Settings.Secure.getInt(getContentResolver(),
                    "dual_screen_brightness_level");
        } catch (Settings.SettingNotFoundException exception) {
            stored = 50;
        }
        cachedBottomBrightness = clamp(Math.round(stored * 2.55f), 1, 255);
        return cachedBottomBrightness;
    }

    private void setAynDisplayBrightness(int displayId, int value) {
        displayExecutor.execute(() -> {
            if (!AynDisplayController.setBrightness(displayId, value)) {
                handler.post(() -> Toast.makeText(this,
                        "Lower-screen control was refused by AYN Settings",
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void emergencyDisable() {
        Prefs.get(this).edit().putBoolean(Prefs.ENABLED, false).apply();
        Toast.makeText(this, "Brightness shortcut disabled — reopen the app to enable it", Toast.LENGTH_LONG).show();
        modifierDown = false;
        stopRepeating();
    }

    private void stopRepeating() {
        activeDirection = 0;
        handler.removeCallbacks(repeater);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // This service intentionally does not inspect screen content or events.
    }

    @Override
    public void onInterrupt() {
        restoreConflictingService();
        modifierDown = false;
        volumeUpDown = false;
        volumeDownDown = false;
        stopRepeating();
    }

    @Override
    public void onDestroy() {
        restoreConflictingService();
        handler.removeCallbacksAndMessages(null);
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(rootPreferenceListener);
        if (rootInput != null) rootInput.stop();
        displayExecutor.shutdownNow();
        super.onDestroy();
    }

    private void suspendConflictingService() {
        SharedPreferences p = Prefs.get(this);
        if (!p.getBoolean(Prefs.SUSPEND_SERVICE, false)) return;
        java.util.Set<String> components = selectedServices(p);
        if (!components.isEmpty() && RootAccessibilityController.setSuspended(this, components, true)) {
            p.edit().putBoolean(Prefs.SUSPEND_SERVICE_ACTIVE, true).apply();
        }
    }

    private void restoreConflictingService() {
        SharedPreferences p = Prefs.get(this);
        if (!p.getBoolean(Prefs.SUSPEND_SERVICE_ACTIVE, false)) return;
        java.util.Set<String> components = selectedServices(p);
        if (!components.isEmpty()) {
            if (RootAccessibilityController.setSuspended(this, components, false)) {
                p.edit().putBoolean(Prefs.SUSPEND_SERVICE_ACTIVE, false).apply();
            }
        }
    }

    private java.util.Set<String> selectedServices(SharedPreferences p) {
        java.util.Set<String> selected = new java.util.HashSet<>(p.getStringSet(
                Prefs.SUSPEND_SERVICE_COMPONENTS, java.util.Collections.emptySet()));
        if (selected.isEmpty()) {
            String legacy = p.getString(Prefs.SUSPEND_SERVICE_COMPONENT, "");
            if (legacy.length() > 0) selected.add(legacy);
        }
        return selected;
    }
}
