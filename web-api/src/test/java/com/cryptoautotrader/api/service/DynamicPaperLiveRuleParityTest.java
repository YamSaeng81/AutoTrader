package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>DYNAMIC 의 PAPER 와 REAL 은 매매 규칙이 완전히 같아야 한다.</b>
 *
 * <h3>왜 이 테스트가 있나</h3>
 * <p>페이퍼를 돌리는 이유는 <b>실전에서 어떨지 비용 없이 미리 보는 것</b>이다. 체결 결과에
 * 슬리피지 오차가 있는 것과, <b>매매 규칙 자체가 다른 것</b>은 완전히 다른 문제다. 규칙이
 * 하나라도 다르면 페이퍼 성과는 실전을 예측하지 못하고, 그 순간 페이퍼를 돌리는 의미가 사라진다.</p>
 *
 * <p>이 원칙은 이미 코드베이스에 있다 — {@code PaperLiveAlignmentTest} 가 고정코인 페이퍼와
 * LIVE 의 규칙 일치를 강제하고, 2026-08-06 에 "페이퍼를 LIVE 와 동일 로직으로 정렬 완료 —
 * 이제 페이퍼 결과가 실전 예측에 유효하다"고 기록했다.</p>
 *
 * <h3>그런데 DYNAMIC 에서 세 번 깨져 있었다</h3>
 * <ul>
 *   <li><b>WF 게이트</b> (2026-08-25) — {@code !session.isPaper()} 로 걸어 페이퍼가 미검증
 *       코인을 사게 했다. 근거는 "페이퍼는 탐색 도구"였는데, <b>탐색은 Walk Forward 가 하는
 *       일</b>이고 페이퍼는 실전 리허설이다. 역할을 섞은 것이다.
 *       08-30 실측에서 미검증 코인은 탐색이 아니라 그냥 손실이었다
 *       (KRW-RE −5.85% · KRW-ONT −5.74% · KRW-BEAM −5.48%). → 08-31 철회.</li>
 *   <li><b>노출 상한</b> (2026-08-25) — 같은 이유로 면제했다가 실현손익으로 08-30 철회.</li>
 *   <li><b>WS 실시간 SL/TP 감시</b> — LIVE 는 5초마다 보는데 PAPER 는 60초 폴링만 했다.
 *       급락장에서 <b>손절 체결가가 갈린다</b>(페이퍼가 더 나쁘게 나오는 보수적 방향이지만
 *       어쨌든 규칙이 다르다). → 08-31 해소.</li>
 * </ul>
 *
 * <p>셋 다 "페이퍼니까 좀 다르게 해도 된다"는 판단에서 나왔고, 셋 다 뒤집혔다.
 * 이 테스트는 그 판단이 다시 코드에 들어오는 것을 막는다.</p>
 *
 * <h3>무엇이 허용되나</h3>
 * <p>{@code isPaper()} 분기 자체가 금지는 아니다. <b>체결 시뮬레이션</b>(주문을 거래소에
 * 보내는 대신 슬리피지·수수료로 계산)과 <b>집계·저장 분리</b>({@code session_kind}, 지문,
 * 알파 집계 제외)는 페이퍼의 정의상 필요하다. 금지되는 것은 <b>매수/매도 여부를 가르는
 * 게이트</b>에 페이퍼 예외를 두는 것이다.</p>
 */
class DynamicPaperLiveRuleParityTest {

    private static final Path SERVICE = Path.of(
            "src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java");

    /**
     * 체결 시뮬레이션·집계 분리처럼 <b>정당한</b> 페이퍼 분기. 여기 없는 새 분기가 생기면
     * 테스트가 실패하고, 그때 "이게 매매 규칙인가 체결/집계인가"를 사람이 판단하게 된다.
     */
    private static final List<String> ALLOWED = List.of(
            "executePaperBuy",          // 체결 시뮬레이션 (매수)
            "executePaperSell",         // 체결 시뮬레이션 (매도) — 일반·강제정지 경로
            "SESSION_KIND_PAPER");      // position/order 저장 분리 (sessionKind 헬퍼)

