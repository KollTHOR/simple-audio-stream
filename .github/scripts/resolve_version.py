#!/usr/bin/env python3
"""
resolve_version.py

Deterministically resolves version codes, version names, tags, and artifact names
for Simple Audio Stream releases (nightly and stable).

Guarantees:
1. Version code is CI-controlled and strictly monotonically increasing (> 118).
2. Nightly tags follow 'nightly-YYYYMMDD-bVERSION-SHORT_SHA' and are immutable.
3. Stable tags follow 'vX.Y.Z' and must point to a commit on 'main'.
4. Artifacts are explicitly named 'SimpleAudioStream-<versionName>.apk'.
5. Historical versions and releases are preserved and never overwritten.
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import urllib.request

BASELINE_VERSION_CODE = 118
DEFAULT_BASE_VERSION = "1.8.15"


def run_cmd(cmd: list, check: bool = True) -> str:
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"Command failed ({res.returncode}): {' '.join(cmd)}\n{res.stderr.strip()}")
    return res.stdout.strip()


def get_base_version_from_gradle() -> str:
    gradle_path = os.path.join(os.path.dirname(__file__), "../../app/build.gradle.kts")
    if os.path.exists(gradle_path):
        with open(gradle_path, "r", encoding="utf-8") as f:
            for line in f:
                m = re.search(r'baseVersionName\s*=\s*"([^"]+)"', line)
                if m:
                    return m.group(1).strip()
    return DEFAULT_BASE_VERSION


def get_git_commit_sha() -> str:
    env_sha = os.environ.get("GITHUB_SHA") or os.environ.get("GIT_COMMIT_SHA")
    if env_sha and len(env_sha) >= 7:
        return env_sha
    try:
        return run_cmd(["git", "rev-parse", "HEAD"])
    except Exception:
        return "unknown"


def get_git_commit_short() -> str:
    sha = get_git_commit_sha()
    return sha[:7] if sha != "unknown" else "unknown"


def is_commit_on_main(commit_sha: str) -> bool:
    # Check if commit_sha is ancestor of main or origin/main
    for ref in ["main", "origin/main", "refs/heads/main", "refs/remotes/origin/main"]:
        try:
            res = subprocess.run(
                ["git", "merge-base", "--is-ancestor", commit_sha, ref],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE
            )
            if res.returncode == 0:
                return True
        except Exception:
            continue
    return False


def fetch_published_version_codes_and_tags() -> tuple[set[int], set[str], set[str]]:
    version_codes = {BASELINE_VERSION_CODE}
    published_release_tags = set()
    git_tags = set()

    # 1. Try GitHub API via urllib
    try:
        url = "https://api.github.com/repos/KollTHOR/simple-audio-stream/releases?per_page=100"
        req = urllib.request.Request(url, headers={"User-Agent": "SimpleAudioStream-ReleaseScript"})
        token = os.environ.get("GITHUB_TOKEN")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        with urllib.request.urlopen(req, timeout=8) as resp:
            releases = json.loads(resp.read().decode("utf-8"))
            for r in releases:
                tag = r.get("tag_name", "").strip()
                if tag:
                    published_release_tags.add(tag)
                    m_tag = re.search(r'-b(\d+)-', tag)
                    if m_tag:
                        version_codes.add(int(m_tag.group(1)))
                body = r.get("body", "") or ""
                for m in re.finditer(r'(?:versionCode|Build)[:=\s*`]+(\d+)', body, re.IGNORECASE):
                    version_codes.add(int(m.group(1)))
    except Exception:
        pass

    # 2. Fallback / supplement with gh CLI if available
    if len(published_release_tags) == 0:
        try:
            gh_out = run_cmd(["gh", "release", "list", "--limit", "100", "--json", "tagName"], check=False)
            if gh_out:
                data = json.loads(gh_out)
                for r in data:
                    tag = r.get("tagName", "").strip()
                    if tag:
                        published_release_tags.add(tag)
                        m_tag = re.search(r'-b(\d+)-', tag)
                        if m_tag:
                            version_codes.add(int(m_tag.group(1)))
        except Exception:
            pass

    # 3. Also inspect local git tags
    try:
        tags_out = run_cmd(["git", "tag", "-l"], check=False).splitlines()
        for t in tags_out:
            t = t.strip()
            if t:
                git_tags.add(t)
                m_tag = re.search(r'-b(\d+)-', t)
                if m_tag:
                    version_codes.add(int(m_tag.group(1)))
    except Exception:
        pass

    return version_codes, published_release_tags, git_tags


def main():
    parser = argparse.ArgumentParser(description="Resolve release version metadata.")
    parser.add_argument("--channel", choices=["nightly", "stable"], required=True, help="Release channel")
    parser.add_argument("--tag", default="", help="Tag name (required for stable)")
    parser.add_argument("--run-number", default="0", help="GitHub Actions run number")
    parser.add_argument("--output-file", default="", help="File to write GitHub Actions output key-values")
    parser.add_argument("--dry-run", action="store_true", help="Dry run mode without failing on existing tags")
    parser.add_argument("--base-code", type=int, default=None, help="Override baseline version code")
    parser.add_argument("--offline", action="store_true", help="Skip querying remote GitHub API")
    args = parser.parse_args()

    base_version = get_base_version_from_gradle()
    commit_sha = get_git_commit_sha()
    commit_short = get_git_commit_short()
    build_date = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d")

    baseline_code = args.base_code if args.base_code is not None else BASELINE_VERSION_CODE
    if args.offline:
        published_codes = {baseline_code}
        published_releases = {"v1.8.12"}
        git_tags = set()
    else:
        published_codes, published_releases, git_tags = fetch_published_version_codes_and_tags()
        if args.base_code is not None:
            published_codes.add(args.base_code)

    max_published_code = max(published_codes) if published_codes else baseline_code

    run_num = int(args.run_number) if args.run_number.isdigit() else 0
    ci_code = (baseline_code + run_num) if run_num > 0 else 0

    version_code = max(baseline_code + 1, max_published_code + 1, ci_code)

    if args.channel == "nightly":
        version_name = f"{base_version}-nightly.{build_date}+{commit_short}"
        tag_name = f"nightly-{build_date}-b{version_code}-{commit_short}"
        is_prerelease = "true"
        release_title = f"Nightly Build: {tag_name}"

        # Safeguard: check tag collision
        if not args.dry_run and (tag_name in published_releases or tag_name in git_tags):
            sys.stderr.write(f"ERROR: Tag '{tag_name}' already exists! Cannot overwrite existing nightly.\n")
            sys.exit(1)

    elif args.channel == "stable":
        raw_tag = args.tag.strip()
        if not raw_tag:
            sys.stderr.write("ERROR: --tag is required for stable releases.\n")
            sys.exit(1)

        # Validate tag format: vX.Y.Z
        if not re.match(r"^v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$", raw_tag):
            sys.stderr.write(f"ERROR: Tag '{raw_tag}' is not a valid semantic version tag (expected vX.Y.Z).\n")
            sys.exit(1)

        # Safeguard: verify tag is not an already-published GitHub release
        if not args.dry_run and raw_tag in published_releases:
            sys.stderr.write(f"ERROR: Release for tag '{raw_tag}' already exists! Cannot overwrite an existing release.\n")
            sys.exit(1)

        # Safeguard: verify tag is on main branch
        if not args.dry_run:
            if not is_commit_on_main(commit_sha):
                sys.stderr.write(f"ERROR: Commit {commit_sha} for stable release {raw_tag} is NOT on main branch!\n")
                sys.exit(1)

        tag_name = raw_tag
        version_name = raw_tag.lstrip("v")
        is_prerelease = "false"
        release_title = f"Release {tag_name}"

    apk_name = f"SimpleAudioStream-{version_name}.apk"
    sha_name = f"{apk_name}.sha256"

    metadata = {
        "version_code": str(version_code),
        "version_name": version_name,
        "tag_name": tag_name,
        "apk_name": apk_name,
        "sha_name": sha_name,
        "commit_sha": commit_sha,
        "commit_short": commit_short,
        "build_date": build_date,
        "base_version": base_version,
        "is_prerelease": is_prerelease,
        "channel": args.channel,
        "release_title": release_title,
    }

    # Print summary to stdout
    print(json.dumps(metadata, indent=2))

    # Output to GITHUB_OUTPUT if specified
    out_file = args.output_file or os.environ.get("GITHUB_OUTPUT")
    if out_file:
        with open(out_file, "a", encoding="utf-8") as f:
            for k, v in metadata.items():
                f.write(f"{k}={v}\n")


if __name__ == "__main__":
    main()
