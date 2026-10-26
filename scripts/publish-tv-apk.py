#!/usr/bin/env python3
"""Publish a verified production-signed TV APK to 182 only.

Usage: python scripts/publish-tv-apk.py APK VERSION_CODE VERSION_NAME
Requires key-based OpenSSH access, aapt2 and apksigner on PATH.
Does not touch the website service or the legacy static server.
"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request
import uuid

HOST = "root@182.92.222.30"
ORIGIN = "https://kvideo.djjk.cc"
CERT = "dda3e9fd5a2df5c9a08d66e50ccedc17301bd02e3e2b8723e6f2cb235610d57d"


def run(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs)


def fetch(path):
    request = urllib.request.Request(ORIGIN + path, headers={"Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def main():
    apk = Path(sys.argv[1]).resolve()
    code = int(sys.argv[2])
    name = sys.argv[3]
    if code <= 0 or not re.fullmatch(r"\d+\.\d+\.\d+", name):
        raise ValueError("Invalid version")
    badging = run(["aapt2", "dump", "badging", str(apk)])
    identity = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not identity or identity.groups() != ("com.djjk.ziqidonglai.tv", str(code), name):
        raise ValueError("APK identity differs from requested version")
    signer = run(["apksigner", "verify", "--print-certs", str(apk)])
    certs = re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", signer)
    if certs != [CERT]:
        raise ValueError("Not the expected production signing certificate")
    data = apk.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    previous = json.loads(fetch("/api/tv-update"))
    if code <= previous["versionCode"]:
        raise ValueError("New version must be strictly newer than production")
    token = uuid.uuid4().hex
    staged = f"/var/www/ziqidonglai/.upload-{token}.apk"
    ssh = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", HOST]
    run(["scp", "-o", "BatchMode=yes", str(apk), f"{HOST}:{staged}"])
    payload = {
        "code": code, "name": name, "sha": sha, "size": len(data),
        "previous": previous, "staged": staged,
    }
    # Fixed Python program; data is JSON, never interpolated into shell commands.
    remote = '''import datetime, fcntl, hashlib, json, os, pathlib, shutil, sys
p = json.loads(sys.stdin.readline())
lock = open('/var/www/ziqidonglai/.publish.lock', 'a')
fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
staged = pathlib.Path(p['staged'])
try:
    manifest = pathlib.Path('/opt/kvideo/tv-update.json')
    current = json.loads(manifest.read_text())
    if any(current.get(k) != p['previous'].get(k) for k in ('versionCode', 'url', 'sha256', 'size')) or p['code'] <= current['versionCode']:
        raise RuntimeError('Production changed since preflight; refusing overwrite')
    if staged.stat().st_size != p['size'] or hashlib.sha256(staged.read_bytes()).hexdigest() != p['sha']:
        raise RuntimeError('Uploaded APK hash/size mismatch')
    directory = staged.parent
    filename = 'ziqidonglai-tv-v' + str(p['code']) + '.apk'
    target = directory / filename
    if target.exists():
        raise RuntimeError('Versioned artifact already exists; refusing overwrite')
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup = pathlib.Path('/root/kvideo-release-backups/single-server-' + stamp)
    backup.mkdir(parents=True, exist_ok=False)
    shutil.copy2(manifest, backup / 'tv-update.json')
    shutil.copy2(directory / 'latest.apk', backup / 'latest.apk')
    (backup / 'latest-target.txt').write_text(os.path.realpath(directory / 'latest.apk'))
    staged.chmod(0o644)
    os.replace(staged, target)
    link = directory / ('.latest-' + str(p['code']))
    link.symlink_to(target)
    os.replace(link, directory / 'latest.apk')
    update = dict(version=p['name'], versionName=p['name'], versionCode=p['code'],
        url='https://kvideo.djjk.cc/ziqidonglai/' + filename,
        sha256=p['sha'], size=p['size'], publishedAt=stamp, mandatory=False,
        notes='单服务器更新；原生播放保留站点代理；修复代理 HLS 类型识别',
        apkPath=str(target), apkFileName=filename)
    temp = manifest.with_suffix('.json.new')
    temp.write_text(json.dumps(update, ensure_ascii=False, indent=2))
    temp.chmod(0o644)
    os.replace(temp, manifest)
    assert json.loads(manifest.read_text()) == update
    assert hashlib.sha256(target.read_bytes()).hexdigest() == p['sha']
    print(json.dumps(dict(backup=str(backup), manifest=update)))
finally:
    staged.unlink(missing_ok=True)
'''
    # python -c is quoted as one remote shell argument.
    import shlex
    result = run(ssh + ["python3 -c " + shlex.quote(remote)], input=json.dumps(payload) + "\n")
    print(result.strip())
    actual = json.loads(fetch("/api/tv-update"))
    if (actual["versionCode"], actual["sha256"], actual["size"]) != (code, sha, len(data)):
        raise RuntimeError("Public manifest does not match publication")
    if actual["url"] != ORIGIN + f"/ziqidonglai/ziqidonglai-tv-v{code}.apk":
        raise RuntimeError("Public manifest is not using the immutable APK URL")
    downloaded = fetch(f"/ziqidonglai/ziqidonglai-tv-v{code}.apk")
    if len(downloaded) != len(data) or hashlib.sha256(downloaded).hexdigest() != sha:
        raise RuntimeError("Public APK does not match publication")
    if hashlib.sha256(fetch("/ziqidonglai/latest.apk")).hexdigest() != sha:
        raise RuntimeError("Public latest.apk does not match publication")
    print("VERIFIED 182-only publication", code, sha, len(data))


if __name__ == "__main__":
    main()
