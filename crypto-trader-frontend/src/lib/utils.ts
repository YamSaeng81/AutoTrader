import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
    return twMerge(clsx(inputs));
}

/**
 * 백엔드 LocalDateTime 문자열(Z 없음)을 UTC로 강제 파싱한다.
 * Instant(Z 포함)는 그대로 통과. 모든 백엔드 타임스탬프 파싱에 사용할 것.
 */
export function parseUtc(dt: string | null | undefined): Date | null {
    if (!dt) return null;
    const s = dt.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(dt) ? dt : dt + 'Z';
    return new Date(s);
}

/** UTC 타임스탬프 문자열을 KST MM/DD HH:mm:ss 형태로 포맷 */
export function fmtKst(dt: string | null | undefined): string {
    const d = parseUtc(dt);
    if (!d || isNaN(d.getTime())) return '-';
    const kst = new Date(d.getTime() + 9 * 60 * 60 * 1000);
    const mm  = String(kst.getUTCMonth() + 1).padStart(2, '0');
    const dd  = String(kst.getUTCDate()).padStart(2, '0');
    const hh  = String(kst.getUTCHours()).padStart(2, '0');
    const min = String(kst.getUTCMinutes()).padStart(2, '0');
    const ss  = String(kst.getUTCSeconds()).padStart(2, '0');
    return `${mm}/${dd} ${hh}:${min}:${ss}`;
}

/** UTC 타임스탬프 문자열을 KST 로케일 문자열로 포맷 (toLocaleString 대체) */
export function fmtKstLocale(dt: string | null | undefined): string {
    const d = parseUtc(dt);
    if (!d || isNaN(d.getTime())) return '-';
    return d.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' });
}

/**
 * 주문 한 건의 "수량" 표기 — `order.quantity` 는 단위가 두 가지라 그대로 찍으면 안 된다.
 *
 * 시장가 매수(MARKET+BUY)는 Upbit `price` 타입 주문이라 quantity 컬럼에 **KRW 총액**이,
 * 그 외(시장가 매도·지정가)에는 **코인 수량**이 들어간다. 백엔드
 * `OrderAmounts`(web-api/util) 와 같은 판정 규칙이다.
 *
 * 시장가 매수는 체결 전이면 코인 수량을 알 수 없으므로 `filledQuantity` 가 채워지기 전까지
 * "N KRW" 로 표기한다.
 */
export function fmtOrderQuantity(order: {
    orderType?: string | null;
    side?: string | null;
    quantity?: number | null;
    filledQuantity?: number | null;
}): string {
    const qty = Number(order.quantity ?? 0);
    if (order.orderType === 'MARKET' && order.side === 'BUY') {
        const filled = Number(order.filledQuantity ?? 0);
        return filled > 0 ? filled.toFixed(6) : `${qty.toLocaleString()} KRW`;
    }
    return qty.toFixed(6);
}

/** 주문 한 건에 투입된 KRW 금액. 시장가 매수는 quantity 자체가 KRW다. */
export function orderKrwAmount(order: {
    orderType?: string | null;
    side?: string | null;
    price?: number | null;
    quantity?: number | null;
}): number {
    const qty = Number(order.quantity ?? 0);
    if (order.orderType === 'MARKET' && order.side === 'BUY') return qty;
    return Number(order.price ?? 0) * qty;
}
