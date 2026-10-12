# BOOX menu and sidebar device experiment — 2026-09-28

Thread: thr_s5u4gvmbs2; parent: thr_v5nqs8czbf. Isolated branch
`experiment/canvas-layer-v016`; original checkout untouched.

## Canvas control

Device: NoteAir5C.
Previously installed: com.scrawl.app 0.1.14, code 20, target 35, DEBUGGABLE.
Backed up base APK and settings before `adb install -r`.
Installed control: commit `5d0aef12ed977630371decd74e40b7ebfab64aed`,
0.1.16-canvas-exp, code 22, target 36;
SHA256 `67013a351b9dce0a743ef062cca9cb7917f076011ef2be40a657d9654ead290a`.
No new implementation was installed during this control test.

Host: existing blank Obsidian note 2026-09-28-10-41-32; focused body via
ADB tap (450,440). InputMethodManager confirmed shown and live input connection.
Both saved pen button assignments were RADIAL_MENU.

11:09:37–49: real stylus strokes, Canvas begin/finish logs.
11:09:41: radial show and onDraw after ink; first show logged size 0x0 before layout.
11:10:18: second radial show, size 1860x2418, onDraw.
11:10:20: radial touch up hit=2 (#); later screenshot has active # sidebar state.
User confirmed “the radial menu works” and then “theywork” for sidebar controls.
11:12:04: sidebar icon click followed by toggleEraser=true.
This establishes Canvas display/input as a working control after real ink, not
proof that raw rendering can coexist with the controls. The user clarified that
retaining raw/EPD rendering is the actual investigation target.

SurfaceFlinger lists before/after ink show only navigation animation-leash churn;
no new named ink layer. This cannot rule out vendor rendering outside SF.
No physical-panel/screenshot mismatch established during the Canvas control.
Collapse/expand, hide/show, and mode-switch regression remain untested.

## Diagnostic changes staged after control

Correct literal escaped root log interpolation; log touch actions, coordinates,
tool/buttons, root generic button transitions and dispatch results; log radial
size and dismissal reason. No rendering policy changed.
`testDebugUnitTest assembleDebug` passed; `git diff --check` passed.
These changes were built but not installed for the control observations above.

## Artifacts (local Mac, outside repository)

`/tmp/scrawl-canvas-layer-v016-evidence/20260928/` contains:

- installed-0.1.14-base.apk, package-before.txt, settings-before.xml
- tested-5d0aef1.apk (pulled back from device; exact hash reverified)
- before-install.png, canvas-ready.png, after-user-menu-confirmed.png
- sequence-baseline/ (45 timestamped Android captures, ~1.2 s interval;
  individual captures do not prove physical panel state)
- live.log, input-method-initial.txt, input-after-ink.txt
- layers-initial.txt, layers-after-ink.txt, build-diagnostics.log

## Raw comparison plan

Restore the master v0.1.16 TouchHelper renderer into this experiment, retaining
the Canvas model drawing helper and diagnostics. Name the APK explicitly raw-exp.
Reproduce with real pen input before trying one bounded raw/UI handoff change.
Working Canvas APK remains available as the correctness/control baseline.
No release build, push, or main-branch merge is authorized or performed.

## Raw control: reproduced physical display mismatch

Comparison source checkpoint `34b17ae` restores IMECanvasView from master
`70c8c7b`, Onyx SDK dependencies, and raw region/lifecycle integration. It retains
the Canvas branch's `drawStroke` helper and does not restore master’s toolbar
DU/refreshScreenRegion calls. It is therefore a controlled raw comparison, not
a byte-for-byte master build. The APK was built before a whitespace-only indent
cleanup in that checkpoint.

Installed `0.1.16-raw-exp` (code 22, target 36):
SHA256 `30abb70a13655f35f649e6e71f555fb150322d27a095edc39313b6e384d7818d`.
Build/unit tests passed. Saved as `raw-control.apk`; application files and prefs
also backed up as `data-before-raw.tar`. Device Android 15, firmware
`2026-07-02_19-03_4.2.1-rel_0702_038ed12af`.

11:15:32: TouchHelper active with writing box Rect(124,0–1860,2418), excluding
left toolbar rail. 11:16:11–13: genuine raw begin/end, 1131 points baked.
11:16:14–16: barrel button transitions, menu show/onDraw, release dismissal.
11:16:22: real stylus sidebar clicks reached Eraser, toggled true then false.
11:16:23: real stylus # click reached action; screenshot shows active state.
User: “tapped them, they dont show” and “they show in the image but the compositing
here is not the asme as on device”. Screenshot
`sequence-raw/111615-263269.png` visibly contains radial UI on top of ink.
`raw-after-ink.png` shows active sidebar #. SF layer list did not change between
raw-ready and raw-after-ink. This is strong display-path evidence, not a proven
missing-input issue for those taps; exact vendor compositor/refresh mechanism
remains unresolved.

SDK reference: https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md
Documents raw render enable/disable independently from raw input. Local 1.5.1
bytecode shows render-disable in SFTouchRender leaving scribble mode and pausing
EpdPenManager, while setRawDrawingEnabled changes both render and input.

## Bounded render-only UI handoff

Installed checkpoint `85c0f87`, `0.1.16-raw-ui-exp`:
SHA256 `260094c1c1382e0663a85d276dec7d7ce648130b1dc15b2b3ce8d7e44c058551`.
Saved as `raw-ui-handoff.apk`. Build and unit tests passed.

Menu entry / button click calls setRawDrawingRenderEnabled(false), redraws the
Canvas model, invalidates Android UI. Menu dismissal leaves render suspended
until the next accepted raw begin, which resumes preview. Raw reader stays alive;
raw gestures beginning under a visible menu are suppressed through their end so
menu taps cannot create model ink. No popup, alternate backend, or added regional
EPD refresh API is layered into this experiment.

**Not yet physically validated.** Do not call this a fix. After reinstall there
has not yet been a confirmed real pen stroke to exercise raw preview resumption.
Need check ink preservation on suspend, panel menu visibility, real stylus menu
selection, next-stroke latency/first segment, raw erase, and hide/show/mode transitions.
The initial control’s raw eraser and model-bake limitations also remain.

## Autonomous testing progress and limits

`tools/boox-ui-probe` now injects a stylus hover with held barrel button via
InputManagerGlobal, then preserves that bit through selection down/up.
11:22:53–56: automated menu show, hit=2, action/dismiss, and button release all
logged; model stroke count stayed 15 through handoffs. Automated stylus taps also
reached sidebar Eraser and #, and collapse. Android captures in `probe-*.png`.
Initial probe using InputManager.getInstance failed in its separate shell process;
InputManagerGlobal resolved it. This was not a Scrawl app crash.

No kernel injection: sendevent on Wacom /dev/input/event5 denied. Node is root:input
mode 0664; shell is in input group, SELinux Enforcing, node input_device label.
SELinux is the likely denial source, but an explicit matching AVC was not found.
ro.debuggable=0, ro.secure=1, ro.build.type=user; no su in standard paths;
ADB root explicitly rejected. No device permissions or firmware were changed.

Physical verification still requires camera or user. Mac exposes USB Video and
MacBook camera; camera positioning/selection was asked, no answer yet. No camera
capture was taken. Installed ffmpeg currently fails at launch due to missing
libx265.215.dylib, so a native capture helper may be needed if camera is selected.
Root can improve tracing/injection and allow policy/framework experiments, but
does not by itself establish physical-panel visibility or expose a usable EPD
composition API. Do not unlock/flash as part of this data-preserving experiment.

The fast hide/show probe was inconclusive: immediate dumps captured mInputShown
true before the hide completed, then false before re-show. Later finger taps did
not re-open it. Service was force-stopped and Scrawl reselected to restage the
host without clearing data. Do not count hide/show as passed. Current service
has onDestroy raw cleanup but lacks explicit onFinishInputView raw teardown;
this is a concrete lifecycle follow-up, separate from the render-only experiment.

## UI2 lifecycle/order corrections and device regression

Code checkpoint `dc170a7`, installed `0.1.16-raw-ui2-exp` (code 22, target 36),
SHA256 `94159b5fd8a1a9175580faa83c8530017147d2727cfb562b14ca9a44fc0c7f80`.
Previous installed APK pulled again and verified against UI1's recorded hash.
Artifacts: `raw-ui2-handoff.apk`, `installed-before-ui2.apk`,
`package-before-ui2.txt`, `build-raw-ui2.log`, `ui2-regression/`.

Two corrections to the experimental handoff:

- Radial actions run after menu dismissal. Eraser's tool-mode setter reenabled raw
  rendering at that point, undoing the dismissal handoff. Each radial action now
  yields to Android UI after applying its state change, matching sidebar actions.
- onFinishInputView dismisses the menu, resets tracked barrel buttons, and closes
  raw drawing. Previously only onDestroy closed it. Deactivation also suppresses
  trailing raw callbacks and resets overlay/suspension state.

40 local unit tests passed (zero failures/errors); assembleDebug and diff check
passed. Existing unit tests cover HWR/model policies, not EPD hardware behavior.

11:28:01–13 automated real-device Android-event regression:

- Selected radial Eraser twice (hit=5), toggled true then false.
- Each action ended with TouchHelper input=true / render=false in getter logs.
- BACK reached mInputShown=false using polling rather than an immediate dump.
- onFinishInputView logged raw deactivation with 15 model strokes.
- Stylus tap in host reopened IME without force-stop; same PID 5396 reopened raw
  input/render and retained all 15 model strokes.
- A new barrel-button probe reopened the menu, followed by release dismissal.
- Seven trace assertions passed; see ui2-regression/assertions.json and layers.log.

This verifies lifecycle and Android input/state behavior only. Cached SDK getter
values and screenshots do not prove physical display behavior. UI2 still needs
real pen input to validate post-handoff raw preview resumption and a physical
panel/camera observation to establish visibility, ink preservation, and latency.
No new real-pen run has been observed since UI2 install as of this regression.

## User real-pen feedback and UI3: annotations and connecting lines

User subsequently confirmed the radial menu appears on the physical BOOX with
UI2. Remaining observations: annotation gestures appear as black raw lines until
another barrel press, and a connecting segment appears from stroke end to start.
UI2 logs at 11:39:51–53 show a real radial # selection, raw gesture begin/end,
then a subsequent menu handoff. This is new hardware evidence allowing work to
continue after the earlier blocked state; no root or camera was required for it.

Pulled `strokes-ui2-before-raw-fix.json`: 30 strokes and 3 annotations.
All 16 raw strokes (indices 14–29) have the exact pattern P + P + END:
identical x/y/pressure/dt blocks followed by the pen-up point. Each middle jump
resets dt to 0. Stroke 14 had 1131 points and a 484.4 px end-to-start jump at 565.
Thus at least this connector is in the saved model, not just a vendor preview.

Onyx SDK 1.5.1 RawInputReader bytecode confirms BEGIN/MOVE append to its internal
list and deliver individual callbacks; release delivers that full list before
END. Scrawl previously appended both streams. The Path renderer accurately drew
the resulting spurious segment. Removed the misleading source comment blaming
CanvasStrokeRenderer for closing paths.

UI3 code checkpoint `7a5278c`, `0.1.16-raw-ui3-exp`, code 22, target 36;
APK SHA256 `de9f77b457f91457813fa4d9bf2ccf3a0ea63286fe7dac5b81f51fcc29090610`.
APK compiled before a comment-only edit included in the code checkpoint.
Changes:

- Replace streamed raw points with the authoritative full callback batch instead
  of appending it; retain the final END point and existing filters.
- On annotation completion, persist the annotation and use the same render-only
  handoff as the menu so Android can display the annotation without a barrel press.
- Recover only exact P+P+END saved corruption with matching coordinates, pressure,
  and timestamps, a dt=0 start and valid within-copy time order. Load-time repair
  leaves the persisted source file intact until a normal future save. Ordinary
  closed paths, geometry revisited at later times, missing times, differing
  pressure, and partial repeats are left unchanged.

45 unit tests passed (5 new recovery/counterexample tests), debug build and diff
check passed. Before install, pulled installed UI2 APK and backed up application
files/prefs again (`installed-before-ui3.apk`, `data-before-ui3.tar`).
11:44:46 startup logs confirm all 16 exact corruptions recovered, retaining all
30 strokes and 3 annotations. `ui3-recovered.png` shows the connector removed from
Android composition; it is not a physical-panel validation. New raw callback
assembly and immediate annotation display still require the next real-pen run.

Additional artifacts: `raw-ui3.apk`, `build-raw-ui3.log`, `ui3-start.log`,
`user-annotation-connector.png`, `package-before-ui3.txt`, and SDK bytecode under
`/tmp/scrawl-canvas-layer-v016-evidence/sdk/RawInputReader.bytecode.txt`.

## UI4: remove the raw black annotation-gesture preview

User reports UI3 “works!” but still sees a black line during drawing. In context,
this is the annotation-selection gesture: UI3 yielded the raw layer on completion,
but still enabled its ordinary black ink preview during the drag.

UI4 checkpoint `e737dd5`, version `0.1.16-raw-ui4-exp`, code 22, target 36;
APK SHA256 `4c9d906be5264c4b4186292a3b85c971fa7227463b91fe88d343116bbb7bfdcc`.
Selecting an annotation now suspends raw rendering before the next pen DOWN.
Its BEGIN callback keeps that suspension while preserving the raw reader, and
point updates schedule the existing Canvas rectangle preview. Ordinary handwriting
resumes raw rendering at its next BEGIN. No additional vendor refresh API added.

45 unit tests passed, debug assembly and diff check passed. Backed up installed
UI3 package metadata/APK and app files/prefs (`package-before-ui4.txt`,
`installed-before-ui4.apk`, `data-before-ui4.tar`) before installing with `-r`.
Confirmed installed UI4 version and restarted IME with a focused host field:
11:51:49 input-view start, mInputShown=true. Artifacts: `raw-ui4.apk`,
`build-raw-ui4.log`, `ui4-start.log`. No physical-panel result for UI4 yet.

Next real-pen verification: choose an annotation, drag slowly, check rectangle
preview without a black trace, lift and check immediate final annotation, then
write normal ink and check raw latency and absence of connecting segments.
Canvas selection preview can refresh more slowly than raw ink. Android screenshots
and synthetic events alone cannot establish the physical-panel result.

## UI5: eraser pen-up and Clear reset

User physically confirmed UI4 annotation preview works. They then reported erased
ink should disappear at pen-up and Clear does not leave drawing ready.
UI4 real-pen logs at 11:53:09.971–79 show software erase removed four strokes
(12 to 8), but refreshAfterRawLayerChange explicitly kept raw rendering enabled.
This matches the existing model/panel mismatch. Clear also retained selected
annotation/eraser tools and restarted the raw layer instead of yielding to Canvas.

UI5 checkpoint `93f07f7`, `0.1.16-raw-ui5-exp`, code 22, target 36;
SHA256 `c1639e17dd4f12b82303ad67b7ad161b79d656615bc4ceb24b77bcbd4619fb15`.
Both raw eraser END callbacks now persist changes, update stroke-count UI and
publish the model with the proven render-only handoff, even for no-hit preview
cleanup. Next BEGIN resumes raw preview. Clear resets pending geometry/bake/erase
state, suppresses trailing callbacks until BEGIN, and uses the same handoff without
restartRawDrawing. The explicit X action also exits annotation and eraser modes
and updates the toolbar to pen mode. No new vendor display APIs added.

45 unit tests passed, assembly and diff check passed. Before install, saved package
metadata, pulled the installed UI4 base APK, and archived app files/prefs (including
the current eight strokes). Installed using -r and reselected the IME; confirmed
UI5 version and focused input. Automated real-device stylus taps selected Eraser
then X: logs confirm mode=WRITE, eraser=false, annotation=None, strokes=0,
rawActive=true and input=true/render=false. Radial menu opened/drew/dismissed after
Clear. Also exercised annotation selection followed by X. These Android-event
checks do not prove the next raw pen stroke or physical erasure visibility.

Artifacts under the existing evidence directory: `build-raw-ui5.log`, `raw-ui5.apk`,
`package-before-ui5.txt`, `installed-before-ui5.apk`, `data-before-ui5.tar`,
`ui5-start.png`, `ui5-cleared.png`, `ui5-clear-regression.log`.
Remaining hardware check: draw, erase and lift (visible immediately); repeat erase;
press X while eraser or annotation is selected, then immediately draw normal ink.

## Release v0.1.17

User accepted UI5 (“nice”) and explicitly requested commit and release, superseding
the earlier no-release constraint. The preceding fixes are committed on the
isolated experiment branch. Release 0.1.17 / code 23 packages that tested state,
using the existing signing identity; the original checkout remains untouched.
The repository release workflow produces a signed AAB and local tag without
pushing or uploading to Play. User acceptance is the physical-panel evidence;
no camera capture or privileged raw-input replay was available.

Release checks: 45 unit tests passed; bundleRelease and assembleRelease completed,
including release lint. APK metadata confirms 0.1.17/code 23. APK signature passes
apksigner. AAB signature verifies via jarsigner; its self-signed certificate and
ZIP-order/JarInputStream warnings were checked with verifying JarFile reads of
all non-META-INF payload entries, requiring the same release signer as the APK.
Release certificate SHA256:
`3b301535cfc3ae872fd79ecca8af215c0a9eb463ece10d711049923b78a30e9e`.

- AAB: 13,419,251 bytes; SHA256
  `9a275483c0acea42395ddfab89b029f6d490824313ae935037d2d63c073119ec`.
- APK: 25,582,597 bytes; SHA256
  `6f68c2e81e577b3114de36ab70760b18153f1c5166bb101a49d76cf148236b06`.

The device remains on the data-preserving debug build tested by the user; the
signed release has not been installed over its different signing identity.
