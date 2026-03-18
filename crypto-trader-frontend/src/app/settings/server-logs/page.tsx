'use client';

import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { settingsApi, ServerLogEntry } from '@/lib/api';
import { cn } from '@/lib/utils';
import { RefreshCw, Terminal, ArrowDownToLine, Search, X } from 'lucide-react';

const LEVEL_FILTERS = [
    { value: 'ALL',   label: '전체',  cls: 'bg-slate-700 text-slate-200' },
    { value: 'ERROR', label: 'ERROR', cls: 'bg-rose-500/20 text-rose-400 border border-rose-500/30' },
    { value: 'WARN',  label: 'WARN',  cls: 'bg-amber-500/20 text-amber-400 border border-amber-500/30' },
    { value: 'INFO',  label: 'INFO',  cls: 'bg-sky-500/20 text-sky-400 border border-sky-500/30' },
    { value: 'DEBUG', label: 'DEBUG', cls: 'bg-slate-600/50 text-slate-400 border border-slate-500/30' },
];

const LINE_OPTIONS = [100, 200, 500, 1000];

const LEVEL_ROW_CLS: Record<string, string> = {
    ERROR: 'bg-rose-500/5 border-l-2 border-rose-500/60',
    WARN:  'bg-amber-500/5 border-l-2 border-amber-500/40',
    INFO:  'border-l-2 border-transparent',
    DEBUG: 'border-l-2 border-transparent opacity-70',
};

const LEVEL_TEXT_CLS: Record<string, string> = {
    ERROR: 'text-rose-400 font-bold',
    WARN:  'text-amber-400 font-semibold',
    INFO:  'text-sky-400',
    DEBUG: 'text-slate-500',
};

