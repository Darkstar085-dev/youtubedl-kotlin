#!/usr/bin/env bash
set -Eeuo pipefail

clear >/dev/null 2>&1 || printf '\033[2J\033[H'

FAILURE_REASON=""
FAILURE_COMMAND=""
die() {
  FAILURE_REASON="$*"
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}
handle_error() {
  local code="$1"
  local command="$2"
  FAILURE_COMMAND="$command"
  FAILURE_REASON="Command failed with exit code $code: $command"
  printf '\nERROR: %s\n' "$FAILURE_REASON" >> "${DASHBOARD_LOG:-${BOOTSTRAP_LOG:-/dev/null}}" 2>/dev/null || true
  if [[ -n "${DASHBOARD_STATE:-}" && -f "${DASHBOARD_STATE:-}" ]]; then
    python3 "$ROOT/.github/scripts/runtime-dashboard.py" update "$DASHBOARD_STATE" "" "$FAILURE_REASON" >/dev/null 2>&1 || true
  fi
  return 0
}
log() {
  local message="$*" task=""
  case "$message" in
    "Removing the cached"*) task="clean-cache" ;;
    "Syncing main"*) task="sync-repo" ;;
    "Refreshing Termux package indexes"*) task="refresh-index" ;;
    "Checking repository candidate versions"*|"Checking "[a-z]*) task="check-versions" ;;
    "Reinstalling required Termux runtime packages"*) task="install-packages" ;;
    "Preparing the Termux package build environment"*) task="build-env" ;;
    "Building native Python"*) task="build-native" ;;
    "Assembling Python runtime"*) task="assemble-python" ;;
    "Bundling OpenSSL provider"*) task="openssl-providers" ;;
    "Python runtime versions changed"*|"Python, PyCryptodomeX"*) task="python-runtime" ;;
    "Checking FFmpeg/aria2"*|"Refreshing only media runtime"*|"FFmpeg and aria2 tracked"*) task="media-runtime" ;;
    "Downloading and validating the official yt-dlp"*) task="ytdlp" ;;
    "Validating runtime payload"*) task="validate" ;;
    "Updating version metadata"*|"Updated runtime versions"*) task="metadata" ;;
    "Reviewing generated diff"*|"No tracked changes"*) task="commit" ;;
    "Pushing runtime changes"*|"Native arm64-v8a runtime preparation complete"*) task="push" ;;
  esac
  printf '%s\n' "$message" >> "${BOOTSTRAP_LOG:-/dev/null}"
  if [[ -n "${DASHBOARD_STATE:-}" && -f "${DASHBOARD_STATE:-}" ]]; then
    python3 "$ROOT/.github/scripts/runtime-dashboard.py" update "$DASHBOARD_STATE" "$task" "$message" || true
  fi
}
manifest_version() {
  awk -F '\t' -v scope="$1" -v component="$2" '$1 == scope && $2 == component { print $3; exit }' runtime-versions.tsv 2>/dev/null
}
runtime_version_changed() {
  local component="$1" package="$2" current previous
  current="$(dpkg-query -W -f='${Version}' "$package" 2>/dev/null || true)"
  previous="$(manifest_version arm64-v8a "$component")"
  [[ -z "$current" || -z "$previous" || "$current" != "$previous" ]]
}
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || die "Run inside the cloned repository."
cd "$ROOT"
normalize_github_remote() {
  local remote="$1"
  case "$remote" in
    git@github.com:*) remote="${remote#git@github.com:}" ;;
    ssh://git@github.com/*) remote="${remote#ssh://git@github.com/}" ;;
    https://github.com/*) remote="${remote#https://github.com/}" ;;
    http://github.com/*) remote="${remote#http://github.com/}" ;;
    *) return 1 ;;
  esac
  remote="${remote%.git}"
  remote="${remote%/}"
  remote="${remote,,}"
  [[ "$remote" =~ ^[a-z0-9_.-]+/[a-z0-9_.-]+$ ]] || return 1
  printf '%s\\n' "$remote"
}
validate_origin() {
  local remote_url expected_url expected_repo
  remote_url="$(git remote get-url origin 2>/dev/null)" || die "Git origin remote is not configured."
  ORIGIN_REPO="$(normalize_github_remote "$remote_url")" || die "Git origin must be a valid GitHub owner/repository URL."
  expected_url="${YOUTUBEDL_EXPECTED_ORIGIN:-$(git config --get runtime-builder.expectedOrigin 2>/dev/null || true)}"
  if [[ -n "$expected_url" ]]; then
    expected_repo="$(normalize_github_remote "$expected_url")" || die "Expected origin must be a valid GitHub owner/repository URL."
    [[ "$ORIGIN_REPO" == "$expected_repo" ]] || die "Git origin does not match the configured expected origin."
  fi
}
validate_origin
INITIAL_ORIGIN_REPO="$ORIGIN_REPO"
export RUNTIME_REPOSITORY="$ORIGIN_REPO"
[[ -n "${PREFIX:-}" && -d "${PREFIX:-}" ]] || die "Run inside Termux."
[[ "$(uname -m)" == "aarch64" || "$(uname -m)" == "arm64" ]] || die "Only native aarch64 / Android arm64-v8a is supported."
[[ "$(git branch --show-current)" == "main" ]] || die "Checkout main first."
[[ -z "$(git status --porcelain)" ]] || die "Working tree must be clean."

