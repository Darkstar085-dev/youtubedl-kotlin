#!/usr/bin/env python3
import argparse
import csv
import re
import subprocess
import sys
import zipfile
from pathlib import Path

VERSION_FIELDS = ["scope", "component", "version"]
SIZE_FIELDS = ["scope", "component", "path", "size_bytes"]
VERSION_START = "<!-- RUNTIME-VERSIONS:START -->"
VERSION_END = "<!-- RUNTIME-VERSIONS:END -->"
SIZE_START = "<!-- RUNTIME-SIZES:START -->"
SIZE_END = "<!-- RUNTIME-SIZES:END -->"
SIZE_FILES = [
    ("all", "yt-dlp", "library/src/main/res/raw/ytdlp"),
    ("arm64-v8a", "Python shared library", "library/src/main/jniLibs/arm64-v8a/libpython.so"),
    ("arm64-v8a", "Python runtime archive", "library/src/main/jniLibs/arm64-v8a/libpython.zip.so"),
    ("arm64-v8a", "QuickJS library", "library/src/main/jniLibs/arm64-v8a/libqjs.so"),
    ("arm64-v8a", "FFmpeg", "ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.so"),
    ("arm64-v8a", "FFmpeg archive", "ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.zip.so"),
    ("arm64-v8a", "ffprobe", "ffmpeg/src/main/jniLibs/arm64-v8a/libffprobe.so"),
    ("arm64-v8a", "aria2", "aria2c/src/main/jniLibs/arm64-v8a/libaria2c.so"),
    ("arm64-v8a", "aria2 archive", "aria2c/src/main/jniLibs/arm64-v8a/libaria2c.zip.so"),
]


def read_tsv(path, expected_fields):
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        if reader.fieldnames is None or not set(expected_fields).issubset(reader.fieldnames):
            raise ValueError(f"{path} must contain columns: {', '.join(expected_fields)}")
        return list(reader)


def read_artifact_manifest(path):
    rows = []
    seen = set()
    with path.open("r", encoding="utf-8", newline="") as handle:
        for line_number, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            fields = line.rstrip("\r\n").split("\t")
            if line_number == 1 and fields == VERSION_FIELDS:
                continue
            if len(fields) != 3:
                raise ValueError(f"{path}:{line_number}: expected three tab-separated columns")
            scope, component, version = (field.strip() for field in fields)
            if not scope or not component or not version or scope != "arm64-v8a":
                raise ValueError(f"{path}:{line_number}: invalid ARM64 runtime entry")
            key = (scope, component)
            if key in seen:
                raise ValueError(f"{path}:{line_number}: duplicate entry {scope}/{component}")
            seen.add(key)
            rows.append({"scope": scope, "component": component, "version": version})
    if not rows:
        raise ValueError(f"{path}: no ARM64 runtime versions were provided")
    return rows


def get_ytdlp_version(path):
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"yt-dlp executable is missing or empty: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.endswith("yt_dlp/version.py"):
                    source = archive.read(name).decode("utf-8", errors="replace")
                    match = re.search(r"(?m)^\s*__version__\s*=\s*['\"]([^'\"]+)['\"]", source)
                    if match:
                        return match.group(1)
    except (zipfile.BadZipFile, OSError):
        pass
    result = subprocess.run(
        [sys.executable, str(path), "--version"],
        capture_output=True, text=True, timeout=30, check=False,
    )
    lines = result.stdout.strip().splitlines()
    if result.returncode == 0 and lines and re.fullmatch(r"\d{4}\.\d{2}\.\d{2}(?:\.\d+)?", lines[0]):
        return lines[0]
    raise ValueError("Could not determine yt-dlp version from the downloaded executable")


def replace_marked_section(readme_path, start_marker, end_marker, table_lines):
    text = readme_path.read_text(encoding="utf-8")
    if text.count(start_marker) != 1 or text.count(end_marker) != 1:
        raise ValueError(f"{readme_path} must contain exactly one pair of {start_marker} / {end_marker} markers")
    before, rest = text.split(start_marker, 1)
    _, after = rest.split(end_marker, 1)
    readme_path.write_text(
        before + start_marker + "\n\n" + "\n".join(table_lines) + "\n\n" + end_marker + after,
        encoding="utf-8",
    )