export default function ServerLogsPage() {
    const [levels, setLevels]   = useState<string[]>(['ALL']);
    const [keyword, setKeyword] = useState('');
    const [lines, setLines]     = useState(200);
    const [autoRefresh, setAutoRefresh] = useState(true);
    const [inputVal, setInputVal] = useState('');
    const bottomRef = useRef<HTMLDivElement>(null);
    const [autoScroll, setAutoScroll] = useState(true);

    const toggleLevel = (value: string) => {
        if (value === 'ALL') {
            setLevels(['ALL']);
            return;
        }
        setLevels(prev => {
            const without = prev.filter(l => l !== 'ALL' && l !== value);
            const next = prev.includes(value) ? without : [...without, value];
            return next.length === 0 ? ['ALL'] : next;
        });
    };

    const { data: res, isLoading, refetch, isFetching, dataUpdatedAt } = useQuery({
        queryKey: ['server-logs', levels, keyword, lines],
        queryFn: () => settingsApi.serverLogs(levels, keyword, lines),
        refetchInterval: autoRefresh ? 5000 : false,
    });

    const entries: ServerLogEntry[] = res?.data?.entries ?? [];
    const total    = res?.data?.total    ?? 0;
    const filtered = res?.data?.filtered ?? 0;
    const returned = res?.data?.returned ?? 0;

    // 자동 스크롤
    useEffect(() => {
        if (autoScroll && bottomRef.current) {
            bottomRef.current.scrollIntoView({ behavior: 'smooth' });
        }
    }, [entries, autoScroll]);

    const applyKeyword = () => setKeyword(inputVal.trim());

    const clearKeyword = () => {
        setInputVal('');
        setKeyword('');
    };

    const updatedTime = dataUpdatedAt
        ? new Date(dataUpdatedAt).toLocaleTimeString('ko-KR')
        : '';

    return (
        <div className="p-6 space-y-4 h-full flex flex-col">
            {/* 헤더 */}
            <div className="flex items-start justify-between gap-4 shrink-0">
                <div>
                    <div className="flex items-center gap-2">
                        <Terminal className="w-5 h-5 text-indigo-400" />
                        <h1 className="text-xl font-bold text-slate-100">서버 로그</h1>
                    </div>
                    <p className="text-sm text-slate-400 mt-0.5">
                        인메모리 버퍼 최근 {returned.toLocaleString()}개
                        {filtered !== total && (
                            <span className="ml-2 text-amber-400">
                                (전체 {total.toLocaleString()}건 중 {filtered.toLocaleString()}건 필터)
                            </span>
                        )}
                        {updatedTime && (
                            <span className="ml-2 text-slate-500">· {updatedTime} 기준</span>
                        )}
                    </p>
                </div>
                <div className="flex items-center gap-2">
                    {/* 자동 갱신 토글 */}
                    <button
                        onClick={() => setAutoRefresh(p => !p)}
                        className={cn(
                            'px-3 py-1.5 rounded-lg text-xs font-medium transition-colors',
                            autoRefresh
                                ? 'bg-indigo-600/20 text-indigo-400 border border-indigo-500/30'
                                : 'bg-slate-800 text-slate-400 border border-slate-700'
                        )}
                    >
                        {autoRefresh ? '자동갱신 ON' : '자동갱신 OFF'}
                    </button>
                    {/* 수동 새로고침 */}
                    <button
                        onClick={() => refetch()}
                        disabled={isFetching}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-800 text-slate-300 hover:bg-slate-700 text-xs font-medium border border-slate-700 transition-colors disabled:opacity-50"
                    >
                        <RefreshCw className={cn('w-3.5 h-3.5', isFetching && 'animate-spin')} />
                        새로고침
                    </button>
                </div>
            </div>

            {/* 필터 바 */}
            <div className="flex flex-wrap items-center gap-3 shrink-0">
                {/* 레벨 필터 (다중 선택) */}
                <div className="flex items-center gap-1">
                    {LEVEL_FILTERS.map(f => {
                        const active = f.value === 'ALL'
                            ? levels.includes('ALL')
                            : levels.includes(f.value);
                        return (
                            <button
                                key={f.value}
                                onClick={() => toggleLevel(f.value)}
                                className={cn(
                                    'px-2.5 py-1 rounded text-xs font-mono font-medium transition-all',
                                    active
                                        ? f.cls + ' ring-2 ring-white/20'
                                        : 'bg-slate-800 text-slate-500 hover:text-slate-300'
                                )}
                            >
                                {f.label}
                            </button>
                        );
                    })}
                </div>

                {/* 키워드 검색 */}
                <div className="flex items-center gap-1 flex-1 min-w-[200px] max-w-md">
                    <div className="relative flex-1">
                        <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-slate-500" />
                        <input
                            type="text"
                            value={inputVal}
                            onChange={e => setInputVal(e.target.value)}
                            onKeyDown={e => e.key === 'Enter' && applyKeyword()}
                            placeholder="키워드 검색 (Enter 적용)"
                            className="w-full pl-8 pr-7 py-1.5 bg-slate-800 border border-slate-700 rounded text-xs text-slate-200 placeholder:text-slate-500 focus:outline-none focus:border-indigo-500"
                        />
                        {inputVal && (
                            <button
                                onClick={clearKeyword}
                                className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-500 hover:text-slate-300"
                            >
                                <X className="w-3 h-3" />
                            </button>
                        )}
                    </div>
                    <button
                        onClick={applyKeyword}
                        className="px-2.5 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded text-xs font-medium transition-colors"
                    >
                        검색
                    </button>
                    {keyword && (
                        <button onClick={clearKeyword} className="text-xs text-slate-400 hover:text-slate-200 px-1">
                            초기화
                        </button>
                    )}
                </div>

                {/* 라인 수 선택 */}
                <div className="flex items-center gap-1">
                    <span className="text-xs text-slate-500">최근</span>
                    {LINE_OPTIONS.map(n => (
                        <button
                            key={n}
                            onClick={() => setLines(n)}
                            className={cn(
                                'px-2 py-1 rounded text-xs font-medium transition-colors',
                                lines === n
                                    ? 'bg-slate-600 text-slate-100'
                                    : 'bg-slate-800 text-slate-500 hover:text-slate-300'
                            )}
                        >
                            {n}
                        </button>
                    ))}
                    <span className="text-xs text-slate-500">줄</span>
                </div>

                {/* 자동 스크롤 */}
                <button
                    onClick={() => {
                        setAutoScroll(p => !p);
                        if (!autoScroll) bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
                    }}
                    className={cn(
                        'flex items-center gap-1 px-2.5 py-1 rounded text-xs font-medium transition-colors',
                        autoScroll
                            ? 'bg-emerald-600/20 text-emerald-400 border border-emerald-500/30'
                            : 'bg-slate-800 text-slate-500 border border-slate-700'
                    )}
                >
                    <ArrowDownToLine className="w-3 h-3" />
                    하단 고정
                </button>
            </div>

            {/* 로그 출력 영역 */}
            <div className="flex-1 overflow-y-auto rounded-lg border border-slate-700/60 bg-slate-950 font-mono text-xs min-h-0">
                {isLoading ? (
                    <div className="flex items-center justify-center h-32 text-slate-500">
                        로그를 불러오는 중...
                    </div>
                ) : entries.length === 0 ? (
                    <div className="flex flex-col items-center justify-center h-32 gap-2 text-slate-500">
                        <Terminal className="w-6 h-6" />
                        <span>
                            {keyword || !levels.includes('ALL')
                                ? '조건에 맞는 로그가 없습니다'
                                : '로그가 없습니다 (서버 재시작 후 로그가 쌓입니다)'}
                        </span>
                    </div>
                ) : (
                    <table className="w-full border-collapse">
                        <tbody>
                            {entries.map((e, i) => (
                                <tr
                                    key={i}
                                    className={cn(
                                        'hover:bg-white/5 transition-colors',
                                        LEVEL_ROW_CLS[e.level] ?? 'border-l-2 border-transparent'
                                    )}
                                >
                                    {/* 타임스탬프 */}
                                    <td className="pl-3 pr-2 py-0.5 whitespace-nowrap text-slate-500 w-[88px]">
                                        {e.timestamp}
                                    </td>
                                    {/* 레벨 */}
                                    <td className="pr-3 py-0.5 whitespace-nowrap w-[52px]">
                                        <span className={cn('text-[11px]', LEVEL_TEXT_CLS[e.level] ?? 'text-slate-400')}>
                                            {e.level.padEnd(5)}
                                        </span>
                                    </td>
                                    {/* 로거 */}
                                    <td className="pr-3 py-0.5 whitespace-nowrap text-slate-600 w-[200px] truncate max-w-[200px]">
                                        {e.logger}
                                    </td>
                                    {/* 메시지 */}
                                    <td className="pr-3 py-0.5 text-slate-200 break-all">
                                        <HighlightedMessage message={e.message} keyword={keyword} />
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
                <div ref={bottomRef} />
            </div>
        </div>
    );
}

function HighlightedMessage({ message, keyword }: { message: string; keyword: string }) {
    if (!keyword) return <>{message}</>;

    const idx = message.indexOf(keyword);
    if (idx === -1) return <>{message}</>;

    return (
        <>
            {message.slice(0, idx)}
            <mark className="bg-yellow-400/30 text-yellow-200 rounded px-0.5">{message.slice(idx, idx + keyword.length)}</mark>
            {message.slice(idx + keyword.length)}
        </>
    );
}
