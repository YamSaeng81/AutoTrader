# 소액 실주문 시험 원문 초안 v2 — 시각·gate 종료값·보유 표시 보완 (총괄 v1.249 §3, 제출)

2026-11-25 KST, Claude Code 단일 실행 세션. **실행하지 않았다.** 함수·파일 검증만 했다. 운영·거래소·실제/가짜 SSH·curl·Docker·PG는 0회이고, launch.sh·smoke.sh 전체도 실행하지 않았다. 원본 [live-smoke-draft-20261125](../live-smoke-draft-20261125/README.md)와 그 체크섬은 그대로다. 사본은 [orig/](orig/)에 있다(해시 동일). 제품, 배포 출처, 게이트 v4 SQL/판정기는 바꾸지 않았다. **이 초안은 계좌 읽기·토큰 준비·세션 생성/시작·주문 승인이 아니다.**

## 1. 변경표

| 파일 | 변경 | 차이 |
|---|---|---|
| [kst.py](kst.py) **신규** (e88bc170) | 고정 오프셋 UTC+09:00, `window <단계> <날짜> <시작> <끝>`. 현재 시각은 `time.time()` 하나에서 날짜와 HHMM을 함께 얻는다. TZ, 로컬 시간대, zoneinfo/tzdata는 쓰지 않는다. 실제 달력 날짜, 00~23시·00~59분, 시작≤끝(자정 넘는 창 없음)을 확인한다. 창 단계(p0·create·verify·start)는 승인 날짜의 [시작, 끝] 분, 끝 분 포함이다. 창 뒤 단계(watch·stop·gate)는 형식만 본다. 통과 0, 거절 3, 예외·인자 오류 2. 임의 시각을 넣는 옵션이나 환경변수는 없고, 고정 시각은 `judge()`를 직접 불러 검증한다 | [batch-r2 kst.py 대비](diff/kst.py-vs-batch-r2.diff) — 변환식 같음 |
| [launch.sh](launch.sh) (1341e873) | ① `TZ=Asia/Seoul date` 2줄을 `kst_win`(python3/python + kst.py)으로 바꿨다. python이 없으면 ssh 0회로 거절한다. ② create/start는 ssh 바로 앞에서 새 시각으로 다시 검사하고 `precheck-<단계>.txt`를 남긴다. ③ 원격에 `KSTPY`(kst.py 원문)를 함께 보낸다. ④ 조립 실패(PIPESTATUS[0])와 종료값·ok 표지 기록 실패를 비0으로 처리한다. ⑤ **gate_finalize**: 원격 rc, 판정기 rc·첫 줄, 기록 실패를 `gate-final.txt`에 따로 남긴다. 원격 비0이면 그 값 그대로, 기록 실패면 4, PASS(0·`VERDICT PASS`)만 0, HOLD 2, FAIL 1, 판정기 실패나 출력 불일치는 6이다. `tee` 없음 | [diff](diff/launch.sh.diff) |
| [smoke.sh](smoke.sh) (a0c6a18f) | ① 원격 시각 검사를 `python3 -I -c "$KSTPY"`로 바꿨다. KSTPY가 비었거나 python3이 없거나 오류가 나면 멈춘다. ② `step_create`·`step_start`는 **POST 바로 앞에서 재검사**하며, 실패하면 표지도 POST도 0이다. ③ 정지 직전 보유를 **`status IN ('OPEN','CLOSING')`**로 센다. 알 수 없는/NULL 상태 수는 따로 표시한다. 0이면 대기 종료, 1 이상이면 강제 정지로 구분한다. 읽기에 실패하면 '미확인'으로 남기고 **정지는 막지 않는다**(정지를 못 보내는 쪽이 더 위험하다). ④ `run_gate`가 psql 종료값을 돌려준다(원래는 마지막 echo 때문에 0). gate.sql 기록 실패면 멈춘다. ⑤ p0 A3 표시를 바꿨다. 아래 §3 | [diff](diff/smoke.sh.diff) |
| [vars.template](vars.template) | 바뀌지 않음(df8ba8fb) | — |

## 2. 직접 영향 검증 1묶음 (기대는 실행 전 [expected.txt](tests/expected.txt) 4894224f로 고정)

| 묶음 | 결과 | 출력 |
|---|---|---|
| K·R·F01~03 kst.py: KST 날짜 넘김, 창 시작 직전/정각, 끝 정각/끝 분 안/직후, 날짜 불일치, 창 뒤 단계 허용, 잘못된 날짜 4종, HHMM 5종, 역전, 알 수 없는 단계, 예외/인자 오류. TZ 변형 5종(UTC·LA·Seoul·Kiritimati·EST+5)과 `-c` 원문 실행 결과가 같다 | **34/34** (Windows Python 3.13 / WSL Linux Python 3.12 줄 단위 동일) | [Windows](tests/out/t_kst.txt) · [WSL](tests/out/t_kst-wsl.txt) |
| P01~07·F05 smoke 함수 블록(WSL bash): 지난 날짜, '앞 단계 통과 뒤 POST 직전 만료' 모형, 대조(통과) 사례, 실제 오늘 창, 깨진 KSTPY. api 대역 호출 수와 표지로 확인했다 | **8/8**: 거절 사례 api 0·표지 0, 통과 사례 api 1 | [출력](tests/out/t_shell-smoke-wsl.txt) |
| P08·F04·G01~09·O01~02·S01~04 launch 함수 블록(Git Bash): gate는 보존된 r3/r1 출력과 실제 v4 판정기로 확인했다 | **17/17** | [출력](tests/out/t_shell-launch.txt) |
| H01~04 보유 표시: r3 fixtures-readback 8행 재사용(PG 미실행) | **4/4** | [출력](tests/out/t_held.txt) |

