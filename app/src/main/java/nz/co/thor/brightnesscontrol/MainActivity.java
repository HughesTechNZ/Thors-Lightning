package nz.co.thor.brightnesscontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.Configuration;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.BackgroundColorSpan;
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
import android.widget.Space;
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
    private TextView modifierMappingLabel;
    private TextView brighterMappingLabel;
    private TextView dimmerMappingLabel;
    private TextView safetyLabel;
    private Button brightnessPermissionButton;
    private Button keyDetectionButton;
    private TextView brightnessStatus;
    private TextView keyStatus;
    private Switch suspendServicesSwitch;
    private Button checkRootButton;
    private boolean rootCheckRunning;
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
    private boolean capturingRootDirection;
    private RootInputMonitor rootDirectionRecorder;
    private String capturePreference;
    private String captureTitle;
    private boolean captureAllowsVolume;
    private AlertDialog recordDialog;
    private TextView recordDialogMessage;
    private boolean recordDialogCompleted;
    private final Handler recordCountdownHandler = new Handler(Looper.getMainLooper());
    private Runnable recordCountdown;

    @Override
    protected void onCreate(Bundle state) {
        prefs = Prefs.get(this);
        prefs.edit().putInt(Prefs.THEME, Prefs.THEME_SYSTEM).apply();
        // A crashed or force-stopped recording must not leave the service in
        // capture mode on the next launch.
        prefs.edit().putBoolean(Prefs.CAPTURING, false).apply();
        super.onCreate(state);
        hideNavigationBar();
        migrateUnsupportedMappings();
        buildUi();
        hideNavigationBar();
    }

    private void hideNavigationBar() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideNavigationBar();
    }

    private void buildUi() {
        // Migrate any older directional modifier mapping now that root axes
        // are brightness-only inputs.
        if (prefs.getBoolean(Prefs.ROOT_AXES, false)
                && isDpadKey(prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1))) {
            prefs.edit().putInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1)
                    .remove(Prefs.ROOT_MODIFIER_SOURCE)
                    .remove(Prefs.ROOT_MODIFIER_DIRECTION).apply();
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Let the cards use the available height down to the navigation bar.
        root.setPadding(dp(16), dp(2), dp(16), dp(14));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Thor's Lightning \u26A1 " + appVersionName()
                + " - Controller-Based Brightness Control", 22, true);
        heading.addView(title);
        header.addView(heading, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Shortcut enabled");
        enabledSwitch.setTextSize(15);
        enabledSwitch.setChecked(prefs.getBoolean(Prefs.ENABLED, true));
        enabledSwitch.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(Prefs.ENABLED, checked).apply());
        header.addView(enabledSwitch);
        root.addView(header, matchMargins(0, 0, 0, 2));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(columns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout setup = card();
        LinearLayout mappings = card();
        LinearLayout behaviour = card();
        columns.addView(setup, weightedCardMargins(0, 0, 6, 0));
        LinearLayout.LayoutParams mappingCardParams = weightedCardMargins(6, 0, 6, 0);
        mappingCardParams.weight = 1f;
        columns.addView(mappings, mappingCardParams);
        columns.addView(behaviour, weightedCardMargins(6, 0, 0, 0));

        setup.addView(section("Setup"));
        permissionStatus = text("", 14, true);
        permissionStatus.setVisibility(View.GONE);
        setup.addView(permissionStatus, margins(0, 2, 0, 2));
        TextView setupHint = text("Tap once to complete both required permissions.", 11, false);
        setupHint.setTextColor(themeColor(android.R.attr.textColorSecondary));
        setup.addView(setupHint, margins(0, 0, 0, 5));

        brightnessPermissionButton = button("Set up permissions");
        styleSetupButton(brightnessPermissionButton);
        brightnessPermissionButton.setGravity(Gravity.CENTER);
        brightnessPermissionButton.setTextSize(14);
        brightnessPermissionButton.setHeight(dp(40));
        brightnessPermissionButton.setOnClickListener(v -> {
            if (Settings.System.canWrite(this) && isAccessibilityServiceEnabled()) {
                AlertDialog complete = new AlertDialog.Builder(this)
                        .setCustomTitle(centeredDialogTitle("Everything is set!"))
                        .setMessage("Both permissions are enabled.\n\nYou can open either of the Android settings pages if you want to review or turn them off.")
                        .setNegativeButton("Accessibility settings", (dialog, which) -> openKeyDetection())
                        .setNeutralButton("Brightness settings", (dialog, which) -> openBrightnessPermission())
                        .setPositiveButton("Got it", null)
                        .create();
                complete.setOnShowListener(dialog -> {
                    View accessibility = complete.findViewById(android.R.id.button2);
                    if (accessibility != null) accessibility.setTranslationX(-dp(30));
                });
                complete.show();
                return;
            }
            if (Settings.System.canWrite(this) && !isAccessibilityServiceEnabled()) {
                showKeyTransitionPrompt();
                return;
            }
            if (!prefs.getBoolean(Prefs.BRIGHTNESS_GUIDE_SHOWN, false)) {
                new AlertDialog.Builder(this)
                        .setCustomTitle(centeredDialogTitle("Brightness permissions"))
                        .setMessage("Press \"CONTINUE\" to open Android settings.\n\nThen enable the \"Allow modifying system settings\" toggle for Thor's Lightning.\n\nPress the Thor's \"B\" button or the Android Back button to return here.")
                        .setPositiveButton("CONTINUE", (dialog, which) -> {
                            prefs.edit().putBoolean(Prefs.BRIGHTNESS_GUIDE_SHOWN, true).apply();
                            prefs.edit().putBoolean(Prefs.ADVANCE_TO_KEY_PENDING, true).apply();
                            openBrightnessPermission();
                        }).setNegativeButton("Cancel", null).show();
                return;
            }
            openBrightnessPermission();
        });
        setup.addView(brightnessPermissionButton, matchMargins(0, 0, 0, 3));

        keyDetectionButton = button("2. Key detection");
        styleSetupButton(keyDetectionButton);
        keyDetectionButton.setOnClickListener(v -> {
            if (!prefs.getBoolean(Prefs.KEY_GUIDE_SHOWN, false)) {
                new AlertDialog.Builder(this)
                        .setCustomTitle(centeredDialogTitle("Key detection"))
                        .setMessage("Press \"Continue\" to open Android settings. Open \"Downloaded apps\", select \"Thor's Lightning controls\", toggle it on, then tap \"Allow\" and return here.")
                        .setPositiveButton("Continue", (dialog, which) -> {
                            prefs.edit().putBoolean(Prefs.KEY_GUIDE_SHOWN, true).apply();
                            prefs.edit().putBoolean(Prefs.ADVANCE_TO_KEY_PENDING, false)
                                    .putBoolean(Prefs.ADVANCE_TO_BRIGHTNESS_PENDING, true).apply();
                            openKeyDetection();
                        }).setNegativeButton("Cancel", null).show();
                return;
            }
            prefs.edit().putBoolean(Prefs.ADVANCE_TO_BRIGHTNESS_PENDING, true).apply();
            openKeyDetection();
        });
        setup.addView(keyDetectionButton, matchMargins(0, 0, 0, 0));
        keyDetectionButton.setVisibility(View.GONE);
        brightnessStatus = text("", 13, false);
        keyStatus = text("", 13, false);
        brightnessStatus.setText("Brightness permissions needed");
        keyStatus.setText("Key detection needed");
        // Permission state is shown by the setup button; keep the card free
        // of duplicate status lines so Root options can use the space.

        addRootControls(setup);

        mappings.addView(section("Button mappings"));
        TextView mappingHint = text("Tap Record, then press the control you want to assign. Hold the modifier, then use Brighter or Dimmer; rooted direction mappings are moved when prompted.", 12, false);
        mappingHint.setTextColor(themeColor(android.R.attr.textColorSecondary));
        mappings.addView(mappingHint, margins(0, 3, 0, 7));
        addMappingRow(mappings, "Modifier", Prefs.MODIFIER,
                prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1), false);
        addMappingRow(mappings, "Brighter", Prefs.UP_KEY,
                prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP), true);
        addMappingRow(mappings, "Dimmer", Prefs.DOWN_KEY,
                prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN), true);
        captureMessage = text("Use Volume, face, shoulder, stick-click, Start or Select. With root enabled, Record can capture D-pad or stick directions.", 12, false);
        captureMessage.setTextColor(themeColor(android.R.attr.textColorSecondary));
        mappings.addView(captureMessage, margins(0, 7, 0, 6));

        LinearLayout reserveBox = new LinearLayout(this);
        reserveBox.setOrientation(LinearLayout.VERTICAL);
        reserveBox.setPadding(dp(8), dp(4), dp(8), dp(10));
        GradientDrawable reserveBackground = new GradientDrawable();
        reserveBackground.setColor(Color.argb(45, 190, 165, 235));
        reserveBackground.setCornerRadius(dp(8));
        reserveBackground.setStroke(dp(1), Color.argb(90, 190, 165, 235));
        reserveBox.setBackground(reserveBackground);
        mappings.addView(reserveBox, margins(0, 2, 0, 6));

        CheckBox consume = new CheckBox(this);
        consume.setText("Reserve the modifier button");
        consume.setTextSize(14);
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
                            + "will be blocked instead. Brighter and Dimmer buttons are already reserved only while the modifier is held. You have been warned.")
                    .setPositiveButton("Reserve", (dialog, which) ->
                            prefs.edit().putBoolean(Prefs.CONSUME_MODIFIER, true).apply())
                    .setNegativeButton("Cancel", (dialog, which) ->
                            consume.setChecked(false))
                    .setOnCancelListener(dialog -> consume.setChecked(false))
                    .show();
        });
        reserveBox.addView(consume, matchMargins(0, 0, 0, 0));
        TextView consumeHint = text(
                "Mapped Brighter and Dimmer buttons are reserved while held. If enabled, the Modifier button is also reserved (games do not receive these inputs when being pressed or held).",
                10, false);
        consumeHint.setIncludeFontPadding(false);
        consumeHint.setTextColor(themeColor(android.R.attr.textColorSecondary));
        reserveBox.addView(consumeHint, margins(0, 0, 0, 0));
        addBehaviourControls(behaviour);
        setContentView(root);
        root.post(() -> {
            maybeShowSetupGuide();
            root.postDelayed(this::maybeShowConflictWarning, 350);
        });
    }

    private void addRootControls(LinearLayout parent) {
        LinearLayout rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        rootBox.setPadding(dp(6), dp(4), dp(6), dp(8));
        GradientDrawable rootBackground = new GradientDrawable();
        rootBackground.setColor(Color.argb(45, 190, 165, 235));
        rootBackground.setCornerRadius(dp(8));
        rootBackground.setStroke(dp(1), Color.argb(90, 190, 165, 235));
        rootBox.setBackground(rootBackground);
        parent.addView(rootBox, margins(0, 5, 0, 0));
        rootBox.addView(section("Root options"), margins(0, 0, 0, 0));
        rootStatus = text("Root access is optional for regular button mappings.", 12, false);
        rootStatus.setTextColor(themeColor(android.R.attr.textColorSecondary));
        rootBox.addView(rootStatus, margins(0, 0, 0, 1));
        Button rootCheckFirst = button("Check root access");
        styleSetupButton(rootCheckFirst);
        rootCheckFirst.setTextSize(14);
        rootCheckFirst.setHeight(dp(36));
        rootCheckFirst.setPadding(dp(6), 0, dp(6), 0);
        rootCheckFirst.setGravity(Gravity.CENTER);
        rootCheckFirst.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(95, 95, 98)));
        rootCheckFirst.setOnClickListener(v -> checkRootAccess(rootCheckFirst));
        rootBox.addView(rootCheckFirst, matchMargins(0, 1, 0, -4));
        rootCheckFirst.getLayoutParams().height = dp(36);
        Switch rootAxes = new Switch(this);
        rootAxes.setText("Enable D-pad / Joystick");
        rootAxes.setTextSize(14);
        rootAxes.setChecked(prefs.getBoolean(Prefs.ROOT_AXES, false));
        rootAxes.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootBox.addView(rootAxes, matchMargins(0, 12, 0, 4));


        checkRootButton = button("Check root access");
        styleSetupButton(checkRootButton);
        checkRootButton.setTextSize(14);
        checkRootButton.setHeight(dp(36));
        checkRootButton.setPadding(dp(6), 0, dp(6), 0);
        checkRootButton.setGravity(Gravity.CENTER);
        Button checkRoot = checkRootButton;
        checkRoot.setOnClickListener(v -> checkRootAccess(checkRoot));
        rootBox.addView(checkRoot, matchMargins(0, 1, 0, -4));
        checkRoot.getLayoutParams().height = dp(36);
        checkRoot.setVisibility(View.GONE);

        Switch suspend = new Switch(this);
        suspendServicesSwitch = suspend;
        suspend.setText("Suspend services during hold");
        suspend.setTextSize(14);
        suspend.setChecked(prefs.getBoolean(Prefs.SUSPEND_SERVICE, false));
        suspend.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootBox.addView(suspend, matchMargins(0, 2, 0, 0));
        TextView suspendHint = text("Selected accessibility services are temporarily disabled while the modifier is held and restored when released. Known conflicts are auto-enabled when root is enabled.", 11, false);
        suspendHint.setTextColor(themeColor(android.R.attr.textColorSecondary));
        rootBox.addView(suspendHint, margins(0, 1, 0, 2));
        Button chooseService = button("Choose services");
        styleSetupButton(chooseService);
        chooseService.setHeight(dp(36));
        chooseService.setGravity(Gravity.CENTER);
        chooseService.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(95, 95, 98)));
        rootBox.addView(chooseService, matchMargins(0, 0, 0, 0));
        chooseService.getLayoutParams().height = dp(36);
        chooseService.setTranslationY(0);
        chooseService.setOnClickListener(v -> chooseSuspendedService());
        suspend.setOnCheckedChangeListener((button, checked) -> {
            if (checked && (!hasRootAccess() || selectedSuspendServices().isEmpty())) {
                button.setChecked(false);
                Toast.makeText(this, "Check root access and choose an enabled service first", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putBoolean(Prefs.SUSPEND_SERVICE, checked).apply();
        });

        rootAxes.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !hasRootAccess()) {
                button.setChecked(false);
                Toast.makeText(this, "Root access was not granted", Toast.LENGTH_LONG).show();
                updateRootStatus(false);
                return;
            }
            prefs.edit().putBoolean(Prefs.ROOT_AXES, checked).apply();
            if (checked) {
                // Root axes are brightness inputs only; do not allow a stick or
                // D-pad direction to remain as the modifier.
                prefs.edit().remove(Prefs.ROOT_MODIFIER_SOURCE)
                        .remove(Prefs.ROOT_MODIFIER_DIRECTION).apply();
                int modifierKey = prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1);
                if (isDpadKey(modifierKey)) {
                    prefs.edit().putInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1).apply();
                }
            }
            if (checked) autoConfigureVolumeLinkSuspension();
            updateRootStatus(checked);
            updateRootMappingLabels();
            updateSafetyText();
            if (checked) {
                new AlertDialog.Builder(this)
                        .setTitle("Root input limitations")
                        .setMessage("Root access is required to read D-pad and stick directions. Please be aware:\n\n"
                                + "• These controls are available for Brighter and Dimmer mappings only; they cannot be used as the modifier.\n"
                                + "• Root reading does not guarantee that games will stop receiving the same movement. A game may react at the same time as the brightness change.\n"
                                + "• Only up and down directions change brightness. Other directions can be recorded but will not adjust it.\n"
                                + "• The Thor L2/R2 triggers can report both button presses and analogue axes, which may cause unexpected results depending on the mapping.\n\n"
                                + "Disable this option if it causes conflicts or unwanted input.")
                        .setPositiveButton("Got it", (dialog, which) ->
                                prefs.edit().putBoolean(Prefs.ROOT_LIMIT_ACK, true).apply())
                        .setNegativeButton("Cancel", (dialog, which) ->
                                rootAxes.setChecked(false))
                        .setOnCancelListener(dialog -> rootAxes.setChecked(false))
                        .show();
            }
        });
        updateRootStatus(rootAxes.isChecked());
    }

    private void checkRootAccess(Button button) {
        button.setEnabled(false);
        new Thread(() -> {
            boolean available = hasRootAccess();
            runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText(available ? "Check root access - Enabled" : "Check root access - Needed");
                button.setBackgroundTintList(ColorStateList.valueOf(available
                        ? Color.rgb(70, 130, 75) : Color.rgb(170, 115, 35)));
                if (available) autoConfigureVolumeLinkSuspension();
                if (rootStatus != null) rootStatus.setText(available
                        ? "Root access is optional for regular button mappings."
                        : "Root access is optional for regular button mappings.");
            });
        }, "ThorRootCheck").start();
    }

    private void openBrightnessPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openKeyDetection() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void showKeyTransitionPrompt() {
        new AlertDialog.Builder(this).setCustomTitle(centeredDialogTitle("Key detection"))
                .setMessage("Press \"CONTINUE\" to open Android Accessibility settings.\n\nThen:\n- Scroll to \"Downloaded apps\".\n- Select \"Thor's Lightning controls\".\n- Toggle it on.\n- Tap \"Allow\" when Android asks.\n\nPress the Thor's \"B\" button or the Android Back button once to return here.")
                .setPositiveButton("CONTINUE", (d, w) -> openKeyDetection())
                .setNegativeButton("Later", null).show();
    }

    private void showBrightnessTransitionPrompt() {
        new AlertDialog.Builder(this).setCustomTitle(centeredDialogTitle("Brightness permissions"))
                .setMessage("After pressing \"Continue\":\n\n- Then enable the \"Allow modifying system settings\" toggle for Thor's Lightning.\n- Press the Thor's \"B\" button or the Android Back button to return here.")
                .setPositiveButton("Continue", (d, w) -> openBrightnessPermission())
                .setNegativeButton("Later", null).show();
    }

    private void chooseSuspendedService() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        java.util.ArrayList<AccessibilityServiceInfo> services = new java.util.ArrayList<>();
        if (manager != null) {
            for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                String id = info.getId();
                if (!id.startsWith(getPackageName() + "/")) services.add(info);
            }
            // Some Android builds return an empty list for FEEDBACK_ALL_MASK even
            // though services are enabled. Fall back to the secure enabled list
            // and installed service metadata so the picker remains usable.
            if (services.isEmpty()) {
                String enabled = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                java.util.Set<String> enabledIds = new java.util.HashSet<>();
                if (enabled != null) enabledIds.addAll(java.util.Arrays.asList(enabled.split(":")));
                for (AccessibilityServiceInfo info : manager.getInstalledAccessibilityServiceList()) {
                    String id = info.getId();
                    if (!id.startsWith(getPackageName() + "/") && enabledIds.contains(id)) services.add(info);
                }
                // A few firmware builds expose enabled IDs with a different
                // component spelling. The installed list is still authoritative;
                // use it as a final fallback and exclude this app itself.
                if (services.isEmpty()) {
                    for (AccessibilityServiceInfo info : manager.getInstalledAccessibilityServiceList()) {
                        if (!info.getId().startsWith(getPackageName() + "/")) services.add(info);
                    }
                }
            }
        }
        if (services.isEmpty()) {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            if (enabled != null) for (String id : enabled.split(":"))
                if (!id.isEmpty() && !id.startsWith(getPackageName() + "/")) ids.add(id);
            if (ids.isEmpty()) {
                Toast.makeText(this, "No other enabled accessibility services found", Toast.LENGTH_LONG).show();
                return;
            }
            java.util.Set<String> selected = selectedSuspendServices();
            String[] labels = ids.toArray(new String[0]);
            boolean[] checked = new boolean[labels.length];
            for (int i = 0; i < labels.length; i++) checked[i] = selected.contains(labels[i]);
            new AlertDialog.Builder(this).setTitle("Choose services to suspend (" + selected.size() + " selected)")
                    .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                        java.util.Set<String> ignored = new java.util.HashSet<>(prefs.getStringSet(Prefs.SUSPEND_SERVICE_IGNORED, java.util.Collections.emptySet()));
                        if (isChecked) { selected.add(ids.get(which)); ignored.remove(ids.get(which)); }
                        else { selected.remove(ids.get(which)); ignored.add(ids.get(which)); }
                        prefs.edit().putStringSet(Prefs.SUSPEND_SERVICE_IGNORED, ignored).apply();
                    })
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save", (dialog, which) -> prefs.edit().putStringSet(Prefs.SUSPEND_SERVICE_COMPONENTS, selected).apply())
                    .show();
            return;
        }
        String[] labels = new String[services.size()];
        for (int i = 0; i < services.size(); i++) {
            CharSequence label = services.get(i).getResolveInfo().loadLabel(getPackageManager());
            labels[i] = label + "\n" + services.get(i).getId();
        }
        java.util.Set<String> selected = selectedSuspendServices();
        boolean[] checked = new boolean[services.size()];
        for (int i = 0; i < services.size(); i++) checked[i] = selected.contains(services.get(i).getId());
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Choose services to suspend")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    java.util.Set<String> ignored = new java.util.HashSet<>(prefs.getStringSet(Prefs.SUSPEND_SERVICE_IGNORED, java.util.Collections.emptySet()));
                    if (isChecked) { selected.add(services.get(which).getId()); ignored.remove(services.get(which).getId()); }
                    else { selected.remove(services.get(which).getId()); ignored.add(services.get(which).getId()); }
                    prefs.edit().putStringSet(Prefs.SUSPEND_SERVICE_IGNORED, ignored).apply();
                    ((AlertDialog) dialog).setTitle("Choose services to suspend (" + selected.size() + " selected)");
                }).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> prefs.edit()
                        .putStringSet(Prefs.SUSPEND_SERVICE_COMPONENTS, selected)
                        .putString(Prefs.SUSPEND_SERVICE_COMPONENT, selected.isEmpty() ? "" : selected.iterator().next())
                        .apply());
        AlertDialog picker = builder.create();
        picker.setOnShowListener(dialog ->
                picker.setTitle("Choose services to suspend (" + selected.size() + " selected)"));
        picker.show();
    }

    private java.util.Set<String> selectedSuspendServices() {
        java.util.Set<String> selected = new java.util.HashSet<>(prefs.getStringSet(
                Prefs.SUSPEND_SERVICE_COMPONENTS, java.util.Collections.emptySet()));
        if (selected.isEmpty()) {
            String legacy = prefs.getString(Prefs.SUSPEND_SERVICE_COMPONENT, "");
            if (legacy.length() > 0) selected.add(legacy);
        }
        return selected;
    }

    private void addBehaviourControls(LinearLayout behaviour) {
        behaviour.addView(section("Screens to adjust"));
        RadioGroup targets = new RadioGroup(this);
        targets.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton both = radio("Both", Prefs.TARGET_BOTH);
        RadioButton top = radio("Top", Prefs.TARGET_TOP);
        RadioButton bottom = radio("Bottom", Prefs.TARGET_BOTTOM);
        both.setTextSize(13);
        top.setTextSize(13);
        bottom.setTextSize(13);
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
        behaviour.addView(targets, matchMargins(0, 0, 0, 0));

        behaviour.addView(section("Brightness step"));
        stepLabel = text("", 15, true);
        behaviour.addView(stepLabel);
        SeekBar step = new SeekBar(this);
        step.setMax(24);
        int savedPress = prefs.getInt(Prefs.PRESS_STEP, prefs.getInt(Prefs.STEP, 5));
        step.setProgress(savedPress - 1);
        updateStepLabel(savedPress);
        step.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int percent = value + 1;
                updateStepLabel(percent);
                if (fromUser) {
                    SharedPreferences.Editor edit = prefs.edit().putInt(Prefs.PRESS_STEP, percent).putInt(Prefs.STEP, percent);
                    if (prefs.getBoolean(Prefs.LINK_HOLD_STEP, true)) edit.putInt(Prefs.HOLD_STEP, percent);
                    edit.apply();
                }
            }
        });
        behaviour.addView(step);

        Switch linkHold = new Switch(this);
        linkHold.setText("Link hold step to press step");
        linkHold.setTextSize(12);
        linkHold.setChecked(prefs.getBoolean(Prefs.LINK_HOLD_STEP, true));
        behaviour.addView(linkHold, margins(0, 0, 0, 0));
        TextView holdLabel = text("Hold: " + prefs.getInt(Prefs.HOLD_STEP, savedPress) + "% per repeat", 13, true);
        behaviour.addView(holdLabel);
        SeekBar hold = new SeekBar(this);
        hold.setMax(24);
        int savedHold = prefs.getInt(Prefs.HOLD_STEP, savedPress);
        hold.setProgress(savedHold - 1);
        hold.setEnabled(!linkHold.isChecked());
        hold.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int percent = value + 1;
                holdLabel.setText("Hold: " + percent + "% per repeat");
                if (fromUser) prefs.edit().putInt(Prefs.HOLD_STEP, percent).apply();
            }
        });
        behaviour.addView(hold);
        linkHold.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(Prefs.LINK_HOLD_STEP, checked)
                    .putInt(Prefs.HOLD_STEP, checked ? prefs.getInt(Prefs.PRESS_STEP, savedPress) : prefs.getInt(Prefs.HOLD_STEP, savedHold)).apply();
            hold.setEnabled(!checked);
            if (checked) {
                int value = prefs.getInt(Prefs.PRESS_STEP, savedPress);
                hold.setProgress(value - 1);
                holdLabel.setText("Hold: " + value + "% per repeat");
            }
        });

        behaviour.addView(section("Hold repeat speed"), margins(0, 2, 0, 0));
        repeatLabel = text("", 15, true);
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

        LinearLayout gentleBox = new LinearLayout(this);
        gentleBox.setOrientation(LinearLayout.VERTICAL);
        gentleBox.setPadding(dp(8), dp(1), dp(8), dp(2));
        GradientDrawable gentleBackground = new GradientDrawable();
        gentleBackground.setColor(Color.argb(45, 190, 165, 235));
        gentleBackground.setCornerRadius(dp(8));
        gentleBackground.setStroke(dp(1), Color.argb(90, 190, 165, 235));
        gentleBox.setBackground(gentleBackground);
        behaviour.addView(gentleBox, matchMargins(0, 2, 0, 0));
        TextView gentleTitle = text("Wake behavior", 13, true);
        gentleBox.addView(gentleTitle, margins(0, 0, 0, 0));
        Switch gentleWake = new Switch(this);
        gentleWake.setText("Gentle brightness on wake");
        gentleWake.setTextSize(12);
        gentleWake.setChecked(prefs.getBoolean(Prefs.GENTLE_WAKE, false));
        gentleWake.setOnCheckedChangeListener((button, checked) -> {
            if (checked) showGentleWakeSettings(gentleWake);
            else prefs.edit().putBoolean(Prefs.GENTLE_WAKE, false).apply();
        });
        gentleBox.addView(gentleWake, matchMargins(0, 0, 0, 0));
        TextView gentleHint = text("Tap to choose wake-up speed.", 10, false);
        gentleHint.setTextColor(themeColor(android.R.attr.textColorSecondary));
        gentleBox.addView(gentleHint, margins(0, 0, 0, 0));

        Space safetySpacer = new Space(this);
        behaviour.addView(safetySpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        safetyLabel = text("", 11, false);
        safetyLabel.setTextColor(Color.rgb(80, 60, 20));
        GradientDrawable safetyBackground = new GradientDrawable();
        safetyBackground.setColor(Color.rgb(255, 246, 210));
        safetyBackground.setCornerRadius(dp(8));
        safetyLabel.setBackground(safetyBackground);
        safetyLabel.setPadding(dp(10), dp(5), dp(10), dp(5));
        updateSafetyText();
        behaviour.addView(safetyLabel, margins(0, 0, 0, 6));

    }

    private void addMappingRow(LinearLayout parent, String title, String preference,
                               int currentKey, boolean allowsVolume) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        GradientDrawable rowBackground = new GradientDrawable();
        rowBackground.setColor(Color.argb(45, 190, 165, 235));
        rowBackground.setCornerRadius(dp(8));
        rowBackground.setStroke(dp(1), Color.argb(90, 190, 165, 235));
        row.setBackground(rowBackground);
        row.setPadding(dp(8), dp(1), dp(8), dp(1));
        TextView label = text("", 14, true);
        label.setTextSize(12);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setText(mappingLabel(title, currentKey));
        if (Prefs.MODIFIER.equals(preference)) modifierMappingLabel = label;
        if (Prefs.UP_KEY.equals(preference)) brighterMappingLabel = label;
        if (Prefs.DOWN_KEY.equals(preference)) dimmerMappingLabel = label;
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button record = button("Record");
        record.setTextSize(16);
        record.setGravity(Gravity.CENTER);
        record.setPadding(0, 0, 0, 0);
        record.setIncludeFontPadding(false);
        record.setOnClickListener(v -> {
            if (!requiredPermissionsReady()) {
                Toast.makeText(this, "Set up both required permissions before recording buttons", Toast.LENGTH_LONG).show();
                return;
            }
            showRecordPrompt(preference, label, title, allowsVolume);
        });
        LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(dp(94), dp(38));
        recordParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(record, recordParams);
        parent.addView(row, matchMargins(0, 3, 0, 3));
    }

    private void showGentleWakeSettings(Switch gentleWake) {
        int saved = Math.max(100, Math.min(10000,
                prefs.getInt(Prefs.GENTLE_WAKE_DURATION, 1500)));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(4), dp(24), 0);
        TextView description = text("When the Thor wakes after the screens were off, brightness rises gradually to reduce glare.", 14, false);
        content.addView(description, matchMargins(0, 0, 0, 4));
        TextView value = text("Transition: " + formatWakeDuration(saved), 15, true);
        content.addView(value, matchMargins(0, 0, 0, 0));
        SeekBar slider = new SeekBar(this);
        slider.setMax(99);
        slider.setProgress((saved / 100) - 1);
        slider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText("Transition: " + formatWakeDuration((progress + 1) * 100));
            }
        });
        content.addView(slider, matchMargins(0, 0, 0, 0));
        int holdMs = Math.max(100, Math.min(10000, prefs.getInt(Prefs.WAKE_HOLD_DURATION, 1000)));
        TextView holdValue = text("Black hold: " + formatWakeDuration(holdMs), 15, true);
        content.addView(holdValue, matchMargins(0, 6, 0, 0));
        SeekBar holdSlider = new SeekBar(this);
        holdSlider.setMax(99);
        holdSlider.setProgress((holdMs / 100) - 1);
        holdSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                holdValue.setText("Black hold: " + formatWakeDuration((progress + 1) * 100));
            }
        });
        content.addView(holdSlider, matchMargins(0, 0, 0, 0));
        int resetMinutes = Math.max(1, Math.min(240,
                (int) (prefs.getLong(Prefs.WAKE_RESET_TIMEOUT, 30L * 60L * 1000L) / 60000L)));
        TextView resetValue = text("Long-close reset: after " + resetMinutes + " minutes", 15, true);
        content.addView(resetValue, matchMargins(0, 6, 0, 0));
        SeekBar resetTime = new SeekBar(this);
        resetTime.setMax(239);
        resetTime.setProgress(resetMinutes - 1);
        resetTime.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                resetValue.setText("Long-close reset: after " + (progress + 1) + " minutes");
            }
        });
        content.addView(resetTime, matchMargins(0, 0, 0, 0));
        TextView resetBrightness = text("Reset brightness: 50%", 15, true);
        content.addView(resetBrightness, matchMargins(0, 4, 0, 0));
        SeekBar resetLevel = new SeekBar(this);
        resetLevel.setMax(254);
        resetLevel.setProgress(prefs.getInt(Prefs.WAKE_RESET_BRIGHTNESS, 128) - 1);
        resetLevel.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                resetBrightness.setText("Reset brightness: " + Math.round((progress + 1) * 100f / 255f) + "%");
            }
        });
        content.addView(resetLevel, matchMargins(0, 0, 0, 0));
        new AlertDialog.Builder(this)
                .setCustomTitle(centeredDialogTitle("Gentle brightness on wake"))
                .setView(content)
                .setPositiveButton("Enable", (dialog, which) -> saveGentleWake((slider.getProgress() + 1) * 100,
                        (holdSlider.getProgress() + 1) * 100,
                        (resetTime.getProgress() + 1) * 60000L, resetLevel.getProgress() + 1))
                .setNegativeButton("Cancel", (dialog, which) -> gentleWake.setChecked(false))
                .setOnCancelListener(dialog -> gentleWake.setChecked(false))
                .show();
    }

    private String formatWakeDuration(int durationMs) {
        return String.format(java.util.Locale.ROOT, "%.1f seconds", durationMs / 1000f);
    }

    private void saveGentleWake(int duration, int holdDuration, long resetTimeout, int resetBrightness) {
        prefs.edit().putBoolean(Prefs.GENTLE_WAKE, true)
                .putInt(Prefs.GENTLE_WAKE_DURATION, duration)
                .putInt(Prefs.WAKE_HOLD_DURATION, holdDuration)
                .putLong(Prefs.WAKE_RESET_TIMEOUT, resetTimeout)
                .putInt(Prefs.WAKE_RESET_BRIGHTNESS, resetBrightness).apply();
    }

    private boolean requiredPermissionsReady() {
        return Settings.System.canWrite(this) && isAccessibilityServiceEnabled();
    }

    private void showRecordPrompt(String preference, TextView label, String title,
                                   boolean allowsVolume) {
        boolean rootDirection = prefs.getBoolean(Prefs.ROOT_AXES, false)
                && (Prefs.UP_KEY.equals(preference) || Prefs.DOWN_KEY.equals(preference));
        String message = Prefs.MODIFIER.equals(preference)
                ? "Waiting for input...\n\nPress the controller button you want to use for the Modifier button."
                : rootDirection
                ? "Waiting for input...\n\nMove the D-pad or stick direction for " + title + "."
                : "Waiting for input...\n\nPress the controller button you want to use for "
                + (Prefs.UP_KEY.equals(preference) ? "turning the brightness up."
                : "turning the brightness down.");
        recordDialogMessage = text(message + "\n\n10 seconds", 16, false);
        recordDialogCompleted = false;
        recordDialogMessage.setGravity(Gravity.CENTER);
        recordDialogMessage.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        recordDialogMessage.setMinHeight(dp(150));
        recordDialogMessage.setPadding(dp(16), dp(18), dp(16), dp(18));
        recordDialog = new AlertDialog.Builder(this)
                .setTitle("Record " + title)
                .setView(recordDialogMessage)
                .create();
        recordDialog.setCancelable(false);
        recordDialog.setOnKeyListener((dialog, keyCode, keyEvent) -> {
            if (recordDialogCompleted) return true;
            if (keyEvent.getAction() == KeyEvent.ACTION_DOWN
                    && keyEvent.getRepeatCount() == 0
                    && keyCode != KeyEvent.KEYCODE_BACK) {
                endCapture(true, keyCode);
                return true;
            }
            return false;
        });
        recordDialog.setOnShowListener(dialog ->
                beginCapture(preference, label, title, allowsVolume));
        recordDialog.show();
        if (recordDialog.getWindow() != null) {
            recordDialog.getWindow().setLayout(dp(650), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        final long deadline = System.currentTimeMillis() + 10000;
        recordCountdown = new Runnable() {
            @Override public void run() {
                if (!capturing || recordDialog == null || !recordDialog.isShowing()) return;
                int remaining = (int) Math.ceil((deadline - System.currentTimeMillis()) / 1000.0);
                if (remaining <= 0) {
                    endCapture(false, 0);
                } else {
                    recordDialogMessage.setText(message + "\n\n" + remaining + " seconds");
                    recordCountdownHandler.postDelayed(this, 250);
                }
            }
        };
        recordCountdownHandler.post(recordCountdown);
    }

    private void finishRecordDialog(String message) {
        if (recordDialogMessage == null || recordDialog == null) return;
        if (recordCountdown != null) recordCountdownHandler.removeCallbacks(recordCountdown);
        recordDialogCompleted = true;
        recordDialogMessage.setText(message);
        recordDialogMessage.setTextSize(20);
        recordDialogMessage.setTypeface(recordDialogMessage.getTypeface(), android.graphics.Typeface.BOLD);
        recordDialogMessage.setTextColor(Color.rgb(70, 150, 75));
        recordDialogMessage.postDelayed(() -> {
            if (recordDialog != null && recordDialog.isShowing()) recordDialog.dismiss();
            recordDialog = null;
            recordDialogMessage = null;
        }, 2000);
    }

    private void beginCapture(String preference, TextView label, String title,
                              boolean allowsVolume) {
        if (prefs.getBoolean(Prefs.ROOT_AXES, false)
                && (Prefs.UP_KEY.equals(preference) || Prefs.DOWN_KEY.equals(preference))) {
            beginRootDirectionCapture(preference, label, title);
            return;
        }
        capturing = true;
        capturePreference = preference;
        captureLabel = label;
        captureTitle = title;
        captureAllowsVolume = allowsVolume;
        prefs.edit().putBoolean(Prefs.CAPTURING, true).apply();
    }

    private void beginRootDirectionCapture(String preference, TextView label, String title) {
        if (!hasRootAccess()) {
            Toast.makeText(this, "Root access is required to record a D-pad or stick direction", Toast.LENGTH_LONG).show();
            return;
        }
        stopRootDirectionRecorder();
        capturing = true;
        capturingRootDirection = true;
        capturePreference = preference;
        captureLabel = label;
        captureTitle = title;
        captureAllowsVolume = false;
        prefs.edit().putBoolean(Prefs.CAPTURING, true).apply();
        String rootCaptureNote = Prefs.MODIFIER.equals(preference)
                ? " Note: using a D-pad or stick direction as the modifier is not recommended because games may also receive that input."
                : "";
        rootDirectionRecorder = new RootInputMonitor(new Handler(Looper.getMainLooper()),
                new RootInputMonitor.Listener() {
                    @Override public void onDirection(int encoded) {
                        if (encoded != 0 && capturingRootDirection) {
                            saveRootDirection(encoded / 10, encoded % 10);
                        }
                    }
                    @Override public void onStopped() { }
                });
        rootDirectionRecorder.start(0);
    }

    private void saveRootDirection(int source, int direction) {
        if (rootDirectionConflict(source, direction)) {
            Toast.makeText(this, "This direction is already used by another brightness mapping", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean recordingBright = Prefs.UP_KEY.equals(capturePreference);
        boolean recordingDim = Prefs.DOWN_KEY.equals(capturePreference);
        boolean swapWithDim = recordingBright
                && source == prefs.getInt(Prefs.ROOT_DIM_SOURCE, -1)
                && direction == prefs.getInt(Prefs.ROOT_DIM_DIRECTION, 0);
        boolean swapWithBright = recordingDim
                && source == prefs.getInt(Prefs.ROOT_BRIGHT_SOURCE, -1)
                && direction == prefs.getInt(Prefs.ROOT_BRIGHT_DIRECTION, 0);
        stopRootDirectionRecorder();
        capturingRootDirection = false;
        capturing = false;
        SharedPreferences.Editor editor = prefs.edit().putBoolean(Prefs.CAPTURING, false);
        if (Prefs.MODIFIER.equals(capturePreference)) {
            editor.putInt(Prefs.ROOT_MODIFIER_DIRECTION, direction)
                    .putInt(Prefs.ROOT_MODIFIER_SOURCE, source);
        } else {
            String directionPreference = Prefs.UP_KEY.equals(capturePreference)
                    ? Prefs.ROOT_BRIGHT_DIRECTION : Prefs.ROOT_DIM_DIRECTION;
            String sourcePreference = Prefs.UP_KEY.equals(capturePreference)
                    ? Prefs.ROOT_BRIGHT_SOURCE : Prefs.ROOT_DIM_SOURCE;
            editor.putInt(directionPreference, direction).putInt(sourcePreference, source);
            if (swapWithDim) {
                editor.putInt(Prefs.ROOT_DIM_DIRECTION, prefs.getInt(Prefs.ROOT_BRIGHT_DIRECTION, 0))
                        .putInt(Prefs.ROOT_DIM_SOURCE, prefs.getInt(Prefs.ROOT_BRIGHT_SOURCE, -1));
            } else if (swapWithBright) {
                editor.putInt(Prefs.ROOT_BRIGHT_DIRECTION, prefs.getInt(Prefs.ROOT_DIM_DIRECTION, 0))
                        .putInt(Prefs.ROOT_BRIGHT_SOURCE, prefs.getInt(Prefs.ROOT_DIM_SOURCE, -1));
            }
        }
        editor.apply();
        updateRootMappingLabels();
        // The record row can be the currently active view while the monitor
        // callback is completing. Update it explicitly so the new modifier or
        // brightness direction is visible without leaving/reopening the page.
        if (captureLabel != null) {
            captureLabel.setText(mappingLabel(captureTitle,
                    Prefs.MODIFIER.equals(capturePreference)
                            ? prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1)
                            : Prefs.UP_KEY.equals(capturePreference)
                            ? prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP)
                            : prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN)));
            captureLabel.requestLayout();
            captureLabel.invalidate();
        }
        // Re-read the committed preferences once the row/layout pass has
        // completed; this also covers the dual-screen UI retaining a stale
        // text snapshot during the monitor callback.
        new Handler(Looper.getMainLooper()).postDelayed(this::updateRootMappingLabels, 120);
        updateRootStatus(true);
        setStaticCaptureMessage();
        finishRecordDialog("Saved the " + captureTitle + " as " + sourceName(source) + " " + rootDirectionName(direction) + ".");
    }

    private boolean rootDirectionConflict(int source, int direction) {
        int brightSource = prefs.getInt(Prefs.ROOT_BRIGHT_SOURCE, -1);
        int brightDirection = prefs.getInt(Prefs.ROOT_BRIGHT_DIRECTION, 0);
        int dimSource = prefs.getInt(Prefs.ROOT_DIM_SOURCE, -1);
        int dimDirection = prefs.getInt(Prefs.ROOT_DIM_DIRECTION, 0);
        int modifierSource = prefs.getInt(Prefs.ROOT_MODIFIER_SOURCE, -1);
        int modifierDirection = prefs.getInt(Prefs.ROOT_MODIFIER_DIRECTION, 0);
        boolean matchesBright = !Prefs.UP_KEY.equals(capturePreference)
                && source == brightSource && direction == brightDirection;
        boolean matchesDim = !Prefs.DOWN_KEY.equals(capturePreference)
                && source == dimSource && direction == dimDirection;
        // A stick/D-pad used as the modifier must remain held. Any direction
        // from that same source would release or replace the modifier, so the
        // whole source is reserved while root modifier mapping is active.
        boolean matchesModifier = !Prefs.MODIFIER.equals(capturePreference)
                && source == modifierSource && modifierDirection != 0;
        if (Prefs.MODIFIER.equals(capturePreference)) {
            return matchesBright || matchesDim;
        }
        if (Prefs.UP_KEY.equals(capturePreference)) {
            // An exact Dimmer match is swapped below; only the modifier source
            // remains a hard conflict.
            return matchesModifier;
        }
        // An exact Brighter match is swapped below; only the modifier source
        // remains a hard conflict.
        return matchesModifier;
    }

    private void stopRootDirectionRecorder() {
        if (rootDirectionRecorder != null) {
            rootDirectionRecorder.stop();
            rootDirectionRecorder = null;
        }
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
        if (Prefs.MODIFIER.equals(capturePreference) && isDpadKey(code)) {
            Toast.makeText(this, "Directional controls cannot be used as the modifier", Toast.LENGTH_LONG).show();
            return true;
        }
        if (prefs.getBoolean(Prefs.ROOT_AXES, false)
                && (Prefs.UP_KEY.equals(capturePreference) || Prefs.DOWN_KEY.equals(capturePreference))
                && isDpadKey(code)) {
            // Root capture must identify the physical D-pad source; never
            // fall back to saving Android's generic DPAD key name.
            return true;
        }
        if (capturingRootDirection) {
            // Root capture listens to axes in parallel with normal key events.
            // Ignore Android's synthetic D-pad events (the root monitor will
            // identify their real D-pad/stick source), but still allow every
            // face, shoulder, click, Start, Select, and other button through.
            if (!isDpadKey(code) && code != KeyEvent.KEYCODE_POWER
                    && code != KeyEvent.KEYCODE_HOME) {
                endCapture(true, code);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_HOME
                || (!captureAllowsVolume && (code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN))) {
            Toast.makeText(this, "That system key cannot be used here", Toast.LENGTH_SHORT).show();
            return true;
        }
        endCapture(true, code);
        return true;
    }

    private void endCapture(boolean save, int code) {
        if (save && Prefs.MODIFIER.equals(capturePreference)
                && prefs.getBoolean(Prefs.ROOT_AXES, false) && isDpadKey(code)) {
            Toast.makeText(this, "Directional controls cannot be used as the modifier", Toast.LENGTH_LONG).show();
            return;
        }
        stopRootDirectionRecorder();
        capturingRootDirection = false;
        capturing = false;
        prefs.edit().putBoolean(Prefs.CAPTURING, false).apply();
        if (save) {
            String swappedTitle = saveMappingWithSwap(code);
            if (swappedTitle == null) {
                setStaticCaptureMessage();
            } else {
                setStaticCaptureMessage();
            }
            if (Prefs.MODIFIER.equals(capturePreference)) {
                prefs.edit().remove(Prefs.ROOT_MODIFIER_SOURCE)
                        .remove(Prefs.ROOT_MODIFIER_DIRECTION).apply();
            }
            // Refresh after clearing any previous root modifier mapping so the
            // visible label always reflects the newly saved control.
            updateRootMappingLabels();
            updateSafetyText();
            finishRecordDialog(swappedTitle == null
                    ? "Saved the " + captureTitle + " as " + keyName(code) + "."
                    : "Saved the " + captureTitle + " as " + keyName(code) + ".");
        } else {
            setStaticCaptureMessage();
            if (recordCountdown != null) recordCountdownHandler.removeCallbacks(recordCountdown);
            if (recordDialog != null) recordDialog.dismiss();
            recordDialog = null;
            recordDialogMessage = null;
        }
        setStaticCaptureMessage();
    }

    private void setStaticCaptureMessage() {
        if (captureMessage == null) return;
        captureMessage.setText("Use Volume, face, shoulder, stick-click, Start or Select. With root enabled, Record can capture D-pad or stick directions.");
        captureMessage.setTextColor(themeColor(android.R.attr.textColorSecondary));
    }

    private String saveMappingWithSwap(int code) {
        int oldCaptureCode = prefs.getInt(capturePreference, defaultKeyFor(capturePreference));
        String conflictingPreference = conflictingPreferenceFor(code);
        SharedPreferences.Editor editor = prefs.edit().putInt(capturePreference, code);
        if (conflictingPreference != null) {
            editor.putInt(conflictingPreference, oldCaptureCode);
        }
        editor.apply();
        return titleForPreference(conflictingPreference);
    }

    private String conflictingPreferenceFor(int code) {
        if (!Prefs.MODIFIER.equals(capturePreference)
                && prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1) == code) {
            return Prefs.MODIFIER;
        }
        if (!Prefs.UP_KEY.equals(capturePreference)
                && prefs.getInt(Prefs.UP_KEY, KeyEvent.KEYCODE_VOLUME_UP) == code) {
            return Prefs.UP_KEY;
        }
        if (!Prefs.DOWN_KEY.equals(capturePreference)
                && prefs.getInt(Prefs.DOWN_KEY, KeyEvent.KEYCODE_VOLUME_DOWN) == code) {
            return Prefs.DOWN_KEY;
        }
        return null;
    }

    private int defaultKeyFor(String preference) {
        if (Prefs.MODIFIER.equals(preference)) return KeyEvent.KEYCODE_BUTTON_R1;
        if (Prefs.UP_KEY.equals(preference)) return KeyEvent.KEYCODE_VOLUME_UP;
        if (Prefs.DOWN_KEY.equals(preference)) return KeyEvent.KEYCODE_VOLUME_DOWN;
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    private String titleForPreference(String preference) {
        if (Prefs.MODIFIER.equals(preference)) return "Modifier";
        if (Prefs.UP_KEY.equals(preference)) return "Brighter";
        if (Prefs.DOWN_KEY.equals(preference)) return "Dimmer";
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (permissionStatus != null) {
            updatePermissionStatus();
            updateSetupButtons();
        }
        if (prefs.getBoolean(Prefs.ADVANCE_TO_KEY_PENDING, false)
                && Settings.System.canWrite(this)) {
            prefs.edit().putBoolean(Prefs.ADVANCE_TO_KEY_PENDING, false).apply();
            if (!isAccessibilityServiceEnabled()) showKeyTransitionPrompt();
        }
        if (prefs.getBoolean(Prefs.ADVANCE_TO_BRIGHTNESS_PENDING, false)
                && isAccessibilityServiceEnabled()) {
            prefs.edit().putBoolean(Prefs.ADVANCE_TO_BRIGHTNESS_PENDING, false).apply();
            if (!Settings.System.canWrite(this)) showBrightnessTransitionPrompt();
        }
        if (Settings.System.canWrite(this) && isAccessibilityServiceEnabled()
                && !prefs.getBoolean(Prefs.SETUP_COMPLETE_SHOWN, false)) {
            prefs.edit().putBoolean(Prefs.SETUP_COMPLETE_SHOWN, true).apply();
            new AlertDialog.Builder(this)
                    .setCustomTitle(centeredDialogTitle("\u26A1 Setup Complete! \u26A1"))
                    .setMessage("Brightness control is ready.\n\nRoot access is required if you want to use the D-pad or analogue sticks. To do this, follow the steps below:\n\n- Tap \"Check root access\".\n- Allow root access when Android asks.\n- Turn on \"Enable D-pad / Joystick\".\n- Tap a Brighter or Dimmer \"Record\" button, then move the control and direction you want to use.\n\nNote: D-pad and stick directions can be recorded independently for Brighter and Dimmer.")
                    .setPositiveButton("Got it", null).show();
        }
        if (enabledSwitch != null) {
            enabledSwitch.setChecked(prefs.getBoolean(Prefs.ENABLED, true));
        }
        // Refresh known conflict services when the app returns to the
        // foreground. This also catches ThorVolumeLink being enabled after
        // the initial root setup.
        if (prefs.getBoolean(Prefs.ROOT_AXES, false) || hasRootAccess()) {
            autoConfigureVolumeLinkSuspension();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && (prefs.getBoolean(Prefs.ROOT_AXES, false) || hasRootAccess())) {
                    autoConfigureVolumeLinkSuspension();
                }
            }, 1500);
        }
        if (prefs.getBoolean(Prefs.AWAITING_STEP_TWO, false)
                && Settings.System.canWrite(this) && !isAccessibilityServiceEnabled()) {
            prefs.edit().putBoolean(Prefs.AWAITING_STEP_TWO, false).apply();
            showStepTwoGuide();
        }
    }

    private void autoConfigureVolumeLinkSuspension() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return;
        java.util.Set<String> matches = new java.util.HashSet<>();
        for (String id : enabled.split(":")) {
            String lower = id.toLowerCase(java.util.Locale.ROOT);
            if (!id.startsWith(getPackageName() + "/")
                    && !prefs.getStringSet(Prefs.SUSPEND_SERVICE_IGNORED, java.util.Collections.emptySet()).contains(id)
                    && (lower.contains("thorvolume") || lower.contains("volumecontrol"))) {
                matches.add(id);
            }
        }
        if (!matches.isEmpty()) {
            java.util.Set<String> selected = selectedSuspendServices();
            selected.addAll(matches);
            prefs.edit().putStringSet(Prefs.SUSPEND_SERVICE_COMPONENTS, selected)
                    .putString(Prefs.SUSPEND_SERVICE_COMPONENT, selected.iterator().next())
                    .putBoolean(Prefs.SUSPEND_SERVICE, true).apply();
            if (suspendServicesSwitch != null) suspendServicesSwitch.setChecked(true);
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
            status = "";
            status = "Ready - permissions enabled";
        } else if (!write && !service) {
            status = "Brightness and key detection needed";
        } else if (!write) {
            status = "Brightness permission needed";
        } else {
            status = "Key detection needed";
        }
        permissionStatus.setText(status);
        permissionStatus.setTextColor(write && service ? Color.rgb(24, 110, 50) : Color.rgb(150, 80, 20));
    }

    private void updateSetupButtons() {
        boolean write = Settings.System.canWrite(this);
        boolean service = isAccessibilityServiceEnabled();
        updateSetupButton(brightnessPermissionButton, "Set up permissions", write && service);
        if (brightnessStatus != null) {
            brightnessStatus.setText("Brightness permission - " + (write ? "Enabled" : "Needed"));
            keyStatus.setText("Key detection - " + (service ? "Enabled" : "Needed"));
            brightnessStatus.setTextColor(write ? Color.rgb(70, 150, 75) : Color.rgb(190, 130, 35));
            keyStatus.setTextColor(service ? Color.rgb(70, 150, 75) : Color.rgb(190, 130, 35));
        }
    }

    private void updateSetupButton(Button button, String label, boolean available) {
        if (button == null) return;
        button.setTextSize(button == checkRootButton ? 14 : button == brightnessPermissionButton ? 14 : 16);
        button.setText(available ? label + " - Enabled" : label + " - Needed");
        button.setTextColor(themeColor(android.R.attr.textColorPrimary));
        button.setBackgroundTintList(available
                ? ColorStateList.valueOf(Color.rgb(70, 130, 75))
                : ColorStateList.valueOf(Color.rgb(170, 115, 35)));
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
                .setCustomTitle(centeredDialogTitle("Welcome to Thor's Lightning! \u26A1"))
                .setMessage("This app lets you use the AYN Thor's controller inputs to adjust the screen brightness!\n\nTo get started, tap \"Set up permissions\" to allow the app to function.")
                .setPositiveButton("Got it", (dialog, which) ->
                        prefs.edit().putBoolean(Prefs.SETUP_GUIDE_SHOWN, true).apply())
                .show();
    }

    private void showStepTwoGuide() {
        new AlertDialog.Builder(this)
                .setCustomTitle(centeredDialogTitle("Step 2 - Enable key detection"))
                .setMessage("Open \"Downloaded apps\", select \"Thor's Lightning controls\", toggle it on, then tap \"Allow\" when Android asks.")
                .setPositiveButton("Open Accessibility", (dialog, which) ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Later", null)
                .show();
    }

    private java.util.Set<String> knownConflictServicesEnabled() {
        java.util.Set<String> matches = new java.util.HashSet<>();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return matches;
        String ours = getPackageName().toLowerCase(java.util.Locale.ROOT);
        for (String id : enabled.split(":")) {
            String lower = id.toLowerCase(java.util.Locale.ROOT);
            if (!lower.startsWith(ours + "/")
                    && (lower.contains("thorvolume") || lower.contains("volumecontrol"))) {
                matches.add(id);
            }
        }
        return matches;
    }

    private void maybeShowConflictWarning() {
        java.util.Set<String> detected = knownConflictServicesEnabled();
        java.util.Set<String> acknowledged = prefs.getStringSet(
                Prefs.CONFLICT_WARNING_SERVICES, java.util.Collections.emptySet());
        java.util.Set<String> newServices = new java.util.HashSet<>(detected);
        newServices.removeAll(acknowledged);
        if (newServices.isEmpty()) return;
        java.util.Set<String> updatedAcknowledged = new java.util.HashSet<>(acknowledged);
        updatedAcknowledged.addAll(newServices);
        prefs.edit().putBoolean(Prefs.CONFLICT_WARNING_SHOWN, true)
                .putStringSet(Prefs.CONFLICT_WARNING_SERVICES, updatedAcknowledged).apply();
        new AlertDialog.Builder(this)
                .setCustomTitle(centeredDialogTitle("Controller conflict detected"))
                .setMessage("Another Thor volume-control service is enabled. Both apps may respond to the same volume or controller input, which can make brightness and volume change together.\n\nIf you want Thor's Lightning to take priority while the modifier is held, enable \"Suspend services during hold\" and choose the detected service. You can dismiss this notice and change the option later.")
                .setPositiveButton("Got it", null)
                .show();
    }


    @Override
    protected void onDestroy() {
        stopRootDirectionRecorder();
        super.onDestroy();
    }

    private void updateStepLabel(int percent) {
        stepLabel.setText(percent + "% per press");
    }

    private void updateRepeatLabel(int delay) {
        repeatLabel.setText(delay + " ms between steps");
    }

    private String appVersionName() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "";
        }
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
        // Keep this explanatory text stable; the switch state itself shows
        // whether root directional input is enabled.
        rootStatus.setText("Root access is optional for regular button mappings.");
    }

    private String keyName(int code) {
        return KeyEvent.keyCodeToString(code).replace("KEYCODE_", "")
                .replace("BUTTON_", "").replace('_', ' ');
    }

    private CharSequence mappingLabel(String title, int fallbackKey) {
        String assigned;
        if ("Modifier".equals(title) && prefs.getInt(Prefs.ROOT_MODIFIER_DIRECTION, 0) != 0) {
            int source = prefs.getInt(Prefs.ROOT_MODIFIER_SOURCE, Prefs.AXIS_DPAD);
            assigned = sourceName(source) + " " + rootDirectionName(
                    prefs.getInt(Prefs.ROOT_MODIFIER_DIRECTION, 0)) + " (root)";
        } else if (!(prefs.getBoolean(Prefs.ROOT_AXES, false)
                && rootDirectionMapped(title))
                || (!"Brighter".equals(title) && !"Dimmer".equals(title))) {
            assigned = keyName(fallbackKey);
        } else {
            int source = prefs.getInt("Brighter".equals(title)
                    ? Prefs.ROOT_BRIGHT_SOURCE : Prefs.ROOT_DIM_SOURCE, Prefs.AXIS_DPAD);
            String control = sourceName(source);
            int direction = prefs.getInt("Brighter".equals(title)
                    ? Prefs.ROOT_BRIGHT_DIRECTION : Prefs.ROOT_DIM_DIRECTION, 0);
            assigned = control + " " + rootDirectionName(direction) + " (root)";
        }
        String full = title + "\n" + assigned;
        SpannableString styled = new SpannableString(full);
        styled.setSpan(new BackgroundColorSpan(Color.rgb(95, 75, 125)),
                full.length() - assigned.length(), full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    private boolean rootDirectionMapped(String title) {
        if ("Brighter".equals(title)) {
            return prefs.getInt(Prefs.ROOT_BRIGHT_DIRECTION, 0) != 0;
        }
        if ("Dimmer".equals(title)) {
            return prefs.getInt(Prefs.ROOT_DIM_DIRECTION, 0) != 0;
        }
        return false;
    }

    private String rootDirectionName(int direction) {
        switch (direction) {
            case Prefs.ROOT_DIRECTION_UP: return "Up";
            case Prefs.ROOT_DIRECTION_DOWN: return "Down";
            case Prefs.ROOT_DIRECTION_LEFT: return "Left";
            case Prefs.ROOT_DIRECTION_RIGHT: return "Right";
            default: return "Not recorded";
        }
    }

    private String sourceName(int source) {
        return source == Prefs.AXIS_RIGHT_STICK ? "R-stick"
                : source == Prefs.AXIS_LEFT_STICK ? "L-stick" : "D-pad";
    }

    private void updateRootMappingLabels() {
        if (modifierMappingLabel != null) {
            modifierMappingLabel.setText(mappingLabel("Modifier",
                    prefs.getInt(Prefs.MODIFIER, KeyEvent.KEYCODE_BUTTON_R1)));
        }
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
                    + " together to disable. These are the saved fallback buttons; stick axes cannot send opposing directions at the same time.");
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
        button.setTextSize(16);
        return button;
    }

    private TextView centeredDialogTitle(String value) {
        TextView title = text(value, 20, false);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setPadding(dp(24), dp(16), dp(24), dp(8));
        return title;
    }

    private void styleSetupButton(Button button) {
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(Color.WHITE);
        buttonBackground.setCornerRadius(dp(8));
        button.setBackground(buttonBackground);
        // Keep the setup controls compact so the fixed three-column layout
        // clears the Thor's three-button navigation bar.
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
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
        card.setPadding(dp(14), dp(4), dp(14), dp(8));
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

