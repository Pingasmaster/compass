#!/usr/bin/env python3
"""Fail CI when gradle/libs.versions.toml or the wrapper is not on the newest Maven version.

Resolves every catalog library/plugin against Google Maven, Maven Central, and
the Gradle Plugin Portal and picks the highest published version, including
alpha/beta/rc. Version keys whose catalog line contains `# hold` are skipped
(native/from-source pins).

Usage:
  scripts/check_latest_deps.py           # exit 1 if anything is behind
  scripts/check_latest_deps.py --apply   # rewrite catalog + wrapper, then check
"""
from __future__ import annotations

import argparse
import re
import sys
import tomllib
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "gradle" / "libs.versions.toml"
WRAPPER = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"
PLUGIN_PORTAL = "https://plugins.gradle.org/m2"
GRADLE_ALL = "https://services.gradle.org/versions/all"
UA = "EfreiLatestDeps/1.0"

PRE_RANK = {
    "dev": 0,
    "snapshot": 0,
    "alpha": 1,
    "a": 1,
    "beta": 2,
    "b": 2,
    "m": 2,
    "milestone": 2,
    "preview": 2,
    "rc": 3,
    "cr": 3,
    "sp": 4,
}


def fetch(url: str, timeout: float = 20.0) -> bytes | None:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    last_error: Exception | None = None
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                if resp.status != 200:
                    last_error = OSError(f"http {resp.status}")
                else:
                    return resp.read()
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = error
        if attempt < 3:
            import time

            time.sleep(attempt * 2)
    return None


def parse_metadata(xml: bytes) -> list[str]:
    root = ET.fromstring(xml)
    versions = [el.text.strip() for el in root.findall("./versioning/versions/version") if el.text]
    if versions:
        return versions
    latest = root.findtext("./versioning/latest") or root.findtext("./versioning/release")
    return [latest.strip()] if latest else []


def version_key(raw: str) -> tuple:
    text = raw.strip().lower().replace("_", "-")
    tokens = re.findall(r"[0-9]+|[a-z]+", text)
    nums: list[int] = []
    pre = (5, 0)
    saw_pre = False
    for i, tok in enumerate(tokens):
        if tok.isdigit():
            if saw_pre:
                pre = (pre[0], int(tok))
                saw_pre = False
            else:
                nums.append(int(tok))
            continue
        if tok in PRE_RANK:
            pre = (PRE_RANK[tok], 0)
            saw_pre = True
    while len(nums) < 4:
        nums.append(0)
    return (tuple(nums[:4]), pre)


def newest(versions: list[str]) -> str | None:
    usable = [v for v in versions if v and "snapshot" not in v.lower()]
    if not usable:
        return None
    return max(usable, key=version_key)


def maven_path(group: str, artifact: str) -> str:
    return f"{group.replace('.', '/')}/{artifact}/maven-metadata.xml"


def plugin_artifact(plugin_id: str) -> tuple[str, str]:
    return plugin_id, f"{plugin_id}.gradle.plugin"


def lookup_latest(group: str, artifact: str) -> str | None:
    path = maven_path(group, artifact)
    for base in (GOOGLE, CENTRAL, PLUGIN_PORTAL):
        body = fetch(f"{base}/{path}")
        if not body:
            continue
        found = newest(parse_metadata(body))
        if found:
            return found
    return None


def hold_keys(text: str) -> set[str]:
    held: set[str] = set()
    in_versions = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            in_versions = stripped == "[versions]"
            continue
        if not in_versions:
            continue
        if "# hold" in line.lower() or "#hold" in line.lower():
            m = re.match(r"\s*([A-Za-z0-9_.-]+)\s*=", line)
            if m:
                held.add(m.group(1))
    return held


def parse_catalog(text: str) -> tuple[dict[str, str], dict[str, list[tuple[str, str]]]]:
    data = tomllib.loads(text)
    versions = {k: str(v) for k, v in data.get("versions", {}).items() if isinstance(v, (str, int, float))}
    refs: dict[str, list[tuple[str, str]]] = {k: [] for k in versions}
    for lib in data.get("libraries", {}).values():
        if not isinstance(lib, dict):
            continue
        ref = lib.get("version", {})
        key = ref.get("ref") if isinstance(ref, dict) else None
        if not key:
            continue
        group = lib.get("group")
        name = lib.get("name")
        module = lib.get("module")
        if module and ":" in str(module):
            group, name = str(module).split(":", 1)
        if group and name and key in refs:
            refs[key].append((str(group), str(name)))
    for plugin in data.get("plugins", {}).values():
        if not isinstance(plugin, dict):
            continue
        ref = plugin.get("version", {})
        key = ref.get("ref") if isinstance(ref, dict) else None
        plugin_id = plugin.get("id")
        if key and plugin_id and key in refs:
            refs[key].append(plugin_artifact(str(plugin_id)))
    return versions, refs


