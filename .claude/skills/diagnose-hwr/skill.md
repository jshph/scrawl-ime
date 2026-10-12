---
name: diagnose-hwr
description: Run an interactive ADB diagnostic session to investigate HWR behavior on a connected Boox device. Guides the user through writing, captures logcat, and analyzes recognition pipeline performance.
argument-hint: [device-serial (optional)]
allowed-tools: Bash, Read, Write, Grep, AskUserQuestion
---

# Diagnose HWR — Interactive ADB Diagnostic

Investigate Scrawl's handwriting recognition pipeline on a connected Boox device via ADB. This is designed for beta testers — it guides them through the process and produces a diagnostic report.

## Prerequisites

- Device connected via USB with ADB debugging enabled
- Scrawl installed on the device

## Execution Flow

### Phase 1: Device Discovery & Environment

1. Run `adb devices -l` to find connected devices. If `$1` is provided, use it as the device serial. If multiple devices and no serial given, ask which one.

2. Collect device info and print a summary:
   ```bash
   adb -s $SERIAL shell getprop ro.product.model
   adb -s $SERIAL shell getprop ro.product.manufacturer
   adb -s $SERIAL shell getprop ro.build.version.sdk
   adb -s $SERIAL shell getprop ro.build.display.id
   adb -s $SERIAL shell cat /proc/meminfo | head -1
   adb -s $SERIAL shell getprop ro.hardware
   adb -s $SERIAL shell nproc
   ```

3. Check Scrawl is installed and get version:
   ```bash
   adb -s $SERIAL shell dumpsys package com.scrawl.app | grep -E "versionCode|versionName"
   ```

4. Check ksync is installed and get its status:
   ```bash
   adb -s $SERIAL shell pm list packages | grep ksync
   adb -s $SERIAL shell dumpsys package com.onyx.android.ksync | grep -E "versionCode|versionName"
   ```

5. Check if KHwrService is currently running:
   ```bash
   adb -s $SERIAL shell dumpsys activity services com.onyx.android.ksync/.service.KHwrService
   ```

6. Check which HWR engine is present (MyScript vs other):
   ```bash
   adb -s $SERIAL shell ls /system/lib64/libMyScript* /system/lib64/libiink* 2>/dev/null
   ```

7. Check installed language packs:
   ```bash
   adb -s $SERIAL shell ls /system/recognition_assets/
   ```

Print all of this as a formatted device summary table.

### Phase 2: Clear & Start Logcat

1. Clear existing logcat:
   ```bash
   adb -s $SERIAL logcat -c
   ```

2. Tell the user:
   > "Open any text field on your device (e.g. Boox Notes, a browser search bar, or any app). Make sure Scrawl is the active keyboard. Write a few words with the stylus, then tap the commit button (checkmark). I'll capture the logs."

   Wait for them to confirm they've done it.

### Phase 3: Capture Logs

1. Capture Scrawl and HWR-related logs:
   ```bash
   adb -s $SERIAL logcat -d -v time | grep -E "OnyxHWR|Scrawl|ScrawlPerf|HWREngine|DeviceProfile|MlKit" | tail -200
   ```

2. Also capture any crashes or errors:
   ```bash
   adb -s $SERIAL logcat -d -v time *:E | grep -i -E "scrawl|hwr|ksync|myscript|iink" | tail -50
   ```

### Phase 4: Analyze & Report

Parse the captured logs and produce a diagnostic report covering:

#### 4a. Engine Selection
- Which engine was selected by HWREngineProvider (Onyx vs ML Kit)?
- Did the Onyx probe succeed?
- Was FORCE_MLKIT active? (check for the warning log)

#### 4b. DeviceProfile
- What tier was detected? (HIGH vs LOW)
- RAM, CPU cores, monochrome flag
- Bake threshold in use

#### 4c. HWR Init
- Did `ensureInitialized` succeed? (`activated=true/false`)
- Which language was requested?
- How long did init take?

#### 4d. Recognition Performance
- How many strokes/points were sent?
- Protobuf size?
- How long did `batchRecognize` take?
- What was the recognized text?
- Was the JSON response well-formed?

#### 4e. Stroke Pipeline
- Touch-to-draw latency from ScrawlPerf logs
- Bake counts and timing
- Progressive bake behavior
- Points filtered

#### 4f. Errors
- Any IPC failures, DeadObjectExceptions
- Any language pack issues
- Any crashes or ANRs

### Phase 5: Recommendations

Based on the analysis, suggest:
- If HWR is slow (>1s), note the device tier and suggest testing with fewer strokes
- If language activation failed, explain which language packs are installed and what to download
- If the service wasn't bound, suggest checking firmware version and ksync installation
- If ML Kit was used instead of Onyx, explain why and whether that's expected
- If there are memory issues (OOM, GC pauses in logs), note the RAM constraint

### Phase 6: Save Report

Write the full diagnostic report to `beta-testing/hwr-diagnostic-{model}-{date}.md` (create the directory if needed). Include:
- Device summary table
- Raw relevant logs (trimmed)
- Analysis findings
- Recommendations

Tell the user the report path so they can share it.

## Tips

- If the user wants to test multiple commits, tell them to repeat the write-and-commit cycle. Clear logcat between runs with `adb logcat -c`.
- If logcat shows nothing from Scrawl, the app may not be the active keyboard. Guide them through Settings > System > Keyboard > Scrawl.
- The `ScrawlPerf` tag gives frame-level performance data (build/render/touch-to-draw timing per 60 frames). Look for the periodic summary lines.
- `OnyxHWR` tag has all recognition pipeline logging.
- `DeviceProfile` tag shows the tier detection that ran at startup.
