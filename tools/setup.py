#!/usr/bin/env python3
"""Bundle version 1 of Google's metadata-bearing YAMNet. Run once before building."""
import argparse
import hashlib
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile
from downloads import download

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
URL = 'https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite'


def validate_model(model: Path, expected_digest: str | None = None):
    data = model.read_bytes()
    if len(data) <= 1_000_000 or data[4:8] != b'TFL3':
        raise ValueError('Expected a complete TFLite YAMNet model, not an archive or HTML download page')
    digest = hashlib.sha256(data).hexdigest()
    if expected_digest and digest != expected_digest:
        raise ValueError('Model differs from its lock; investigate before replacing either file')
    with zipfile.ZipFile(model) as archive:
        candidates = [archive.read(n).decode('utf-8').splitlines() for n in archive.namelist() if n.endswith('.txt')]
        labels = next((x for x in candidates if len(x) == 521), None)
        if labels is None or labels[38].strip() != 'Snoring':
            raise ValueError('Model labels do not match the verified YAMNet contract')
    return digest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-file', type=Path, help='Import an already downloaded version-1 yamnet.tflite without network access')
    arguments = parser.parse_args(argv)
    model = ASSETS / 'yamnet.tflite'
    lock = ASSETS / 'yamnet.sha256'
    expected = lock.read_text().strip() if lock.exists() else None
    ASSETS.mkdir(parents=True, exist_ok=True)
    if arguments.model_file is not None:
        # First import trusts the operator's source, like the initial HTTPS download.
        # An existing lock must still match. Validate before replacing a working asset.
        digest = validate_model(arguments.model_file, expected)
        with tempfile.TemporaryDirectory(dir=ASSETS) as stage:
            staged = Path(stage) / 'yamnet.tflite'
            shutil.copyfile(arguments.model_file, staged)
            staged.replace(model)
        lock.write_text(digest + '\n')
    elif model.exists():
        digest = validate_model(model, expected)
        if expected is None:
            lock.write_text(digest + '\n')
    else:
        # First download trusts HTTPS from the versioned Google URL, not an independent signature.
        # GCS MD5 is checked when supplied; commit the resulting model and SHA-256 lock.
        with tempfile.TemporaryDirectory(dir=ASSETS) as stage:
            staged = Path(stage) / 'yamnet.tflite'
            download(URL, staged, expected)
            digest = validate_model(staged, expected)
            staged.replace(model)
        lock.write_text(digest + '\n')
    print('YAMNet bundled and SHA-256 locked. Commit yamnet.tflite and yamnet.sha256 for reproducible offline app builds.')
    print('Model: ' + URL)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        sys.exit(f'Setup failed: {error}\nNo detector fallback is available. Restore network access, or use --model-file PATH for a trusted predownloaded model.')
