'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { adminReportApi } from '@/lib/api';
import {
    BookOpen, Save, RefreshCw, ExternalLink, CheckCircle, XCircle,
    Clock, FileText, ChevronDown, ChevronUp, Play
} from 'lucide-react';

interface ReportConfig {
    notion_token: string;
    database_id: string;
    report_enabled: string;
    report_schedule: string;
    report_title_prefix: string;
    [key: string]: string;
}

interface ReportLog {
    id: number;
    reportType: string;
    periodStart: string;
    periodEnd: string;
    status: string;
    notionPageId: string | null;
    notionPageUrl: string | null;
    llmSummary: string | null;
    llmAnalysis: string | null;
    errorMessage: string | null;
    createdAt: string;
}

export default function ReportsPage() {
    const [config, setConfig] = useState<ReportConfig>({
        notion_token: '',
        database_id: '',
        report_enabled: 'false',
        report_schedule: '0 0 0,12 * * *',
        report_title_prefix: '📊 일일 리포트',
    });
    const [editConfig, setEditConfig] = useState<ReportConfig>({ ...config });
    const [configDirty, setConfigDirty] = useState(false);
    const [savingConfig, setSavingConfig] = useState(false);
    const [logs, setLogs] = useState<ReportLog[]>([]);
    const [logsLoading, setLogsLoading] = useState(true);
    const [triggering, setTriggering] = useState(false);
    const [triggerHours, setTriggerHours] = useState(12);
    const [triggerResult, setTriggerResult] = useState<string | null>(null);
    const [expandedLog, setExpandedLog] = useState<number | null>(null);

    const loadConfig = useCallback(async () => {
        try {
            const res = await adminReportApi.getConfig();
            const raw = (res as any).data ?? res;
            const configData: ReportConfig = {
                notion_token: raw.notion_token ?? '',
                database_id: raw.database_id ?? '',
                report_enabled: raw.report_enabled ?? 'false',
                report_schedule: raw.report_schedule ?? '0 0 0,12 * * *',
                report_title_prefix: raw.report_title_prefix ?? '📊 일일 리포트',
                ...Object.fromEntries(
                    Object.entries(raw).filter(([k]) =>
                        !['notion_token','database_id','report_enabled','report_schedule','report_title_prefix'].includes(k)
                    ).map(([k, v]) => [k, v ?? ''])
                ),
            };
            setConfig(configData);
            setEditConfig(configData);
        } catch (e) {
            console.error(e);
        }
    }, []);

    const loadLogs = useCallback(async () => {
        setLogsLoading(true);
        try {
            const res = await adminReportApi.getHistory(20);
            setLogs((res as any).data ?? []);
        } catch (e) {
            console.error(e);
        } finally {
            setLogsLoading(false);
        }
    }, []);

    useEffect(() => { loadConfig(); loadLogs(); }, [loadConfig, loadLogs]);

    const handleConfigChange = (key: string, value: string) => {
        setEditConfig(prev => ({ ...prev, [key]: value }));
        setConfigDirty(true);
    };

    const handleSaveConfig = async () => {
        setSavingConfig(true);
        try {
            await adminReportApi.updateConfig(editConfig);
            setConfig(editConfig);
            setConfigDirty(false);
        } catch (e: any) {
            alert('저장 실패: ' + e.message);
        } finally {
            setSavingConfig(false);
        }
    };

    const handleTrigger = async () => {
        setTriggering(true);
        setTriggerResult(null);
        try {
            const res = await adminReportApi.trigger(triggerHours);
            const d = (res as any).data ?? res;
            setTriggerResult('보고서 생성 성공: ' + (d.notionPageUrl || d.message || '완료'));
            loadLogs();
        } catch (e: any) {
            setTriggerResult('오류: ' + (e.message || '보고서 생성 실패'));
        } finally {
            setTriggering(false);
        }
    };

    const statusBadge = (status: string) => {
        if (status === 'SUCCESS') return <span className="flex items-center gap-1 text-xs text-green-400 bg-green-500/10 px-2 py-0.5 rounded-full"><CheckCircle className="w-3 h-3" />성공</span>;
        if (status === 'FAILED') return <span className="flex items-center gap-1 text-xs text-red-400 bg-red-500/10 px-2 py-0.5 rounded-full"><XCircle className="w-3 h-3" />실패</span>;
        return <span className="flex items-center gap-1 text-xs text-yellow-400 bg-yellow-500/10 px-2 py-0.5 rounded-full"><Clock className="w-3 h-3" />진행중</span>;
    };

    return (
        <div className="p-6 space-y-6 text-slate-100">
            <div className="flex items-center gap-3">
                <BookOpen className="w-6 h-6 text-indigo-400" />
                <h1 className="text-2xl font-bold">Notion 보고서</h1>
            </div>

            {/* Notion 설정 */}
            <div className="bg-slate-800 border border-slate-700 rounded-xl p-5 space-y-4">
                <div className="flex items-center justify-between">
                    <h2 className="text-lg font-semibold text-indigo-300">Notion 연동 설정</h2>
                    {configDirty && (
                        <button
                            onClick={handleSaveConfig}
                            disabled={savingConfig}
                            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 rounded-lg text-sm disabled:opacity-50 transition-colors"
                        >
                            <Save className="w-4 h-4" />
                            {savingConfig ? '저장 중...' : '저장'}
                        </button>
                    )}
                </div>

                <div className="grid grid-cols-2 gap-4">
                    <div className="col-span-2">
                        <label className="block text-xs text-slate-400 mb-1">Notion Integration Token</label>
                        <input
                            type="password"
                            className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                            placeholder="secret_..."
                            value={editConfig.notion_token}
                            onChange={e => handleConfigChange('notion_token', e.target.value)}
                        />
                        <p className="text-xs text-slate-500 mt-1">Notion Integrations 페이지에서 발급한 토큰</p>
                    </div>

                    <div>
                        <label className="block text-xs text-slate-400 mb-1">Database ID</label>
                        <input
                            className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                            placeholder="32자리 hex ID"
                            value={editConfig.database_id}
                            onChange={e => handleConfigChange('database_id', e.target.value)}
                        />
                    </div>

                    <div>
                        <label className="block text-xs text-slate-400 mb-1">보고서 제목 접두사</label>
                        <input
                            className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                            value={editConfig.report_title_prefix}
                            onChange={e => handleConfigChange('report_title_prefix', e.target.value)}
                        />
                    </div>

                    <div>
                        <label className="block text-xs text-slate-400 mb-1">스케줄 (Cron 표현식)</label>
                        <input
                            className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                            value={editConfig.report_schedule}
                            onChange={e => handleConfigChange('report_schedule', e.target.value)}
                        />
                        <p className="text-xs text-slate-500 mt-1">기본: 매일 0시, 12시 (KST)</p>
                    </div>

                    <div className="flex items-center gap-3">
                        <input
                            type="checkbox"
                            id="report-enabled"
                            checked={editConfig.report_enabled === 'true'}
                            onChange={e => handleConfigChange('report_enabled', e.target.checked ? 'true' : 'false')}
                            className="w-4 h-4 accent-indigo-500"
                        />
                        <label htmlFor="report-enabled" className="text-sm">자동 보고서 생성 활성화</label>
                    </div>
                </div>
            </div>

            {/* 수동 트리거 */}
            <div className="bg-slate-800 border border-slate-700 rounded-xl p-5">
                <h2 className="text-lg font-semibold text-indigo-300 mb-4">보고서 즉시 생성</h2>
                <div className="flex items-center gap-3">
                    <div>
                        <label className="block text-xs text-slate-400 mb-1">분석 기간 (시간)</label>
                        <input
                            type="number"
                            className="w-28 bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                            value={triggerHours}
                            onChange={e => setTriggerHours(Number(e.target.value))}
                            min={1}
                            max={168}
                        />
                    </div>
                    <div className="mt-5">
                        <button
                            onClick={handleTrigger}
                            disabled={triggering}
                            className="flex items-center gap-2 px-4 py-2 bg-green-700 hover:bg-green-600 disabled:opacity-50 rounded-lg text-sm transition-colors"
                        >
                            <Play className="w-4 h-4" />
                            {triggering ? '생성 중...' : '보고서 생성'}
                        </button>
                    </div>
                </div>
                {triggerResult && (
                    <p className={`mt-3 text-sm ${triggerResult.startsWith('오류') ? 'text-red-400' : 'text-green-400'}`}>
                        {triggerResult}
                    </p>
                )}
            </div>

            {/* 보고서 이력 */}
            <div className="bg-slate-800 border border-slate-700 rounded-xl p-5">
                <div className="flex items-center justify-between mb-4">
                    <h2 className="text-lg font-semibold text-indigo-300">보고서 이력</h2>
                    <button onClick={loadLogs} className="p-1.5 rounded-lg bg-slate-700 hover:bg-slate-600 transition-colors">
                        <RefreshCw className={`w-4 h-4 ${logsLoading ? 'animate-spin' : ''}`} />
                    </button>
                </div>

                {logsLoading ? (
                    <div className="text-center py-8 text-slate-400">로딩 중...</div>
                ) : (
                    <div className="space-y-2">
                        {logs.map(log => (
                            <div key={log.id} className="bg-slate-700/50 rounded-lg overflow-hidden">
                                <button
                                    onClick={() => setExpandedLog(expandedLog === log.id ? null : log.id)}
                                    className="w-full flex items-center justify-between p-3 hover:bg-slate-700 transition-colors text-left"
                                >
                                    <div className="flex items-center gap-3">
                                        {statusBadge(log.status)}
                                        <span className="text-sm font-medium">{log.reportType}</span>
                                        <span className="text-xs text-slate-400">
                                            {new Date(log.periodStart).toLocaleString('ko-KR')} ~ {new Date(log.periodEnd).toLocaleString('ko-KR')}
                                        </span>
                                    </div>
                                    <div className="flex items-center gap-2">
                                        {log.notionPageUrl && (
                                            <a
                                                href={log.notionPageUrl}
                                                target="_blank"
                                                rel="noreferrer"
                                                onClick={e => e.stopPropagation()}
                                                className="flex items-center gap-1 text-xs text-indigo-400 hover:text-indigo-300"
                                            >
                                                <ExternalLink className="w-3 h-3" />
                                                Notion
                                            </a>
                                        )}
                                        <span className="text-xs text-slate-500">{new Date(log.createdAt).toLocaleString('ko-KR')}</span>
                                        {expandedLog === log.id ? <ChevronUp className="w-4 h-4 text-slate-400" /> : <ChevronDown className="w-4 h-4 text-slate-400" />}
                                    </div>
                                </button>

                                {expandedLog === log.id && (
                                    <div className="px-4 pb-4 space-y-3 border-t border-slate-600/50 pt-3">
                                        {log.errorMessage && (
                                            <div className="bg-red-900/30 border border-red-700/50 rounded-lg p-3">
                                                <p className="text-xs text-red-300 font-medium mb-1">오류 메시지</p>
                                                <p className="text-sm text-red-200">{log.errorMessage}</p>
                                            </div>
                                        )}
                                        {log.llmSummary && (
                                            <div>
                                                <p className="text-xs text-slate-400 font-medium mb-1 flex items-center gap-1">
                                                    <FileText className="w-3 h-3" />LLM 요약
                                                </p>
                                                <p className="text-sm text-slate-300 bg-slate-800 rounded-lg p-3 whitespace-pre-wrap">{log.llmSummary}</p>
                                            </div>
                                        )}
                                        {log.llmAnalysis && (
                                            <div>
                                                <p className="text-xs text-slate-400 font-medium mb-1 flex items-center gap-1">
                                                    <FileText className="w-3 h-3" />LLM 분석
                                                </p>
                                                <p className="text-sm text-slate-300 bg-slate-800 rounded-lg p-3 whitespace-pre-wrap">{log.llmAnalysis}</p>
                                            </div>
                                        )}
                                    </div>
                                )}
                            </div>
                        ))}
                        {logs.length === 0 && (
                            <div className="text-center py-8 text-slate-500">보고서 이력이 없습니다.</div>
                        )}
                    </div>
                )}
            </div>
        </div>
    );
}
