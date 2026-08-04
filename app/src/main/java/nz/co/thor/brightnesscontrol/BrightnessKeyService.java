package nz.co.thor.brightnesscontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BrightnessKeyService extends AccessibilityService {
    private static final String TAG = "ThorsLightningWake";
    // Temporary diagnostic mode: verify that close writes zero and that the
    // system does not restore brightness on open before re-enabling the ramp.
    private static final boolean WAKE_ZERO_TEST_MODE = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean wakePending;
    private boolean wakeRampActive;
    private int wakeTop = -1;
    private int wakeBottom = -1;
    private int wakeStep;
    private long wakeStartedAt;
    private final ContentObserver wakeBrightnessObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            if (wakePending && !wakeRampActive && isScreenInteractive()) {
                int current = readSystemInt(Settings.System.SCREEN_BRIGHTNESS, 0);
                if (current > 0) writeTopZero();
            }
        }
    };
    private final Runnable dimGuard = new Runnable() {
        @Override public void run() {
            if (!wakePending || isScreenInteractive()) return;
            writeTopZero();
            handler.postDelayed(this, 10);
        }
    };
    private final Runnable wakeZeroGuard = new Runnable() {
        @Override public void run() {
            if (!wakePending || wakeRampActive) return;
            writeWakeBrightness(0, 0);
            handler.postDelayed(this, 10);
        }
    };
    private SensorManager sensorManager;
    private Sensor hallSensor;
    private float lastHallValue = Float.NaN;
    private final SensorEventListener hallListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event.values.length == 0) return;
            float value = event.values[0];
            if (Float.isNaN(lastHallValue)) {
                lastHallValue = value;
                return;
            }
            if (Math.abs(value - lastHallValue) > 0.1f) {
                lastHallValue = value;
                Log.d(TAG, "hall transition value=" + value);
                if (Prefs.get(BrightnessKeyService.this).getBoolean(Prefs.GENTLE_WAKE, false)) {
                    // The Hall transition precedes Android's display wake
                    // restore, so clamp brightness before the visible flash.
                    writeTopZero();
                    setAynDisplayBrightness(4, 0);
                }
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Prefs.get(BrightnessKeyService.this).getBoolean(Prefs.GENTLE_WAKE, false)
                    && !WAKE_ZERO_TEST_MODE) return;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                SharedPreferences p = Prefs.get(BrightnessKeyService.this);
                wakeTop = readSystemInt(Settings.System.SCREEN_BRIGHTNESS, 128);
                wakeBottom = readBottomBrightness();
                p.edit().putInt(Prefs.WAKE_TOP, wakeTop).putInt(Prefs.WAKE_BOTTOM, wakeBottom).apply();
                wakePending = true;
                handler.removeCallbacks(wakeRunnable);
                // Keep the saved target, but leave the displays at a low level
                // so Android cannot visibly restore the old brightness first.
                writeWakeBrightness(0, 0);
                wakeRampActive = false;
                handler.postDelayed(() -> writeWakeBrightness(0, 0), 8);
                handler.postDelayed(() -> writeWakeBrightness(0, 0), 24);
                handler.removeCallbacks(dimGuard);
                handler.post(dimGuard);
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && wakePending) {
                wakeTop = pInt(Prefs.WAKE_TOP, wakeTop);
                wakeBottom = pInt(Prefs.WAKE_BOTTOM, wakeBottom);
                // Apply the dim starting point synchronously before scheduling
                // the small, frequent ramp increments.
                writeWakeBrightness(0, 0);
                handler.removeCallbacks(dimGuard);
                handler.removeCallbacks(wakeZeroGuard);
                handler.post(wakeZeroGuard);
                if (WAKE_ZERO_TEST_MODE) {
                    // Keep enforcing zero after wake; otherwise Android may
                    // restore the top panel level after our one-time write.
                    wakeRampActive = false;
                    handler.removeCallbacks(wakeZeroGuard);
                    handler.post(wakeZeroGuard);
                    Log.d(TAG, "zero-hold test: keeping brightness at zero after wake");
                    return;
                }
                wakeStartedAt = android.os.SystemClock.uptimeMillis();
                Log.d(TAG, "wake ramp start targetTop=" + wakeTop + " targetBottom=" + wakeBottom);
                handler.removeCallbacks(wakeRunnable);
                // Android may restore the previous brightness just after the
                // screen-on broadcast. Hold the minimum briefly before rising.
                handler.postDelayed(() -> writeWakeBrightness(0, 0), 8);
                handler.postDelayed(() -> writeWakeBrightness(0, 0), 24);
                handler.postDelayed(() -> writeWakeBrightness(0, 0), 100);
                handler.postDelayed(() -> writeWakeBrightness(0, 0), 200);
                handler.postDelayed(() -> writeWakeBrightness(0, 0), 300);
                handler.postDelayed(() -> writeWakeBrightness(0, 0), 400);
                handler.postDelayed(() -> {
                    wakeStep = 1;
                    wakeRampActive = true;
                    handler.removeCallbacks(wakeZeroGuard);
                    handler.post(wakeRunnable);
                }, 10000);
            }
        }
    };
    private final Runnable wakeRunnable = new Runnable() {
        @Override public void run() {
            if (!wakePending || wakeTop < 0) return;
            wakeStep++;
            int duration = Math.max(100, Math.min(10000, pInt(Prefs.GENTLE_WAKE_DURATION, 1500)));
            int totalSteps = Math.max(1, duration / 16);
            // Ease in from black. The first part stays deliberately near zero,
            // then rises smoothly toward the saved brightness without a flash.
            float linear = Math.min(1f, wakeStep / (float) totalSteps);
            float fraction = linear * linear * (3f - 2f * linear);
            writeWakeBrightness((int) (wakeTop * fraction), (int) (wakeBottom * fraction));
            if (fraction < 1f) handler.postDelayed(this, 16);
            else {
                wakePending = false;
                Log.d(TAG, "wake ramp complete updates=" + wakeStep
                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - wakeStartedAt));
            }
        }
    };
    private boolean modifierDown;
    private boolean rootModifierDown;
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
                if (Prefs.ROOT_AXES.equals(key)) {
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

        if (keyCode == modifier && !rootModifierMapped(prefs)) {
            modifierDown = down;
            if (down) suspendConflictingService();
            else restoreConflictingService();
            if (!down) {
                stopRepeating();
            }
            return isRemappingEnabled() && reserveModifier;
        }

        // Once a root direction has been recorded, it becomes the active
        // source. Do not also run the configured button mappings (including
        // the default volume keys) at the same time.
        if (prefs.getBoolean(Prefs.ROOT_AXES, false)
                && rootDirectionsMapped(prefs)) {
            volumeUpDown = false;
            volumeDownDown = false;
            return false;
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
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        IntentFilter screenOn = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, screenOn);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
                if (sensor.getName().toLowerCase(java.util.Locale.ROOT).contains("hall effect")) {
                    hallSensor = sensor;
                    boolean registered = sensorManager.registerListener(hallListener, sensor, SensorManager.SENSOR_DELAY_FASTEST);
                    Log.d(TAG, "hall sensor registered=" + registered + " name=" + sensor.getName());
                    break;
                }
            }
            if (hallSensor == null) Log.d(TAG, "no hall effect sensor found");
        }
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false,
                wakeBrightnessObserver);
        // Updating Android's enabled-service list can reconnect this service.
        // Do not restore a conflict while that suspension is still active.
        if (!Prefs.get(this).getBoolean(Prefs.SUSPEND_SERVICE_ACTIVE, false)) {
            restoreConflictingService();
        }
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

    private int pInt(String key, int fallback) {
        return Prefs.get(this).getInt(key, fallback);
    }

    private void writeWakeBrightness(int top, int bottom) {
        try {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            // The top panel is controlled through Android's setting; the Thor
            // display controller's panel ID is valid for the lower screen.
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    Math.max(0, top));
            setAynDisplayBrightness(4, Math.max(0, bottom));
        } catch (Exception ignored) { }
    }

    private void writeTopZero() {
        try {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 0);
        } catch (Exception ignored) { }
    }

    private void refreshRootMonitor(SharedPreferences prefs) {
        if (rootInput == null) return;
        boolean enabled = prefs.getBoolean(Prefs.ROOT_AXES, false);
        if (enabled == rootMonitorEnabled) return;
        rootInput.stop();
        rootMonitorEnabled = enabled;
        rootMonitorSource = enabled ? 0 : -1;
        if (enabled) rootInput.start(0);
    }

    private void handleRootDirection(int encoded) {
        int source = encoded / 10;
        int direction = encoded % 10;
        SharedPreferences prefs = Prefs.get(this);
        if (!prefs.getBoolean(Prefs.ROOT_AXES, false)) {
            rootModifierDown = false;
            modifierDown = false;
            if (direction == 0) stopRepeating();
            return;
        }
        if (rootModifierMapped(prefs)) {
            boolean modifierMatch = source == prefs.getInt(Prefs.ROOT_MODIFIER_SOURCE, -1)
                    && direction == prefs.getInt(Prefs.ROOT_MODIFIER_DIRECTION, 0);
            if (modifierMatch && !rootModifierDown) {
                rootModifierDown = true;
                modifierDown = true;
                suspendConflictingService();
            } else if (direction == 0 && rootModifierDown) {
                rootModifierDown = false;
                modifierDown = false;
                restoreConflictingService();
                stopRepeating();
            }
        }
        if (!rootDirectionsMapped(prefs)) return;
        int brightnessDirection = source == prefs.getInt(Prefs.ROOT_BRIGHT_SOURCE, -1)
                && direction == prefs.getInt(Prefs.ROOT_BRIGHT_DIRECTION, 0) ? 1
                : source == prefs.getInt(Prefs.ROOT_DIM_SOURCE, -1)
                && direction == prefs.getInt(Prefs.ROOT_DIM_DIRECTION, 0) ? -1 : 0;
        if (brightnessDirection == 0) {
            stopRepeating();
            return;
        }
        if (!modifierDown || !isRemappingEnabled() || !isScreenInteractive()) {
            if (direction == 0) stopRepeating();
            return;
        }
        if (direction == 0) {
            stopRepeating();
        } else if (activeDirection != brightnessDirection) {
            int pressStep = Prefs.get(this).getInt(Prefs.PRESS_STEP,
                    Prefs.get(this).getInt(Prefs.STEP, 5));
            adjustBrightness(brightnessDirection, pressStep);
            activeDirection = brightnessDirection;
            handler.removeCallbacks(repeater);
            handler.postDelayed(repeater, 450);
        }
    }

    private boolean isRemappingEnabled() {
        return Prefs.get(this).getBoolean(Prefs.ENABLED, true);
    }

    private boolean rootDirectionsMapped(SharedPreferences prefs) {
        return prefs.getInt(Prefs.ROOT_BRIGHT_DIRECTION, 0) != 0
                && prefs.getInt(Prefs.ROOT_DIM_DIRECTION, 0) != 0;
    }

    private boolean rootModifierMapped(SharedPreferences prefs) {
        return prefs.getBoolean(Prefs.ROOT_AXES, false)
                && prefs.getInt(Prefs.ROOT_MODIFIER_SOURCE, -1) >= 0
                && prefs.getInt(Prefs.ROOT_MODIFIER_DIRECTION, 0) != 0;
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
        // Accessibility may interrupt/reconnect while the enabled-service
        // list is being edited for a modifier hold. Keep the selected conflict
        // suspended until the modifier is actually released.
        if (!Prefs.get(this).getBoolean(Prefs.SUSPEND_SERVICE_ACTIVE, false)) {
            restoreConflictingService();
        }
        modifierDown = false;
        volumeUpDown = false;
        volumeDownDown = false;
        stopRepeating();
    }

    @Override
    public void onDestroy() {
        restoreConflictingService();
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        if (sensorManager != null) sensorManager.unregisterListener(hallListener);
        getContentResolver().unregisterContentObserver(wakeBrightnessObserver);
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(rootPreferenceListener);
        if (rootInput != null) rootInput.stop();
        displayExecutor.shutdownNow();
        super.onDestroy();
    }

    private void suspendConflictingService() {
        SharedPreferences p = Prefs.get(this);
        if (!p.getBoolean(Prefs.SUSPEND_SERVICE, false)) return;
        java.util.Set<String> components = selectedServices(p);
        if (!components.isEmpty()) {
            // Mark active before changing the service list; the write can
            // trigger an immediate service reconnect.
            p.edit().putBoolean(Prefs.SUSPEND_SERVICE_ACTIVE, true).apply();
            if (!RootAccessibilityController.setSuspended(this, components, true)) {
                p.edit().putBoolean(Prefs.SUSPEND_SERVICE_ACTIVE, false).apply();
            }
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
