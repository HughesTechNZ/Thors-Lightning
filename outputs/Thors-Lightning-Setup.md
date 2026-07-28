# Thor’s Lightning 1.3.2

Installed package: `nz.co.thor.brightnesscontrol`

This version is entirely non-root. It uses Android's accessibility key-filtering
service and the user-granted **Modify system settings** permission. It does not
read screen content.

## One-time setup on the Thor

1. Open **Thor’s Lightning**.
2. Tap **1. Allow brightness control** and enable **Allow modifying system
   settings**.
3. Return to the app and tap **2. Enable key detection**.
4. Select **Thor brightness button control** and enable **Use service**.
5. Return to the app. Both permission indicators should show check marks.
6. Tap **Record controller button**, then press the controller button you want
   to use as the modifier.
7. Record separate **Modifier**, **Brighter**, and **Dimmer** inputs. Volume
   Up/Down are the defaults. Face, shoulder, stick-click, Start, and Select
   buttons are supported.
8. Hold the modifier and press the mapped Brighter or Dimmer input.

If Android says the accessibility setting is restricted, open:

**Settings > Apps > Thor’s Lightning > three-dot menu > Allow restricted
settings**

Then repeat step 3.

## Configuration

- **Brightness shortcut enabled**: master switch.
- **Screens to adjust**: independently target **Both**, **Top**, or **Bottom**.
  Both is the default and preserves each screen's existing relative brightness.
- **Appearance**: follow Android's system theme or force **Light** or **Dark**.
- **Button mappings**: independently record the modifier, brighter, and dimmer
  controls.

The Thor reports its D-pad as a joystick hat axis rather than background key
events. D-pad brightness mappings therefore require the optional root input
backend and are not available in this non-root release.
- **Reserve the modifier button for brightness**: prevents the selected button
  from reaching games. Enabled by default.
- **Brightness step**: 1–25 percent per press.
- **Hold repeat speed**: 100–1000 ms between brightness steps.

## Safety

Ordinary volume control remains unchanged whenever the modifier is not held or
the screen is off.

To disable the shortcut immediately, hold the configured modifier and press
both physical volume buttons together. Reopen the app to enable it again.
