'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { adminDiscordApi } from '@/lib/api';
import {
    MessagesSquare, Save, RefreshCw, CheckCircle, XCircle, Play,
    Send, Eye, EyeOff, Bell
} from 'lucide-react';

interface DiscordChannel {
    id: number;
    channelType: string;
    webhookConfigured: boolean;
    enabled: boolean;       // 백엔드 응답 필드명
    displayName: string | null; // 백엔드 응답 필드명
    description: string | null;
}

interface SendLog {
    id: number;
    channelType: string;
    messageType: string;
    status: string;
    errorMessage: string | null;
    createdAt: string;      // 백엔드 응답 필드명
}

const CHANNEL_DESCRIPTIONS: Record<string, string> = {
    TRADING_REPORT: '매매 분석 리포트 — LLM이 요약한 전략/신호/수익 분석',
    CRYPTO_NEWS: '코인 뉴스 요약 — 암호화폐 관련 최신 뉴스',
    ECONOMY_NEWS: '경제 뉴스 요약 — 거시경제/금융 관련 뉴스',
    ALERT: '알림 — 리스크 경보, 시스템 이상 등',
};

export default function DiscordPage() {
    const [channels, setChannels] = useState<DiscordChannel[]>([]);
    const [editForms, setEditForms] = useState<Record<string, { webhookUrl: string; enabled: boolean; displayName: string }>>({});
    const [dirtyChannels, setDirtyChannels] = useState<Set<string>>(new Set());
    const [savingChannel, setSavingChannel] = useState<string | null>(null);
    const [testingChannel, setTestingChannel] = useState<string | null>(null);
    const [testResults, setTestResults] = useState<Record<string, { ok: boolean; msg: string }>>({});
    const [showWebhook, setShowWebhook] = useState<Record<string, boolean>>({});
    const [logs, setLogs] = useState<SendLog[]>([]);
    const [logsLoading, setLogsLoading] = useState(true);
    const [triggeringBriefing, setTriggeringBriefing] = useState(false);
    const [briefingResult, setBriefingResult] = useState<string | null>(null);
    const [selectedChannels, setSelectedChannels] = useState<Set<string>>(
        new Set(['TRADING_REPORT', 'CRYPTO_NEWS', 'ECONOMY_NEWS', 'ALERT'])
    );

    const loadChannels = useCallback(async () => {
        try {
            const res = await adminDiscordApi.getChannels();
            const data: DiscordChannel[] = (res as any).data ?? [];
            setChannels(data);
            const forms: typeof editForms = {};
            data.forEach((ch: DiscordChannel) => {
                forms[ch.channelType] = {
                    webhookUrl: '',
                    enabled: ch.enabled,
                    displayName: ch.displayName || '',
                };
            });
            setEditForms(forms);
        } catch (e) {
            console.error(e);
        }
    }, []);

    const loadLogs = useCallback(async () => {
        setLogsLoading(true);
        try {
            const res = await adminDiscordApi.getLogs(30);
            setLogs((res as any).data ?? []);
        } catch (e) {
            console.error(e);
        } finally {
            setLogsLoading(false);
        }
    }, []);

    useEffect(() => { loadChannels(); loadLogs(); }, [loadChannels, loadLogs]);

    const handleFormChange = (channelType: string, field: string, value: string | boolean) => {
        setEditForms(prev => ({ ...prev, [channelType]: { ...prev[channelType], [field]: value } }));
        setDirtyChannels(prev => new Set(prev).add(channelType));
    };

    const handleSave = async (channelType: string) => {
        setSavingChannel(channelType);
        try {
            const form = editForms[channelType];
            await adminDiscordApi.updateChannel(channelType, {
                webhookUrl: form.webhookUrl || undefined,
                enabled: form.enabled,
                displayName: form.displayName,
            });
            setDirtyChannels(prev => { const s = new Set(prev); s.delete(channelType); return s; });
            loadChannels();
        } catch (e: any) {
            alert('저장 실패: ' + e.message);
        } finally {
            setSavingChannel(null);
        }
    };

    const handleTest = async (channelType: string) => {
        setTestingChannel(channelType);
        setTestResults(prev => ({ ...prev, [channelType]: { ok: false, msg: '전송 중...' } }));
        try {
            await adminDiscordApi.testChannel(channelType);
            setTestResults(prev => ({ ...prev, [channelType]: { ok: true, msg: '테스트 메시지 전송 완료' } }));
            loadLogs();
        } catch (e: any) {
            setTestResults(prev => ({ ...prev, [channelType]: { ok: false, msg: e.message || '전송 실패' } }));
        } finally {
            setTestingChannel(null);
        }
    };

    const toggleChannel = (type: string) => {
        setSelectedChannels(prev => {
            const next = new Set(prev);
            next.has(type) ? next.delete(type) : next.add(type);
            return next;
        });
    };

    const handleSendBriefing = async () => {
        if (selectedChannels.size === 0) {
            setBriefingResult('전송할 채널을 하나 이상 선택하세요.');
            return;
        }
        setTriggeringBriefing(true);
        setBriefingResult(null);
        try {
            await adminDiscordApi.sendBriefing([...selectedChannels]);
            setBriefingResult(`전송 완료: ${[...selectedChannels].join(', ')}`);
            loadLogs();
        } catch (e: any) {
            setBriefingResult('오류: ' + (e.message || '전송 실패'));
        } finally {
            setTriggeringBriefing(false);
        }
    };

    const statusBadge = (status: string) => {
        if (status === 'SUCCESS') return <span className="flex items-center gap-1 text-xs text-green-400"><CheckCircle className="w-3 h-3" />성공</span>;
        return <span className="flex items-center gap-1 text-xs text-red-400"><XCircle className="w-3 h-3" />실패</span>;
    };

    const channelTypeColor: Record<string, string> = {
        TRADING_REPORT: 'bg-blue-500/20 text-blue-300',
        CRYPTO_NEWS: 'bg-yellow-500/20 text-yellow-300',
        ECONOMY_NEWS: 'bg-green-500/20 text-green-300',
        ALERT: 'bg-red-500/20 text-red-300',
    };

    const CHANNEL_LABELS: Record<string, string> = {
        TRADING_REPORT: '📊 매매 분석',
        CRYPTO_NEWS:    '🪙 코인 뉴스',
        ECONOMY_NEWS:   '📰 경제 뉴스',
        ALERT:          '🔔 시스템 알림',
    };

    return (
        <div className="p-6 space-y-6 text-slate-100">
            <div className="flex items-center gap-3">
                <MessagesSquare className="w-6 h-6 text-indigo-400" />
                <h1 className="text-2xl font-bold">Discord 설정</h1>
            </div>

            {/* 즉시 전송 패널 */}
            <div className="bg-slate-800 border border-slate-700 rounded-xl p-4">
                <p className="text-sm font-semibold text-indigo-300 mb-3 flex items-center gap-2">
                    <Bell className="w-4 h-4" />모닝 브리핑 즉시 전송
                </p>
                <div className="flex flex-wrap gap-3 mb-3">
                    {Object.entries(CHANNEL_LABELS).map(([type, label]) => (
                        <label key={type} className="flex items-center gap-2 cursor-pointer select-none">
                            <input
                                type="checkbox"
                                checked={selectedChannels.has(type)}
                                onChange={() => toggleChannel(type)}
                                className="w-4 h-4 accent-indigo-500"
                            />
                            <span className="text-sm">{label}</span>
                        </label>
                    ))}
                </div>
                <button
                    onClick={handleSendBriefing}
                    disabled={triggeringBriefing || selectedChannels.size === 0}
                    className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 rounded-lg text-sm transition-colors"
                >
                    <Send className="w-4 h-4" />
                    {triggeringBriefing ? '전송 중...' : `선택 채널 전송 (${selectedChannels.size}개)`}
                </button>
            </div>

            {briefingResult && (
                <div className={`px-4 py-3 rounded-lg text-sm ${briefingResult.startsWith('오류') ? 'bg-red-900/30 border border-red-700/50 text-red-300' : 'bg-green-900/30 border border-green-700/50 text-green-300'}`}>
                    {briefingResult}
                </div>
            )}

            <p className="text-sm text-slate-400">
                모닝 브리핑은 매일 오전 7시(KST)에 자동 전송됩니다. Webhook URL은 Discord 채널 설정 → 연동 → 웹후크에서 생성할 수 있습니다.
            </p>

            {/* 채널 설정 */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                {channels.map(ch => {
                    const form = editForms[ch.channelType] || { webhookUrl: '', enabled: ch.enabled, displayName: '' };
                    const isDirty = dirtyChannels.has(ch.channelType);
                    const isShowingWebhook = showWebhook[ch.channelType];

                    return (
                        <div key={ch.channelType} className="bg-slate-800 border border-slate-700 rounded-xl p-4 space-y-3">
                            <div className="flex items-start justify-between">
                                <div>
                                    <div className="flex items-center gap-2">
                                        <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${channelTypeColor[ch.channelType] || 'bg-slate-600 text-slate-300'}`}>
                                            {ch.channelType}
                                        </span>
                                        {ch.enabled
                                            ? <span className="flex items-center gap-1 text-xs text-green-400"><CheckCircle className="w-3 h-3" />활성</span>
                                            : <span className="flex items-center gap-1 text-xs text-slate-500"><XCircle className="w-3 h-3" />비활성</span>
                                        }
                                        {ch.webhookConfigured
                                            ? <span className="text-xs text-blue-400 bg-blue-500/10 px-2 py-0.5 rounded-full">Webhook 설정됨</span>
                                            : <span className="text-xs text-slate-500 bg-slate-700 px-2 py-0.5 rounded-full">미설정</span>
                                        }
                                    </div>
                                    <p className="text-xs text-slate-400 mt-1">{CHANNEL_DESCRIPTIONS[ch.channelType] || ch.description || ''}</p>
                                </div>
                            </div>

                            <div className="space-y-2">
                                <div>
                                    <label className="block text-xs text-slate-400 mb-1">채널 이름 (선택)</label>
                                    <input
                                        className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                                        placeholder="예: #trading-report"
                                        value={form.displayName}
                                        onChange={e => handleFormChange(ch.channelType, 'displayName', e.target.value)}
                                    />
                                </div>

                                <div>
                                    <label className="block text-xs text-slate-400 mb-1">
                                        Webhook URL {ch.webhookConfigured && <span className="text-slate-500">(변경 시에만 입력)</span>}
                                    </label>
                                    <div className="relative">
                                        <input
                                            type={isShowingWebhook ? 'text' : 'password'}
                                            className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 pr-10 text-sm focus:outline-none focus:border-indigo-500"
                                            placeholder="https://discord.com/api/webhooks/..."
                                            value={form.webhookUrl}
                                            onChange={e => handleFormChange(ch.channelType, 'webhookUrl', e.target.value)}
                                        />
                                        <button
                                            onClick={() => setShowWebhook(prev => ({ ...prev, [ch.channelType]: !isShowingWebhook }))}
                                            className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-200"
                                        >
                                            {isShowingWebhook ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                                        </button>
                                    </div>
                                </div>

                                <div className="flex items-center gap-2">
                                    <input
                                        type="checkbox"
                                        id={`enabled-${ch.channelType}`}
                                        checked={form.enabled}
                                        onChange={e => handleFormChange(ch.channelType, 'enabled', e.target.checked)}
                                        className="w-4 h-4 accent-indigo-500"
                                    />
                                    <label htmlFor={`enabled-${ch.channelType}`} className="text-sm">채널 활성화</label>
                                </div>
                            </div>

                            {testResults[ch.channelType] && (
                                <p className={`text-xs ${testResults[ch.channelType].ok ? 'text-green-400' : 'text-red-400'}`}>
                                    {testResults[ch.channelType].msg}
                                </p>
                            )}

                            <div className="flex gap-2 pt-1">
                                <button
                                    onClick={() => handleTest(ch.channelType)}
                                    disabled={testingChannel === ch.channelType || !ch.webhookConfigured}
                                    title={!ch.webhookConfigured ? 'Webhook URL을 먼저 설정하세요' : undefined}
                                    className="flex-1 flex items-center justify-center gap-1 px-3 py-2 bg-slate-700 hover:bg-slate-600 disabled:opacity-40 rounded-lg text-sm transition-colors"
                                >
                                    <Send className={`w-3 h-3 ${testingChannel === ch.channelType ? 'animate-pulse' : ''}`} />
                                    테스트 전송
                                </button>
                                {isDirty && (
                                    <button
                                        onClick={() => handleSave(ch.channelType)}
                                        disabled={savingChannel === ch.channelType}
                                        className="flex-1 flex items-center justify-center gap-1 px-3 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 rounded-lg text-sm transition-colors"
                                    >
                                        <Save className="w-3 h-3" />
                                        {savingChannel === ch.channelType ? '저장 중...' : '저장'}
                                    </button>
                                )}
                            </div>
                        </div>
                    );
                })}
            </div>

            {/* 전송 로그 */}
            <div className="bg-slate-800 border border-slate-700 rounded-xl p-5">
                <div className="flex items-center justify-between mb-4">
                    <h2 className="text-lg font-semibold text-indigo-300">전송 로그</h2>
                    <button onClick={loadLogs} className="p-1.5 rounded-lg bg-slate-700 hover:bg-slate-600 transition-colors">
                        <RefreshCw className={`w-4 h-4 ${logsLoading ? 'animate-spin' : ''}`} />
                    </button>
                </div>

                {logsLoading ? (
                    <div className="text-center py-8 text-slate-400">로딩 중...</div>
                ) : (
                    <div className="overflow-x-auto">
                        <table className="w-full text-sm">
                            <thead>
                                <tr className="text-left text-xs text-slate-400 border-b border-slate-700">
                                    <th className="pb-2 pr-4">상태</th>
                                    <th className="pb-2 pr-4">채널</th>
                                    <th className="pb-2 pr-4">메시지 타입</th>
                                    <th className="pb-2 pr-4">오류</th>
                                    <th className="pb-2">전송 시각</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-700/50">
                                {logs.map(log => (
                                    <tr key={log.id} className="py-2">
                                        <td className="py-2.5 pr-4">{statusBadge(log.status)}</td>
                                        <td className="py-2.5 pr-4">
                                            <span className={`text-xs px-2 py-0.5 rounded-full ${channelTypeColor[log.channelType] || 'bg-slate-600 text-slate-300'}`}>
                                                {log.channelType}
                                            </span>
                                        </td>
                                        <td className="py-2.5 pr-4 text-slate-300">{log.messageType}</td>
                                        <td className="py-2.5 pr-4 text-xs text-red-400 max-w-48 truncate">{log.errorMessage || '-'}</td>
                                        <td className="py-2.5 text-xs text-slate-400">{new Date(log.createdAt).toLocaleString('ko-KR')}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                        {logs.length === 0 && (
                            <div className="text-center py-8 text-slate-500">전송 로그가 없습니다.</div>
                        )}
                    </div>
                )}
            </div>
        </div>
    );
}
