# 실제 jar 에 중복 한 항목만 더한 대역 jar 를 만든다(총괄 v1.135 H2 대역용, 운영 jar 아님).
# 사용: python -I -X utf8 mkdupjar.py <원본 jar> <출력 jar(없어야 함)> <inner-diff|inner-same|outer-same>
#   inner-*: core-engine 자체 모듈 jar 안 첫 .class 항목 앞에 같은 이름 항목을 하나 더 넣는다(diff=다른 바이트, same=같은 바이트)
#   outer-same: 바깥 BOOT-INF/classes 첫 .class 항목을 같은 바이트로 한 번 더 넣는다
import io, pathlib, re, sys, warnings, zipfile
src, dst, mode = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
assert not dst.exists() and mode in ('inner-diff', 'inner-same', 'outer-same')
OWN = re.compile(r'BOOT-INF/lib/core-engine[^/]*\.jar$')
warnings.simplefilter('ignore', UserWarning)
done = []


def clone(zi):
    n = zipfile.ZipInfo(zi.filename, date_time=zi.date_time)
    n.compress_type, n.external_attr, n.comment, n.extra = zi.compress_type, zi.external_attr, zi.comment, zi.extra
    return n


with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
    for zi in zin.infolist():
        data = zin.read(zi)
        if mode.startswith('inner') and OWN.match(zi.filename) and not done:
            b = io.BytesIO()
            with zipfile.ZipFile(io.BytesIO(data)) as ii, zipfile.ZipFile(b, 'w') as io_:
                for z2 in ii.infolist():
                    d2 = ii.read(z2)
                    if not done and z2.filename.endswith('.class'):
                        io_.writestr(clone(z2), d2 if mode == 'inner-same' else d2 + b'\x00standin-dup')
                        done.append(zi.filename + '!' + z2.filename)
                    io_.writestr(clone(z2), d2)
            data = b.getvalue()
        zout.writestr(clone(zi), data)
        if mode == 'outer-same' and not done and zi.filename.startswith('BOOT-INF/classes/') and zi.filename.endswith('.class'):
            zout.writestr(clone(zi), data)
            done.append(zi.filename)
assert len(done) == 1, done
print('중복 1항목:', done[0], '→', dst)
