'use client';

import { useStrategies, useToggleStrategyActive } from '@/hooks';
import { StrategyInfo } from '@/lib/types';
import { Loader2, Settings, AlertCircle, Power } from 'lucide-react';
import { useState } from 'react';
import StrategyConfigForm from '@/components/features/strategy/StrategyConfigForm';

export default function StrategiesPage() {
    const [selectedStrategy, setSelectedStrategy] = useState<StrategyInfo | null>(null);

    const { data: strategies = [], isLoading } = useStrategies();
    const toggleActive = useToggleStrategyActive();

    const handleToggleActive = (e: React.MouseEvent, strategyName: string) => {
        e.stopPropagation();
        toggleActive.mutate(strategyName);
    };

    return (
        <div className="space-y-6 animate-in fade-in duration-500 max-w-6xl mx-auto py-6 px-4">
            <div>
                <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">전략 관리</h1>
                <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
                    사용 가능한 트레이딩 전략을 조회하고 파라미터를 설정합니다.
                    활성화된 전략만 모의투자·실전매매에서 사용할 수 있습니다.
                </p>
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-3 gap-8 items-start">
                {/* 전략 목록 */}
                <div className="lg:col-span-2 space-y-4">
                    {isLoading ? (
                        <div className="p-12 text-center text-slate-500 dark:text-slate-400 flex flex-col items-center gap-3 bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700">
                            <Loader2 className="w-6 h-6 animate-spin text-indigo-500" />
                            <span>전략 목록을 불러오는 중...</span>
                        </div>
                    ) : strategies.length === 0 ? (
                        <div className="p-12 text-center text-slate-500 dark:text-slate-400 bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700">
                            사용 가능한 전략이 없습니다.
                        </div>
                    ) : (
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                            {strategies.map(s => (
                                <div
                                    key={s.name}
                                    onClick={() => setSelectedStrategy(s)}
                                    className={`group bg-white dark:bg-slate-900 rounded-xl shadow-sm border transition-all cursor-pointer p-5 flex flex-col justify-between ${
                                        !s.isActive
                                            ? 'opacity-60 border-slate-200 dark:border-slate-700'
                                            : selectedStrategy?.name === s.name
                                                ? 'border-indigo-500 ring-1 ring-indigo-500 shadow-md'
                                                : 'border-slate-200 dark:border-slate-700 hover:border-indigo-300 hover:shadow-md'
                                    }`}
                                >
                                    <div className="flex justify-between items-start mb-4">
                                        <h3 className="text-lg font-bold text-slate-800 dark:text-slate-100 tracking-tight">{s.name}</h3>
                                        <div className="flex items-center gap-2">
                                            <span className={`px-2.5 py-1 text-[10px] font-bold tracking-widest uppercase rounded-md border ${
                                                s.status === 'AVAILABLE'
                                                    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                                                    : 'border-slate-200 bg-slate-50 text-slate-500'
                                            }`}>
                                                {s.status === 'AVAILABLE' ? '사용 가능' : '구현 예정'}
                                            </span>
                                        </div>
                                    </div>
                                    <p className="text-sm text-slate-500 leading-relaxed font-medium mb-5 min-h-[40px]">{s.description}</p>
                                    <div className="flex items-center justify-between">
                                        <span className="flex items-center gap-1.5 bg-slate-50 px-2 py-1 rounded-md border border-slate-100 text-xs text-slate-400 font-medium">
                                            <Settings className="w-3.5 h-3.5" /> 설정 및 백테스트 지원
                                        </span>
                                        {s.status === 'AVAILABLE' && (
                                            <button
                                                onClick={(e) => handleToggleActive(e, s.name)}
                                                disabled={toggleActive.isPending}
                                                title={s.isActive ? '비활성화 (모의투자·실전매매에서 숨김)' : '활성화 (모의투자·실전매매에서 표시)'}
                                                className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold border transition-all disabled:opacity-50 ${
                                                    s.isActive
                                                        ? 'bg-emerald-50 text-emerald-700 border-emerald-200 hover:bg-emerald-100'
                                                        : 'bg-slate-100 text-slate-500 border-slate-200 hover:bg-slate-200'
                                                }`}
                                            >
                                                <Power className="w-3 h-3" />
                                                {s.isActive ? '활성' : '비활성'}
                                            </button>
                                        )}
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </div>

                {/* 설정 내용 */}
                <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden sticky top-6">
                    <div className="p-5 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50 flex items-center justify-between">
                        <h2 className="font-semibold text-slate-800 dark:text-slate-100 flex items-center gap-2">
                            <Settings className="w-4 h-4 text-slate-500" /> 전략 파라미터
                        </h2>
                    </div>
                    <div className="p-6">
                        {!selectedStrategy ? (
                            <div className="text-center py-10 text-slate-400 flex flex-col items-center">
                                <AlertCircle className="w-10 h-10 mb-3 opacity-20" />
                                <p className="text-sm font-medium">좌측에서 전략을 선택해주세요.</p>
                            </div>
                        ) : selectedStrategy.status === 'SKELETON' ? (
                            <div className="text-center py-12 text-slate-500 bg-slate-50 rounded-xl border border-dashed border-slate-200">
                                <p className="font-medium text-slate-600 mb-1">{selectedStrategy.name} 전략</p>
                                <p className="text-sm">현재 구현 진행 중인 로직입니다.</p>
                            </div>
                        ) : (
                            <div>
                                {!selectedStrategy.isActive && (
                                    <div className="mb-4 px-3 py-2 bg-amber-50 border border-amber-200 rounded-lg text-xs text-amber-700 font-medium">
                                        비활성화된 전략입니다. 모의투자·실전매매에서 표시되지 않습니다.
                                    </div>
                                )}
                                <StrategyConfigForm strategyName={selectedStrategy.name} />
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}
