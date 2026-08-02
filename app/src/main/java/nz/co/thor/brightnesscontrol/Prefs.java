package nz.co.thor.brightnesscontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

final class Prefs {
    static final String FILE = "brightness_control";
    static final String ENABLED = "enabled";
    static final String MODIFIER = "modifier_key";
    static final String STEP = "step";
    static final String PRESS_STEP = "press_step";
    static final String HOLD_STEP = "hold_step";
    static final String LINK_HOLD_STEP = "link_hold_step";
    static final String REPEAT_DELAY = "repeat_delay";
    static final String CONSUME_MODIFIER = "consume_modifier";
    static final String CAPTURING = "capturing";
    static final String TARGET = "target";
    static final String THEME = "theme";
    static final String UP_KEY = "up_key";
    static final String DOWN_KEY = "down_key";
    static final String ROOT_AXES = "root_axes";
    static final String AXIS_SOURCE = "axis_source";
    static final String ROOT_LIMIT_ACK = "root_limit_ack";
    static final String SETUP_GUIDE_SHOWN = "setup_guide_shown";
    static final String AWAITING_STEP_TWO = "awaiting_step_two";
    static final String BRIGHTNESS_GUIDE_SHOWN = "brightness_guide_shown";
    static final String KEY_GUIDE_SHOWN = "key_guide_shown";
    static final String ADVANCE_TO_KEY_PENDING = "advance_to_key_pending";
    static final String ADVANCE_TO_BRIGHTNESS_PENDING = "advance_to_brightness_pending";
    static final String SETUP_COMPLETE_SHOWN = "setup_complete_shown";
    static final String SUSPEND_SERVICE = "suspend_service";
    static final String SUSPEND_SERVICE_COMPONENT = "suspend_service_component";
    static final String SUSPEND_SERVICE_ACTIVE = "suspend_service_active";
    static final String SUSPEND_SERVICE_COMPONENTS = "suspend_service_components";
    static final String SUSPEND_SERVICE_IGNORED = "suspend_service_ignored";

    static final int AXIS_DPAD = 0;
    static final int AXIS_RIGHT_STICK = 1;
    static final int AXIS_LEFT_STICK = 2;

    static final int TARGET_BOTH = 0;
    static final int TARGET_TOP = 1;
    static final int TARGET_BOTTOM = 2;

    static final int THEME_SYSTEM = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int modifier(Context context) {
        return get(context).getInt(MODIFIER, KeyEvent.KEYCODE_BUTTON_R1);
    }
}
