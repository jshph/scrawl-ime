# Handwriting Recognition Engine Research

Research into HWR engines across e-ink tablet platforms, how they work internally, and what's accessible to third-party apps like Scrawl.

## Engine Overview

| | **Boox** | **reMarkable** | **Supernote** |
|---|---|---|---|
| Engine | Microsoft (newer firmware) / MyScript (older firmware) | MyScript | MyScript |
| On-device? | Yes — fully on-device | No — cloud only | Mostly on-device (first use needs internet to activate, then offline) |
| Quality | Very good, especially Latin scripts | Good, but gated behind subscription ($3/mo Connect plan) | Very good, real-time conversion available |
| Tappable from 3rd-party app? | **Yes** — AIDL service (`KHwrService`) | No — API key held server-side | No documented internal API |
| Platform | Android | Custom Linux | Custom Linux (not Android) |

## Boox: Microsoft-based Recognizer

### What Scrawl uses today

Scrawl binds to `com.onyx.android.ksync.service.KHwrService` via AIDL. The protobuf payload uses `recognizerType = "MS_ON_SCREEN"`, which references a Microsoft on-screen handwriting recognizer.

### Licensing

- The Microsoft on-device HWR engine is **proprietary**, not open source or freely available.
- Licensed to OEMs (Boox) through commercial agreements. Likely the same family as the Windows Ink engine.
- **Scrawl doesn't need a license** — we're a client of Boox's system service, not directly using the Microsoft SDK. Same as calling Android's built-in speech recognition.
- Risk: Boox could change the service interface, rename the package, or switch engines in a future firmware. No public API contract or stability guarantee.

### Firmware split

- **Older Boox firmware (pre-2023)**: Used **MyScript** for HWR. Different package/component — `KHwrService` won't exist.
- **Newer Boox firmware (3.5+)**: Switched to the **Microsoft-based** recognizer in `com.onyx.android.ksync`.
- The Nova Air 2 (2022) may ship with MyScript depending on firmware version. If `KHwrService` isn't found, `bindService` returns false and HWR silently fails.
- Scrawl now has an ML Kit fallback via `HWREngineProvider` to handle this case.

### Azure Ink Recognizer (retired)

Microsoft had a public cloud-based Ink Recognizer API (Azure Cognitive Services) that was **retired in 2021**. The Boox engine appears to be the on-device variant, not the cloud one.

## reMarkable: MyScript (Cloud-Only)

### How it works

- reMarkable sends handwriting strokes to **reMarkable's own servers**, which proxy to MyScript's REST API.
- The MyScript API key lives **server-side on reMarkable's infrastructure**, not on the tablet.
- Requires reMarkable Connect subscription ($3/mo) for HWR access.
- No on-device processing. No local service to bind to.

### Can you tap into it?

**No.** There's no on-device API. The community has reverse-engineered the cloud sync protocol but not the HWR proxy.

Workarounds:
- **rmfakecloud** ([ddvk/rmfakecloud](https://github.com/ddvk/rmfakecloud)) — self-hosted cloud replacement. You can supply your own MyScript API key for HWR, but it's still cloud-based.
- **Your own MyScript account** — developer.myscript.com, charges per recognition event. Free tier available for dev use.

### Relevant GitHub repos

- [akeil/rescript](https://github.com/akeil/rescript) — extracts text from reMarkable notes via MyScript REST API (needs own API key)
- [bkirwi/armrest](https://github.com/bkirwi/armrest) — Rust library that runs **on the reMarkable itself** with a custom LSTM-based HWR model trained from scratch. Completely independent of MyScript. Not portable to Android.
- [splitbrain/ReMarkableAPI](https://github.com/splitbrain/ReMarkableAPI) — reverse-engineered cloud sync API (PHP)
- [SamMorrowDrums/remarkable-mcp](https://github.com/SamMorrowDrums/remarkable-mcp) — MCP server for Claude/Copilot to read reMarkable notes including OCR
- [reHackable/awesome-reMarkable](https://github.com/reHackable/awesome-reMarkable) — curated list of community tools

## Supernote: MyScript (On-Device)

### How it works

- Uses MyScript on-device. First use requires internet to activate the license, then works fully offline.
- Supports 60+ languages.
- Real-time conversion available: create a "Real-Time Recognition Note" and text appears as you write.
- Supernote is a **custom Linux platform, not Android** — no `bindService`, no AIDL, no way for a third-party app to call into the HWR at runtime.

### Can you tap into it?

**No.** No documented inter-app API. The only way to get recognized text is through Supernote's own UI or by post-processing exported files.

### Relevant GitHub repos

- [jya-dev/supernote-tool](https://github.com/jya-dev/supernote-tool) — Python tool to extract text from real-time recognition notes (`convert -t txt`), export to PDF
- [allenporter/supernote](https://github.com/allenporter/supernote) — Personal Knowledge Management hub. Parses notebooks, uses Gemini Vision for OCR, semantic search across handwriting. Has a Python API and MCP support.
- [liketheduck/supernote-ocr-enhancer](https://github.com/liketheduck/supernote-ocr-enhancer) — replaces Supernote's built-in OCR with Apple Vision Framework. Claims +41.8% more text captured, 0.8s/page. Writes enhanced OCR data back into `.note` files.
- [adrianba/supernote-cloud-api](https://github.com/adrianba/supernote-cloud-api) — unofficial cloud storage API
- [fharper/awesome-supernote](https://github.com/fharper/awesome-supernote) — curated list

## MyScript SDK (if we ever need direct access)

MyScript offers the **iink SDK** for OEMs and app developers:
- On-device handwriting recognition
- 70+ languages, math, diagrams, shapes
- Commercial license required — pricing is per-app/per-device, expensive for indie devs
- Developer portal: developer.myscript.com
- GitHub: [MyScript/iinkJS](https://github.com/MyScript/iinkJS) (web version)

## Scrawl's Strategy

1. **Primary**: Boox `KHwrService` (Microsoft engine) — best quality, zero latency, no cost
2. **Fallback**: Google ML Kit Digital Ink Recognition — free, on-device, 300+ languages, works on any Android device
3. **Detection**: `HWREngineProvider` probes for `KHwrService` at startup, falls back to ML Kit if unavailable
4. **No MyScript dependency** — avoids per-event cloud costs and licensing complexity

This means Scrawl works on:
- Boox devices with newer firmware (Microsoft engine)
- Boox devices with older firmware (ML Kit fallback)
- Hypothetically any Android device (ML Kit only)

Supernote and reMarkable are not Android, so Scrawl can't run on them regardless.

## Quality Ranking (community consensus)

1. **Supernote** — best writing feel (Wacom EMR, ceramic nibs), very accurate HWR, real-time conversion
2. **Boox** — most flexible (full Android, app ecosystem), very good HWR, best export options
3. **reMarkable** — best pen latency, good HWR quality but cloud-only and subscription-locked

For **HWR accuracy specifically**, Boox and Supernote are roughly tied. reMarkable is slightly behind but still good for most use cases.

---

*Last updated: 2026-03-16*
