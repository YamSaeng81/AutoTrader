'use client';

import { useState, useEffect } from 'react';
import { useRiskConfig, useUpdateRiskConfig } from '@/hooks';

type FormState = {
  // 포트폴리오 수준
  maxDailyLossPct: number;
  maxWeeklyLossPct: number;
  maxMonthlyLossPct: number;
  maxPositions: number;
  cooldownMinutes: number;
  portfolioLimitKrw: number;
  // 서킷 브레이커
  circuitBreakerEnabled: boolean;
  mddThresholdPct: number;
  consecutiveLossLimit: number;
  // 포지션 수준 (ExitRuleConfig)
  stopLossPct: number;
  takeProfitMultiplier: number;
  trailingEnabled: boolean;
  trailingTpMarginPct: number;
  trailingSlMarginPct: number;
  investRatioPct: number;
};

const DEFAULTS: FormState = {
  maxDailyLossPct: 3.0,
  maxWeeklyLossPct: 7.0,
  maxMonthlyLossPct: 15.0,
  maxPositions: 3,
  cooldownMinutes: 60,
  portfolioLimitKrw: 0,
  circuitBreakerEnabled: true,
  mddThresholdPct: 20.0,
  consecutiveLossLimit: 5,
  stopLossPct: 5.0,
  takeProfitMultiplier: 2.0,
  trailingEnabled: true,
  trailingTpMarginPct: 0.5,
  trailingSlMarginPct: 0.3,
  investRatioPct: 80.0,
};

