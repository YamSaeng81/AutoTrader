'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { adminLlmApi } from '@/lib/api';

type Provider = Record<string, unknown>;
type Task = Record<string, unknown>;
type TestResult = Record<string, unknown> | null;

const PROVIDER_LABELS: Record<string, string> = {
    OPENAI: 'OpenAI', OLLAMA: 'Ollama (로컬)', CLAUDE: 'Anthropic Claude', MOCK: 'Mock (테스트)',
};
const TASK_LABELS: Record<string, string> = {
    LOG_SUMMARY: '로그 요약', SIGNAL_ANALYSIS: '신호 분석',
    NEWS_SUMMARY: '뉴스 요약', REPORT_NARRATION: '보고서 서술',
};
const TASK_HINTS: Record<string, string> = {
    LOG_SUMMARY: '로컬 LLM (Ollama) 권장 — 대용량 반복 처리',
    SIGNAL_ANALYSIS: 'Cloud LLM (OpenAI/Claude) 권장 — 정밀 분석',
    NEWS_SUMMARY: '설정에 따라',
    REPORT_NARRATION: '설정에 따라',
};

function StatusBadge({ ok, label }: { ok: boolean; label?: string }) {
    return (
        <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${ok ? 'bg-green-500/20 text-green-400' : 'bg-slate-600/50 text-slate-400'}`}>
            {label ?? (ok ? '활성' : '비활성')}
        </span>
    );
}

export default function LlmConfigPage() {
    const [providers, setProviders] = useState<Provider[]>([]);
    const [tasks, setTasks] = useState<Task[]>([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState<string | null>(null);
    const [editProvider, setEditProvider] = useState<string | null>(null);
    const [editTask, setEditTask] = useState<string | null>(null);
    const [formData, setFormData] = useState<Record<string, unknown>>({});
    const [testResult, setTestResult] = useState<TestResult>(null);
    const [testLoading, setTestLoading] = useState(false);
    const [testPrompt, setTestPrompt] = useState('암호화폐 시장 현황을 한 문장으로 설명해주세요.');

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const [p, t] = await Promise.all([adminLlmApi.getProviders(), adminLlmApi.getTasks()]);
            if (p.data) setProviders(p.data);
            if (t.data) setTasks(t.data);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { load(); }, [load]);

    const saveProvider = async (providerName: string) => {
        setSaving(providerName);
        try {
            await adminLlmApi.updateProvider(providerName, formData);
            setEditProvider(null);
            await load();
        } finally {
            setSaving(null);
        }
    };

    const saveTask = async (taskName: string) => {
        setSaving(taskName);
        try {
            await adminLlmApi.updateTask(taskName, formData);
            setEditTask(null);
            await load();
        } finally {
            setSaving(null);
        }
    };

    const testProvider = async (providerName: string) => {
        setTestLoading(true);
        setTestResult(null);
        try {
            const res = await adminLlmApi.testProvider(providerName, testPrompt);
            setTestResult(res.data ?? null);
        } finally {
            setTestLoading(false);
        }
    };

    if (loading) return <div className="p-6 text-slate-400">로딩 중...</div>;

    return (
        <div className="p-6 space-y-6 max-w-4xl">
            <div>
                <h1 className="text-2xl font-bold text-white">LLM 설정</h1>
                <p className="text-sm text-slate-400 mt-1">AI 프로바이더 연결 및 작업별 라우팅 설정</p>
            </div>

            {/* 프로바이더 설정 */}
            <section>
                <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-3">프로바이더 설정</h2>
                <div className="space-y-3">
                    {providers.map((p) => {
                        const name = p.providerName as string;
                        const isEditing = editProvider === name;
                        return (
                            <div key={name} className="bg-slate-800 rounded-xl border border-slate-700/50 p-4">
                                <div className="flex items-center justify-between mb-2">
                                    <div className="flex items-center gap-3">
                                        <span className="font-semibold text-white">{PROVIDER_LABELS[name] ?? name}</span>
                                        <StatusBadge ok={p.enabled as boolean} />
                                        {p.available && <StatusBadge ok={true} label="연결됨" />}
                                        {!p.available && (p.enabled as boolean) && <StatusBadge ok={false} label="미연결" />}
                                    </div>
                                    <div className="flex gap-2">
                                        {!isEditing && (
                                            <>
                                                <button
                                                    onClick={() => { setEditProvider(name); setFormData({ enabled: p.enabled, defaultModel: p.defaultModel, baseUrl: p.baseUrl }); }}
                                                    className="text-xs px-3 py-1.5 bg-slate-700 hover:bg-slate-600 text-slate-200 rounded-lg transition-colors"
                                                >편집</button>
                                                {name !== 'MOCK' && (
                                                    <button
                                                        onClick={() => testProvider(name)}
                                                        disabled={testLoading}
                                                        className="text-xs px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white rounded-lg transition-colors"
                                                    >테스트</button>
                                                )}
                                            </>
                                        )}
                                    </div>
                                </div>

                                <div className="text-xs text-slate-500 space-y-0.5">
                                    <div>모델: <span className="text-slate-300">{p.defaultModel as string}</span></div>
                                    {p.baseUrl && <div>URL: <span className="text-slate-300">{p.baseUrl as string}</span></div>}
                                    <div>API 키: <span className={p.apiKeyConfigured ? 'text-green-400' : 'text-slate-500'}>{p.apiKeyConfigured ? '설정됨' : '미설정'}</span></div>
                                </div>

                                {isEditing && (
                                    <div className="mt-4 space-y-3 pt-4 border-t border-slate-700/50">
                                        <div className="grid grid-cols-2 gap-3">
                                            <div>
                                                <label className="text-xs text-slate-400 block mb-1">기본 모델</label>
                                                <input
                                                    className="w-full bg-slate-700 text-white text-sm rounded-lg px-3 py-2 border border-slate-600 focus:border-indigo-500 outline-none"
                                                    value={(formData.defaultModel as string) ?? ''}
                                                    onChange={e => setFormData(f => ({ ...f, defaultModel: e.target.value }))}
                                                    placeholder="gpt-4o-mini"
                                                />
                                            </div>
                                            <div>
                                                <label className="text-xs text-slate-400 block mb-1">Base URL</label>
                                                <input
                                                    className="w-full bg-slate-700 text-white text-sm rounded-lg px-3 py-2 border border-slate-600 focus:border-indigo-500 outline-none"
                                                    value={(formData.baseUrl as string) ?? ''}
                                                    onChange={e => setFormData(f => ({ ...f, baseUrl: e.target.value }))}
                                                    placeholder="https://api.openai.com/v1"
                                                />
                                            </div>
                                        </div>
                                        <div>
                                            <label className="text-xs text-slate-400 block mb-1">API 키 (변경 시만 입력)</label>
                                            <input
                                                type="password"
                                                className="w-full bg-slate-700 text-white text-sm rounded-lg px-3 py-2 border border-slate-600 focus:border-indigo-500 outline-none"
                                                onChange={e => setFormData(f => ({ ...f, apiKey: e.target.value }))}
                                                placeholder="sk-... / secret_..."
                                            />
                                        </div>
                                        <div className="flex items-center gap-2">
                                            <input
                                                type="checkbox"
                                                id={`enabled-${name}`}
                                                checked={(formData.enabled as boolean) ?? false}
                                                onChange={e => setFormData(f => ({ ...f, enabled: e.target.checked }))}
                                                className="rounded"
                                            />
                                            <label htmlFor={`enabled-${name}`} className="text-sm text-slate-300">활성화</label>
                                        </div>
                                        <div className="flex gap-2">
                                            <button
                                                onClick={() => saveProvider(name)}
                                                disabled={saving === name}
                                                className="px-4 py-2 text-sm bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white rounded-lg transition-colors"
                                            >{saving === name ? '저장중...' : '저장'}</button>
                                            <button
                                                onClick={() => setEditProvider(null)}
                                                className="px-4 py-2 text-sm bg-slate-700 hover:bg-slate-600 text-slate-200 rounded-lg transition-colors"
                                            >취소</button>
                                        </div>
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>
            </section>

            {/* 테스트 프롬프트 */}
            <section className="bg-slate-800 rounded-xl border border-slate-700/50 p-4">
                <h2 className="text-sm font-semibold text-white mb-3">연결 테스트 프롬프트</h2>
                <input
                    className="w-full bg-slate-700 text-white text-sm rounded-lg px-3 py-2 border border-slate-600 focus:border-indigo-500 outline-none mb-3"
                    value={testPrompt}
                    onChange={e => setTestPrompt(e.target.value)}
                />
                {testLoading && <p className="text-sm text-slate-400">테스트 중...</p>}
                {testResult && (
                    <div className="mt-2 p-3 bg-slate-700/50 rounded-lg">
                        <div className="flex gap-4 text-xs text-slate-400 mb-2">
                            <span>프로바이더: <span className="text-slate-200">{testResult.providerName as string}</span></span>
                            <span>모델: <span className="text-slate-200">{testResult.modelUsed as string}</span></span>
                            <span className={testResult.success ? 'text-green-400' : 'text-red-400'}>{testResult.success ? '성공' : '실패'}</span>
                        </div>
                        <p className="text-sm text-slate-200 whitespace-pre-wrap">
                            {testResult.success ? testResult.content as string : testResult.errorMessage as string}
                        </p>
                    </div>
                )}
            </section>

            {/* 작업별 라우팅 */}
            <section>
                <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-3">작업별 LLM 라우팅</h2>
                <div className="space-y-3">
                    {tasks.map((t) => {
                        const name = t.taskName as string;
                        const isEditing = editTask === name;
                        return (
                            <div key={name} className="bg-slate-800 rounded-xl border border-slate-700/50 p-4">
                                <div className="flex items-center justify-between">
                                    <div>
                                        <span className="font-semibold text-white">{TASK_LABELS[name] ?? name}</span>
                                        <p className="text-xs text-slate-500 mt-0.5">{TASK_HINTS[name]}</p>
                                    </div>
                                    <div className="flex items-center gap-3">
                                        <div className="text-right text-xs">
                                            <div className="text-slate-300">{t.providerName as string}{t.model ? ` / ${t.model}` : ''}</div>
                                            <div className="text-slate-500">temp {t.temperature as number} · {t.maxTokens as number} tokens</div>
                                        </div>
                                        <StatusBadge ok={t.enabled as boolean} />
                                        {!isEditing && (
                                            <button
                                                onClick={() => { setEditTask(name); setFormData({ providerName: t.providerName, model: t.model, temperature: t.temperature, maxTokens: t.maxTokens, enabled: t.enabled }); }}
                                                className="text-xs px-3 py-1.5 bg-slate-700 hover:bg-slate-600 text-slate-200 rounded-lg transition-colors"
                                            >편집</button>
                                        )}
                                    </div>
                                </div>
                                {isEditing && (
                                    <div className="mt-4 space-y-3 pt-4 border-t border-slate-700/50">
                                        <div className="grid grid-cols-2 gap-3">
                                            <div>
                                                <label className="text-xs text-slate-400 block mb-1">프로바이더</label>
                                                <select
                                                    className="w-full bg-slate-700 text-white text-sm rounded-lg px-3 py-2 border border-slate-600 focus:border-indigo-500 outline-none"
                                                    value={(formData.providerName as string) ?? ''}
                                                    onChange={e => setFormData(f => ({ ...f, providerName: e.target.value }))}
                                                >
                                                    {providers.map(p => (
                                                        <option key={p.providerName as string} value={p.providerName as string}>
                                                            {PROVIDER_LABELS[p.providerName as string] ?? p.providerName as string}
                                                        </option>
                                                    ))}
                                                </select>
                                            </div>
                                            <div>
                                                <label className="text-xs text-slate-400 block mb-1">모델 (빈값=기본)</label>
                                                <input
                                                    className="w-full bg-slate-700 text-white text-sm rounded-lg px-3 py-2 border border-slate-600 focus:border-indigo-500 outline-none"
                                                    value={(formData.model as string) ?? ''}
                                                    onChange={e => setFormData(f => ({ ...f, model: e.target.value }))}
                                                    placeholder="기본값 사용"
                                                />
                                            </div>
                                            <div>
                                                <label className="text-xs text-slate-400 block mb-1">Temperature (0~1)</label>
                                                <input type="number" step="0.1" min="0" max="1"
                                                    className="w-full bg-slate-700 text-white text-sm rounded-lg px-3 py-2 border border-slate-600 focus:border-indigo-500 outline-none"
                                                    value={(formData.temperature as number) ?? 0.3}
                                                    onChange={e => setFormData(f => ({ ...f, temperature: parseFloat(e.target.value) }))}
                                                />
                                            </div>
                                            <div>
                                                <label className="text-xs text-slate-400 block mb-1">Max Tokens</label>
                                                <input type="number" step="100"
                                                    className="w-full bg-slate-700 text-white text-sm rounded-lg px-3 py-2 border border-slate-600 focus:border-indigo-500 outline-none"
                                                    value={(formData.maxTokens as number) ?? 2000}
                                                    onChange={e => setFormData(f => ({ ...f, maxTokens: parseInt(e.target.value) }))}
                                                />
                                            </div>
                                        </div>
                                        <div className="flex items-center gap-2">
                                            <input type="checkbox" id={`task-enabled-${name}`}
                                                checked={(formData.enabled as boolean) ?? true}
                                                onChange={e => setFormData(f => ({ ...f, enabled: e.target.checked }))}
                                                className="rounded"
                                            />
                                            <label htmlFor={`task-enabled-${name}`} className="text-sm text-slate-300">활성화</label>
                                        </div>
                                        <div className="flex gap-2">
                                            <button onClick={() => saveTask(name)} disabled={saving === name}
                                                className="px-4 py-2 text-sm bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white rounded-lg transition-colors">
                                                {saving === name ? '저장중...' : '저장'}
                                            </button>
                                            <button onClick={() => setEditTask(null)}
                                                className="px-4 py-2 text-sm bg-slate-700 hover:bg-slate-600 text-slate-200 rounded-lg transition-colors">취소</button>
                                        </div>
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>
            </section>
        </div>
    );
}