gate 결과: 원격0+PASS → **0**, +HOLD → **2**, +FAIL → **1**, 원격255(연결 종료) → **255**, 원격1(부분 출력) → **1**, registration 기록 실패 → **4**, 판정기 없음 → **6**, GATE-RC 줄 없음 → **1**, gate-final 기록 실패 → **4**.

**원래 초안 반례:** 종료 처리 38~44행만 잘라([사본](tests/out/orig-launch-38-44.sh)) 실행했다. FAIL 입력에서 최종 **0**, HOLD 입력에서 최종 **0**이 나왔다. 원래 실행기가 판정을 원격 rc로 덮는다는 것이 재현됐다.

**보유 표시:** v2는 sid1(CLOSED, size 0.000199, 정상 전량 매도)=0, OPEN/CLOSING 3건=1, CLOSED 반례 2건=0이다. 원래 조건은 8건 모두 1이어서 정상 종료도 '보유 중 강제 정지'로 표시했다. HELD/NULL 2건은 별도 수로 표시한다. 게이트의 `unknown_position_status`는 그대로다.

검증 중 대역 ssh/curl/docker/psql 호출 기록은 0건이다(시험 PATH 앞에 '부르면 실패 기록' 대역을 두고, `command -v ssh`가 대역인지 확인한 뒤 진행했다).

### 시험 회차 기록 (기대는 바꾸지 않았다)

| 회차 | 결과 | 원인 / 조치 |
|---|---|---|
| t_kst r1 | K02까지 출력하고 rc1 ([기록](tests/out/r1-t_kst-note.txt)) | Windows cp949 출력에서 🔴 인코딩 예외. **kst.py 본체에도 같은 결함이 있었다**: 거절 문구 출력 때 예외가 나서 종료 3 대신 1이 된다(거절은 유지되나 계약 위반). kst.py `__main__`과 시험기에 `stdout.reconfigure(utf-8)`를 넣었다 |
| t_shell launch r1 | 11/17 ([원문](tests/out/r1-t_shell-launch.txt)) | 시험기 결함: `set -- $cs`가 경로의 공백("Claude Code")을 쪼갰다. S03은 주석 속 'zoneinfo'를, S04는 'UNKNOWN' 속 'NOW'를 셌다. 이 회차의 통과 값은 판정에 쓰지 않았다 |
| t_shell launch r2 | 16/17 ([원문](tests/out/r2-t_shell-launch.txt)) | 시험기 결함: O02의 `sid $H.reg`에 따옴표가 없어 SID가 비었다(O01도 같은 이유로 FAIL이었다. 최종 회차에서 정상 SID로 재확인) |
| t_shell smoke r2 | 실행 0건, rc2 ([원문](tests/out/r2-t_shell-smoke-wsl.txt)) | 시험기를 파이썬 텍스트 모드로 고치면서 CRLF가 됐다. LF로 되돌렸다. r1([원문](tests/out/r1-t_shell-smoke-wsl.txt) 8/8)은 수정 전 시험기다 |

최종 시험기는 [t_kst.py](tests/t_kst.py) · [t_shell.sh](tests/t_shell.sh) · [t_held.py](tests/t_held.py)다. 제품·초안 파일은 회차 사이에 kst.py 출력 인코딩 1곳만 바뀌었다.

## 3. A3는 여전히 미확정이다 (v1.249 §4)

p0의 계좌 부등식은 **필요조건**으로만 남긴다. 출력도 "표시(원 단위 반올림)"와 "필요조건 통과/미충족"으로 바꿨다. `a3-unresolved.txt`에 다음을 따로 기록하고, P0 통과 문구에도 "A3는 필요조건만 — 충분 판정 아님"을 붙인다.

| 미완료 | 상태 |
|---|---|
| availableKrw·lockedKrw 원 단위 HALF_UP 반올림 | 정확한 여유와 묶인 금액 0을 확정하지 못한다 |
| A3 시점·배정 합·변화 근거 | 미확정 |
| 실제 수수료율(코드 0.0005 가정)·최소 주문액(5,000 가정)·장부 밖 잔량 | 미확정 |

새 계좌 API·SQL·제품 코드는 만들지 않았다. 배포·인수 뒤 필요한 최소 읽기와 실행 창은 총괄의 별도 검토 대상이다.

## 4. 남은 결정 (총괄)

원문 수용 여부, 실행 날짜·창·결과 이름, 설치 SHA·이미지(B 포함판 배포 뒤), 풀 4값·배정합, A3 확정 방법. 정지 경합은 여전히 해결되지 않았다(원본 README와 같다).

## 체크섬

[SHA256SUMS](SHA256SUMS) (이 폴더 전체, 자신 제외). `* -text`로 바이트를 보존한다.
