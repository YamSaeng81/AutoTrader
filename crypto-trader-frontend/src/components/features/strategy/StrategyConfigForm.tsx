'use client';

import { useState, useEffect } from 'react';
import { strategyParamsMock, StrategyParam } from '@/mocks/data';
import { strategyApi } from '@/lib/api';
import { Loader2, Save, CheckCircle2 } from 'lucide-react';
import { useMutation } from '@tanstack/react-query';

export default function StrategyConfigForm({ strategyName }: { strategyName: string }) {
    const [paramsMeta, setParamsMeta] = useState<StrategyParam[]>([]);
    const [formData, setFormData] = useState<Record<string, number>>({});
    const [saved, setSaved] = useState(false);

    useEffect(() => {
        // 백엔드가 개발될 때까지 MSW 데이터를 직접 활용하여 동적 랜더링 (개발 환경 한정)
        // 실제로는 별도의 API (e.g. GET /api/v1/strategies/params) 를 통해 메타데이터를 받아와야 함.
        const meta = strategyParamsMock[strategyName] || [];
        setParamsMeta(meta);

        const initialData: Record<string, number> = {};
        meta.forEach(p => {
            initialData[p.name] = p.default;
        });
        setFormData(initialData);
        setSaved(false);
    }, [strategyName]);

    const saveMutation = useMutation({
        mutationFn: () => strategyApi.create({ strategy: strategyName, parameters: formData }),
        onSuccess: () => {
            setSaved(true);
            setTimeout(() => setSaved(false), 3000);
        }
    });

    const handleChange = (name: string, value: string) => {
        setFormData(prev => ({ ...prev, [name]: Number(value) }));
        setSaved(false);
    };

    if (!paramsMeta.length) {
        return <div className="text-sm text-slate-500 dark:text-slate-400 text-center py-6">설정 가능한 파라미터가 없습니다.</div>;
    }

    return (
        <form
            onSubmit={(e) => { e.preventDefault(); saveMutation.mutate(); }}
            className="space-y-6 animate-in fade-in zoom-in-95 duration-300"
        >
            <div className="space-y-4">
                {paramsMeta.map((param) => (
                    <div key={param.name} className="flex flex-col gap-1.5">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">{param.label}</label>
                        <div className="flex items-center gap-3">
                            <input
                                type="number"
                                name={param.name}
                                min={param.min}
                                max={param.max}
                                step={param.type === 'integer' ? 1 : 0.1}
                                value={formData[param.name] ?? param.default}
                                onChange={(e) => handleChange(param.name, e.target.value)}
                                className="w-full p-2.5 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-lg text-sm font-medium text-slate-800 dark:text-slate-200 transition-shadow focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            />
                        </div>
                        <div className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">범위: {param.min} ~ {param.max}</div>
                    </div>
                ))}
            </div>

            <div className="pt-4 border-t border-slate-100 dark:border-slate-800 flex items-center justify-between">
                <div className="text-sm font-medium">
                    {saved && (
                        <span className="flex items-center gap-1.5 text-emerald-600 animate-in fade-in duration-300">
                            <CheckCircle2 className="w-4 h-4" /> 저장되었습니다
                        </span>
                    )}
                </div>
                <button
                    type="submit"
                    disabled={saveMutation.isPending}
                    className="flex items-center gap-2 bg-slate-900 hover:bg-slate-800 text-white font-semibold flex-1 justify-center py-2.5 px-4 rounded-xl shadow-md transition-all active:scale-[0.98] disabled:opacity-70 disabled:pointer-events-none"
                >
                    {saveMutation.isPending ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                    {saveMutation.isPending ? '저장 중...' : '설정 저장'}
                </button>
            </div>
        </form>
    );
}