BOOTSTRAP_LOG="$PREFIX/tmp/youtubedl-bootstrap.log"
: > "$BOOTSTRAP_LOG"
command -v pkg >/dev/null 2>&1 || die "Termux pkg command is missing; repair the Termux package manager first."
command -v apt-get >/dev/null 2>&1 || die "Termux apt-get is missing; repair the Termux package manager first."
SCRIPT_COMMANDS=(curl zip unzip readelf dpkg-deb dpkg-query apt-cache apt-get awk find grep install readlink mktemp basename)
MISSING_PACKAGES=()
for cmd in "${SCRIPT_COMMANDS[@]}"; do
  command -v "$cmd" >/dev/null 2>&1 && continue
  case "$cmd" in
    curl) package="curl" ;;
    zip) package="zip" ;;
    unzip) package="unzip" ;;
    readelf) package="binutils" ;;
    dpkg-deb|dpkg-query) package="dpkg" ;;
    apt-cache|apt-get) package="apt" ;;
    awk) package="gawk" ;;
    find) package="findutils" ;;
    grep) package="grep" ;;
    install|readlink|mktemp|basename) package="coreutils" ;;
    *) die "No Termux package mapping is defined for missing command: $cmd" ;;
  esac
  found=0
  for existing in "${MISSING_PACKAGES[@]}"; do
    [[ "$existing" == "$package" ]] && found=1
  done
  [[ "$found" -eq 1 ]] || MISSING_PACKAGES+=("$package")
done
if [[ "${#MISSING_PACKAGES[@]}" -gt 0 ]]; then
  printf 'Installing missing script prerequisites silently: %s\n' "${MISSING_PACKAGES[*]}" >>"$BOOTSTRAP_LOG"
  if ! pkg install -y "${MISSING_PACKAGES[@]}" >>"$BOOTSTRAP_LOG" 2>&1; then
    tail -n 20 "$BOOTSTRAP_LOG" >&2 || true
    die "Unable to install script prerequisites. Runtime packages were not included in this prerequisite install."
  fi
fi
for cmd in "${SCRIPT_COMMANDS[@]}"; do
  command -v "$cmd" >/dev/null 2>&1 || die "Required script command is still missing after prerequisite installation: $cmd"
done

PKGDIR="$HOME/.cache/youtubedl-termux-packages"
BUILD_MARKER_DIR="$HOME/.termux-build/.built-packages"
[[ "$PKGDIR" == "$HOME/.cache/youtubedl-termux-packages" ]] || die "Unexpected ARM64 cache path; refusing to clean."
[[ "$BUILD_MARKER_DIR" == "$HOME/.termux-build/.built-packages" ]] || die "Unexpected Termux build-marker path; refusing to clean."
log "Removing the cached Termux package-build checkout and forcing native package rebuilds"
rm -rf -- "$PKGDIR"
mkdir -p "$BUILD_MARKER_DIR"
for marker in python python-pycryptodomex quickjs-ng; do
  rm -f -- "$BUILD_MARKER_DIR/$marker"
done
log "Build cache cleared; Python, PyCryptodomeX and QuickJS will be rebuilt on every run."
log "Syncing main"
git fetch origin main >>"$BOOTSTRAP_LOG" 2>&1
git pull --ff-only origin main >>"$BOOTSTRAP_LOG" 2>&1
log "Refreshing Termux package indexes and checking available runtime versions"
if apt-get update >>"$BOOTSTRAP_LOG" 2>&1; then
  log "Termux package indexes refreshed."
else
  log "WARNING: apt-get update returned a non-zero status; continuing with cached package indexes."