export default function RiskConfigPage() {
  const { data: config, isLoading } = useRiskConfig();
  const updateConfig = useUpdateRiskConfig();
  const [form, setForm] = useState<FormState>(DEFAULTS);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (config) {
      setForm({
        maxDailyLossPct: config.maxDailyLossPct ?? DEFAULTS.maxDailyLossPct,
        maxWeeklyLossPct: config.maxWeeklyLossPct ?? DEFAULTS.maxWeeklyLossPct,
        maxMonthlyLossPct: config.maxMonthlyLossPct ?? DEFAULTS.maxMonthlyLossPct,
        maxPositions: config.maxPositions ?? DEFAULTS.maxPositions,
        cooldownMinutes: config.cooldownMinutes ?? DEFAULTS.cooldownMinutes,
        portfolioLimitKrw: config.portfolioLimitKrw ?? DEFAULTS.portfolioLimitKrw,
        circuitBreakerEnabled: config.circuitBreakerEnabled ?? DEFAULTS.circuitBreakerEnabled,
        mddThresholdPct: config.mddThresholdPct ?? DEFAULTS.mddThresholdPct,
        consecutiveLossLimit: config.consecutiveLossLimit ?? DEFAULTS.consecutiveLossLimit,
        stopLossPct: config.stopLossPct ?? DEFAULTS.stopLossPct,
        takeProfitMultiplier: config.takeProfitMultiplier ?? DEFAULTS.takeProfitMultiplier,
        trailingEnabled: config.trailingEnabled ?? DEFAULTS.trailingEnabled,
        trailingTpMarginPct: config.trailingTpMarginPct ?? DEFAULTS.trailingTpMarginPct,
        trailingSlMarginPct: config.trailingSlMarginPct ?? DEFAULTS.trailingSlMarginPct,
        investRatioPct: config.investRatioPct ?? DEFAULTS.investRatioPct,
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

  const num = (key: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [key]: Number(e.target.value) });

  const toggle = (key: keyof FormState) => () =>
    setForm({ ...form, [key]: !form[key as keyof typeof form] });

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
        <p className="text-sm text-slate-400 mt-1">자동매매의 리스크 관리 파라미터를 설정합니다 — 백테스트·모의매매·실전매매 공통 적용</p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-6">

        {/* ── 포트폴리오 수준 ── */}
        <section className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-6 space-y-5">
          <h2 className="text-sm font-semibold text-slate-300 uppercase tracking-wide">포트폴리오 리스크</h2>

          {([
            { key: 'maxDailyLossPct',   label: '일일 최대 손실률',    unit: '%',   step: '0.5', min: 0.5, max: 50 },
            { key: 'maxWeeklyLossPct',  label: '주간 최대 손실률',    unit: '%',   step: '0.5', min: 1,   max: 100 },
            { key: 'maxMonthlyLossPct', label: '월간 최대 손실률',    unit: '%',   step: '1',   min: 1,   max: 100 },
            { key: 'maxPositions',      label: '최대 동시 포지션',    unit: '개',  step: '1',   min: 1,   max: 20 },
            { key: 'cooldownMinutes',   label: '쿨다운 시간',         unit: '분',  step: '10',  min: 0,   max: 1440 },
            { key: 'portfolioLimitKrw', label: '포트폴리오 한도',     unit: 'KRW', step: '100000', min: 0 },
          ] as { key: keyof FormState; label: string; unit: string; step: string; min: number; max?: number }[]).map(
            ({ key, label, unit, step, min, max }) => (
              <div key={key}>
                <label className="block text-sm font-medium text-slate-300 mb-1.5">
                  {label} <span className="text-slate-500">({unit})</span>
                </label>
                <input
                  type="number"
                  value={form[key] as number}
                  onChange={num(key)}
                  step={step} min={min} max={max}
                  className="w-full px-4 py-2.5 bg-slate-900/50 border border-slate-600 rounded-lg text-white focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-colors"
                />
              </div>
            )
          )}
        </section>

        {/* ── 서킷 브레이커 ── */}
        <section className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-6 space-y-5">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-slate-300 uppercase tracking-wide">서킷 브레이커</h2>
            <button
              type="button"
              onClick={toggle('circuitBreakerEnabled')}
              className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                form.circuitBreakerEnabled ? 'bg-indigo-600' : 'bg-slate-600'
              }`}
            >
              <span
                className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                  form.circuitBreakerEnabled ? 'translate-x-6' : 'translate-x-1'
                }`}
              />
            </button>
          </div>

          <div className={form.circuitBreakerEnabled ? '' : 'opacity-40 pointer-events-none'}>
            <div className="space-y-5">
              <div>
                <label className="block text-sm font-medium text-slate-300 mb-1.5">
                  세션 MDD 임계값 <span className="text-slate-500">(%)</span>
                </label>
                <input
                  type="number" value={form.mddThresholdPct} onChange={num('mddThresholdPct')}
                  step="1" min="5" max="80"
                  className="w-full px-4 py-2.5 bg-slate-900/50 border border-slate-600 rounded-lg text-white focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-colors"
                />
                <p className="text-xs text-slate-500 mt-1">초과 시 세션 강제 정지</p>
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-300 mb-1.5">
                  연속 손실 허용 횟수 <span className="text-slate-500">(회)</span>
                </label>
                <input
                  type="number" value={form.consecutiveLossLimit} onChange={num('consecutiveLossLimit')}
                  step="1" min="1" max="20"
                  className="w-full px-4 py-2.5 bg-slate-900/50 border border-slate-600 rounded-lg text-white focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-colors"
                />
                <p className="text-xs text-slate-500 mt-1">초과 시 세션 강제 정지</p>
              </div>
            </div>
          </div>
        </section>

        {/* ── 포지션 수준 (ExitRuleConfig) ── */}
        <section className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-6 space-y-5">
          <h2 className="text-sm font-semibold text-slate-300 uppercase tracking-wide">포지션 리스크 (백테스트·모의·실전 공통)</h2>

          <div>
            <label className="block text-sm font-medium text-slate-300 mb-1.5">
              손절 비율 <span className="text-slate-500">(%)</span>
            </label>
            <input
              type="number" value={form.stopLossPct} onChange={num('stopLossPct')}
              step="0.5" min="0.5" max="30"
              className="w-full px-4 py-2.5 bg-slate-900/50 border border-slate-600 rounded-lg text-white focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-colors"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-300 mb-1.5">
              익절 배수 <span className="text-slate-500">(TP% = SL% × 배수)</span>
            </label>
            <input
              type="number" value={form.takeProfitMultiplier} onChange={num('takeProfitMultiplier')}
              step="0.5" min="0.5" max="10"
              className="w-full px-4 py-2.5 bg-slate-900/50 border border-slate-600 rounded-lg text-white focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-colors"
            />
            <p className="text-xs text-slate-500 mt-1">
              현재: SL {form.stopLossPct}% → TP {(form.stopLossPct * form.takeProfitMultiplier).toFixed(1)}%
            </p>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-300 mb-1.5">
              투자 비율 <span className="text-slate-500">(가용자금 대비 %)</span>
            </label>
            <input
              type="number" value={form.investRatioPct} onChange={num('investRatioPct')}
              step="5" min="10" max="100"
              className="w-full px-4 py-2.5 bg-slate-900/50 border border-slate-600 rounded-lg text-white focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-colors"
            />
          </div>

          {/* 트레일링 */}
          <div className="border-t border-slate-700/50 pt-5 space-y-4">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium text-slate-300">트레일링 스탑</span>
              <button
                type="button"
                onClick={toggle('trailingEnabled')}
                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                  form.trailingEnabled ? 'bg-indigo-600' : 'bg-slate-600'
                }`}
              >
                <span
                  className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                    form.trailingEnabled ? 'translate-x-6' : 'translate-x-1'
                  }`}
                />
              </button>
            </div>

            <div className={form.trailingEnabled ? 'space-y-4' : 'space-y-4 opacity-40 pointer-events-none'}>
              <div>
                <label className="block text-sm font-medium text-slate-300 mb-1.5">
                  트레일링 TP 마진 <span className="text-slate-500">(고점 대비 %)</span>
                </label>
                <input
                  type="number" value={form.trailingTpMarginPct} onChange={num('trailingTpMarginPct')}
                  step="0.1" min="0.1" max="5"
                  className="w-full px-4 py-2.5 bg-slate-900/50 border border-slate-600 rounded-lg text-white focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-colors"
                />
                <p className="text-xs text-slate-500 mt-1">고점에서 이 비율 하락 시 익절</p>
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-300 mb-1.5">
                  트레일링 SL 조임 마진 <span className="text-slate-500">(저점 대비 %)</span>
                </label>
                <input
                  type="number" value={form.trailingSlMarginPct} onChange={num('trailingSlMarginPct')}
                  step="0.1" min="0.1" max="5"
                  className="w-full px-4 py-2.5 bg-slate-900/50 border border-slate-600 rounded-lg text-white focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-colors"
                />
                <p className="text-xs text-slate-500 mt-1">급락 시 손실 포지션의 손절가 자동 상향</p>
              </div>
            </div>
          </div>
        </section>

        <div className="flex items-center gap-4">
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
        <h3 className="text-sm font-semibold text-slate-300 mb-3">적용 범위</h3>
        <ul className="text-xs text-slate-400 space-y-2">
          <li>포트폴리오 리스크: 일일/주간/월간 손실률 초과 시 신규 주문 자동 거부.</li>
          <li>서킷 브레이커: 세션 MDD 또는 연속 손실 초과 시 해당 세션 강제 정지.</li>
          <li>포지션 리스크: 백테스트·모의매매·실전매매 모두 동일 SL/TP·트레일링·투자비율 적용.</li>
          <li>포트폴리오 한도: 총 투자 가능 금액 (0 = 무제한).</li>
        </ul>
      </div>
    </div>
  );
}
