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

/** 정지 응답(단건·일괄) — 서버는 정지를 커밋한 같은 트랜잭션에서 센 청산 대기 수를 준다 (2026-11-12, 총괄 v1.153 G). */
export interface PaperStopResult {
  id?: number;
  status?: string;
  finalAsset?: number | null;
  lastRecordedTotalKrw?: number | null;
  assetStatus?: string;
  stoppedAt?: string;
  pendingCloseCount?: number | null;
  settlement?: string;
  stoppedCount?: number;
}

/**
 * 정지 결과 문구. 대기 수가 응답에 없으면(옛 서버) 정산 완료라고 쓰지 않는다. 대기가 있으면 최종 자산 대신 마지막 기록 총자산을
 * "확정 아님"과 함께만 보인다.
 */
export function stopResultText(r: PaperStopResult | null | undefined, kind: 'single' | 'all'): string {
  if (!r) return '';
  const head = kind === 'all' ? `${r.stoppedCount ?? 0}개 세션 정지` : `세션 #${r.id ?? '?'} 정지`;
  const pending = r.pendingCloseCount;
  if (pending === null || pending === undefined) return `${head} — 정산 상태 응답 없음(정산 완료로 보지 않음)`;
  if (pending > 0) {
    const last = kind === 'single' && r.lastRecordedTotalKrw !== null && r.lastRecordedTotalKrw !== undefined
      ? ` · 마지막 기록 총자산 ${krwOrDash(r.lastRecordedTotalKrw)} KRW(확정 아님, 현재 평가 아님)` : '';
    return `${head} — 청산 대기 ${pending}건, 첫 유효 시세에 정산(정산 완료 아님)${last}`;
  }
  const fin = kind === 'single' && r.finalAsset !== null && r.finalAsset !== undefined ? ` · 최종 자산 ${krwOrDash(r.finalAsset)} KRW` : '';
  return `${head} — 정산 완료${fin}`;
}
