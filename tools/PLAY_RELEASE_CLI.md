# Cut and upload Scrawl releases from the CLI

Run these commands from the repository root. The cut command builds and tags a signed release locally. The upload command sends an already-signed AAB to Google Play as a **draft** release. The rollout command writes release notes and submits that draft to the selected track. Scrawl's closed-testing track ID is `beta`.

## One-time setup

1. Enable the Android Publisher API in a Google Cloud project and create a service account with app-specific Play Console access to `com.scrawl.app`.
2. Its key is stored outside Git at `~/.config/scrawl/play-publisher.json` (mode `0600`). The signing keystore and passwords are separate at `~/.config/scrawl/scrawl-release.jks` and `~/.config/scrawl/signing.env` (also mode `0600`). Both commands use these paths by default; environment variables can override them.
3. Java 17 and the Android SDK live under `~/.local/share/scrawl-build/jdk17` and `~/.local/share/android-sdk` when present. The scripts select them automatically; otherwise set `JAVA_HOME` and `ANDROID_HOME`.
4. BOOX's Maven server can time out. After one successful build, `tools/prepare-onyx-maven.py` copies the six BOOX SDK AARs and three transitive AARs from the Gradle cache into `~/.local/share/scrawl-build/onyx-maven` and writes their dependency metadata. The release scripts select that local repository automatically when present. Run `python3 tools/prepare-onyx-maven.py` if the local repository needs rebuilding; it requires the exact cached AAR versions. No BOOX binaries or signing material are committed to Git.

## Cut a new release

From a clean worktree based on the latest release, prepare a plain-text notes file outside the worktree and run:

```bash
./tools/cut-release.sh 0.1.18 /path/to/release-notes.txt
```

The command bumps `versionCode`, runs unit tests, builds signed AAB and APK files, checks the AAB signature, copies artifacts and notes to Downloads, commits, and tags locally. It does not push or upload. A failed build leaves the changed source files for inspection. The notes file must be at most 500 characters.

## Upload

Update `app/src/main/play/release-notes/en-US/default.txt` for the version being uploaded, then run from a checkout based on that release:

```bash
./tools/upload-play-draft.sh "$HOME/Downloads/scrawl-v0.1.17.aab" beta
```

The script isolates the supplied AAB in a temporary directory, so Gradle Play Publisher cannot upload another artifact from the same Downloads folder. The Gradle configuration sets `releaseStatus` to `DRAFT`. The script does not push Git commits or tags.

## Start the beta rollout

After reviewing the release notes in `app/src/main/play/release-notes/en-US/default.txt`, run:

```bash
python3 tools/start-play-rollout.py beta 23 --dry-run
python3 tools/start-play-rollout.py beta 23
```

The command requires exactly one draft with that version code on the named track. It writes the English release notes, sets the release to `completed`, validates and commits the Play edit, then reads the track again to verify status and notes. Google Play review or managed publishing may still delay availability after the edit is committed. The command leaves production untouched when run against `beta`.

## Release directly to production

For a new version, upload its AAB as a production draft and then start the full production rollout:

```bash
./tools/upload-play-draft.sh "$HOME/Downloads/scrawl-v0.1.18.aab" production
python3 tools/start-play-rollout.py production 24 --dry-run
python3 tools/start-play-rollout.py production 24
```

To promote a version already completed on beta, reuse its uploaded artifact and release notes:

```bash
python3 tools/start-play-rollout.py production 23 --from-track beta --dry-run
python3 tools/start-play-rollout.py production 23 --from-track beta
```

The rollout command replaces the older release on the target track only when its version code is lower and there is no other draft, active rollout, or halted rollout. `completed` requests 100% of eligible production users; Google Play review or managed publishing can still delay availability.

If the source beta release is already in review, Play normally rejects a separate production commit. Add `--resubmit-in-review` to the promotion command to cancel that review and submit code 23 on beta and production together. The command first checks that this exact beta release is the only release in review; otherwise it stops without committing.

For future releases, start from the latest release commit (including this tooling), cut with `cut-release.sh`, upload the resulting AAB, then run `start-play-rollout.py` with its new version code. Never generate a replacement upload keystore for an existing Play app.
