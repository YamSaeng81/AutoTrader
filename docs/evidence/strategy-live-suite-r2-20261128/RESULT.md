# v1.262 짧은 전략별 실운영 점검 완료

서버 배치는11-28 18:59:20 KST 시작, **19:06:40 KST COMPLETE**로 종료됐다. 총괄19:09에 전체 결과를 읽었다. 후보11개 중 비활성7개는 생성 없이 건너뛰었고 허용4개만 순차 생성·시작·자연 판단·정지했다. 전역 활성/위험 설정·제품/이미지·SID203은 바꾸지 않았다.

| 전략 | LIVE ID | 실제 판단 | 주문 | 종료 |
|---|---:|---|---:|---|
| MOMENTUM_ICHIMOKU_V2 |206|HOLD, buy0/sell0.15 점수 미달|0|STOPPED·gate PASS·현금 불변·BTC0|
| MOMENTUM_ICHIMOKU |207|HOLD, buy0/sell0.21 점수 미달|0|STOPPED·gate PASS·현금 불변·BTC0|
| MTF_BTC |208|HOLD, buy0/sell0.08 점수 미달|0|STOPPED·gate PASS·현금 불변·BTC0|
| MTF_MOMENTUM |209|SELL, H4 SELL·보유 포지션 없음|0|STOPPED·gate PASS·현금 불변·BTC0|

각 전략은 전체 이름 앞 COMPOSITE_를 포함한다. 비활성: BREAKOUT, REGIME_ROUTER, SUPERTREND, RSI, BOLLINGER, ATR_BREAKOUT, MOMENTUM. 실제 DB false를 임의로 true로 바꾸지 않았다. 처음 배치의 BREAKOUT 생성 거절은 신규 세션/주문0으로 따로 보존했다.

원가 추가0원. **현재 누적 매수대금+매수수수료9,954.18210375원**, 상한50,000원, 미사용40,045.81789625원이다. 매도 대금을 누적 한도에 되더하지 않는다. 앞 실제 왕복 순손익−10.19550429원은 별도11월 운영 시험 비용이다.

네 로그의 규칙 지문은8b624d720e93으로 같고 원문 SHA/12자리 일치·maxEntries1 포함을 확인했다. 고정 RulesetRegistry.hashFor(LIVE)는 공통 엔진/위험/세션 파라미터를 해시하고 전략 이름 자체는 넣지 않으므로 같은 지문만으로 전략을 묶지 않는다. 분석에는 코드·세션 종류·전략·코인·주기·지문·세션 ID를 함께 사용한다. 지문을 바꾸는 제품 변경은 하지 않았다.

본 결과는 실제 생성/시작·판단·무거래 정지/대조 확인이다. **전략별 실체결·자연 매도·수익성은 미검증**이다. 시장 신호를 바꾸거나 가짜 매수/이력을 넣지 않았다. 다음 다른 시간대 관측은 [야간 고정 실행안](../strategy-live-night-20261128/README.md)으로 분리한다. 첫 snapshot 진단 SQL 실패는 열명 수정 전 실패 그대로, 기존31경계/새39경계는 각 실행 결과로 남긴다.
