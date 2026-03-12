'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { LineChart, PlusCircle, Settings, Database, TrendingUp, Zap, Briefcase, List, Shield, LayoutDashboard, GitCompare, FileText, History, Moon, Sun, FlaskConical, ChevronLeft, ChevronRight } from 'lucide-react';
import { useTheme } from './ThemeProvider';
import { useUiStore } from '@/store';
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: (string | undefined | null | false)[]) {
    return twMerge(clsx(inputs));
}

const navItems = [
    // Phase 2
    { href: '/', label: '대시보드', icon: LayoutDashboard, phase: 2 },
    { href: '/backtest', label: '백테스트 이력', icon: LineChart, phase: 2 },
    { href: '/backtest/new', label: '새 백테스트', icon: PlusCircle, phase: 2 },
    { href: '/backtest/compare', label: '전략 비교', icon: GitCompare, phase: 2 },
    { href: '/backtest/walk-forward', label: 'Walk Forward', icon: FlaskConical, phase: 2 },
    { href: '/data', label: '데이터 수집', icon: Database, phase: 2 },
    { href: '/logs', label: '로그', icon: FileText, phase: 2 },
    // Phase 3
    { href: '/strategies', label: '전략 관리', icon: Settings, phase: 3 },
    // Phase 3.5
    { href: '/paper-trading', label: '모의투자', icon: TrendingUp, phase: 3.5, excludePrefix: '/paper-trading/history' },
    { href: '/paper-trading/history', label: '모의투자 이력', icon: History, phase: 3.5 },
    // Phase 4
    { href: '/trading', label: '실전 매매', icon: Zap, phase: 4 },
    { href: '/trading/risk', label: '리스크 설정', icon: Shield, phase: 4 },
];

export function Sidebar() {
    const pathname = usePathname();
    const { theme, toggle } = useTheme();
    const { sidebarCollapsed, toggleSidebar } = useUiStore();

    return (
        <div
            className={cn(
                "bg-slate-900 border-r border-slate-800 text-slate-100 flex flex-col h-screen fixed shadow-xl transition-all duration-300",
                sidebarCollapsed ? "w-16" : "w-64"
            )}
        >
            {/* 로고 영역 */}
            <div className={cn("px-4 py-8 flex items-center", sidebarCollapsed ? "justify-center" : "gap-3 px-6")}>
                <div className="w-8 h-8 rounded-lg bg-indigo-500 flex items-center justify-center shadow-lg shadow-indigo-500/30 shrink-0">
                    <LineChart className="w-5 h-5 text-white" />
                </div>
                {!sidebarCollapsed && (
                    <div className="overflow-hidden">
                        <h1 className="text-xl font-bold tracking-tight text-white whitespace-nowrap">Crypto Trader</h1>
                    </div>
                )}
            </div>

            {/* 네비게이션 */}
            <nav className="flex-1 px-2 space-y-1.5 mt-2 overflow-y-auto">
                {navItems.map((item) => {
                    const Icon = item.icon;
                    const isActive = (pathname === item.href || (item.href !== '/' && pathname.startsWith(item.href)))
                        && !((item as any).excludePrefix && pathname.startsWith((item as any).excludePrefix));

                    if (item.disabled) {
                        return (
                            <div
                                key={item.href}
                                className={cn(
                                    "flex items-center rounded-lg text-sm font-medium border border-transparent text-slate-500/50 cursor-not-allowed",
                                    sidebarCollapsed ? "justify-center px-3 py-3" : "gap-3 px-3.5 py-3"
                                )}
                                title={sidebarCollapsed ? item.label : "향후 업데이트 지원 예정 기능입니다."}
                            >
                                <Icon className="w-5 h-5 opacity-50 shrink-0" />
                                {!sidebarCollapsed && <span>{item.label}</span>}
                            </div>
                        );
                    }

                    return (
                        <Link
                            key={item.href}
                            href={item.href}
                            title={sidebarCollapsed ? item.label : undefined}
                            className={cn(
                                "flex items-center rounded-lg transition-all duration-200 text-sm font-medium border border-transparent",
                                sidebarCollapsed ? "justify-center px-3 py-3" : "gap-3 px-3.5 py-3",
                                isActive
                                    ? "bg-indigo-600/10 text-indigo-400 border-indigo-500/20 shadow-sm"
                                    : "text-slate-400 hover:bg-slate-800/50 hover:text-slate-200"
                            )}
                        >
                            <Icon className={cn("w-5 h-5 shrink-0", isActive ? "text-indigo-400" : "text-slate-500")} />
                            {!sidebarCollapsed && <span>{item.label}</span>}
                        </Link>
                    );
                })}
            </nav>

            {/* 하단 영역 */}
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
