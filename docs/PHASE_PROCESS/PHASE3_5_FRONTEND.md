# Phase 3.5: Paper Trading - 프론트엔드 구현 완료

## 문서 정보
- 완료일: 2026-03-06
- 대상: Do-Frontend Phase 3 + 3.5

---

## 1. 구현 범위

| # | 항목 | 상태 |
|---|------|------|
| Phase 3 | 전략 관리 페이지 (/strategies) | 완료 |
| Phase 3.5 | 모의투자 현황 페이지 (/paper-trading) | 완료 |
| 실서버 연동 | MSW 제어 로직 분리, 핸들러 분리, API 스펙 동기화 | 완료 |

---

## 2. 추가 및 수정된 파일 (주요 경로)

- `src/lib/types.ts`: 전략 관리 및 모의투자(백엔드 스펙 반영) 관련 타입 정의
- `src/lib/api.ts`: `strategyApi`, `paperTradingApi` 추가 및 엔드포인트 연동
- `src/app/layout.tsx`: `NEXT_PUBLIC_USE_MOCK` 환경 변수를 통한 MSW 동작 제어 로직 적용
- `src/mocks/handlers.ts`: 실서버 개발 완료된 API의 모킹 핸들러 제거 (미구현인 Phase 3 설정 POST/PUT 쪽만 임시 유지)
- `src/app/strategies/page.tsx`: 전략 리스트 및 상태별 동적 UI 렌더링
- `src/components/features/strategy/StrategyConfigForm.tsx`: 전략 파라미터 설정 동적 폼 컴포넌트
- `src/app/paper-trading/page.tsx`: 자산 잔고 표기 및 거래 내역 테이블 / 포지션 모니터링 대시보드 뷰

---

## 3. 구현된 화면 및 기능

- **전략 관리 (`/strategies`)**: 현재 사용 가능한 9가지 전략 상태 목록 표시 가능.
- **모의투자 대시보드 (`/paper-trading`)**: 실시간 잔고(포지션 자산 및 가용현금) 요약 스코어보드 표기, 시작/중단/초기화 트리거, 현재 보유 중인 자산 포지션 및 전략 표시.

---

## 4. MSW → 실서버 전환 완료 항목

- [x] Phase 2 백테스팅 API 모킹 제거 (실서버 의존)
- [x] Phase 3 (GET) API 모킹 제거
- [x] Phase 3.5 (모드 모의투자) API 모킹 제거
- [x] `.env.local` 파일 추가 (NEXT_PUBLIC_USE_MOCK=false / NEXT_PUBLIC_API_URL 연결)
- [x] `layout.tsx` 조건부 MSW (환경변수 체크) 동작 변경 완료

---

## 5. 특이사항 / 설계 결정

- Phase 3에서 백엔드 미구현 상태인 **전략 설정/수정 (`POST`, `PUT`, `PATCH`) API 호출부는 임시적으로 지속 가능하도록 `src/mocks/handlers.ts` 에 MSW 핸들러를 유지**하였습니다. (Phase 4 작업 시 백엔드 연동을 다시 조율)
- 변경된 Paper Trading 백엔드 API 인터페이스 (`enteredAt -> openedAt`, `unrealizedPnlPct -> totalReturnPct` 등)에 맞게 `PaperTradingPage`의 변수 참조 및 계산식을 모두 교체 완료하였습니다.

---

## 6. 다음 단계 (Phase 4 Frontend 준비사항)

- 실 거래/자동 매매 환경 제어 화면 설계 시작 (`/trading`, `/positions`, `/orders`)
- 백엔드 미구현 상태인 전략 관리 API (POST/PUT/PATCH 완료 후 연계 테스트 수행)
- 실시간 API 통신 (SSE 혹은 단순 Polling 형태) 최적화 도입 검토.
