import {
    LineChart, PlusCircle, Database, TrendingUp, Zap,
    Shield, GitCompare, FileText, History,
    FlaskConical, Wallet, Settings, Clock,
    BarChart2, MessageSquare, Activity, Trash2, Terminal, PieChart,
    Bot, Newspaper, BookOpen, MessagesSquare, SlidersHorizontal, HeartPulse,
} from 'lucide-react';

export interface NavItem {
    href: string;
    label: string;
    icon: React.ComponentType<{ className?: string }>;
    /** '|' 로 구분된 접두사들. 현재 경로가 이 중 하나로 시작하면 활성 판정에서 제외한다. */
    excludePrefix?: string;
}

export interface NavGroup {
    /** 그룹 식별자 겸 데스크톱 사이드바 라벨 */
    label: string;
    /** 모바일 하단 탭에 쓰는 짧은 라벨 (없으면 label) */
    shortLabel?: string;
    icon: React.ComponentType<{ className?: string }>;
    items: NavItem[];
}

/**
 * 5개 대분류. 순서 = 사용 빈도(검증 → 실전 → 튜닝 → 사후분석 → 운영설정).
 * 모바일 하단 탭 바도 이 순서를 그대로 쓴다.
 */
export const navGroups: NavGroup[] = [
    {
        label: '백테스트 · 모의투자',
        shortLabel: '검증',
        icon: FlaskConical,
        items: [
            { href: '/backtest',              label: '백테스트 이력',  icon: History, excludePrefix: '/backtest/new|/backtest/compare|/backtest/walk-forward|/backtest/scheduler' },
            { href: '/backtest/new',          label: '새 백테스트',    icon: PlusCircle },
            { href: '/backtest/compare',      label: '전략 비교',      icon: GitCompare },
            { href: '/paper-trading',         label: '모의투자',       icon: TrendingUp, excludePrefix: '/paper-trading/history' },
            { href: '/paper-trading/history', label: '모의투자 이력',  icon: History },
            { href: '/data',                  label: '데이터 수집',    icon: Database },
        ],
    },
    {
        label: '실전매매',
        shortLabel: '실전',
        icon: Zap,
        items: [
            { href: '/trading',         label: '실전 매매',     icon: Zap,     excludePrefix: '/trading/history|/trading/dynamic|/trading/risk' },
            { href: '/trading/dynamic', label: '동적 멀티코인', icon: Bot },
            { href: '/trading/history', label: '실전매매 이력', icon: History },
            { href: '/account',         label: '계좌 현황',     icon: Wallet },
            { href: '/trading/risk',    label: '리스크 설정',   icon: Shield },
        ],
    },
    {
        label: '전략관리',
        shortLabel: '전략',
        icon: SlidersHorizontal,
        items: [
            { href: '/strategies',            label: '전략 관리',    icon: Settings },
            { href: '/backtest/walk-forward', label: 'Walk Forward', icon: FlaskConical },
            { href: '/backtest/scheduler',    label: '자동 스케줄',  icon: Clock },
            { href: '/admin/llm-config',      label: 'LLM 전략 설정', icon: Bot },
            { href: '/admin/news-sources',    label: '뉴스 소스',    icon: Newspaper },
        ],
    },
    {
        label: '분석',
        shortLabel: '분석',
        icon: BarChart2,
        items: [
            { href: '/performance',         label: '손익 대시보드',  icon: PieChart },
            { href: '/logs/signal-quality', label: '신호 품질 분석', icon: BarChart2 },
            { href: '/logs',                label: '전략 로그',      icon: FileText, excludePrefix: '/logs/signal-quality|/logs/llm' },
            { href: '/logs/llm',            label: 'LLM 호출 로그',  icon: Bot },
            { href: '/admin/reports',       label: 'Notion 보고서',  icon: BookOpen },
        ],
    },
    {
        label: '설정',
        shortLabel: '설정',
        icon: Settings,
        items: [
            { href: '/settings/upbit-status', label: 'Upbit 연동 상태', icon: Activity },
            { href: '/settings/upbit-logs',   label: 'Upbit 주문 로그', icon: Activity },
            { href: '/settings/telegram',     label: '텔레그램 이력',   icon: MessageSquare },
            { href: '/admin/discord',         label: 'Discord 설정',    icon: MessagesSquare },
            { href: '/admin/health-check',    label: '헬스체크 이력',   icon: HeartPulse },
            { href: '/settings/server-logs',  label: '서버 로그',       icon: Terminal },
            { href: '/settings/db-reset',     label: 'DB 초기화',       icon: Trash2 },
        ],
    },
];

export const DASHBOARD_ITEM: NavItem = { href: '/', label: '대시보드', icon: LineChart };

/** 현재 경로가 해당 메뉴 항목에 해당하는지 */
export function isItemActive(item: NavItem, pathname: string): boolean {
    const excluded = item.excludePrefix?.split('|').some(p => pathname.startsWith(p.trim())) ?? false;
    if (excluded) return false;
    if (item.href === '/') return pathname === '/';
    return pathname === item.href || pathname.startsWith(item.href + '/');
}

/** 현재 경로가 속한 그룹 (없으면 null — 대시보드 등) */
export function activeGroup(pathname: string): NavGroup | null {
    return navGroups.find(g => g.items.some(i => isItemActive(i, pathname))) ?? null;
}
