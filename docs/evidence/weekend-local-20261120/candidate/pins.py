"""총괄 v1.212 주말 로컬 후보 공통 — 수용 원천을 고정 해시로 확인한 뒤에만 불러온다.

고정값은 지시서 폴더의 source-pins.json(읽기 전용)에서 읽는다. 해시가 다르면 불러오지 않고 PinError 를 낸다.
원천 폴더(기존 evidence)에 __pycache__ 를 남기지 않도록 바이트코드 쓰기를 끈다. 네트워크·DB·명령 실행 없음.
"""
import hashlib, importlib.util, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, '..', '..', '..', '..'))
PINS_PATH = os.path.join(REPO, 'docs', 'evidence', 'weekend-work-order-20261120', 'source-pins.json')


class PinError(RuntimeError):
    pass


def sha256_file(path):
    with open(path, 'rb') as f:
        return hashlib.sha256(f.read()).hexdigest()


def pins():
    with open(PINS_PATH, encoding='utf-8') as f:
        return json.load(f)['sources']


def pinned_path(key):
    p = pins()[key]
    if 'git_revision' in p:
        raise PinError(f'{key} 는 Git 고정 원천 — 파일로 추출해 해시를 확인한 뒤 입력으로 넘긴다')
    return os.path.join(REPO, *p['path'].split('/')), p['sha256']


def verify(key):
    path, want = pinned_path(key)
    got = sha256_file(path)
    if got != want:
        raise PinError(f'{key} 해시 불일치: 기대 {want[:8]} / 실제 {got[:8]}')
    return path, got


_loaded = {}


def load(key, extra_path=None):
    """고정 원천 모듈을 해시 확인 후 불러온다. extra_path: 그 모듈이 같은 폴더의 다른 모듈을 import 할 때만."""
    if key in _loaded:
        return _loaded[key]
    path, _ = verify(key)
    sys.dont_write_bytecode = True
    d = os.path.dirname(path)
    added = False
    if extra_path and d not in sys.path:
        sys.path.insert(0, d)
        added = True
    try:
        spec = importlib.util.spec_from_file_location(f'pinned_{key}', path)
        m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(m)
    finally:
        if added:
            sys.path.remove(d)
    _loaded[key] = m
    return m


def dump(obj, path=None):
    text = json.dumps(obj, ensure_ascii=False, indent=1, sort_keys=False, default=str) + '\n'
    if path:
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(text)
    else:
        if hasattr(sys.stdout, 'reconfigure'):
            sys.stdout.reconfigure(encoding='utf-8', newline='\n')
        sys.stdout.write(text)
