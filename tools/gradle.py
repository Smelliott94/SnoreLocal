#!/usr/bin/env python3
"""Small pinned Gradle bootstrap used until a standard wrapper can be generated."""
import os
from pathlib import Path
import subprocess
import sys
import zipfile
from downloads import download, remote_sha

ROOT = Path(__file__).resolve().parents[1]
VERSION = '8.13'


def main():
    directory = ROOT / '.tools'
    executable = directory / f'gradle-{VERSION}/bin' / ('gradle.bat' if os.name == 'nt' else 'gradle')
    if not executable.exists():
        url = f'https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip'
        sha = remote_sha(url + '.sha256')
        archive = directory / f'gradle-{VERSION}-bin.zip'
        download(url, archive, sha)
        with zipfile.ZipFile(archive) as bundle:
            for member in bundle.infolist():
                if not (directory / member.filename).resolve().is_relative_to(directory.resolve()):
                    raise ValueError('Unsafe distribution ZIP path')
            bundle.extractall(directory)
        executable.chmod(0o755)
        archive.unlink()
    return subprocess.call([str(executable), *sys.argv[1:]], cwd=ROOT)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        sys.exit(f'Gradle bootstrap failed: {error}\nInstall Gradle 8.13 locally or restore network access. See README.md.')
