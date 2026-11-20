"""Package only source and reviewed release files; never include game configs or test worlds."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--build-dir', type=Path, default=root / 'build')
parser.add_argument('--output-dir', type=Path, default=root / 'dist')
args = parser.parse_args()
version = re.search(r"^mod_version=(.+)$", (root / "gradle.properties").read_text(), re.M)[1].strip()
dist = args.output_dir.resolve()
dist.mkdir(parents=True, exist_ok=True)
jar = args.build_dir.resolve() / "libs" / f"superwy-fabric-{version}.jar"
if not jar.is_file():
    raise SystemExit("Run gradlew test build first")
shutil.copy2(jar, dist / jar.name)
for name in ("README.md", "VERIFICATION.md", "THIRD_PARTY.md", "PRIVATE_MESSAGES.md"):
    shutil.copy2(root / name, dist / name)
shutil.copy2(root / "src/main/resources/web/openapi.json", dist / "openapi.json")
excluded = {".git", ".gradle", ".idea", "build", "build-memory", "build-memory-final", "build-restock", "dist", "run", "run-smoke", "test-libs", "__pycache__"}
with zipfile.ZipFile(dist / f"superwy-fabric-{version}-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if not path.is_file() or path.is_relative_to(args.build_dir.resolve()) or path.is_relative_to(dist) or any(part in excluded or part.startswith("build-") for part in relative.parts) or path.suffix in {".log", ".iml", ".pyc"}:
            continue
        archive.write(path, relative.as_posix())
# Bundle the common protocol sources so the source archive builds independently.
shared = root.parent / "shared/src/main/java"
with zipfile.ZipFile(dist / f"superwy-fabric-{version}-source.zip", "a", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(shared.rglob("*.java")):
        archive.write(path, "shared-src/" + path.relative_to(shared).as_posix())
manifest = {"version": version, "minecraft": "26.1.2", "java": ">=25", "fabricLoader": ">=0.19.5",
            "fabricApi": ">=0.155.3 (26.1.2)", "files": {}}
for path in sorted(dist.iterdir()):
    if path.is_file() and path.name != "release-manifest.json":
        manifest["files"][path.name] = {"bytes": path.stat().st_size,
                                       "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
(dist / "release-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(manifest, ensure_ascii=False, indent=2))