def format_size(size):
    size = int(size)
    return f"{size / 1_000_000:.2f} MB" if size >= 1_000_000 else f"{size:,} bytes"


def main():
    parser = argparse.ArgumentParser(description="Update runtime version/size manifests and README tables.")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--artifacts-dir", required=True, type=Path)
    parser.add_argument("--ytdlp", required=True, type=Path)
    parser.add_argument("--description-file", required=True, type=Path)
    parser.add_argument("--sizes-manifest", type=Path, default=Path("runtime-sizes.tsv"))
    parser.add_argument("--readme", type=Path, default=Path("README.md"))
    args = parser.parse_args()

    old_version_rows = read_tsv(args.manifest, VERSION_FIELDS)
    versions = {}
    for row in old_version_rows:
        scope, component, version = (row.get(field, "").strip() for field in VERSION_FIELDS)
        if not scope or not component or not version:
            raise ValueError(f"{args.manifest}: empty scope/component/version entry")
        key = (scope, component)
        if key in versions:
            raise ValueError(f"{args.manifest}: duplicate entry {scope}/{component}")
        versions[key] = version
    before_versions = dict(versions)

    for row in read_artifact_manifest(args.artifacts_dir / "arm64-v8a.tsv"):
        versions[(row["scope"], row["component"])] = row["version"]
    versions[("all", "yt-dlp")] = get_ytdlp_version(args.ytdlp)

    scope_order = {"all": 0, "arm64-v8a": 1}
    version_rows = [
        {"scope": scope, "component": component, "version": version}
        for (scope, component), version in versions.items()
    ]
    version_rows.sort(key=lambda row: (scope_order.get(row["scope"], 99), row["component"], row["scope"]))
    with args.manifest.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=VERSION_FIELDS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(version_rows)

    version_table = ["| Scope | Component | Version |", "| --- | --- | --- |"]
    version_table.extend(f"| {r['scope']} | {r['component']} | {r['version']} |" for r in version_rows)

    size_rows_by_key = {}
    if args.sizes_manifest.exists():
        for row in read_tsv(args.sizes_manifest, SIZE_FIELDS):
            scope, component = row["scope"].strip(), row["component"].strip()
            if scope in ("all", "arm64-v8a") and scope and component:
                size_rows_by_key[(scope, component)] = [scope, component, row["path"].strip(), row["size_bytes"].strip()]
    for scope, component, relative_path in SIZE_FILES:
        file_path = Path(relative_path)
        if not file_path.is_file():
            raise ValueError(f"Required runtime artifact is missing: {file_path}")
        size_rows_by_key[(scope, component)] = [scope, component, relative_path, str(file_path.stat().st_size)]

    size_rows = sorted(size_rows_by_key.values(), key=lambda row: (scope_order.get(row[0], 99), row[1]))
    with args.sizes_manifest.open("w", encoding="utf-8", newline="") as handle:
        handle.write("\t".join(SIZE_FIELDS) + "\n")
        for row in size_rows:
            handle.write("\t".join(row) + "\n")

    size_table = ["| ABI | Component | File size |", "| --- | --- | ---: |"]
    size_table.extend(f"| {scope} | {component} | {format_size(size)} |" for scope, component, _, size in size_rows)
    replace_marked_section(args.readme, VERSION_START, VERSION_END, version_table)
    replace_marked_section(args.readme, SIZE_START, SIZE_END, size_table)

    changes = []
    for row in version_rows:
        key = (row["scope"], row["component"])
        old, new = before_versions.get(key), row["version"]
        if old != new:
            changes.append(f"- {key[0]} {key[1]}: {old + ' -> ' if old is not None else 'added '}{new}")
    args.description_file.parent.mkdir(parents=True, exist_ok=True)
    description = "Updated bundled runtime versions:\n" + "\n".join(changes) + "\n" if changes else ""
    args.description_file.write_text(description, encoding="utf-8")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, csv.Error, subprocess.SubprocessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
