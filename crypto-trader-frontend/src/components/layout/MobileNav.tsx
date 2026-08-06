'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import {
    LineChart, LayoutDashboard, Menu, X, Moon, Sun, LogOut,
    ChevronDown, ChevronUp,
} from 'lucide-react';
import { useTheme } from './ThemeProvider';
import { cn } from '@/lib/utils';
import { navGroups, isItemActive, activeGroup, type NavGroup } from './navConfig';

/** 상단바 제목: "그룹 › 항목" (대시보드는 단독) */
function useCurrentTitle(pathname: string) {
    if (pathname === '/') return { group: null as string | null, item: '대시보드' };
    for (const g of navGroups) {
        const found = g.items.find(i => isItemActive(i, pathname));
        if (found) return { group: g.label, item: found.label };
    }
    return { group: null as string | null, item: 'Crypto Trader' };
}

/**
 * lg 미만 전용 네비게이션.
 * - 상단 앱바: 햄버거 + 현재 위치 + 테마 토글
 * - 드로어: 전체 메뉴(아코디언)
 * - 하단 탭바: 5개 대분류 → 탭하면 해당 그룹의 하위 메뉴 시트가 열린다
 */
export function MobileNav() {
    const pathname = usePathname();
    const router = useRouter();
    const { theme, toggle } = useTheme();

    const [drawerOpen, setDrawerOpen] = useState(false);
    const [sheetGroup, setSheetGroup] = useState<NavGroup | null>(null);
    const [openGroups, setOpenGroups] = useState<Set<string>>(
        () => new Set([activeGroup(pathname)?.label ?? navGroups[0].label])
    );

    const title = useCurrentTitle(pathname);
    const current = activeGroup(pathname);
    const anyOverlayOpen = drawerOpen || sheetGroup !== null;

    const closeAll = () => {
        setDrawerOpen(false);
        setSheetGroup(null);
    };

    // 경로가 바뀌면 열려 있던 오버레이를 닫는다.
    // effect가 아니라 렌더 중 조정 — 새 화면 위에 메뉴가 한 프레임 겹쳐 보이지 않는다.
    // https://react.dev/learn/you-might-not-need-an-effect
    const [lastPath, setLastPath] = useState(pathname);
    if (lastPath !== pathname) {
        setLastPath(pathname);
        if (drawerOpen) setDrawerOpen(false);
        if (sheetGroup) setSheetGroup(null);
    }

    // 오버레이가 열려 있는 동안 배경 스크롤 잠금 + ESC 닫기
    useEffect(() => {
        if (!anyOverlayOpen) return;
        const prev = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') closeAll(); };
        window.addEventListener('keydown', onKey);
        return () => {
            document.body.style.overflow = prev;
            window.removeEventListener('keydown', onKey);
        };
    }, [anyOverlayOpen]);

    const handleLogout = async () => {
        await fetch('/api/auth/logout', { method: 'POST' });
        router.push('/login');
        router.refresh();
    };

    const toggleGroup = (label: string) => {
        setOpenGroups(prev => {
            const next = new Set(prev);
            if (next.has(label)) next.delete(label);
            else next.add(label);
            return next;
        });
    };

    return (
        <>
            {/* ─── 상단 앱바 ─── */}
            <header className="lg:hidden fixed top-0 inset-x-0 z-40 h-14 bg-slate-900 border-b border-slate-800 flex items-center gap-2 px-2 pt-[env(safe-area-inset-top)]">
                <button
                    onClick={() => { setSheetGroup(null); setDrawerOpen(true); }}
                    aria-label="메뉴 열기"
                    className="p-2.5 rounded-lg text-slate-300 hover:bg-slate-800 active:bg-slate-700 transition-colors"
                >
                    <Menu className="w-5 h-5" />
                </button>

                <Link href="/" className="flex items-center gap-2 min-w-0 flex-1">
                    <div className="w-7 h-7 rounded-lg bg-indigo-500 flex items-center justify-center shrink-0">
                        <LineChart className="w-4 h-4 text-white" />
                    </div>
                    <div className="min-w-0">
                        {title.group && (
                            <p className="text-[10px] leading-tight text-slate-500 truncate">{title.group}</p>
                        )}
                        <p className="text-sm font-semibold text-slate-100 leading-tight truncate">{title.item}</p>
                    </div>
                </Link>

                <button
                    onClick={toggle}
                    aria-label={theme === 'dark' ? '라이트 모드로 전환' : '다크 모드로 전환'}
                    className="p-2.5 rounded-lg text-slate-400 hover:bg-slate-800 transition-colors shrink-0"
                >
                    {theme === 'dark' ? <Sun className="w-5 h-5" /> : <Moon className="w-5 h-5" />}
                </button>
            </header>

            {/* ─── 백드롭 ─── */}
            {anyOverlayOpen && (
                <div
                    onClick={closeAll}
                    className="lg:hidden fixed inset-0 z-40 bg-black/60 backdrop-blur-[2px]"
                    aria-hidden="true"
                />
            )}

            {/* ─── 전체 메뉴 드로어 ─── */}
            <div
                role="dialog"
                aria-modal="true"
                aria-label="전체 메뉴"
                aria-hidden={!drawerOpen}
                className={cn(
                    'lg:hidden fixed inset-y-0 left-0 z-50 w-[82%] max-w-[19rem] bg-slate-900 border-r border-slate-800 flex flex-col shadow-2xl transition-transform duration-300 ease-out',
                    drawerOpen ? 'translate-x-0' : '-translate-x-full pointer-events-none'
                )}
            >
                <div className="flex items-center gap-3 px-4 h-14 border-b border-slate-800 shrink-0 pt-[env(safe-area-inset-top)]">
                    <div className="w-7 h-7 rounded-lg bg-indigo-500 flex items-center justify-center">
                        <LineChart className="w-4 h-4 text-white" />
                    </div>
                    <h2 className="flex-1 text-base font-bold text-white">Crypto Trader</h2>
                    <button
                        onClick={closeAll}
                        aria-label="메뉴 닫기"
                        className="p-2 rounded-lg text-slate-400 hover:bg-slate-800"
                    >
                        <X className="w-5 h-5" />
                    </button>
                </div>

                <nav aria-label="전체 메뉴" className="flex-1 overflow-y-auto px-2 py-3 space-y-1 overscroll-contain">
                    <Link
                        href="/"
                        aria-current={pathname === '/' ? 'page' : undefined}
                        className={cn(
                            'flex items-center gap-3 rounded-lg px-3 py-3 text-sm font-medium border border-transparent',
                            pathname === '/'
                                ? 'bg-indigo-600/10 text-indigo-400 border-indigo-500/20'
                                : 'text-slate-300 hover:bg-slate-800/60'
                        )}
                    >
                        <LayoutDashboard className={cn('w-5 h-5', pathname === '/' ? 'text-indigo-400' : 'text-slate-500')} />
                        대시보드
                    </Link>

                    {navGroups.map(group => {
                        const GroupIcon = group.icon;
                        const isOpen = openGroups.has(group.label);
                        const hasActive = group.items.some(i => isItemActive(i, pathname));
                        return (
                            <div key={group.label}>
                                <button
                                    onClick={() => toggleGroup(group.label)}
                                    aria-expanded={isOpen}
                                    className={cn(
                                        'w-full flex items-center gap-3 px-3 py-3 rounded-lg text-sm font-semibold transition-colors',
                                        hasActive ? 'text-indigo-400' : 'text-slate-300 hover:bg-slate-800/60'
                                    )}
                                >
                                    <GroupIcon className={cn('w-5 h-5 shrink-0', hasActive ? 'text-indigo-400' : 'text-slate-500')} />
                                    <span className="flex-1 text-left">{group.label}</span>
                                    {isOpen ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                                </button>

                                {isOpen && (
                                    <div className="ml-4 pl-3 border-l border-slate-700/50 space-y-0.5 mb-1">
                                        {group.items.map(item => {
                                            const Icon = item.icon;
                                            const active = isItemActive(item, pathname);
                                            return (
                                                <Link
                                                    key={item.href}
                                                    href={item.href}
                                                    aria-current={active ? 'page' : undefined}
                                                    className={cn(
                                                        'flex items-center gap-3 rounded-lg px-3 py-3 text-sm font-medium border border-transparent',
                                                        active
                                                            ? 'bg-indigo-600/10 text-indigo-400 border-indigo-500/20'
                                                            : 'text-slate-400 hover:bg-slate-800/50'
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

                <div className="border-t border-slate-800 px-4 py-3 flex items-center justify-between shrink-0 pb-[max(0.75rem,env(safe-area-inset-bottom))]">
                    <span className="text-xs text-slate-500 font-medium">v0.3.0</span>
                    <button
                        onClick={handleLogout}
                        className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-slate-400 hover:bg-red-900/40 hover:text-red-300 transition-colors"
                    >
                        <LogOut className="w-4 h-4" /> 로그아웃
                    </button>
                </div>
            </div>

            {/* ─── 그룹 하위메뉴 바텀시트 ─── */}
            {sheetGroup && (
                <div
                    role="dialog"
                    aria-modal="true"
                    aria-label={`${sheetGroup.label} 메뉴`}
                    className="lg:hidden fixed inset-x-0 bottom-0 z-50 bg-slate-900 border-t border-slate-800 rounded-t-2xl shadow-2xl max-h-[75vh] flex flex-col animate-[slideUp_.2s_ease-out]"
                >
                    <div className="pt-2 pb-1 flex justify-center shrink-0">
                        <span className="w-10 h-1 rounded-full bg-slate-700" />
                    </div>
                    <div className="px-4 pb-2 flex items-center gap-2 shrink-0">
                        <sheetGroup.icon className="w-4 h-4 text-indigo-400" />
                        <h2 className="flex-1 text-sm font-semibold text-slate-200">{sheetGroup.label}</h2>
                        <button onClick={closeAll} aria-label="닫기" className="p-1.5 rounded-lg text-slate-500 hover:bg-slate-800">
                            <X className="w-4 h-4" />
                        </button>
                    </div>
                    <div className="overflow-y-auto overscroll-contain px-2 pb-[max(1rem,env(safe-area-inset-bottom))] space-y-0.5">
                        {sheetGroup.items.map(item => {
                            const Icon = item.icon;
                            const active = isItemActive(item, pathname);
                            return (
                                <Link
                                    key={item.href}
                                    href={item.href}
                                    aria-current={active ? 'page' : undefined}
                                    className={cn(
                                        'flex items-center gap-3 rounded-xl px-4 py-3.5 text-sm font-medium border border-transparent',
                                        active
                                            ? 'bg-indigo-600/10 text-indigo-400 border-indigo-500/20'
                                            : 'text-slate-300 active:bg-slate-800'
                                    )}
                                >
                                    <Icon className={cn('w-5 h-5 shrink-0', active ? 'text-indigo-400' : 'text-slate-500')} />
                                    <span>{item.label}</span>
                                </Link>
                            );
                        })}
                    </div>
                </div>
            )}

            {/* ─── 하단 탭바 ─── */}
            <nav
                aria-label="빠른 메뉴"
                className="lg:hidden fixed bottom-0 inset-x-0 z-30 bg-slate-900/95 backdrop-blur border-t border-slate-800 flex pb-[env(safe-area-inset-bottom)]"
            >
                <Link
                    href="/"
                    aria-current={pathname === '/' ? 'page' : undefined}
                    className={cn(
                        'flex-1 flex flex-col items-center justify-center gap-1 py-2 min-h-14 text-[10px] font-medium transition-colors',
                        pathname === '/' ? 'text-indigo-400' : 'text-slate-500 active:text-slate-300'
                    )}
                >
                    <LayoutDashboard className="w-5 h-5" />
                    홈
                </Link>

                {navGroups.map(group => {
                    const GroupIcon = group.icon;
                    const active = current?.label === group.label;
                    return (
                        <button
                            key={group.label}
                            onClick={() => { setDrawerOpen(false); setSheetGroup(sheetGroup?.label === group.label ? null : group); }}
                            aria-expanded={sheetGroup?.label === group.label}
                            className={cn(
                                'flex-1 flex flex-col items-center justify-center gap-1 py-2 min-h-14 text-[10px] font-medium transition-colors',
                                active ? 'text-indigo-400' : 'text-slate-500 active:text-slate-300'
                            )}
                        >
                            <GroupIcon className="w-5 h-5" />
                            {group.shortLabel ?? group.label}
                        </button>
                    );
                })}
            </nav>
        </>
    );
}
