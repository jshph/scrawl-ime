#!/usr/bin/env python3
"""Set release notes and start a Play draft or promote a tested release."""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


PACKAGE = "com.scrawl.app"
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}/edits"
APPLICATION_BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
DEFAULT_NOTES = Path(__file__).resolve().parents[1] / "app/src/main/play/release-notes/en-US/default.txt"


def access_token(credential: Path) -> str:
    env = dict(os.environ, GOOGLE_APPLICATION_CREDENTIALS=str(credential))
    return subprocess.check_output(
        ["gcloud", "auth", "application-default", "print-access-token",
         "--scopes=https://www.googleapis.com/auth/androidpublisher"],
        env=env, text=True, stderr=subprocess.DEVNULL,
    ).strip()


def request(url: str, token: str, method: str = "GET", body: object = None) -> dict:
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                content = response.read()
                return json.loads(content) if content else {}
        except urllib.error.HTTPError as error:
            try:
                message = json.loads(error.read()).get("error", {}).get("message", "")
            except (ValueError, OSError):
                message = ""
            retryable = error.code in (429, 500, 502, 503, 504) and (method in ("GET", "PUT") or url.endswith(":validate"))
            if retryable and attempt < 2:
                time.sleep(2 ** attempt)
                continue
            raise ValueError(f"HTTP {error.code}: {message[:500]}") from error


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("track", help="Google Play track ID, for example beta")
    parser.add_argument("version_code", help="Exact version code to release")
    parser.add_argument("--notes-file", type=Path, default=DEFAULT_NOTES)
    parser.add_argument("--from-track", help="Promote a completed release from another track")
    parser.add_argument("--resubmit-in-review", action="store_true", help="Resubmit this version's existing review together with the target rollout")
    parser.add_argument("--dry-run", action="store_true", help="Inspect the proposed release without changing it")
    args = parser.parse_args()
    if not args.version_code.isdecimal():
        parser.error("version_code must be decimal")
    if not args.track or any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-" for char in args.track):
        parser.error("track contains invalid characters")
    if args.from_track and args.from_track == args.track:
        parser.error("source and target tracks must differ")
    if args.resubmit_in_review and not args.from_track:
        parser.error("--resubmit-in-review requires --from-track")
    if args.from_track and any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-" for char in args.from_track):
        parser.error("source track contains invalid characters")
    notes = args.notes_file.read_text(encoding="utf-8").strip()
    if not notes or len(notes) > 500:
        parser.error("release notes must contain 1 to 500 characters")
    credential = Path(os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", Path.home() / ".config/scrawl/play-publisher.json"))
    if not credential.is_file():
        parser.error(f"missing Play service-account JSON: {credential}")

    token = access_token(credential)
    edit = request(BASE, token, "POST", {})["id"]
    committed = False
    try:
        track_url = f"{BASE}/{edit}/tracks/{urllib.parse.quote(args.track, safe='')}"
        track = request(track_url, token)
        releases = track.get("releases", [])
        if args.from_track:
            source_url = f"{BASE}/{edit}/tracks/{urllib.parse.quote(args.from_track, safe='')}"
            source = request(source_url, token)
            matches = [r for r in source.get("releases", []) if args.version_code in r.get("versionCodes", [])]
            if len(matches) != 1 or matches[0].get("status") != "completed":
                raise ValueError(f"expected exactly one completed release for code {args.version_code} on {args.from_track}")
            release = dict(matches[0])
        else:
            matches = [r for r in releases if args.version_code in r.get("versionCodes", [])]
            if len(matches) != 1 or matches[0].get("status") != "draft":
                raise ValueError(f"expected exactly one draft for code {args.version_code} on {args.track}")
            release = dict(matches[0])
        if any(r.get("status") not in ("draft", "completed") for r in releases):
            raise ValueError(f"{args.track} has an active or halted rollout")
        other_releases = [r for r in releases if args.from_track or r is not matches[0]]
        if any(r.get("status") == "draft" for r in other_releases):
            raise ValueError(f"{args.track} has another draft release")
        if any(int(code) >= int(args.version_code) for r in other_releases for code in r.get("versionCodes", [])):
            raise ValueError(f"{args.track} already has this or a newer version code")
        other_notes = [n for n in release.get("releaseNotes", []) if n.get("language") != "en-US"]
        release["releaseNotes"] = other_notes + [{"language": "en-US", "text": notes}]
        release["status"] = "completed"
        release.pop("userFraction", None)
        print(f"Release: {PACKAGE} code {args.version_code} on {args.track}" + (f" from {args.from_track}" if args.from_track else ""))
        print(f"Current {args.track} codes: {', '.join(code for r in releases for code in r.get('versionCodes', [])) or 'none'}")
        print(f"Release notes (en-US): {notes}")
        review_behavior = "ERROR_IF_IN_REVIEW"
        if args.resubmit_in_review:
            track_list = request(f"{BASE}/{edit}/tracks", token).get("tracks", [])
            in_review = []
            for listed_track in track_list:
                name = listed_track["track"]
                encoded_name = urllib.parse.quote(name, safe="")
                lifecycle = request(f"{APPLICATION_BASE}/tracks/{encoded_name}/releases", token)
                for reviewed in lifecycle.get("releases", []):
                    if reviewed.get("releaseLifecycleState") == "RELEASE_LIFECYCLE_STATE_IN_REVIEW":
                        codes = [str(a.get("versionCode")) for a in reviewed.get("activeArtifacts", [])]
                        in_review.append((name, codes))
            if in_review != [(args.from_track, [args.version_code])]:
                raise ValueError(f"expected only {args.from_track} code {args.version_code} in review; found {in_review}")
            review_behavior = "CANCEL_IN_REVIEW_AND_SUBMIT"
            print(f"Will resubmit the {args.from_track} code {args.version_code} review with {args.track}")
        if args.dry_run:
            print("Dry run: no Play changes committed")
            return 0
        print("Updating track", flush=True)
        request(track_url, token, "PUT", {"track": args.track, "releases": [release]})
        print("Validating edit", flush=True)
        request(f"{BASE}/{edit}:validate", token, "POST")
        commit_url = f"{BASE}/{edit}:commit?changesInReviewBehavior={review_behavior}"
        print("Committing edit", flush=True)
        request(commit_url, token, "POST")
        committed = True
        print("Play edit committed; rollout submitted")
    finally:
        if not committed:
            try:
                request(f"{BASE}/{edit}", token, "DELETE")
            except (urllib.error.HTTPError, ValueError):
                pass

    verification_edit = request(BASE, token, "POST", {})["id"]
    try:
        current = request(f"{BASE}/{verification_edit}/tracks/{urllib.parse.quote(args.track, safe='')}", token)
        matches = [r for r in current.get("releases", []) if args.version_code in r.get("versionCodes", [])]
        if len(matches) != 1:
            raise ValueError("release not found after commit")
        current_release = matches[0]
        actual_notes = next((n.get("text") for n in current_release.get("releaseNotes", []) if n.get("language") == "en-US"), None)
        print(f"Verified Play status: {current_release.get('status')}")
        if current_release.get("status") != "completed" or actual_notes != notes:
            raise ValueError("Play status or release notes do not match the submitted values")
    finally:
        request(f"{BASE}/{verification_edit}", token, "DELETE")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError, urllib.error.HTTPError, ValueError) as error:
        print(f"Play rollout failed: {error}", file=sys.stderr)
        sys.exit(1)
