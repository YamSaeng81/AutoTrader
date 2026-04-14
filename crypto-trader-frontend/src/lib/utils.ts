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
