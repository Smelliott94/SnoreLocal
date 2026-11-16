"""Build-time HTTPS downloads only. The Android app has no network permission."""
import base64
import hashlib
from pathlib import Path
import urllib.request


def download(url: str, target: Path, expected_sha256: str | None = None):
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + '.download')
    sha = hashlib.sha256()
    md5 = hashlib.md5()
    print(f'Downloading {url}', flush=True)
    try:
        with urllib.request.urlopen(url, timeout=45) as response, temporary.open('wb') as output:
            google_hash = response.headers.get('x-goog-hash', '')
            expected_md5 = next((h.strip()[4:] for h in google_hash.split(',') if h.strip().startswith('md5=')), None)
            while chunk := response.read(1024 * 1024):
                sha.update(chunk); md5.update(chunk); output.write(chunk)
        if expected_sha256 and sha.hexdigest() != expected_sha256:
            raise ValueError('SHA-256 mismatch: ' + url)
        if expected_md5 and base64.b64encode(md5.digest()).decode() != expected_md5:
            raise ValueError('GCS transport checksum mismatch: ' + url)
        temporary.replace(target)
        return sha.hexdigest()
    finally:
        temporary.unlink(missing_ok=True)


def remote_sha(url: str):
    with urllib.request.urlopen(url, timeout=30) as response:
        value = response.read().decode().strip().split()[0]
    if len(value) != 64 or any(c not in '0123456789abcdef' for c in value):
        raise ValueError('Invalid official SHA-256 response')
    return value
