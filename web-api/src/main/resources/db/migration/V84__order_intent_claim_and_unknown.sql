-- 주문 의도·claim·응답 불명 (2026-10-12)
--
-- ■ 고치는 문제
--
-- 체결 응답을 받았을 때의 회계는 V81~V83 으로 맞췄다. 그런데 **응답을 잃으면** 주문은 여전히
-- `FAILED` + `exchange_order_id = NULL` 로 떨어진다. 그 상태에는 두 가지 문제가 있다:
--
--   ① **추적 단서가 없다.** UUID 는 응답에만 오므로, 응답이 유실되면 그 주문을 거래소에 물어볼
--      방법이 사라진다. 접수됐는지 아닌지 **확인할 수가 없다.**
--   ② **그 상태에서 자금·슬롯이 해제된다.** 재시작 복구가 `FAILED` 매수를 "거래소가 거절했다" 로
--      읽고 포지션을 닫고 KRW 를 복원한다 — 🔴 실제로 체결됐다면 **쓰인 돈을 되돌리는 것**이고,
--      장부에는 없는 코인을 거래소가 들고 있게 된다.
--
-- ■ 구조
--
-- 외부 호출 **전에** 의도·식별자를 커밋하고, claim 에 성공한 작업자만 한 번 제출한다.
--
--   A  트랜잭션 + 세션 행 잠금 : 한도 확인 → 주문 생성(INTENT_READY) + 식별자 커밋
--   B  트랜잭션 + 세션 행 잠금 : INTENT_READY → SUBMITTING 조건부 전환(claim). 세션 상태 재확인
--   C  **트랜잭션 밖**          : 거래소 호출. B 가 **커밋된 뒤에만** 일어난다
--   D  트랜잭션                : SUBMITTED / UNKNOWN / FAILED 기록
--
-- 🔴 **claim 전환이 1행을 돌려준 것만으로는 부족하다.** 그 트랜잭션은 이후 단계 실패·커밋 실패로
--    롤백될 수 있고, 그때 제출하면 **아무도 claim 하지 않은 주문이 외부로 나간다.** 그래서 외부
--    호출은 **커밋 이후**에 한다.
--
-- 🔴 **복구 작업자는 재제출하지 않는다.** `SUBMITTING` 을 읽은 것과 그 전환에 성공한 것은 다르다 —
--    상태는 재기동 후에도 보이지만 전환 성공은 한 번만 일어난다. 복구는 **식별자로 조회만** 한다.
--
-- ■ 정지 보장의 범위가 좁아진다 (🔴 그대로 적는다)
--
-- 종전 가드는 거래소 호출을 트랜잭션 **안에서** 하며 세션 행 잠금을 쥐고 있었으므로 "정지 커밋 후
-- 제출 0회" 가 성립했다. 외부 호출이 밖으로 나가면 **claim 커밋과 외부 호출 사이에 정지가 끼어들 수
-- 있다.** 이미 claim 된 주문이 나가는 것은 막을 방법이 없다 — 접수 가능성이 있기 때문이다.
--
--   종전: 정지 커밋 후 **제출** 0회
--   이후: 정지 커밋 후 **신규 claim** 0회 + 이미 claim 된 주문의 **추적·정리 지속**
--
-- 바꾼 이유는 추적 불가가 더 큰 위험이기 때문이다. 제출을 막지 못한 주문은 식별자로 추적되지만,
-- 추적 단서가 없는 주문은 **영구히 확인할 수 없다.** 그래서 정지는 "신규 승인 차단" 까지만 보장하고,
-- 미해결 주문이 남은 세션을 **정리 완료로 표시하지 않는다.**

ALTER TABLE "order"
    -- 우리가 제출 **전에** 정하는 식별자. 응답을 잃어도 이 값으로 조회한다.
    -- 🔴 멱등 키가 아니다 — 재사용 불가이고, 같은 값 재요청이 기존 응답을 돌려준다는 보장이 없다.
    ADD COLUMN IF NOT EXISTS client_order_id     VARCHAR(64),
    -- 의도를 커밋한 시각. 이 값이 있으면 **외부에 나갔을 가능성이 있다**고 본다.
    ADD COLUMN IF NOT EXISTS intent_committed_at TIMESTAMPTZ,
    -- claim 전환이 커밋된 시각. 🔴 이 값이 있어도 제출 성공을 뜻하지 않는다(나갔을 수 있다는 뜻).
    ADD COLUMN IF NOT EXISTS claimed_at          TIMESTAMPTZ,
    -- 식별자 조회를 몇 번 시도했는가. 영구 조회 실패를 사람이 보기 위한 값이다.
    ADD COLUMN IF NOT EXISTS unknown_probe_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS unknown_probed_at   TIMESTAMPTZ,
    -- 불명으로 판정한 근거(시간 초과 / 통신 실패 / 조회 불가). 사람이 종결할 때 읽는다.
    ADD COLUMN IF NOT EXISTS unknown_reason      VARCHAR(500);

COMMENT ON COLUMN "order".client_order_id IS
    '제출 전에 정하는 클라이언트 식별자. 응답 유실 시 유일한 추적 단서다. 멱등 키가 아니며 재사용 불가.';
COMMENT ON COLUMN "order".intent_committed_at IS
    '주문 의도와 식별자를 커밋한 시각. 이 값이 있는 주문은 외부에 나갔을 가능성을 배제하지 못한다.';
COMMENT ON COLUMN "order".claimed_at IS
    'INTENT_READY → SUBMITTING 전환이 커밋된 시각. 제출 성공이 아니라 제출 권한을 얻은 시각이다.';
COMMENT ON COLUMN "order".unknown_probe_count IS
    '식별자 조회 시도 횟수. 영구 조회 실패를 사람이 판단하기 위한 값이며 자동 종결의 근거가 아니다.';
COMMENT ON COLUMN "order".unknown_reason IS
    '불명 판정의 근거. 자동으로 FAILED 로 바꾸지 않으므로 사람이 종결할 때 이 값을 읽는다.';

-- 🔴 식별자는 계정 전체에서 고유해야 한다(업비트 제약). 로컬에서도 같은 값을 두 주문이 들고 있으면
--    조회 결과를 어느 주문에 붙일지 알 수 없으므로 DB 에서 막는다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_order_client_order_id
    ON "order" (client_order_id)
    WHERE client_order_id IS NOT NULL;

-- 복구 작업자가 추적 대상을 찾는다. 🔴 재제출용이 아니라 **조회**용이다.
CREATE INDEX IF NOT EXISTS idx_order_unresolved_submission
    ON "order" (state, unknown_probed_at)
    WHERE state IN ('INTENT_READY', 'SUBMITTING', 'UNKNOWN');
