'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import {
    LineChart, LayoutDashboard, Moon, Sun,
    ChevronLeft, ChevronRight, ChevronDown, ChevronUp, LogOut,
} from 'lucide-react';
import { useTheme } from './ThemeProvider';
import { useUiStore } from '@/store';
import { cn } from '@/lib/utils';
import { navGroups, isItemActive, activeGroup } from './navConfig';

export { cn };

/**
 * 데스크톱(lg 이상) 전용 고정 사이드바.
 * lg 미만에서는 숨고 MobileNav(상단바 + 드로어 + 하단 탭)가 대신한다.
 */
export function Sidebar() {
    const pathname = usePathname();
    const { theme, toggle } = useTheme();
    const { sidebarCollapsed, toggleSidebar } = useUiStore();
    const router = useRouter();

    const handleLogout = async () => {
        await fetch('/api/auth/logout', { method: 'POST' });
        router.push('/login');
        router.refresh();
    };

    // 현재 경로가 속한 그룹을 열어둔다 (없으면 첫 그룹)
    const [openGroups, setOpenGroups] = useState<Set<string>>(
        () => new Set([activeGroup(pathname)?.label ?? navGroups[0].label])
    );

    // 경로가 다른 그룹으로 넘어가면 그 그룹을 자동으로 펼친다 (기존 열림 상태는 유지).
    // effect가 아니라 렌더 중 조정 — 펼쳐지지 않은 중간 프레임이 깜빡이지 않는다.
    // https://react.dev/learn/you-might-not-need-an-effect
    const [lastPath, setLastPath] = useState(pathname);
    if (lastPath !== pathname) {
        setLastPath(pathname);
        const g = activeGroup(pathname);
        if (g && !openGroups.has(g.label)) {
            setOpenGroups(prev => new Set(prev).add(g.label));
        }
    }

    const toggleGroup = (label: string) => {
        setOpenGroups(prev => {
            const next = new Set(prev);
            if (next.has(label)) next.delete(label);
            else next.add(label);
            return next;
        });
    };

    return (
        <div
            className={cn(
                'hidden lg:flex bg-slate-900 border-r border-slate-800 text-slate-100 flex-col h-screen fixed inset-y-0 left-0 z-30 shadow-xl transition-all duration-300',
                sidebarCollapsed ? 'w-16' : 'w-64'
            )}
        >
            {/* 로고 */}
            <div className={cn('px-4 py-6 flex items-center shrink-0', sidebarCollapsed ? 'justify-center' : 'gap-3 px-6')}>
                <div className="w-8 h-8 rounded-lg bg-indigo-500 flex items-center justify-center shadow-lg shadow-indigo-500/30 shrink-0">
                    <LineChart className="w-5 h-5 text-white" />
                </div>
                {!sidebarCollapsed && (
                    <div className="overflow-hidden">
                        {/* 브랜드는 h1이 아니다 — 페이지 제목이 문서의 h1을 갖는다 */}
                        <span className="text-xl font-bold tracking-tight text-white whitespace-nowrap">Crypto Trader</span>
                    </div>
                )}
            </div>

            {/* 대시보드 단독 항목 */}
            <div className="px-2 mb-1 shrink-0">
                <Link
                    href="/"
                    title={sidebarCollapsed ? '대시보드' : undefined}
                    aria-current={pathname === '/' ? 'page' : undefined}
                    className={cn(
                        'flex items-center rounded-lg transition-all duration-200 text-sm font-medium border border-transparent',
                        sidebarCollapsed ? 'justify-center px-3 py-3' : 'gap-3 px-3.5 py-3',
                        pathname === '/'
                            ? 'bg-indigo-600/10 text-indigo-400 border-indigo-500/20'
                            : 'text-slate-400 hover:bg-slate-800/50 hover:text-slate-200'
                    )}
                >
                    <LayoutDashboard className={cn('w-5 h-5 shrink-0', pathname === '/' ? 'text-indigo-400' : 'text-slate-500')} />
                    {!sidebarCollapsed && <span>대시보드</span>}
                </Link>
            </div>

            {/* 그룹 네비게이션 */}
            <nav aria-label="주 메뉴" className="flex-1 px-2 space-y-0.5 overflow-y-auto">
                {navGroups.map((group) => {
                    const GroupIcon = group.icon;
                    const isOpen = openGroups.has(group.label);
                    const hasActive = group.items.some(i => isItemActive(i, pathname));

                    if (sidebarCollapsed) {
                        // 접힌 상태: 그룹 구분선 + 아이콘만 (라벨은 툴팁)
                        return (
                            <div key={group.label} className="space-y-0.5 pt-2 mt-2 first:mt-0 first:pt-0 border-t border-slate-800 first:border-t-0">
                                {group.items.map(item => {
                                    const Icon = item.icon;
                                    const active = isItemActive(item, pathname);
                                    return (
                                        <Link
                                            key={item.href}
                                            href={item.href}
                                            title={`${group.label} › ${item.label}`}
                                            aria-current={active ? 'page' : undefined}
                                            className={cn(
                                                'flex justify-center items-center rounded-lg px-3 py-3 transition-all duration-200 text-sm font-medium border border-transparent',
                                                active
                                                    ? 'bg-indigo-600/10 text-indigo-400 border-indigo-500/20'
                                                    : 'text-slate-400 hover:bg-slate-800/50 hover:text-slate-200'
                                            )}
                                        >
                                            <Icon className={cn('w-5 h-5 shrink-0', active ? 'text-indigo-400' : 'text-slate-500')} />
                                        </Link>
                                    );
                                })}
                            </div>
                        );
                    }

                    return (
                        <div key={group.label}>
                            <button
                                onClick={() => toggleGroup(group.label)}
                                aria-expanded={isOpen}
                                className={cn(
                                    'w-full flex items-center gap-2 px-3 py-2 rounded-lg text-xs font-semibold uppercase tracking-wider transition-colors',
                                    hasActive ? 'text-indigo-400' : 'text-slate-500 hover:text-slate-300'
                                )}
                            >
                                <GroupIcon className="w-3.5 h-3.5 shrink-0" />
                                <span className="flex-1 text-left normal-case">{group.label}</span>
                                {!isOpen && hasActive && <span className="w-1.5 h-1.5 rounded-full bg-indigo-400" />}
                                {isOpen ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
                            </button>

                            {isOpen && (
                                <div className="ml-2 pl-2 border-l border-slate-700/50 space-y-0.5 mb-1">
                                    {group.items.map(item => {
                                        const Icon = item.icon;
                                        const active = isItemActive(item, pathname);
                                        return (
                                            <Link
                                                key={item.href}
                                                href={item.href}
                                                aria-current={active ? 'page' : undefined}
                                                className={cn(
                                                    'flex items-center gap-3 rounded-lg px-3 py-2.5 transition-all duration-200 text-sm font-medium border border-transparent',
                                                    active
                                                        ? 'bg-indigo-600/10 text-indigo-400 border-indigo-500/20'
                                                        : 'text-slate-400 hover:bg-slate-800/50 hover:text-slate-200'
                                                )}
                                            >
                                                <Icon className={cn('w-4 h-4 shrink-0', active ? 'text-indigo-400' : 'text-slate-500')} />
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
                'px-2 py-4 border-t border-slate-800 flex items-center shrink-0',
                sidebarCollapsed ? 'flex-col gap-2' : 'justify-between px-4'
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
                    onClick={handleLogout}
                    className="p-2 rounded-lg text-slate-400 hover:bg-red-900 hover:text-red-300 transition-colors"
                    title="로그아웃"
                >
                    <LogOut className="w-4 h-4" />
                </button>
                <button
                    onClick={toggleSidebar}
                    className="p-2 rounded-lg text-slate-400 hover:bg-slate-800 hover:text-slate-200 transition-colors"
                    title={sidebarCollapsed ? '사이드바 펼치기' : '사이드바 접기'}
                >
                    {sidebarCollapsed ? <ChevronRight className="w-4 h-4" /> : <ChevronLeft className="w-4 h-4" />}
                </button>
            </div>
        </div>
    );
}
