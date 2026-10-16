import re
import shutil
import subprocess
import sys
from pathlib import Path

prefix = Path(sys.argv[1])
libdir = prefix / "lib"
platform_libraries = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libjnigraphics.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so",
    "libGLESv3.so", "libOpenSLES.so", "libvulkan.so", "libnativewindow.so",
    "libsync.so", "libmediandk.so", "libaaudio.so", "libcamera2ndk.so",
}

def stage_dependencies(stage_name, root_names):
    stage = Path(stage_name)
    output_lib = stage / "usr" / "lib"
    output_lib.mkdir(parents=True, exist_ok=True)
    queue = []
    for root_name in root_names:
        root = prefix / "bin" / root_name
        if not root.is_file():
            raise SystemExit(f"Required executable is missing: {root}")
        queue.append(root)
    scanned = set()
    copied_sonames = set()
    while queue:
        binary = queue.pop()
        real_binary = binary.resolve()
        if real_binary in scanned:
            continue
        scanned.add(real_binary)
        result = subprocess.run(
            ["readelf", "-d", str(real_binary)],
            capture_output=True, text=True, check=False
        )
        if result.returncode:
            raise SystemExit(f"readelf failed for {real_binary}: {result.stderr.strip()}")
        needed_names = re.findall(r"Shared library: \[([^\]]+)\]", result.stdout)
        for soname in needed_names:
            if soname in platform_libraries or soname in copied_sonames:
                continue
            if soname in {"libcrypto.so", "libssl.so"}:
                continue
            matches = sorted(path for path in libdir.glob(soname) if path.exists())
            if not matches:
                raise SystemExit(f"Could not find runtime dependency {soname!r}, required by {real_binary}")
            source = matches[0]
            relative = source.relative_to(prefix)
            target = stage / "usr" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.is_symlink() or target.exists():
                target.unlink()
            shutil.copy2(source.resolve(), target)
            copied_sonames.add(soname)
            queue.append(source.resolve())
    if not any(path.is_file() and ".so" in path.name for path in output_lib.iterdir()):
        raise SystemExit(f"No shared-library dependencies were staged for {root_names}")

if sys.argv[4] == "1":
    stage_dependencies(sys.argv[2], ["ffmpeg", "ffprobe"])
if sys.argv[5] == "1":
    stage_dependencies(sys.argv[3], ["aria2c"])
