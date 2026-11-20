"""Package reviewed source files, excluding test worlds, dependencies and configuration."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
version = next(line.split('=', 1)[1].strip() for line in (root / 'gradle.properties').read_text().splitlines() if line.startswith('mod_version='))
dist = root / 'dist'
dist.mkdir(exist_ok=True)
jar = root / 'build/libs' / f'baritone-steering-fix-{version}.jar'
shutil.copy2(jar, dist / jar.name)
for name in ('README.md', 'VERIFICATION.md'):
    shutil.copy2(root / name, dist / name)
with zipfile.ZipFile(dist / f'baritone-steering-fix-{version}-source.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for name in ('.gitignore', 'README.md', 'VERIFICATION.md', 'gradle.properties', 'build.gradle', 'settings.gradle', 'gradlew', 'gradlew.bat'):
        z.write(root / name, name)
    for folder in ('src', 'gradle', 'tools'):
        for p in sorted((root / folder).rglob('*')):
            if p.is_file() and '__pycache__' not in p.parts:
                z.write(p, p.relative_to(root).as_posix())
manifest = {'version': version, 'minecraft': '26.1.2', 'baritone': 'baritone-api-fabric-1.18.0.jar', 'files': {}}
for p in sorted(dist.iterdir()):
    if p.is_file() and p.name != 'release-manifest.json':
        manifest['files'][p.name] = {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
(dist / 'release-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(manifest, ensure_ascii=False, indent=2))
