import shutil
import sys
from pathlib import Path

prefix, stage = map(Path, sys.argv[1:3])
source = prefix / "lib" / "ossl-modules"
target = stage / "usr" / "lib" / "ossl-modules"
config = stage / "usr" / "etc" / "tls" / "openssl.cnf"
if not source.is_dir():
    raise SystemExit(f"OpenSSL provider directory is missing: {source}")
if not config.is_file():
    raise SystemExit(f"OpenSSL config is missing from runtime stage: {config}")
modules = {p.name: p for p in source.glob("*.so") if p.is_file()}
if "legacy.so" not in modules:
    raise SystemExit("OpenSSL legacy provider (legacy.so) is required but unavailable")
target.mkdir(parents=True, exist_ok=True)
for name, module in modules.items():
    shutil.copy2(module.resolve(), target / name)
if not (target / "legacy.so").is_file():
    raise SystemExit("Failed to stage OpenSSL legacy provider")
