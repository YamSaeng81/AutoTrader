# ── 시험 사본 전용 가드(생산용 초안에는 없음, v1.225 §3) ─────────────────────────────
# (1)~(4)는 셸 내장 명령만 쓴다. PATH 를 확인하기 전에는 외부 프로그램을 하나도 부르지 않는다.
# 원격 호출은 아래에서 고정한 가짜 절대 경로 "$FAKE" 로만 한다. 실제 원격 도구로의 기본값·대체 경로는 없다.
[ -z "$(declare -F)" ] || { printf '🔴 시험 가드 거절(종료 2): 상속 함수 있음 — 가짜/외부 도구 호출 0회\n'; exit 2; }
tdeny() { printf '🔴 시험 가드 거절(종료 2): %s — 가짜/외부 도구 호출 0회\n' "$*"; exit 2; }
A1T_FAKE_SHA=@@FAKE_SHA@@
A1T_HOST=a1-test.invalid
A1T_PROD="cat""-prod"
# (1) 셸 초기화 주입
[ -z "${BASH_ENV:-}" ] && [ -z "${ENV:-}" ] || tdeny "BASH_ENV/ENV 설정됨"
# (2) PATH: 모든 항목이 절대 POSIX 경로여야 한다(Windows 형식·빈 항목·상대 경로 거절)
case "$PATH" in ''|:*|*:|*::*) tdeny "PATH 빈 항목";; esac
case "$PATH" in *'\'*|*';'*) tdeny "PATH 에 Windows 형식(역슬래시/세미콜론)";; esac
IFS=: read -r -a A1T_PE <<< "$PATH"
for A1T_P in "${A1T_PE[@]}"; do
  case "$A1T_P" in /*) ;; *) tdeny "PATH 항목이 절대 POSIX 경로가 아님: '$A1T_P'";; esac
  case "$A1T_P/" in */./*|*/../*|*//*) tdeny "PATH 항목 정규화 안 됨: '$A1T_P'";; esac
done
# (3) 시험 대상: 존재하지 않는 시험 전용 이름만. 운영 별칭 혼입 거절
case "${A1_TEST_HOST:-}" in *"$A1T_PROD"*) tdeny "운영 별칭 혼입(A1_TEST_HOST)";; esac
[ "${A1_TEST_HOST:-}" = "$A1T_HOST" ] || tdeny "A1_TEST_HOST 는 $A1T_HOST 이어야 함(받은 값 '${A1_TEST_HOST:-}')"
# (4) 가짜 절대 경로: 이 시험 사본 키트의 fake/fake-remote.sh 하나만 허용
case "$0" in /*/approval/a1-read-test.sh) ;; *) tdeny "시험 사본을 절대 경로로 실행해야 함";; esac
A1T_KIT=${0%/approval/a1-read-test.sh}
case "$A1T_KIT/" in */./*|*/../*|*//*) tdeny "키트 경로 정규화 안 됨";; esac
[ -n "${A1_FAKE_BIN:-}" ] || tdeny "가짜 경로(A1_FAKE_BIN) 누락"
case "$A1_FAKE_BIN" in /*) ;; *) tdeny "가짜 경로가 절대 경로가 아님";; esac
[ "$A1_FAKE_BIN" = "$A1T_KIT/fake/fake-remote.sh" ] || tdeny "가짜 경로 불일치(허용: $A1T_KIT/fake/fake-remote.sh)"
[ -f "$A1_FAKE_BIN" ] && [ ! -L "$A1_FAKE_BIN" ] || tdeny "가짜 파일 누락/연결 파일"
[ -x "$A1_FAKE_BIN" ] || tdeny "가짜 파일 실행 불가"
# (5) 여기서부터 외부 프로그램(검증한 PATH). 가짜 해시·운영 별칭 문자열 검사
[ "$(sha256sum < "$A1_FAKE_BIN" | cut -d' ' -f1)" = "$A1T_FAKE_SHA" ] || tdeny "가짜 해시 불일치"
! grep -qF "$A1T_PROD" "$0" "$A1_FAKE_BIN" || tdeny "운영 별칭 문자열이 시험 사본/가짜에 있음"
FAKE=$A1_FAKE_BIN; TEST_HOST=$A1T_HOST
# ── 시험 사본 전용 가드 끝 ─────────────────────────────────────────────────────────
