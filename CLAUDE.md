# Scrawl

Handwriting IME for Onyx Boox e-ink devices. Write with the stylus, text is committed to any app's text field via HWR (handwriting recognition).

## Dev workflow

### Installing on device

After `./gradlew installDebug`, always force-stop before testing:

```bash
adb shell am force-stop com.scrawl.app
```

Then open a text field to restart the IME. Without this, the old process keeps running and new code (especially `onSizeChanged`/`initTouchHelper` initialization) won't execute.

### Useful log filters

```bash
adb logcat -s "ScrawlPerf:*"          # All IMECanvasView logs (TAG = ScrawlPerf)
adb logcat -s "DeviceProfile:*"        # Device tier detection
```