fi
log "Checking repository candidate versions"
for component in python python-pycryptodomex quickjs-ng openssl libandroid-support zlib ffmpeg aria2; do
  log "Checking $component"
  candidate="$(apt-cache policy "$component" 2>/dev/null | awk '/Candidate:/ { print $2; exit }' || true)"
  previous="$(manifest_version arm64-v8a "$component")"
  installed="$(dpkg-query -W -f='${Version}' "$component" 2>/dev/null || true)"
  [[ -n "$candidate" && "$candidate" != "(none)" ]] || die "No repository candidate version found for $component."
  if [[ "$candidate" != "$previous" ]]; then
    log "Runtime update available: $component (manifest: ${previous:-missing}, installed: ${installed:-missing}, candidate: $candidate)"
  else
    log "Runtime version matches manifest: $component ($candidate)"
  fi
done
log "Reinstalling required Termux runtime packages without interactive prompts"
if ! apt-get install --reinstall -y python python-pycryptodomex quickjs-ng openssl libandroid-support zlib ffmpeg aria2 >>"$BOOTSTRAP_LOG" 2>&1; then
  tail -n 30 "$BOOTSTRAP_LOG" >&2 || true
  die "Termux runtime package reinstall failed."
fi
command -v python3 >/dev/null 2>&1 || die "Python runtime package was installed but python3 is still unavailable."
if ! python3 -m pip --version >>"$BOOTSTRAP_LOG" 2>&1; then
  log "Installing pip support required by the build script"
  if ! pkg install -y python-pip >>"$BOOTSTRAP_LOG" 2>&1; then
    tail -n 30 "$BOOTSTRAP_LOG" >&2 || true
    die "Python pip support is required to install Mutagen."
  fi
  python3 -m pip --version >/dev/null 2>&1 || die "Python pip support is still unavailable after installation."
fi
DASHBOARD_STATE="$PREFIX/tmp/youtubedl-runtime-state.json"
DASHBOARD_LOG="$PREFIX/tmp/youtubedl-runtime-last.log"
: > "$DASHBOARD_LOG"
python3 .github/scripts/runtime-dashboard.py init "$DASHBOARD_STATE"
python3 .github/scripts/runtime-dashboard.py watch "$DASHBOARD_STATE" "$DASHBOARD_LOG" >/dev/null 2>&1 &
DASHBOARD_PID=$!
if [[ -w /dev/tty ]]; then
  exec 3>/dev/tty
else
  exec 3>&1
fi
exec >>"$DASHBOARD_LOG" 2>&1
cleanup() {
  local result=$?
  trap - EXIT ERR
  if [[ -n "${WORK:-}" && -d "${WORK:-}" ]]; then rm -rf -- "$WORK"; fi
  if [[ "$result" -ne 0 && -z "$FAILURE_REASON" ]]; then
    FAILURE_REASON="Runtime builder exited with code $result."
  fi
  python3 .github/scripts/runtime-dashboard.py finish "$DASHBOARD_STATE" "$result" || true
  if [[ -n "${DASHBOARD_PID:-}" ]]; then wait "$DASHBOARD_PID" || true; fi
  rm -f -- "$DASHBOARD_STATE" "$DASHBOARD_STATE.tmp"
  if [[ "$result" -ne 0 ]]; then
    {
      printf '\n\033[91m✗ BUILD FAILED\033[0m\n'
      printf 'Reason: %s\n' "$FAILURE_REASON"
      if [[ -n "$FAILURE_COMMAND" ]]; then printf 'Command: %s\n' "$FAILURE_COMMAND"; fi
      printf 'Full log: %s\n' "$DASHBOARD_LOG"
      printf '\nLast command output:\n'
      tail -n 18 "$DASHBOARD_LOG" 2>/dev/null || true
      printf '\n'
    } >&3
  fi
  exec 3>&-
}
trap 'handle_error "$?" "$BASH_COMMAND"' ERR
trap cleanup EXIT
[[ -e "$PREFIX/lib/libandroid-support.so" || -e "$PREFIX/lib/libandroid-support.so.29" ]] || die "libandroid-support package installed but its shared library was not found under $PREFIX/lib."
for exe in ffmpeg ffprobe aria2c; do
  [[ -x "$PREFIX/bin/$exe" ]] || die "Required Termux runtime executable is missing: $PREFIX/bin/$exe"
