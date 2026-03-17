'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import {
    LineChart, PlusCircle, Database, TrendingUp, Zap,
    Shield, LayoutDashboard, GitCompare, FileText, History,
    Moon, Sun, FlaskConical, ChevronLeft, ChevronRight,
    Wallet, Settings, ChevronDown, ChevronUp,
    BarChart2, MessageSquare, Activity,
} from 'lucide-react';
import { useTheme } from './ThemeProvider';
import { useUiStore } from '@/store';
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: (string | undefined | null | false)[]) {
    return twMerge(clsx(inputs));
}

interface NavItem {
    href: string;
    label: string;
    icon: React.ComponentType<{ className?: string }>;
    excludePrefix?: string;
}

interface NavGroup {
    label: string;
    icon: React.ComponentType<{ className?: string }>;
    items: NavItem[];
}

const navGroups: NavGroup[] = [
    {
        label: '백테스트',
        icon: LineChart,
        items: [
            { href: '/backtest',              label: '백테스트 이력',  icon: History },
            { href: '/backtest/new',           label: '새 백테스트',   icon: PlusCircle },
            { href: '/backtest/compare',       label: '전략 비교',     icon: GitCompare },
            { href: '/data',                   label: '데이터 수집',   icon: Database },
        ],
    },
    {
        label: '전략관리',
        icon: BarChart2,
        items: [
            { href: '/backtest/walk-forward',  label: 'Walk Forward',  icon: FlaskConical },
            { href: '/strategies',             label: '전략 관리',     icon: Settings },
        ],
    },
    {
        label: '모의투자',
        icon: TrendingUp,
        items: [
            { href: '/paper-trading',         label: '모의투자',      icon: TrendingUp, excludePrefix: '/paper-trading/history' },
            { href: '/paper-trading/history', label: '모의투자 이력', icon: History },
        ],
    },
    {
        label: '실전매매',
        icon: Zap,
        items: [
            { href: '/trading',         label: '실전 매매',     icon: Zap,     excludePrefix: '/trading/history' },
            { href: '/trading/history', label: '실전매매 이력', icon: History },
            { href: '/trading/risk',    label: '리스크 설정',   icon: Shield },
            { href: '/account',         label: '계좌 현황',     icon: Wallet },
        ],
    },
    {
        label: '설정',
        icon: Settings,
        items: [
            { href: '/settings/telegram',    label: '텔레그램 이력',  icon: MessageSquare },
            { href: '/settings/upbit-logs',  label: 'Upbit 주문 로그', icon: Activity },
            { href: '/logs',                 label: '시스템 로그',    icon: FileText },
        ],
    },
];

