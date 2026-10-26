#!/usr/bin/env python3
"""Create a clean source archive; excludes devices, builds and signing material."""
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXCLUDE = {'build', '.gradle', '.gradle-user', '.kotlin', '.idea', '.git', '__pycache__', 'dist'}
EXTENSIONS = {'.kt', '.kts', '.xml', '.png', '.md', '.txt', '.py', '.sh', '.command', '.bat', '.properties', '.json'}


def main():
    files = []
    for path in sorted(ROOT.rglob('*')):
        relative = path.relative_to(ROOT)
        if any(part in EXCLUDE or part.startswith('.venv') or part.startswith('._') for part in relative.parts):
            continue
        if not path.is_file() or path.name in {'local.properties', '.DS_Store'}:
            continue
        if path.is_symlink():
            raise SystemExit('Refusing symlink in source tree')
        if path.suffix not in EXTENSIONS and relative.as_posix() not in {'.gitignore', 'gradlew', 'gradle/wrapper/gradle-wrapper.jar'}:
            raise SystemExit('Unexpected source artefact: ' + relative.as_posix())
        data = path.read_bytes()
        if re.search(rb'/(?:Users|home)/[A-Za-z0-9_.-]+/', data):
            raise SystemExit('Local account path in ' + relative.as_posix())
        if re.search(rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----', data):
            raise SystemExit('Private key in ' + relative.as_posix())
        files.append((relative, data))
    out = ROOT / 'dist/Beeb-Android-0.1.6-source.zip'
    out.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as archive:
        for relative, data in files:
            info = zipfile.ZipInfo('Beeb-Android/' + relative.as_posix(), (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            mode = 0o755 if relative.name == 'gradlew' or relative.suffix in {'.py', '.sh', '.command'} else 0o644
            info.external_attr = (0o100000 | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    print(f'Packaged {len(files)} source files without local build/signing data.')


if __name__ == '__main__':
    main()
