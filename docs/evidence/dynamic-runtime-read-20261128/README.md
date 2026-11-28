# v1.260 DYNAMIC 실제 설정 최소 읽기

사용자의 운영 대리인 위임에 따라 2026-11-28 18:04:37 KST SSH1회로 운영1b719645/이미지66c4eba8·기동 시각을 대조하고 읽기 전용 SQL1회와 정확히 지정된 비밀 아닌 환경 키3개만 읽었다. rc0·stderr0, 쓰기0·생성/주문0이다. 세션/계좌/주문/인증 원문은 읽거나 반출하지 않았다.

| 항목 | 실제 저장값 | 코드에서의 처리 |
|---|---|---|
| scan_max_candidate_size | NULL | 요청 생략 시30 |
| scan_min_atr_pct | NULL | 요청 생략 시0.5 |
| scan_max_spread_pct | NULL | 요청 생략 시0.1 |
| scan_min_trade_value_krw | NULL | 품질 필터50억 원 |
| scan_max_atr_pct | NULL | 품질 필터4.0% |
| scan_require_uptrend | **false** | 명시적 false라 true 기본값을 쓰지 않음 |
| scan_exclude_crashing | **false** | 명시적 false라 true 기본값을 쓰지 않음 |
| SHARED_UNIVERSE_ENABLED/TIMEFRAME/REFRESH_MIN | 컨테이너 환경에 세 키 모두 없음 | application.yml 기본은true/H1/30 |

운영 RiskManagementService는 최신 risk_config 행을 조회하며, 이번 조회는 정확히1행임을 SQL에서 확인했다. 위 코드 해석은 **고정 제품1b719645**의 DynamicTradingService·application.yml에 근거한다. 환경 키 부재만으로 JVM 인자·다른 프로퍼티 출처까지 포함한 최종 Spring 바인딩을 증명하지는 않으므로 공유 유니버스는 source fallback/실제 binding 미확인을 구분한다.

앞 제안의 상승추세·급락 제외 true를 운영값으로 확정할 수 없다. 이번에는 전역 위험 설정을 바꾸지 않았다. 전역 변경은 기존66세션의 조건을 바꿀 수 있으므로 DYNAMIC 새 세션을 준비하려고 이를 조용히 변경하지 않는다. 고정1과 동적1의 상태를 분리한다. 고정1 시작 준비는 계속하고, 동적1은 실제 값에 맞춘 운용 조건 판단·병행 등록/감시 계약·시작/정지/성과 원천이 닫힌 뒤 진행한다.

향후 개선은 공통 설정의 출처/실효값 표시와 세션별 품질 필터 고정 가능성을 FD13의 판단 당시 자료·FD15 세션 상세 화면과 연결해 검토한다. 이번 읽기를 수익성 또는 시작 승인으로 쓰지 않는다.
