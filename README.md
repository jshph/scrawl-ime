# Scrawl

A handwriting keyboard for Onyx Boox e-ink tablets. Write with the stylus in any app's text field and Scrawl converts it to text.

<a href="https://play.google.com/store/apps/details?id=com.scrawl.app">Get it on Google Play</a>. Buying it there supports development and gets you automatic updates.

## Features

- Full-screen handwriting canvas as a system input method
- Recognition through the on-device Boox handwriting service, with ML Kit Digital Ink as a fallback
- Pen and eraser tools, undo/redo
- `[[wikilink]]` and `#tag` annotations for note-taking apps such as Obsidian
- Audio recording and screenshots saved to a folder you choose

## Building

Requires JDK 17 and the Android SDK (compile SDK 36).

```bash
./gradlew installDebug
adb shell am force-stop com.scrawl.app
```

Then enable Scrawl under Settings → Languages & input and focus a text field. Debug builds need no signing setup.

The Onyx SDK is downloaded from Boox's Maven server, which is sometimes slow or unreachable. Set `SCRAWL_ONYX_MAVEN` to a local Maven repository to use a mirror.

Low-latency pen input, e-ink refresh and Boox recognition only work on Boox hardware. On other devices and emulators Scrawl falls back to standard Android input and ML Kit.

## Notes

- The Onyx SDK and ML Kit are proprietary dependencies and are not covered by this project's license.
- The `com.onyx.android.sdk.hwr.service` interface is undocumented by Onyx and may change between firmware versions.
- Design notes are in [`docs/`](docs/).

## License

[GPL-3.0-only](LICENSE). The Scrawl name and icon are not covered by the license: forks distributed to others must use a different name, icon and application ID.
