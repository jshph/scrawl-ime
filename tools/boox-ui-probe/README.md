# BOOX Android UI probe

Verified on a Note Air5 C, Android 15, with SDK 36 and JDK 17. Set `ANDROID_SERIAL` if more than one device is connected.
Compiles a small shell Java program; installs no app and changes no settings.
Uses hidden InputManagerGlobal through `app_process` as ADB shell. Verified on
firmware `2026-07-02_19-03_4.2.1-rel_0702_038ed12af`.

```sh
tools/boox-ui-probe/run.sh press 1000 1100
# Capture/inspect Android screenshot and (separately) physical camera view here.
tools/boox-ui-probe/run.sh select 1130 1077  # radial # at this center/density
tools/boox-ui-probe/run.sh release 1130 1077
```

Coordinates are screen pixels. `select` preserves the held barrel-button bit
through hover-exit/down/up. Ordinary `adb shell input stylus tap` clears it,
causing the menu to dismiss before the tap. Always release after an experiment.
To dismiss without selection, just run `release` at the opening coordinates.

Sidebar taps can use `adb shell input stylus tap X Y`; obtain coordinates
from a current screenshot because toolbars can move. Space taps apart to allow
Android click processing. Finger injection was suppressed during the initial
probe while a stylus hover was present; stylus injection reached the root.

This tests Android event delivery, menu hit testing, model/UI state and transitions.
It DOES NOT inject Wacom kernel events, generate proven raw ink, observe physical
EPD output, or validate preview latency. Start with a real pen stroke and compare
with a camera/person observing the panel. Never infer panel visibility from
`screencap`, even if all automated checks pass.

Direct `sendevent /dev/input/event5 0 0 0` is denied on this production firmware.
`adb root` returns “adbd cannot run as root in production builds”.
No root, SELinux, bootloader, or firmware changes are performed by this probe.
