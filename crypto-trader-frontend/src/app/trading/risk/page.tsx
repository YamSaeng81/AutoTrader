'use client';

import { useState, useEffect } from 'react';
import { useRiskConfig, useUpdateRiskConfig } from '@/hooks';
import type { RiskConfig } from '@/lib/types';

export default function RiskConfigPage() {
  const { data: config, isLoading } = useRiskConfig();
  const updateConfig = useUpdateRiskConfig();
  const [form, setForm] = useState({
    maxDailyLossPct: 3.0,
    maxWeeklyLossPct: 7.0,
    maxMonthlyLossPct: 15.0,
    maxPositions: 3,
    cooldownMinutes: 60,
    portfolioLimitKrw: 0,
  });
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (config) {
      setForm({
        maxDailyLossPct: config.maxDailyLossPct,
        maxWeeklyLossPct: config.maxWeeklyLossPct,
        maxMonthlyLossPct: config.maxMonthlyLossPct,
        maxPositions: config.maxPositions,
        cooldownMinutes: config.cooldownMinutes,
        portfolioLimitKrw: config.portfolioLimitKrw,
      });
    }
  }, [config]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    updateConfig.mutate(form, {
      onSuccess: () => {
        setSaved(true);
        setTimeout(() => setSaved(false), 3000);
      },
      onError: () => alert('리스크 설정 저장 실패'),
    });
  };

  const fields: { key: keyof typeof form; label: string; unit: string; step: string; min: number; max?: number }[] = [
    { key: 'maxDailyLossPct', label: '일일 최대 손실률', unit: '%', step: '0.5', min: 0.5, max: 50 },
    { key: 'maxWeeklyLossPct', label: '주간 최대 손실률', unit: '%', step: '0.5', min: 1, max: 100 },
    { key: 'maxMonthlyLossPct', label: '월간 최대 손실률', unit: '%', step: '1', min: 1, max: 100 },
    { key: 'maxPositions', label: '최대 동시 포지션', unit: '개', step: '1', min: 1, max: 20 },
    { key: 'cooldownMinutes', label: '쿨다운 시간', unit: '분', step: '10', min: 0, max: 1440 },
    { key: 'portfolioLimitKrw', label: '포트폴리오 한도', unit: 'KRW', step: '100000', min: 0 },
  ];

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  return (
    <div className="max-w-2xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-white">리스크 설정</h1>
        <p className="text-sm text-slate-400 mt-1">자동매매의 리스크 관리 파라미터를 설정합니다</p>
      </div>

      <form onSubmit={handleSubmit} className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-6 space-y-5">
        {fields.map(({ key, label, unit, step, min, max }) => (
          <div key={key}>
            <label className="block text-sm font-medium text-slate-300 mb-1.5">
              {label} <span className="text-slate-500">({unit})</span>
            </label>
            <input
              type="number"
              value={form[key]}
              onChange={(e) => setForm({ ...form, [key]: Number(e.target.value) })}
              step={step}
              min={min}
              max={max}
              className="w-full px-4 py-2.5 bg-slate-900/50 border border-slate-600 rounded-lg text-white focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-colors"
            />
          </div>
        ))}

        <div className="flex items-center gap-4 pt-2">
          <button
            type="submit"
            disabled={updateConfig.isPending}
            className="px-6 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white font-medium rounded-lg transition-colors disabled:opacity-50"
          >
            {updateConfig.isPending ? '저장 중...' : '설정 저장'}
          </button>
          {saved && (
            <span className="text-green-400 text-sm font-medium">저장되었습니다</span>
          )}
        </div>
      </form>

      {/* 설명 카드 */}
      <div className="bg-slate-800/30 border border-slate-700/30 rounded-xl p-5">
        <h3 className="text-sm font-semibold text-slate-300 mb-3">리스크 관리 규칙</h3>
        <ul className="text-xs text-slate-400 space-y-2">
          <li>일일/주간/월간 손실률을 초과하면 자동으로 신규 주문이 거부됩니다.</li>
          <li>최대 동시 포지션 수를 초과하면 추가 진입이 차단됩니다.</li>
          <li>쿨다운: 손실 한도 초과 후 설정한 시간이 지나야 매매가 재개됩니다.</li>
          <li>포트폴리오 한도: 총 투자 가능 금액 (0 = 무제한).</li>
        </ul>
      </div>
    </div>
  );
}
