// 모의투자 평가 상태 표시 (2026-11-11, 총괄 v1.150 §3-4) — 청산 대기(CLOSING, 유효 시세 없음)일 때 서버는 총자산·평가액·손익·수익률을
// null 로 준다. 화면은 null 을 0 / 0.00% 로 바꾸지 않고 "평가 불가"로 보인다. 마지막 기록값은 현재 평가가 아니라는 표시와 함께만 보인다.

export const PENDING_VALUATION_LABEL = '평가 불가 — 청산 대기';

export interface ValuationFields {
  pendingCloseCount?: number | null;
  valuationStatus?: string | null;
  lastRecordedTotalKrw?: number | null;
  lastRecordedNote?: string | null;
}

export function isPendingValuation(v?: ValuationFields | null): boolean {
  return (v?.pendingCloseCount ?? 0) > 0;
}

/** null·undefined 는 '-' — 0 으로 바꾸지 않는다. */
export function krwOrDash(v: number | string | null | undefined): string {
  return v === null || v === undefined ? '-' : Number(v).toLocaleString();
}

/** 수익률 — null 이면 null(호출 쪽이 "평가 불가"로 표시). */
export function pctOrNull(v: number | string | null | undefined): number | null {
  return v === null || v === undefined ? null : Number(v);
}

/** 포지션 상태 이름 — CLOSING 은 청산 대기(미정산 보유)로 보인다. */
export function positionStatusLabel(status?: string | null, reason?: string | null): string {
  if (status === 'CLOSING') return `청산 대기${reason ? ` (${reason})` : ''} — 미정산 보유`;
  if (status === 'CLOSED') return '청산 완료';
  return '보유';
}