export function Sidebar() {
    const pathname = usePathname();
    const { theme, toggle } = useTheme();
    const { sidebarCollapsed, toggleSidebar } = useUiStore();

    // 현재 경로가 속한 그룹을 기본으로 열어둠
    const initialOpen = () => {
        const set = new Set<string>();
        for (const group of navGroups) {
            for (const item of group.items) {
                const active = (pathname === item.href || (item.href !== '/' && pathname.startsWith(item.href)))
                    && !(item.excludePrefix && pathname.startsWith(item.excludePrefix));
                if (active) set.add(group.label);
            }
        }
        // 아무 그룹도 매칭 안 되면 첫 번째 그룹 열기
        if (set.size === 0) set.add(navGroups[0].label);
        return set;
    };

    const [openGroups, setOpenGroups] = useState<Set<string>>(initialOpen);

    const toggleGroup = (label: string) => {
        setOpenGroups(prev => {
            const next = new Set(prev);
            if (next.has(label)) next.delete(label);
            else next.add(label);
            return next;
        });
    };

    const isItemActive = (item: NavItem) =>
        (pathname === item.href || (item.href !== '/' && pathname.startsWith(item.href)))
        && !(item.excludePrefix && pathname.startsWith(item.excludePrefix));

    return (
        <div
            className={cn(
                "bg-slate-900 border-r border-slate-800 text-slate-100 flex flex-col h-screen fixed shadow-xl transition-all duration-300",
                sidebarCollapsed ? "w-16" : "w-64"
            )}
        >
            {/* 로고 */}
            <div className={cn("px-4 py-6 flex items-center", sidebarCollapsed ? "justify-center" : "gap-3 px-6")}>
                <div className="w-8 h-8 rounded-lg bg-indigo-500 flex items-center justify-center shadow-lg shadow-indigo-500/30 shrink-0">
                    <LineChart className="w-5 h-5 text-white" />
                </div>
                {!sidebarCollapsed && (
                    <div className="overflow-hidden">
                        <h1 className="text-xl font-bold tracking-tight text-white whitespace-nowrap">Crypto Trader</h1>
                    </div>
                )}
            </div>

            {/* 대시보드 단독 항목 */}
            <div className="px-2 mb-1">
                <Link
                    href="/"
                    title={sidebarCollapsed ? '대시보드' : undefined}
                    className={cn(
                        "flex items-center rounded-lg transition-all duration-200 text-sm font-medium border border-transparent",
                        sidebarCollapsed ? "justify-center px-3 py-3" : "gap-3 px-3.5 py-3",
                        pathname === '/'
                            ? "bg-indigo-600/10 text-indigo-400 border-indigo-500/20"
                            : "text-slate-400 hover:bg-slate-800/50 hover:text-slate-200"
                    )}
                >
                    <LayoutDashboard className={cn("w-5 h-5 shrink-0", pathname === '/' ? "text-indigo-400" : "text-slate-500")} />
                    {!sidebarCollapsed && <span>대시보드</span>}
                </Link>
            </div>

            {/* 그룹 네비게이션 */}
            <nav className="flex-1 px-2 space-y-0.5 overflow-y-auto">
                {navGroups.map((group) => {
                    const GroupIcon = group.icon;
                    const isOpen = openGroups.has(group.label);
                    const hasActive = group.items.some(isItemActive);

                    if (sidebarCollapsed) {
                        // 접힌 상태: 아이콘만, 모든 항목 툴팁으로 표시
                        return (
                            <div key={group.label} className="space-y-0.5">
                                {group.items.map(item => {
                                    const Icon = item.icon;
                                    const active = isItemActive(item);
                                    return (
                                        <Link
                                            key={item.href}
                                            href={item.href}
                                            title={item.label}
                                            className={cn(
                                                "flex justify-center items-center rounded-lg px-3 py-3 transition-all duration-200 text-sm font-medium border border-transparent",
                                                active
                                                    ? "bg-indigo-600/10 text-indigo-400 border-indigo-500/20"
                                                    : "text-slate-400 hover:bg-slate-800/50 hover:text-slate-200"
                                            )}
                                        >
                                            <Icon className={cn("w-5 h-5 shrink-0", active ? "text-indigo-400" : "text-slate-500")} />
                                        </Link>
                                    );
                                })}
                            </div>
                        );
                    }

                    return (
                        <div key={group.label}>
                            {/* 그룹 헤더 */}
                            <button
                                onClick={() => toggleGroup(group.label)}
                                className={cn(
                                    "w-full flex items-center gap-2 px-3 py-2 rounded-lg text-xs font-semibold uppercase tracking-wider transition-colors",
                                    hasActive ? "text-indigo-400" : "text-slate-500 hover:text-slate-300"
                                )}
                            >
                                <GroupIcon className="w-3.5 h-3.5 shrink-0" />
                                <span className="flex-1 text-left">{group.label}</span>
                                {isOpen
                                    ? <ChevronUp className="w-3 h-3" />
                                    : <ChevronDown className="w-3 h-3" />
                                }
                            </button>

                            {/* 그룹 아이템 */}
                            {isOpen && (
                                <div className="ml-2 pl-2 border-l border-slate-700/50 space-y-0.5 mb-1">
                                    {group.items.map(item => {
                                        const Icon = item.icon;
                                        const active = isItemActive(item);
                                        return (
                                            <Link
                                                key={item.href}
                                                href={item.href}
                                                className={cn(
                                                    "flex items-center gap-3 rounded-lg px-3 py-2.5 transition-all duration-200 text-sm font-medium border border-transparent",
                                                    active
                                                        ? "bg-indigo-600/10 text-indigo-400 border-indigo-500/20"
                                                        : "text-slate-400 hover:bg-slate-800/50 hover:text-slate-200"
                                                )}
                                            >
                                                <Icon className={cn("w-4 h-4 shrink-0", active ? "text-indigo-400" : "text-slate-500")} />
                                                <span>{item.label}</span>
                                            </Link>
                                        );
                                    })}
                                </div>
                            )}
                        </div>
                    );
                })}
            </nav>

            {/* 하단 */}
            <div className={cn(
                "px-2 py-4 border-t border-slate-800 flex items-center",
                sidebarCollapsed ? "flex-col gap-2" : "justify-between px-4"
            )}>
                {!sidebarCollapsed && (
                    <span className="text-xs text-slate-500 font-medium">v0.3.0</span>
                )}
                <button
                    onClick={toggle}
                    className="p-2 rounded-lg text-slate-400 hover:bg-slate-800 hover:text-slate-200 transition-colors"
                    title={theme === 'dark' ? '라이트 모드' : '다크 모드'}
                >
                    {theme === 'dark' ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
                </button>
                <button
                    onClick={toggleSidebar}
                    className="p-2 rounded-lg text-slate-400 hover:bg-slate-800 hover:text-slate-200 transition-colors"
                    title={sidebarCollapsed ? '사이드바 펼치기' : '사이드바 접기'}
                >
                    {sidebarCollapsed
                        ? <ChevronRight className="w-4 h-4" />
                        : <ChevronLeft className="w-4 h-4" />
                    }
                </button>
            </div>
        </div>
    );
}
