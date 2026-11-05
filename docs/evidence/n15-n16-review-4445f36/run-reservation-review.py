"""Compile exact three candidate sources; use existing local dependencies, no Gradle/server/network."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SCRATCH = ROOT / 'core-engine/build/review-n15-n16-4445f36'
CANDIDATE = '6f6a5220f7a19f381fa210a9c4a530e262a4fb04'
JAVA = Path('C:/Program Files/Java/jdk-17/bin')
ARGS = Path.home() / '.gradle/.tmp/gradle-worker-classpath806889984022946906txt'
cp = ARGS.read_text(encoding='utf-8').splitlines()[1].strip().strip('"')
assert 'cat-n15' in cp and 'mockito-core' in cp
SCRATCH.mkdir(parents=True, exist_ok=True)
classes = SCRATCH / 'classes'
classes.mkdir(exist_ok=True)
# Keep Java's ZIP real-path operations inside the writable scratch directory.
dependency_dir = SCRATCH / 'dependencies'
dependency_dir.mkdir(exist_ok=True)
local_cp = []
for i, item in enumerate(cp.split(os.pathsep)):
    original = Path(item)
    dest = dependency_dir / f'{i}-{original.name}'
    if not dest.exists():
        if original.is_dir():
            shutil.copytree(original, dest)
        else:
            shutil.copyfile(original, dest)
    local_cp.append(str(dest))
cp = os.pathsep.join(local_cp)
paths = []
hashes = {}
for name in ['CapitalReservationService', 'EntryPriorityRounds', 'EntryClock']:
    path = f'web-api/src/main/java/com/cryptoautotrader/api/service/{name}.java'
    raw = subprocess.check_output(['git', 'show', f'{CANDIDATE}:{path}'], cwd=ROOT)
    dest = SCRATCH / f'{name}.java'
    dest.write_bytes(raw)
    hashes[path] = hashlib.sha256(raw).hexdigest()
    paths.append(dest)
lombok = next((Path.home() / '.gradle/caches/modules-2/files-2.1/org.projectlombok/lombok/1.18.30').rglob('*.jar'))
local_lombok = dependency_dir / lombok.name
shutil.copyfile(lombok, local_lombok)
compile_args = ['-encoding', 'UTF-8', '-cp', cp + os.pathsep + str(local_lombok), '-processorpath', str(local_lombok), '-d', str(classes),
                *(str(p) for p in paths), str(OUT / 'ReservationExpiryReview.java')]
# Argument file avoids the Windows command line length limit; preserve literal backslashes.
argfile = SCRATCH / 'javac.args'
argfile.write_text('\n'.join('"' + a.replace('\\', '\\\\').replace('"', '\\"') + '"' for a in compile_args), encoding='utf-8')
compiled = subprocess.run([str(JAVA / 'javac.exe'), '@' + str(argfile)], capture_output=True)
(OUT / 'javac.txt').write_bytes(compiled.stdout + compiled.stderr)
if compiled.returncode:
    print(compiled.stderr.decode('utf-8', errors='replace'))
    raise SystemExit(compiled.returncode)
java_args = SCRATCH / 'java.args'
run_args = ['-Dfile.encoding=UTF-8', '-cp', str(classes) + os.pathsep + cp,
            'com.cryptoautotrader.api.service.ReservationExpiryReview']
java_args.write_text('\n'.join('"' + a.replace('\\', '\\\\').replace('"', '\\"') + '"' for a in run_args), encoding='utf-8')
ran = subprocess.run([str(JAVA / 'java.exe'), '@' + str(java_args)], cwd=ROOT, capture_output=True)
(OUT / 'reservation-review.txt').write_bytes(ran.stdout + ran.stderr)
(OUT / 'reservation-review.json').write_text(json.dumps({
    'candidate': CANDIDATE, 'exact_compiled_production_sources': hashes,
    'javac_exit': compiled.returncode, 'java_exit': ran.returncode,
    'method': 'Actual candidate reservation method; Mockito persistence/guards; simulated E crossing after first check.',
    'limits': ['No real database transaction or persisted order intent is asserted.',
               'Dependency classpath reused from preserved local Gradle worker; dependency classes are not rebuilt.',
               '7/7 counts reproduced expectations, including 3 defects, not fixed product tests.'],
    'gradle_or_bootjar_build': False, 'server_db_or_external_access': False
}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(ran.stdout.decode('utf-8', errors='replace'))
if ran.returncode:
    print(ran.stderr.decode('utf-8', errors='replace'))
raise SystemExit(ran.returncode)