done
WORK="$(mktemp -d "$PREFIX/tmp/youtubedl.XXXXXX")"
: # WORK is cleaned by the dashboard-aware EXIT handler
if [[ -d "$PKGDIR/.git" ]]; then
  git -C "$PKGDIR" fetch origin
  git -C "$PKGDIR" pull --ff-only
else
  git clone --depth 1 https://github.com/termux/termux-packages.git "$PKGDIR"
fi
if [[ ! -f "$PKGDIR/.termux-build-env-ready" ]]; then
  log "Preparing the Termux package build environment"
  (cd "$PKGDIR" && ./scripts/setup-termux.sh)
  touch "$PKGDIR/.termux-build-env-ready"
fi
log "Building native Python, PyCryptodomeX and QuickJS (reuse installed/build cache where possible)"
mkdir -p "$WORK/debs"
(cd "$PKGDIR" && ./build-package.sh -I -o "$WORK/debs" python python-pycryptodomex quickjs-ng)
if compgen -G "$WORK/debs/*.deb" >/dev/null; then
  dpkg -i "$WORK/debs"/*.deb
else
  log "No new package archives produced; continuing with installed Termux packages."
fi
log "Assembling Python runtime archive and ELF dependencies"
python3 .github/scripts/assemble-python-runtime.py "$PREFIX" "$WORK/stage"
log "Bundling OpenSSL provider modules for standalone runtime"
python3 .github/scripts/bundle-openssl-providers.py "$PREFIX" "$WORK/stage"
PYLIB="$(find "$WORK/stage/usr/lib" -maxdepth 1 -type d -name 'python3.*' -print -quit)"
SITE="$(find "$PYLIB" -maxdepth 4 -type d -name site-packages -print -quit)"
[[ -n "$SITE" ]] || die "Python site-packages not found."
"$PREFIX/bin/python3" -m pip install --disable-pip-version-check --no-compile --no-deps --target "$SITE" mutagen
MUTAGEN_VERSION="$(PYTHONPATH="$SITE" "$PREFIX/bin/python3" -c 'import mutagen; print(mutagen.version_string)')"
PYTHON_RUNTIME_CHANGED=0
for component in python python-pycryptodomex quickjs-ng openssl libandroid-support zlib; do
  if runtime_version_changed "$component" "$component"; then
    PYTHON_RUNTIME_CHANGED=1
  fi
done
PREVIOUS_MUTAGEN_VERSION="$(manifest_version arm64-v8a mutagen)"
[[ "$MUTAGEN_VERSION" == "$PREVIOUS_MUTAGEN_VERSION" ]] || PYTHON_RUNTIME_CHANGED=1
for artifact in library/src/main/jniLibs/arm64-v8a/libpython.so library/src/main/jniLibs/arm64-v8a/libpython.zip.so library/src/main/jniLibs/arm64-v8a/libqjs.so; do
  [[ -f "$artifact" ]] || PYTHON_RUNTIME_CHANGED=1
done
if [[ "$PYTHON_RUNTIME_CHANGED" -eq 1 ]]; then
  log "Python runtime versions changed or artifacts are missing; refreshing Python/QuickJS runtime"
  mkdir -p library/src/main/jniLibs/arm64-v8a
  install -m 0755 "$(readlink -f "$PREFIX/bin/python3")" library/src/main/jniLibs/arm64-v8a/libpython.so
  install -m 0755 "$(readlink -f "$PREFIX/bin/qjs")" library/src/main/jniLibs/arm64-v8a/libqjs.so
  (cd "$WORK/stage" && zip -q -y -r "$WORK/libpython.zip.so" usr/lib usr/etc)
  install -m 0644 "$WORK/libpython.zip.so" library/src/main/jniLibs/arm64-v8a/libpython.zip.so
else
  log "Python, PyCryptodomeX, QuickJS and tracked runtime libraries are unchanged; keeping existing runtime artifacts"
fi

log "Checking FFmpeg/aria2 runtime versions"
FFMPEG_RUNTIME_CHANGED=0
ARIA2_RUNTIME_CHANGED=0
for component in ffmpeg openssl zlib; do
  if runtime_version_changed "$component" "$component"; then
    FFMPEG_RUNTIME_CHANGED=1
  fi
done
for component in aria2 openssl zlib; do
  if runtime_version_changed "$component" "$component"; then
    ARIA2_RUNTIME_CHANGED=1
  fi
done
for artifact in \
  ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.so \
  ffmpeg/src/main/jniLibs/arm64-v8a/libffprobe.so \
  ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.zip.so; do
  [[ -f "$artifact" ]] || FFMPEG_RUNTIME_CHANGED=1
done
for artifact in \
  aria2c/src/main/jniLibs/arm64-v8a/libaria2c.so \
  aria2c/src/main/jniLibs/arm64-v8a/libaria2c.zip.so; do
  [[ -f "$artifact" ]] || ARIA2_RUNTIME_CHANGED=1
done
if [[ "$FFMPEG_RUNTIME_CHANGED" -eq 1 || "$ARIA2_RUNTIME_CHANGED" -eq 1 ]]; then
  log "Refreshing only media runtime components whose tracked versions changed"
  if [[ "$FFMPEG_RUNTIME_CHANGED" -eq 1 ]]; then
    mkdir -p ffmpeg/src/main/jniLibs/arm64-v8a
    install -m 0755 "$(readlink -f "$PREFIX/bin/ffmpeg")" ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.so
    install -m 0755 "$(readlink -f "$PREFIX/bin/ffprobe")" ffmpeg/src/main/jniLibs/arm64-v8a/libffprobe.so
  fi
  if [[ "$ARIA2_RUNTIME_CHANGED" -eq 1 ]]; then
    mkdir -p aria2c/src/main/jniLibs/arm64-v8a
    install -m 0755 "$(readlink -f "$PREFIX/bin/aria2c")" aria2c/src/main/jniLibs/arm64-v8a/libaria2c.so
  fi
python3 .github/scripts/stage-media-dependencies.py "$PREFIX" "$WORK/ffmpeg-stage" "$WORK/aria2-stage" "$FFMPEG_RUNTIME_CHANGED" "$ARIA2_RUNTIME_CHANGED"
if [[ "$FFMPEG_RUNTIME_CHANGED" -eq 1 ]]; then
  (cd "$WORK/ffmpeg-stage" && zip -q -r "$WORK/libffmpeg.zip.so" usr/lib)
  unzip -tq "$WORK/libffmpeg.zip.so" >/dev/null || die "FFmpeg runtime archive failed ZIP validation."
  install -m 0644 "$WORK/libffmpeg.zip.so" ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.zip.so
fi
if [[ "$ARIA2_RUNTIME_CHANGED" -eq 1 ]]; then
  (cd "$WORK/aria2-stage" && zip -q -r "$WORK/libaria2c.zip.so" usr/lib)
  unzip -tq "$WORK/libaria2c.zip.so" >/dev/null || die "aria2 runtime archive failed ZIP validation."
  install -m 0644 "$WORK/libaria2c.zip.so" aria2c/src/main/jniLibs/arm64-v8a/libaria2c.zip.so
fi
else
  log "FFmpeg and aria2 tracked versions are unchanged; keeping existing media runtime artifacts"
fi
log "Downloading and validating the official yt-dlp asset"
curl --fail --silent --show-error --location --retry 3 https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest -o "$WORK/release.json"
python3 .github/scripts/validate-ytdlp.py "$WORK/release.json" "$WORK/ytdlp"
mkdir -p library/src/main/res/raw
install -m 0644 "$WORK/ytdlp" library/src/main/res/raw/ytdlp
log "Validating runtime payload (Android Gradle build intentionally skipped)"
ARCHIVE_LIST="$WORK/runtime-archive-files.txt"
unzip -Z1 library/src/main/jniLibs/arm64-v8a/libpython.zip.so > "$ARCHIVE_LIST" || die "Unable to list runtime archive contents."
grep -Fxq 'usr/lib/libandroid-support.so' "$ARCHIVE_LIST" || die "Runtime archive lacks libandroid-support.so."
grep -Eq '^usr/lib/libz\.so' "$ARCHIVE_LIST" || die "Runtime archive lacks zlib."
if grep -Eq '^usr/lib/lib(crypto|ssl)\.so$' "$ARCHIVE_LIST"; then
  die "Python runtime archive contains an unversioned OpenSSL alias that may shadow Android system libraries."
fi
grep -Fxq 'usr/lib/ossl-modules/legacy.so' "$ARCHIVE_LIST" || die "Python runtime archive lacks OpenSSL legacy provider."
grep -Fxq 'usr/etc/tls/openssl.cnf' "$ARCHIVE_LIST" || die "Python runtime archive lacks OpenSSL configuration."
for archive in \
  ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.zip.so \
  aria2c/src/main/jniLibs/arm64-v8a/libaria2c.zip.so; do
  archive_listing="$WORK/$(basename "$archive").list"
  unzip -Z1 "$archive" > "$archive_listing" || die "Unable to list runtime archive: $archive"
  grep -Eq '^usr/lib/[^/]+\.so(\..*)?$' "$archive_listing" || die "Runtime archive contains no shared libraries: $archive"
  if grep -Eq '^usr/lib/lib(crypto|ssl)\.so$' "$archive_listing"; then
    die "Runtime archive contains an unversioned OpenSSL alias: $archive"
  fi
done
FFMPEG_LIST="$WORK/ffmpeg-archive-files.txt"
unzip -Z1 ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.zip.so > "$FFMPEG_LIST" || die "Unable to list FFmpeg archive."
for soname in libavcodec libavformat libavutil; do
  grep -Eq "^usr/lib/${soname}\.so(\.|$)" "$FFMPEG_LIST" || die "FFmpeg archive lacks ${soname}."
done
log "Updating version metadata"
RUNTIME_METADATA_DIR="$WORK/runtime-versions"
mkdir -p "$RUNTIME_METADATA_DIR"
{
  printf 'arm64-v8a\tpython\t%s\n' "$(dpkg-query -W -f='${Version}' python)"
  printf 'arm64-v8a\tpython-pycryptodomex\t%s\n' "$(dpkg-query -W -f='${Version}' python-pycryptodomex)"
  printf 'arm64-v8a\tquickjs-ng\t%s\n' "$(dpkg-query -W -f='${Version}' quickjs-ng)"
  printf 'arm64-v8a\tffmpeg\t%s\n' "$(dpkg-query -W -f='${Version}' ffmpeg)"
  printf 'arm64-v8a\taria2\t%s\n' "$(dpkg-query -W -f='${Version}' aria2)"
  printf 'arm64-v8a\topenssl\t%s\n' "$(dpkg-query -W -f='${Version}' openssl)"
  printf 'arm64-v8a\tlibandroid-support\t%s\n' "$(dpkg-query -W -f='${Version}' libandroid-support)"
  printf 'arm64-v8a\tzlib\t%s\n' "$(dpkg-query -W -f='${Version}' zlib)"
  printf 'arm64-v8a\tmutagen\t%s\n' "$MUTAGEN_VERSION"
} > "$RUNTIME_METADATA_DIR/arm64-v8a.tsv"
python3 .github/scripts/update-runtime-versions.py --manifest runtime-versions.tsv --artifacts-dir "$RUNTIME_METADATA_DIR" --ytdlp library/src/main/res/raw/ytdlp --description-file "$WORK/version-description.txt"

log "Updated runtime versions, binary sizes, and README tables"
log "Reviewing generated diff and committing only changed outputs"
git add README.md runtime-versions.tsv runtime-sizes.tsv library/src/main/res/raw/ytdlp library/src/main/jniLibs/arm64-v8a/libpython.so library/src/main/jniLibs/arm64-v8a/libpython.zip.so library/src/main/jniLibs/arm64-v8a/libqjs.so ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.so ffmpeg/src/main/jniLibs/arm64-v8a/libffmpeg.zip.so ffmpeg/src/main/jniLibs/arm64-v8a/libffprobe.so aria2c/src/main/jniLibs/arm64-v8a/libaria2c.so aria2c/src/main/jniLibs/arm64-v8a/libaria2c.zip.so
if git diff --cached --quiet; then log "No tracked changes; nothing to commit."; exit 0; fi
git diff --cached --check
git diff --cached --stat
DESCRIPTION="$(cat "$WORK/version-description.txt")"
git config user.name >/dev/null || die "Git user.name is not configured."
git config user.email >/dev/null || die "Git user.email is not configured."
COMMIT_ARGS=(-m "build: refresh Android runtime artifacts")
if [[ -n "$DESCRIPTION" ]]; then
  COMMIT_ARGS+=(-m "$DESCRIPTION")
fi
COMMIT_ARGS+=(-m "Signed-off-by: S I P U N <sipunkumar85@gmail.com>")
git commit "${COMMIT_ARGS[@]}"
log "Pushing runtime changes and verifying remote HEAD"
validate_origin
[[ "$ORIGIN_REPO" == "$INITIAL_ORIGIN_REPO" ]] || die "Git origin changed during the build."
git fetch origin main
git rebase origin/main
validate_origin
[[ "$ORIGIN_REPO" == "$INITIAL_ORIGIN_REPO" ]] || die "Git origin changed during the build."
git push origin HEAD:main
git fetch origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "Remote HEAD verification failed."
git show --stat --oneline --decorate HEAD
log "Native arm64-v8a runtime preparation complete. No Android app build was run."
