# Thor's Lightning

Controller-driven dual-screen brightness control for the AYN Thor.

Thor's Lightning lets you hold a configurable modifier and use mapped buttons
to brighten or dim the top screen, bottom screen, or both. Standard Android
button mappings work without root; D-pad and analog-stick directions can be
enabled on rooted devices.

## Features

- Configurable modifier, brighter, and dimmer controls
- Top, bottom, or dual-screen brightness adjustment
- Adjustable brightness step and hold-repeat speed
- Non-root support for volume, face, shoulder, stick-click, Start, and Select
- Optional root input support for D-pad and left/right analog-stick directions
- Starts again after device reboot once its accessibility service is enabled
- Follows the Android system light/dark appearance

## Installation

1. Download the APK from the latest GitHub release.
2. Install it on the AYN Thor.
3. Open Thor's Lightning and complete **1. Brightness permission**.
4. Complete **2. Key detection** to enable its accessibility service.
5. Choose the button mappings and screens you want to control.

Root axis input is optional. Axis events remain visible to games, so D-pad or
stick movement cannot always be fully reserved while adjusting brightness.

## Building

The project targets Android SDK 35 and requires JDK 17.

```powershell
.\gradlew.bat test assembleRelease
```

The release build currently uses the Android debug signing key for direct
installation and update testing.
