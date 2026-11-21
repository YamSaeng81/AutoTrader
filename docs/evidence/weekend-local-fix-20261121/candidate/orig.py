"""총괄 v1.213 보완 공통 — 원본 W 후보(weekend-local-20261120/candidate, 체크섬 고정)를 복사하지 않고 해시 확인 뒤 불러온다.

원본 파일은 읽기만 한다. 기대 해시는 원본 폴더 SHA256SUMS(fee13712 커밋, 총괄 v1.213 §2 보존 대조 603/603)의 값이다.
바이트코드를 쓰지 않는다(원본 폴더에 __pycache__ 를 남기지 않음). 네트워크·DB·명령 실행 없음.
"""
import hashlib, importlib.util, os, sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ORIG = os.path.normpath(os.path.join(HERE, '..', '..', 'weekend-local-20261120', 'candidate'))
WANT = {
    'pins': '674e746e00a85f94f09cf9cf25d5ff12572a7b3afeda19ab00b40835824182c8',
    'build_monthly': '1b7739b3c2e010ce61dafdd18349f23d9cce0b86ae4268e565dc365d425befdb',
    'run_offline': '59b80aa512dcc01c08589eaea98272fa0471e998d39165d0e78bece9e7436d0d',
}   # 그 밖의 원본(cases_w3/w4/w5 등 사례 입력 생성기)은 원본 SHA256SUMS 의 값으로 확인


class OrigError(RuntimeError):
    pass


def path(name):
    return os.path.join(ORIG, f'{name}.py')


def sha(p):
    with open(p, 'rb') as f:
        return hashlib.sha256(f.read()).hexdigest()


def _sums():
    out = {}
    with open(os.path.join(os.path.dirname(ORIG), 'SHA256SUMS'), encoding='utf-8') as f:
        for line in f:
            h, n = line.rstrip('\n').split(' ', 1)
            out[n.lstrip('*').strip()] = h
    return out


def verify(name):
    want = WANT.get(name) or _sums().get(f'candidate/{name}.py')
    got = sha(path(name))
    if want is None or got != want:
        raise OrigError(f'원본 {name}.py 해시 불일치/미등록: 기대 {str(want)[:8]} / 실제 {got[:8]}')
    return got


def load(name):
    verify('pins')
    verify(name)
    if ORIG not in sys.path:
        sys.path.insert(0, ORIG)
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, path(name))
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m
