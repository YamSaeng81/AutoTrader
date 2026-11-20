"""W3 P7 v8·F3 v9 소비 연결기(총괄 v1.212 §2 W3). 로컬 파일 입력 전용 — SSH·psql·DB·HTTP·거래소 호출 없음.

판정식을 복제하지 않는다. 고정 해시를 확인한 원천의 함수만 부른다.
  P7 v8 reconcile.evaluate()·load_file()(156c3828) → F3 v9 f3.judge()(f825ca5e). DB 부분 묶음은 조립기 verify_committed()(ff854934).

입력
  전체: --registry R --base B --now N [--krw-quantum Q]  세 파일이 모두 명시됐을 때만 P7→F3 를 연결한다.
  부분: --db-bundle DIR  verify_committed() 를 먼저 거친다. 통과해도 '추가 원천 필요'로 끝난다 — 지문 전후·거래소 전후·코드/자본·
        시계 근거를 채워 전체 관측으로 만들지 않는다. 통과하지 못하면 '묶음 거절'.
  전체 입력의 일부만 있으면(예: 등록표만) 빠진 입력을 적고 '추가 원천 필요'.
  --out 결과 JSON(P7 원문 출력·P7 종료값·F3 통과 여부·F3 사유·F3 종료값을 함께 보존)

종료값: F3_PASS 0 · NEEDS_MORE_SOURCES 10 · F3_NOT_PASS 20 · BUNDLE_REJECTED 30 · ERROR 40
F3_NOT_PASS 를 경고로 낮추지 않는다. P7 이 OK 여도 F3 가 불통과이면 불통과다.
"""
import argparse, os, sys
from decimal import Decimal as D

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pins  # noqa: E402

TOOL, VERSION = 'check_parallel', 'w3-1'
EXIT = {'F3_PASS': 0, 'NEEDS_MORE_SOURCES': 10, 'F3_NOT_PASS': 20, 'BUNDLE_REJECTED': 30, 'ERROR': 40}


def run_full(reg_path, base_path, now_path, q='0'):
    R, F = pins.load('p7_v8'), pins.load('f3_v9')
    try:
        quantum = R.num(q, D(0))
    except R.Bad as e:
        p7 = {'overall': 'ERROR', 'error': 'Bad', 'message': f'--krw-quantum {e}', 'anomalies': [], 'holds': []}
    else:
        try:
            p7 = R.evaluate(R.load_file(reg_path), R.load_file(base_path), R.load_file(now_path), quantum)
        except Exception as e:  # noqa: BLE001 — P7 main() 과 같은 처리: 무출력 대신 구조화된 ERROR
            p7 = {'overall': 'ERROR', 'error': type(e).__name__, 'message': '파일 입력 단계 예외', 'anomalies': [], 'holds': []}
    ok, why = F.judge(p7)
    return {'mode': 'full', 'p7_output': p7, 'p7_overall': p7.get('overall'), 'p7_exit': R.EXIT.get(p7.get('overall')),
            'f3': {'pass': ok, 'reasons': why, 'exit': 0 if ok else 1},
            'verdict': 'F3_PASS' if ok else 'F3_NOT_PASS'}


def run_bundle(path):
    A = pins.load('assembler_committed')
    ok, b = A.verify_committed(path)
    if not ok:
        return {'mode': 'db_bundle', 'bundle_verified': False, 'bundle_reason': b, 'verdict': 'BUNDLE_REJECTED',
                'note': '확정되지 않았거나 해시가 맞지 않는 묶음은 소비하지 않는다'}
    return {'mode': 'db_bundle', 'bundle_verified': True, 'bundle_kind': b.get('kind'), 'bundle_status': b.get('status'),
            'needs_sources': list(A.NOT_IN_BUNDLE), 'verdict': 'NEEDS_MORE_SOURCES',
            'note': 'DB 추출 부분 묶음만으로는 P7 전체 관측·F3 입력을 만들지 않는다. 빠진 원천을 기본값으로 채우지 않는다'}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--registry')
    ap.add_argument('--base')
    ap.add_argument('--now')
    ap.add_argument('--krw-quantum', default='0')
    ap.add_argument('--db-bundle')
    ap.add_argument('--out')
    a = ap.parse_args(argv)
    head = {'tool': TOOL, 'version': VERSION}
    try:
        head['pins'] = {k: pins.verify(k)[1] for k in ('p7_v8', 'f3_v9', 'assembler_committed')}
        full = {'registry': a.registry, 'base': a.base, 'now': a.now}
        given = [k for k, v in full.items() if v]
        if len(given) == 3:
            out = run_full(a.registry, a.base, a.now, a.krw_quantum)
            if a.db_bundle:   # 함께 왔어도 전체 입력을 대신하거나 보충하지 않는다 — 검증 결과만 기록
                out['db_bundle_side_check'] = run_bundle(a.db_bundle)
        elif a.db_bundle:
            out = run_bundle(a.db_bundle)
            if given:
                out['partial_full_inputs_given'] = given
                out['missing_full_inputs'] = [k for k in full if k not in given]
        else:
            out = {'mode': 'none', 'verdict': 'NEEDS_MORE_SOURCES', 'missing_full_inputs': [k for k in full if k not in given],
                   'note': '전체 입력(등록표·기준 관측·현재 관측)이 모두 명시돼야 P7→F3 를 연결한다'}
    except pins.PinError as e:
        out = {'verdict': 'ERROR', 'error': 'PinError', 'message': str(e)}
    except Exception as e:  # noqa: BLE001
        out = {'verdict': 'ERROR', 'error': type(e).__name__, 'message': '예상 밖 예외(입력 값 미출력)'}
    out = {**head, **out}
    out['exit'] = EXIT[out['verdict']]
    out['summary_line'] = {
        'F3_PASS': 'F3 통과(P7 v8 OK + F3 v9 조건 전부)',
        'F3_NOT_PASS': f"F3 불통과 — P7 {out.get('p7_overall')}, 사유 {len(out.get('f3', {}).get('reasons', []))}건",
        'NEEDS_MORE_SOURCES': '추가 원천 필요 — 계좌·F3 판정하지 않음',
        'BUNDLE_REJECTED': f"DB 부분 묶음 거절 — {out.get('bundle_reason')}",
        'ERROR': f"오류 — {out.get('error')}"}[out['verdict']]
    pins.dump(out, a.out)
    return out['exit']


if __name__ == '__main__':
    sys.exit(main())