def gradle_wrapper_version(text: str) -> str | None:
    m = re.search(r"gradle-([0-9][0-9A-Za-z.+-]+)-bin\.zip", text)
    return m.group(1) if m else None


def latest_gradle() -> str | None:
    import json

    body = fetch(GRADLE_ALL)
    if not body:
        return None
    try:
        rows = json.loads(body)
    except json.JSONDecodeError:
        return None
    names = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        ver = row.get("version")
        if isinstance(ver, str) and not row.get("snapshot") and not row.get("nightly"):
            names.append(ver)
    return newest(names)


def gradle_sha256(version: str) -> str | None:
    body = fetch(f"https://services.gradle.org/distributions/gradle-{version}-bin.zip.sha256")
    if not body:
        return None
    return body.decode().strip().split()[0]


def apply_version(text: str, key: str, new: str) -> str:
    pattern = rf'^(\s*{re.escape(key)}\s*=\s*")([^"]*)(".*)$'
    return re.sub(pattern, rf"\g<1>{new}\g<3>", text, count=1, flags=re.M)


def apply_wrapper(text: str, new: str, sha: str | None) -> str:
    text = re.sub(
        r"gradle-[0-9][0-9A-Za-z.+-]+-bin\.zip",
        f"gradle-{new}-bin.zip",
        text,
        count=1,
    )
    if sha:
        text = re.sub(r"(distributionSha256Sum=)[0-9a-fA-F]+", rf"\g<1>{sha}", text, count=1)
    return text


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not CATALOG.is_file():
        print(f"ERROR: missing {CATALOG}", file=sys.stderr)
        return 1
    raw = CATALOG.read_text(encoding="utf-8")
    held = hold_keys(raw)
    versions, refs = parse_catalog(raw)
    jobs: list[tuple[str, str, str, str]] = []
    for key, current in versions.items():
        if key in held:
            print(f"HOLD  {key}={current}")
            continue
        coords = refs.get(key) or []
        if not coords:
            print(f"SKIP  {key}={current} (not referenced by a library/plugin)")
            continue
        group, artifact = coords[0]
        jobs.append((key, current, group, artifact))

    stale: list[tuple[str, str, str]] = []
    unknown: list[str] = []
    with ThreadPoolExecutor(max_workers=16) as pool:
        futs = {pool.submit(lookup_latest, g, a): (k, cur, g, a) for k, cur, g, a in jobs}
        for fut in as_completed(futs):
            key, current, group, artifact = futs[fut]
            latest = fut.result()
            if latest is None:
                unknown.append(f"{key} {group}:{artifact}={current}")
                continue
            if version_key(latest) > version_key(current):
                stale.append((key, current, latest))
            else:
                print(f"OK    {key}={current}")

    wrapper_raw = WRAPPER.read_text(encoding="utf-8") if WRAPPER.is_file() else ""
    current_gradle = gradle_wrapper_version(wrapper_raw) if wrapper_raw else None
    latest_g = latest_gradle() if current_gradle else None
    gradle_stale = None
    if current_gradle and latest_g and version_key(latest_g) > version_key(current_gradle):
        gradle_stale = (current_gradle, latest_g)
    elif current_gradle:
        print(f"OK    gradle-wrapper={current_gradle}")

    if unknown:
        print("ERROR: could not resolve Maven metadata (fail closed):", file=sys.stderr)
        for item in sorted(unknown):
            print(f"  {item}", file=sys.stderr)
        return 1

    if args.apply and (stale or gradle_stale):
        updated = raw
        for key, _old, new in stale:
            updated = apply_version(updated, key, new)
        if updated != raw:
            CATALOG.write_text(updated, encoding="utf-8")
        if gradle_stale and wrapper_raw:
            _old, new = gradle_stale
            sha = gradle_sha256(new)
            WRAPPER.write_text(apply_wrapper(wrapper_raw, new, sha), encoding="utf-8")
        print("applied catalog/wrapper updates")
        return 0

    if stale or gradle_stale:
        print("ERROR: dependencies are not on the latest published versions:", file=sys.stderr)
        for key, current, latest in sorted(stale):
            print(f"  {key}: {current} -> {latest}", file=sys.stderr)
        if gradle_stale:
            print(f"  gradle-wrapper: {gradle_stale[0]} -> {gradle_stale[1]}", file=sys.stderr)
        print("Run: python3 scripts/check_latest_deps.py --apply", file=sys.stderr)
        return 1

    print("latest-deps: all catalog libraries, plugins, and the Gradle wrapper are current")
    return 0


if __name__ == "__main__":
    sys.exit(main())
