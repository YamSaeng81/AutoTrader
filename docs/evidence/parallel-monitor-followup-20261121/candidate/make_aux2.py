"""pm-read-2 post 단계의 pm-aux-2 작성기 pm-aux-writer-1 (총괄 v1.216 §3-2). 로컬 파일 전용 — 서버·DB 접근 없음.
pm-read.sh(pm-read-1) 안의 python 본문을 파일로 뺀 것이다(해시 고정·Windows 표준 입력 인코딩 의존 제거). 판단 규칙은 같다:
  · n03-values.json 이 있으면 {source_file(docs/evidence 기준 보존 N03 원문 상대 경로), source_sha256, error_logs, paper_522_587, observed_window}
    를 받아 원문을 post 폴더로 복사하고 해시를 확인한다(해시 불일치·필드 형식 위반이면 종료 2, aux 를 쓰지 않는다).
  · 없으면 N03 항목은 null(수집 불통과) — 0·빈 목록·정상으로 채우지 않는다.
  · P0 는 p0_input 결과 파일(p0-result.json)만 원천으로 가리킨다.
사용: python -I -X utf8 make_aux2.py <post 폴더> <n03-values.json 또는 빈 문자열> <docs/evidence 경로>
종료값: 0 작성 · 2 입력 거절
"""
import hashlib, json, os, shutil, sys

sys.dont_write_bytecode = True
N03_KEYS = {'source_file', 'source_sha256', 'error_logs', 'paper_522_587', 'observed_window'}


def h(p):
    with open(p, 'rb') as f:
        return hashlib.sha256(f.read()).hexdigest()


def main(argv):
    d, n3, evi = argv
    p0 = os.path.join(d, 'p0-result.json')
    if not os.path.isfile(p0):
        print('p0-result.json 없음'); return 2
    if n3:
        v = json.load(open(n3, encoding='utf-8'))
        if not isinstance(v, dict) or set(v) != N03_KEYS or not isinstance(v['observed_window'], str) or not v['observed_window'].strip():
            print('n03-values 형식(필드 이름만 확인, 값 미출력)'); return 2
        src0 = os.path.normpath(os.path.join(evi, v['source_file']))
        if not src0.startswith(os.path.normpath(evi) + os.sep) or not os.path.isfile(src0) or h(src0) != v['source_sha256']:
            print('N03 원문 경로 밖·없음·해시 불일치'); return 2
        src = os.path.join(d, 'n03-source' + os.path.splitext(src0)[1])
        shutil.copyfile(src0, src)
        n03 = {'source': {'file': os.path.basename(src), 'sha256': h(src), 'rc': 0}, 'error_logs': v['error_logs'],
               'paper_522_587': v['paper_522_587']}
        note = {'n03_source_file': v['source_file'], 'observed_window': v['observed_window'],
                'note': 'N03 관측 구간은 P0 로그 구간·db 구간과 같은 관측이라고 선언하지 않는다'}
    else:
        src = os.path.join(d, 'n03-source.txt')
        with open(src, 'w', encoding='utf-8', newline='\n') as f:
            f.write('N03 값 미제공 — 이 읽기에서 N03 항목은 null(수집 불통과)\n')
        n03 = {'source': {'file': 'n03-source.txt', 'sha256': h(src), 'rc': 0}, 'error_logs': None, 'paper_522_587': None}
        note = {'n03_source_file': None, 'observed_window': None, 'note': 'N03 값 미제공'}
    aux = {'aux_version': 'pm-aux-2', 'synthetic': False, 'n03': n03, 'p0': {'source': {'file': 'p0-result.json', 'sha256': h(p0)}}}
    with open(os.path.join(d, 'n03-p0-aux.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(aux, f, ensure_ascii=False, indent=1)
        f.write('\n')
    with open(os.path.join(d, 'n03-window.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(note, f, ensure_ascii=False, indent=1)
        f.write('\n')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
