import hashlib
import json
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

release = json.loads(Path(sys.argv[1]).read_text())
assets = [a for a in release.get("assets", []) if a.get("name") == "yt-dlp"]
if len(assets) != 1:
    raise SystemExit("Expected one official yt-dlp asset")
asset = assets[0]
url, digest = asset.get("browser_download_url", ""), asset.get("digest", "")
if not url.startswith("https://github.com/yt-dlp/yt-dlp/releases/download/") or not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", digest):
    raise SystemExit("yt-dlp asset URL or SHA-256 digest is invalid")
with urllib.request.urlopen(url, timeout=60) as response:
    data = response.read()
if hashlib.sha256(data).hexdigest() != digest.split(":", 1)[1].lower() or not data.startswith(b"#!"):
    raise SystemExit("yt-dlp digest or shebang validation failed")
Path(sys.argv[2]).write_bytes(data)
with zipfile.ZipFile(sys.argv[2]) as archive:
    if archive.testzip() or "__main__.py" not in archive.namelist() or "yt_dlp/__init__.py" not in archive.namelist():
        raise SystemExit("yt-dlp archive validation failed")