    private static String source() throws IOException {
        return Files.readString(SERVICE, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("🔴 WF 게이트에 페이퍼 예외가 없다 — 08-25 면제를 08-31 철회했다")
    void walkForwardGateHasNoPaperExemption() throws IOException {
        String src = source();
        int gate = src.indexOf("walkForwardValidationGate.isEnabled()");
        assertThat(gate).as("WF 게이트 호출부를 찾지 못했다 — 테스트를 갱신할 것").isPositive();

        // 게이트 조건식 앞 400자에 isPaper 분기가 있으면 안 된다
        String around = src.substring(Math.max(0, gate - 400), gate);
        assertThat(around)
                .as("WF 게이트에 페이퍼 예외가 다시 들어왔다 — 페이퍼가 미검증 코인을 사면 "
                        + "실전 예측력이 사라진다(08-30 실측: 미검증 코인 −5%대 손실)")
                .doesNotContain("isPaper()");
    }

    @Test
    @DisplayName("🔴 노출 상한에 페이퍼 예외가 없다 — 08-25 면제를 08-30 철회했다")
    void exposureCapHasNoPaperExemption() throws IOException {
        String src = source();
        int call = src.indexOf("crossSessionExposureBlockReason(");
        assertThat(call).isPositive();

        String around = src.substring(Math.max(0, call - 400), call);
        assertThat(around)
                .as("노출 상한에 페이퍼 예외가 다시 들어왔다 — 08-30 실현손익에서 3세션 이상 "
                        + "집중이 −1.33~−2.13%였다")
                .doesNotContain("isPaper()");
    }

    @Test
    @DisplayName("페이퍼 분기가 허용 목록 밖으로 늘어나지 않는다 — 새 분기는 사람이 판단할 것")
    void paperBranchesStayWithinAllowlist() throws IOException {
        String src = source();
        List<String> unexpected = new ArrayList<>();

        Matcher m = Pattern.compile("isPaper\\(\\)").matcher(src);
        while (m.find()) {
            // 분기 전후 문맥을 보고 허용 목록에 걸리는지 확인
            String ctx = src.substring(Math.max(0, m.start() - 250),
                    Math.min(src.length(), m.end() + 250));
            boolean ok = ALLOWED.stream().anyMatch(ctx::contains)
                    // 엔티티 정의·주석 참조는 대상이 아니다
                    || ctx.contains("sessionKind(DynamicSessionEntity")
                    || ctx.contains("* ");
            if (!ok) {
                int line = (int) src.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                unexpected.add("line " + line);
            }
        }

        assertThat(unexpected)
                .as("허용되지 않은 페이퍼 분기가 생겼다. 체결 시뮬레이션/집계 분리면 ALLOWED 에 "
                        + "추가하고, 매수·매도 여부를 가르는 규칙이면 제거할 것 — "
                        + "규칙이 다르면 페이퍼가 실전을 예측하지 못한다")
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 WS 실시간 SL/TP 감시가 페이퍼에도 열려 있다 — 08-31 해소")
    void realtimeSlTpMonitoringCoversPaper() throws IOException {
        String src = source();

        // ① 구독 대상 산정에서 페이퍼를 빼지 않는다
        int sub = src.indexOf("private void refreshWsSubscription()");
        assertThat(sub).isPositive();
        String subBody = src.substring(sub, Math.min(src.length(), sub + 1400));
        assertThat(subBody)
                .as("페이퍼가 WS 구독에서 빠지면 SL/TP 를 60초 폴링으로만 보게 되어 "
                        + "급락장에서 손절 체결가가 실전과 갈린다")
                .doesNotContain("!s.isPaper()");

        // ② 실시간 이벤트 핸들러가 페이퍼를 건너뛰지 않는다
        int handler = src.indexOf("doOnRealtimePriceEvent(RealtimePriceEvent");
        assertThat(handler).isPositive();
        String handlerBody = src.substring(handler, Math.min(src.length(), handler + 2600));
        assertThat(handlerBody)
                .as("실시간 핸들러가 페이퍼를 건너뛰면 WS 구독을 해도 판정이 안 돈다")
                .doesNotContain("session.isPaper()) continue");

        // ③ 실시간 핸들러는 세션의 실제 kind 로 포지션을 찾아야 한다
        //    (SESSION_KIND 하드코딩이면 페이퍼 포지션을 못 찾아 조용히 감시가 빈다)
        assertThat(handlerBody)
                .as("페이퍼 포지션은 session_kind='DYN_PAPER' 라 SESSION_KIND 로는 안 잡힌다")
                .contains("sessionKind(session)");
    }

    @Test
    @DisplayName("SL 미점검 워치독이 페이퍼도 감시한다 — 감시가 멈추면 실전 예측력이 사라진다")
    void slWatchdogCoversPaper() throws IOException {
        String src = source();
        int wd = src.indexOf("public void warnStaleSlCheck()");
        assertThat(wd).isPositive();

        String body = src.substring(wd, Math.min(src.length(), wd + 700));
        assertThat(body)
                .as("페이퍼를 워치독에서 빼면 SL 감시가 멈춰도 아무도 모른다")
                .doesNotContain("!s.isPaper()");
    }
}
