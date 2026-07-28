package nz.co.thor.brightnesscontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView permissionStatus;
    private TextView captureMessage;
    private TextView captureLabel;
    private TextView stepLabel;
    private TextView repeatLabel;
    private TextView rootStatus;
    private TextView brighterMappingLabel;
    private TextView dimmerMappingLabel;
    private TextView safetyLabel;
    private Switch enabledSwitch;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (sharedPreferences, key) -> {
                if (Prefs.ENABLED.equals(key) && enabledSwitch != null) {
                    boolean enabled = sharedPreferences.getBoolean(Prefs.ENABLED, true);
                    if (enabledSwitch.isChecked() != enabled) {
                        enabledSwitch.setChecked(enabled);
                    }
                }
            };
    private boolean capturing;
    private String capturePreference;
    private String captureTitle;
    private boolean captureAllowsVolume;

    @Override
    protected void onCreate(Bundle state) {
        prefs = Prefs.get(this);
        prefs.edit().putInt(Prefs.THEME, Prefs.THEME_SYSTEM).apply();
        super.onCreate(state);
        migrateUnsupportedMappings();
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(4), dp(16), dp(14));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Thor’s Lightning ⚡", 22, true);
        heading.addView(title);
        TextView subtitle = text("Dual-Screen Brightness Control", 13, false);
        subtitle.setTextColor(themeColor(android.R.attr.textColorSecondary));
        heading.addView(subtitle);
        header.addView(heading, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Shortcut enabled");
        enabledSwitch.setTextSize(15);
        enabledSwitch.setChecked(prefs.getBoolean(Prefs.ENABLED, true));
        enabledSwitch.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(Prefs.ENABLED, checked).apply());
        header.addView(enabledSwitch);
        root.addView(header, matchMargins(0, 0, 0, 8));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(columns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout setup = card();
        LinearLayout mappings = card();
        LinearLayout behaviour = card();
        columns.addView(setup, weightedCardMargins(0, 0, 6, 0));
        columns.addView(mappings, weightedCardMargins(6, 0, 6, 0));
        columns.addView(behaviour, weightedCardMargins(6, 0, 0, 0));

        setup.addView(section("Setup"));
        permissionStatus = text("", 14, true);
        setup.addView(permissionStatus, margins(0, 4, 0, 5));

        Button writeSettings = button("1. Brightness permission");
        styleSetupButton(writeSettings);
        writeSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        setup.addView(writeSettings, matchMargins(0, 0, 0, 3));

        Button accessibility = button("2. Key detection");
        styleSetupButton(accessibility);
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        setup.addView(accessibility, matchMargins(0, 0, 0, 12));

        addRootControls(setup);

        mappings.addView(section("Button mappings"));
        TextView mappingHint = text("Hold the modifier, then press Bright or Dimmer buttons.", 12, false);
        mappingHint.setTextColor(themeColor(android.R.attr.textColorSecondary));
        mappings.addView(mappingHint, margins(0, 3, 0, 7));
        addMappingRow(mappings, "Modifier", Prefs.MODIFIER,
                prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1), false);
        addMappingRow(mappings, "Brighter", Prefs.UP_KEY,
                prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP), true);
        addMappingRow(mappings, "Dimmer", Prefs.DOWN_KEY,
                prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN), true);
        captureMessage = text("Use Volume, face, shoulder, stick-click, Start or Select buttons. D-pad and stick directions require root.", 12, false);
        captureMessage.setTextColor(themeColor(android.R.attr.textColorSecondary));
        mappings.addView(captureMessage, margins(0, 7, 0, 6));

        CheckBox consume = new CheckBox(this);
        consume.setText("Reserve modifier button");
        consume.setTextSize(14);
        consume.setTranslationX(-dp(8));
        consume.setChecked(prefs.getBoolean(Prefs.CONSUME_MODIFIER, false));
        consume.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                prefs.edit().putBoolean(Prefs.CONSUME_MODIFIER, false).apply();
                return;
            }
            String modifierName = keyName(
                    prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1));
            new AlertDialog.Builder(this)
                    .setTitle("Reserve modifier button?")
                    .setMessage("This blocks whichever modifier you have selected from games whenever it is pressed "
                            + "(currently " + modifierName + "). If you remap the modifier, the newly selected button "
                            + "will be blocked instead. Brighter and Dimmer buttons are already reserved only while the modifier is held.")
                    .setPositiveButton("Reserve", (dialog, which) ->
                            prefs.edit().putBoolean(Prefs.CONSUME_MODIFIER, true).apply())
                    .setNegativeButton("Cancel", (dialog, which) ->
                            consume.setChecked(false))
                    .setOnCancelListener(dialog -> consume.setChecked(false))
                    .show();
        });
        mappings.addView(consume, matchMargins(0, 0, 0, 0));
        TextView consumeHint = text(
                "Brighter and Dimmer buttons are always blocked while the modifier is held. This option also blocks the modifier itself.",
                11, false);
        consumeHint.setTextColor(themeColor(android.R.attr.textColorSecondary));
        mappings.addView(consumeHint, margins(0, 0, 0, 2));

        addBehaviourControls(behaviour);
        setContentView(root);
        root.post(this::maybeShowSetupGuide);
    }

    private void addRootControls(LinearLayout parent) {
        parent.addView(section("Root axis input"), margins(0, 8, 0, 0));
        Switch rootAxes = new Switch(this);
        rootAxes.setText("Enable D-pad / Joystick");
        rootAxes.setTextSize(14);
        rootAxes.setChecked(prefs.getBoolean(Prefs.ROOT_AXES, false));
        parent.addView(rootAxes);

        rootStatus = text("", 12, false);
        rootStatus.setTextColor(themeColor(android.R.attr.textColorSecondary));
        parent.addView(rootStatus, margins(0, 1, 0, 2));

        Button checkRoot = button("Check root access");
        checkRoot.setOnClickListener(v -> {
            checkRoot.setEnabled(false);
            rootStatus.setText("Checking root access…");
            new Thread(() -> {
                boolean available = hasRootAccess();
                runOnUiThread(() -> {
                    checkRoot.setEnabled(true);
                    rootStatus.setText(available
                            ? "✓ Root access available"
                            : "○ Root unavailable or permission denied");
                    rootStatus.setTextColor(available
                            ? Color.rgb(24, 110, 50) : Color.rgb(150, 80, 20));
                });
            }, "ThorRootCheck").start();
        });
        parent.addView(checkRoot, matchMargins(0, 2, 0, 2));

        RadioGroup axisSources = new RadioGroup(this);
        axisSources.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton dpadAxis = radio("D-pad", Prefs.AXIS_DPAD);
        RadioButton leftAxis = radio("L stick", Prefs.AXIS_LEFT_STICK);
        RadioButton rightAxis = radio("R stick", Prefs.AXIS_RIGHT_STICK);
        axisSources.addView(dpadAxis, new RadioGroup.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        axisSources.addView(leftAxis, new RadioGroup.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        axisSources.addView(rightAxis, new RadioGroup.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int savedAxis = prefs.getInt(Prefs.AXIS_SOURCE, Prefs.AXIS_DPAD);
        (savedAxis == Prefs.AXIS_RIGHT_STICK ? rightAxis
                : savedAxis == Prefs.AXIS_LEFT_STICK ? leftAxis : dpadAxis).setChecked(true);
        axisSources.setEnabled(rootAxes.isChecked());
        setChildrenEnabled(axisSources, rootAxes.isChecked());
        axisSources.setOnCheckedChangeListener((group, checkedId) -> {
            View selected = group.findViewById(checkedId);
            if (selected != null && selected.getTag() instanceof Integer) {
                prefs.edit().putInt(Prefs.AXIS_SOURCE, (Integer) selected.getTag()).apply();
                updateRootMappingLabels();
            }
        });
        parent.addView(axisSources);

        rootAxes.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !hasRootAccess()) {
                button.setChecked(false);
                Toast.makeText(this, "Root access was not granted", Toast.LENGTH_LONG).show();
                updateRootStatus(false);
                return;
            }
            prefs.edit().putBoolean(Prefs.ROOT_AXES, checked).apply();
            setChildrenEnabled(axisSources, checked);
            updateRootStatus(checked);
            updateRootMappingLabels();
            updateSafetyText();
            if (checked && !prefs.getBoolean(Prefs.ROOT_LIMIT_ACK, false)) {
                new AlertDialog.Builder(this)
                        .setTitle("Root input limitation")
                        .setMessage("Root mode can read D-pad and stick directions, but cannot hide those axis movements from games. The Thor’s L2/R2 triggers also report analog axes, so a game may still detect a reserved trigger.")
                        .setPositiveButton("Continue", (dialog, which) ->
                                prefs.edit().putBoolean(Prefs.ROOT_LIMIT_ACK, true).apply())
                        .setNegativeButton("Cancel", (dialog, which) ->
                                rootAxes.setChecked(false))
                        .setOnCancelListener(dialog -> rootAxes.setChecked(false))
                        .show();
            }
        });
        updateRootStatus(rootAxes.isChecked());
    }

    private void addBehaviourControls(LinearLayout behaviour) {
        behaviour.addView(section("Screens to adjust"));
        RadioGroup targets = new RadioGroup(this);
        targets.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton both = radio("Both", Prefs.TARGET_BOTH);
        RadioButton top = radio("Top", Prefs.TARGET_TOP);
        RadioButton bottom = radio("Bottom", Prefs.TARGET_BOTTOM);
        targets.addView(both, new RadioGroup.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        targets.addView(top, new RadioGroup.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        targets.addView(bottom, new RadioGroup.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int savedTarget = prefs.getInt(Prefs.TARGET, Prefs.TARGET_BOTH);
        (savedTarget == Prefs.TARGET_TOP ? top
                : savedTarget == Prefs.TARGET_BOTTOM ? bottom : both).setChecked(true);
        targets.setOnCheckedChangeListener((group, checkedId) -> {
            View selected = group.findViewById(checkedId);
            if (selected != null && selected.getTag() instanceof Integer) {
                prefs.edit().putInt(Prefs.TARGET, (Integer) selected.getTag()).apply();
            }
        });
        behaviour.addView(targets, matchMargins(0, 0, 0, 10));

        behaviour.addView(section("Brightness step"));
        stepLabel = text("", 16, true);
        behaviour.addView(stepLabel);
        SeekBar step = new SeekBar(this);
        step.setMax(24);
        step.setProgress(prefs.getInt(Prefs.STEP, 5) - 1);
        updateStepLabel(step.getProgress() + 1);
        step.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int percent = value + 1;
                updateStepLabel(percent);
                if (fromUser) prefs.edit().putInt(Prefs.STEP, percent).apply();
            }
        });
        behaviour.addView(step);

        behaviour.addView(section("Hold repeat speed"), margins(0, 8, 0, 0));
        repeatLabel = text("", 16, true);
        behaviour.addView(repeatLabel);
        SeekBar repeat = new SeekBar(this);
        repeat.setMax(18);
        int savedDelay = prefs.getInt(Prefs.REPEAT_DELAY, 180);
        repeat.setProgress(Math.max(0, Math.min(18, (savedDelay - 100) / 50)));
        updateRepeatLabel(100 + repeat.getProgress() * 50);
        repeat.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int delay = 100 + value * 50;
                updateRepeatLabel(delay);
                if (fromUser) prefs.edit().putInt(Prefs.REPEAT_DELAY, delay).apply();
            }
        });
        behaviour.addView(repeat);

        safetyLabel = text("", 12, false);
        safetyLabel.setTextColor(Color.rgb(80, 60, 20));
        safetyLabel.setBackgroundColor(Color.rgb(255, 246, 210));
        safetyLabel.setPadding(dp(10), dp(7), dp(10), dp(7));
        updateSafetyText();
        behaviour.addView(safetyLabel, margins(0, 10, 0, 0));

    }

    private void addMappingRow(LinearLayout parent, String title, String preference,
                               int currentKey, boolean allowsVolume) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        TextView label = text(mappingLabel(title, currentKey), 14, true);
        if (Prefs.UP_KEY.equals(preference)) brighterMappingLabel = label;
        if (Prefs.DOWN_KEY.equals(preference)) dimmerMappingLabel = label;
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button record = button("Record");
        record.setOnClickListener(v ->
                beginCapture(preference, label, title, allowsVolume));
        row.addView(record, new LinearLayout.LayoutParams(
                dp(94), dp(44)));
        parent.addView(row, matchMargins(0, 0, 0, 0));
    }

    private void beginCapture(String preference, TextView label, String title,
                              boolean allowsVolume) {
        capturing = true;
        capturePreference = preference;
        captureLabel = label;
        captureTitle = title;
        captureAllowsVolume = allowsVolume;
        prefs.edit().putBoolean(Prefs.CAPTURING, true).apply();
        captureMessage.setText("Listening for " + title + "… Back cancels.");
        captureMessage.setTextColor(themeColor(android.R.attr.colorAccent));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!capturing) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return true;
        if (code == KeyEvent.KEYCODE_BACK) {
            endCapture(false, 0);
            return true;
        }
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_HOME
                || (!captureAllowsVolume && (code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN))) {
            Toast.makeText(this, "That system key cannot be used here", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (isDpadKey(code)) {
            if (prefs.getBoolean(Prefs.ROOT_AXES, false)
                    && !capturePreference.equals(Prefs.MODIFIER)) {
                prefs.edit().putInt(Prefs.AXIS_SOURCE, Prefs.AXIS_DPAD)
                        .putBoolean(Prefs.CAPTURING, false).apply();
                capturing = false;
                updateRootMappingLabels();
                captureMessage.setText("Root D-pad selected: Up brightens and Down dims.");
                captureMessage.setTextColor(themeColor(android.R.attr.textColorSecondary));
                return true;
            }
            Toast.makeText(this,
                    "Enable and grant Root axis input before selecting the D-pad",
                    Toast.LENGTH_LONG).show();
            return true;
        }
        int modifier = prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1);
        int up = prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP);
        int down = prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN);
        if ((capturePreference.equals(Prefs.MODIFIER) && (code == up || code == down))
                || (capturePreference.equals(Prefs.UP_KEY) && (code == modifier || code == down))
                || (capturePreference.equals(Prefs.DOWN_KEY) && (code == modifier || code == up))) {
            Toast.makeText(this, "Each mapping must use a different button", Toast.LENGTH_SHORT).show();
            return true;
        }
        endCapture(true, code);
        return true;
    }

    private void endCapture(boolean save, int code) {
        capturing = false;
        prefs.edit().putBoolean(Prefs.CAPTURING, false).apply();
        if (save) {
            prefs.edit().putInt(capturePreference, code).apply();
            captureLabel.setText(captureTitle + ": " + keyName(code));
            captureMessage.setText("Saved " + captureTitle + " as " + keyName(code) + ".");
            updateSafetyText();
        } else {
            captureMessage.setText("Recording cancelled.");
        }
        captureMessage.setTextColor(themeColor(android.R.attr.textColorSecondary));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (permissionStatus != null) updatePermissionStatus();
        if (enabledSwitch != null) {
            enabledSwitch.setChecked(prefs.getBoolean(Prefs.ENABLED, true));
        }
        if (prefs.getBoolean(Prefs.AWAITING_STEP_TWO, false)
                && Settings.System.canWrite(this) && !isAccessibilityServiceEnabled()) {
            prefs.edit().putBoolean(Prefs.AWAITING_STEP_TWO, false).apply();
            showStepTwoGuide();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    @Override
    protected void onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        super.onStop();
    }

    @Override
    protected void onPause() {
        if (capturing) endCapture(false, 0);
        super.onPause();
    }

    private void updatePermissionStatus() {
        boolean write = Settings.System.canWrite(this);
        boolean service = isAccessibilityServiceEnabled();
        String status;
        if (write && service) {
            status = "✓ Ready — permissions enabled";
        } else if (!write && !service) {
            status = "○ Brightness and key detection needed";
        } else if (!write) {
            status = "○ Brightness permission needed";
        } else {
            status = "○ Key detection needed";
        }
        permissionStatus.setText(status);
        permissionStatus.setTextColor(write && service ? Color.rgb(24, 110, 50) : Color.rgb(150, 80, 20));
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName ours = new ComponentName(this, BrightnessKeyService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName component = ComponentName.unflattenFromString(splitter.next());
            if (ours.equals(component)) return true;
        }
        return false;
    }

    private void maybeShowSetupGuide() {
        if (prefs.getBoolean(Prefs.SETUP_GUIDE_SHOWN, false)
                || (Settings.System.canWrite(this) && isAccessibilityServiceEnabled())) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Two-step setup")
                .setMessage("Step 1 — Brightness permission\n"
                        + "Android will open “Modify system settings”. Turn on “Allow modifying system settings” for Thor’s Lightning, then come back.\n\n"
                        + "Step 2 — Key detection\n"
                        + "Android will open Accessibility. Select Thor’s Lightning and enable it. The service reads controller-button presses only; it does not inspect screen content.")
                .setPositiveButton("Start step 1", (dialog, which) -> {
                    prefs.edit()
                            .putBoolean(Prefs.SETUP_GUIDE_SHOWN, true)
                            .putBoolean(Prefs.AWAITING_STEP_TWO, true)
                            .apply();
                    startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                })
                .setNegativeButton("Later", (dialog, which) ->
                        prefs.edit().putBoolean(Prefs.SETUP_GUIDE_SHOWN, true).apply())
                .show();
    }

    private void showStepTwoGuide() {
        new AlertDialog.Builder(this)
                .setTitle("Step 2 — Enable key detection")
                .setMessage("Select Thor’s Lightning in Accessibility and turn it on. "
                        + "This lets the app detect mapped controller buttons in the background. "
                        + "It does not read or inspect screen content.")
                .setPositiveButton("Open Accessibility", (dialog, which) ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Later", null)
                .show();
    }

    private void updateStepLabel(int percent) {
        stepLabel.setText(percent + "% per press");
    }

    private void updateRepeatLabel(int delay) {
        repeatLabel.setText(delay + " ms between steps");
    }

    private boolean hasRootAccess() {
        try {
            Process process = new ProcessBuilder("su", "-c", "id").start();
            int result = process.waitFor();
            return result == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateRootStatus(boolean enabled) {
        if (rootStatus == null) return;
        rootStatus.setText(enabled
                ? "Root active — hold your modifier and move the selected control up/down."
                : "Optional; existing mappings still work without root.");
    }

    private void setChildrenEnabled(ViewGroup group, boolean enabled) {
        for (int index = 0; index < group.getChildCount(); index++) {
            group.getChildAt(index).setEnabled(enabled);
        }
    }

    private String keyName(int code) {
        return KeyEvent.keyCodeToString(code).replace("KEYCODE_", "")
                .replace("BUTTON_", "").replace('_', ' ');
    }

    private String mappingLabel(String title, int fallbackKey) {
        if (!prefs.getBoolean(Prefs.ROOT_AXES, false)
                || (!"Brighter".equals(title) && !"Dimmer".equals(title))) {
            return title + ": " + keyName(fallbackKey);
        }
        int source = prefs.getInt(Prefs.AXIS_SOURCE, Prefs.AXIS_DPAD);
        String control = source == Prefs.AXIS_RIGHT_STICK ? "R stick"
                : source == Prefs.AXIS_LEFT_STICK ? "L stick" : "D-pad";
        return title + ": " + control + ("Brighter".equals(title) ? " Up (root)" : " Down (root)");
    }

    private void updateRootMappingLabels() {
        if (brighterMappingLabel != null) {
            brighterMappingLabel.setText(mappingLabel("Brighter",
                    prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP)));
        }
        if (dimmerMappingLabel != null) {
            dimmerMappingLabel.setText(mappingLabel("Dimmer",
                    prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN)));
        }
    }

    private void updateSafetyText() {
        if (safetyLabel == null) return;
        String modifier = keyName(prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1));
        String up = keyName(prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP));
        String down = keyName(prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN));
        if (prefs.getBoolean(Prefs.ROOT_AXES, false)) {
            safetyLabel.setText("Root safety: press " + modifier + " + " + up + " + " + down
                    + " together to disable. These are the saved fallback buttons; analog directions cannot be pressed together.");
        } else {
            safetyLabel.setText("Safety: press " + modifier + " + " + up + " + " + down
                    + " together to disable. Mapped buttons work normally without the modifier.");
        }
    }

    private void migrateUnsupportedMappings() {
        int up = prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP);
        int down = prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        if (isDpadKey(up)) {
            editor.putInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP);
            changed = true;
        }
        if (isDpadKey(down)) {
            editor.putInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN);
            changed = true;
        }
        if (changed) editor.apply();
    }

    private boolean isDpadKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    private TextView section(String value) {
        TextView view = text(value, 19, true);
        view.setTextColor(themeColor(android.R.attr.colorAccent));
        return view;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private void styleSetupButton(Button button) {
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(20), 0, dp(20), 0);
        button.setMinHeight(dp(48));
    }

    private RadioButton radio(String value, int target) {
        RadioButton radio = new RadioButton(this);
        radio.setId(View.generateViewId());
        radio.setText(value);
        radio.setTextSize(14);
        radio.setTag(target);
        return radio;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(6), dp(14), dp(12));
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? Color.rgb(54, 52, 57) : Color.rgb(247, 243, 249));
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), dark
                ? Color.rgb(82, 79, 88) : Color.rgb(222, 216, 226));
        card.setBackground(background);
        return card;
    }

    private LinearLayout.LayoutParams weightedCardMargins(
            int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams matchMargins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int themeColor(int attribute) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        if (value.resourceId != 0) {
            try {
                return getResources().getColor(value.resourceId, getTheme());
            } catch (Resources.NotFoundException ignored) {
                // Fall through to a directly resolved color.
            }
        }
        return value.data;
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
