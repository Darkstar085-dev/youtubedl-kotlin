import re
import shutil
import subprocess
import sys
from pathlib import Path

prefix, stage = Path(sys.argv[1]), Path(sys.argv[2])
lib, usr = prefix / "lib", stage / "usr"
py_candidates = sorted(p for p in lib.glob("python3.*") if p.is_dir())
if not py_candidates:
    raise SystemExit("Python standard library not found")
py = py_candidates[0]
shutil.copytree(
    py,
    usr / "lib" / py.name,
    symlinks=True,
    ignore=shutil.ignore_patterns("test", "tests", "__pycache__"),
)
if (prefix / "etc").is_dir():
    shutil.copytree(prefix / "etc", usr / "etc", symlinks=True, dirs_exist_ok=True)
roots = [prefix / "bin/python3", prefix / "bin/qjs"]
roots += [p for p in py.rglob("*.so") if p.is_file()]
if not all(p.is_file() for p in roots[:2]):
    raise SystemExit("python3 or qjs executable is missing")
queue, seen = list(roots), set()
while queue:
    binary = queue.pop()
    result = subprocess.run(["readelf", "-d", str(binary)], capture_output=True, text=True)
    for name in re.findall(r"Shared library: \[([^\]]+)\]", result.stdout):
        if name in seen:
            continue
        seen.add(name)
        if name in {"libcrypto.so", "libssl.so"}:
            continue
        found = next(iter(lib.glob(name)), None)
        if found is None:
            continue
        target = usr / found.relative_to(prefix)
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            shutil.copy2(found.resolve(), target)
        queue.append(found)
for pattern in ("libandroid-support.so", "libandroid-posix-semaphore.so*", "libz.so*", "libssl.so*", "libcrypto.so*"):
    for found in lib.glob(pattern):
        if found.name in {"libcrypto.so", "libssl.so"}:
            continue
        target = usr / found.relative_to(prefix)
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            shutil.copy2(found.resolve(), target)
support = lib / "libandroid-support.so"
if not support.is_file():
    raise SystemExit(f"Required runtime library is missing: {support}")
support_target = usr / "lib" / "libandroid-support.so"
support_target.parent.mkdir(parents=True, exist_ok=True)
if support_target.is_symlink() or support_target.exists():
    support_target.unlink()
shutil.copy2(support.resolve(), support_target)
if not support_target.is_file() or support_target.stat().st_size == 0:
    raise SystemExit("Failed to stage libandroid-support.so")
