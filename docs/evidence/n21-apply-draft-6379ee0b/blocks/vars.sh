# N21 적용안 변수 — 초안(총괄 v1.138 §106-3). 서버 실행은 미승인이다.
# 운영 서버 셸에서 이 변수부터 넣고 A0 → A1 → (A1 뒤 첫 :19 회차가 끝난 다음) A2 순서로 쓴다. A1-되돌림은 운영자 판단 때만.
NB=21
cd ~/crypto_trader/AutoTrader
OLD=1899b49f633f8aefdc66b15c42debdaba8b3852c          # 현재 운영 HEAD
MID=99bd4eaadb0ff3037518c3cacbf6bb98de91bf4e          # 실패 시험만 추가한 커밋
NEW=6379ee0b2a7527b8136d7fba27e83881980d8555          # N21 후보(총괄 v1.138 로컬 수용)
F=scripts/prospective/start_66.py
T=scripts/prospective/test_freshness_recheck_fail.py   # NEW 에서 새로 추적되는 시험 파일 — 서버에서 실행하지 않는다
BOLD=460031d30ce488f4604be6eb6aea78c10db50caf         # OLD 의 start_66.py 블롭
BNEW=6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d         # NEW 의 start_66.py 블롭
HOLD=adca8829b9fb6b4c6996dc853fadade58d685d9c0c044135eb6f92665dba337d   # OLD start_66.py 파일 sha256
HNEW=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b   # NEW start_66.py 파일 sha256
IMG=sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1   # backend 이미지 — 바뀌면 안 된다(재빌드·재기동 없음)
CRONH=f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b      # crontab -l 원문 sha256 — 바꾸지 않는다
DC="docker compose -f docker-compose.prod.yml"
M=~/maint-n21; mkdir -p "$M"
