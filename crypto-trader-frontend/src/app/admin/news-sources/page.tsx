'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { adminNewsApi } from '@/lib/api';
import {
    Newspaper, Plus, RefreshCw, Trash2, Edit2, Save, X, Eye,
    CheckCircle, XCircle, Clock, ChevronDown, ChevronUp
} from 'lucide-react';

interface NewsSource {
    id: number;
    sourceId: string;
    displayName: string;
    sourceType: string;
    category: string;
    url: string;
    enabled: boolean;
    fetchIntervalMin: number;
    lastFetchedAt: string | null;
    configJson: string | null;
    apiKeyConfigured: boolean;
    supported: boolean;
}

interface NewsItem {
    id: number;
    sourceId: string;
    externalId: string;
    title: string;
    url: string;
    originalSummary: string | null;
    category: string;
    publishedAt: string;
    fetchedAt: string;
    isSummarized: boolean;
}

const SOURCE_TYPES = ['CRYPTOPANIC', 'RSS', 'COINGECKO'];
const CATEGORIES = ['crypto', 'economy', 'technology', 'general'];

const defaultForm = {
    sourceId: '',
    sourceType: 'RSS',
    category: 'crypto',
    url: '',
    apiKey: '',
    enabled: true,
    fetchIntervalMin: 15,
    configJson: '',
};

export default function NewsSourcesPage() {
    const [sources, setSources] = useState<NewsSource[]>([]);
    const [cacheItems, setCacheItems] = useState<NewsItem[]>([]);
    const [loading, setLoading] = useState(true);
    const [fetchingId, setFetchingId] = useState<string | null>(null);
    const [showAddForm, setShowAddForm] = useState(false);
    const [editingId, setEditingId] = useState<string | null>(null);
    const [form, setForm] = useState({ ...defaultForm });
    const [editForm, setEditForm] = useState<Record<string, any>>({});
    const [showCache, setShowCache] = useState(false);
    const [cacheCategory, setCacheCategory] = useState('');
    const [cacheLoading, setCacheLoading] = useState(false);
    const [fetchResults, setFetchResults] = useState<Record<string, { ok: boolean; msg: string }>>({});

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const res = await adminNewsApi.getSources();
            setSources((res as any).data ?? []);
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    }, []);

    const loadCache = useCallback(async () => {
        setCacheLoading(true);
        try {
            const res = await adminNewsApi.getCache({ size: 50, category: cacheCategory || undefined });
            setCacheItems((res as any).data ?? []);
        } catch (e) {
            console.error(e);
        } finally {
            setCacheLoading(false);
        }
    }, [cacheCategory]);

    useEffect(() => { load(); }, [load]);
    useEffect(() => { if (showCache) loadCache(); }, [showCache, loadCache]);

    const handleCreate = async () => {
        try {
            await adminNewsApi.createSource(form);
            setShowAddForm(false);
            setForm({ ...defaultForm });
            load();
        } catch (e: any) {
            alert('생성 실패: ' + e.message);
        }
    };

    const startEdit = (src: NewsSource) => {
        setEditingId(src.sourceId);
        setEditForm({
            sourceType: src.sourceType,
            category: src.category,
            url: src.url,
            apiKey: '',
            enabled: src.enabled,
            fetchIntervalMin: src.fetchIntervalMin,
            configJson: src.configJson || '',
        });
    };

    const handleUpdate = async (sourceId: string) => {
        try {
            await adminNewsApi.updateSource(sourceId, editForm);
            setEditingId(null);
            load();
        } catch (e: any) {
            alert('수정 실패: ' + e.message);
        }
    };

    const handleDelete = async (sourceId: string) => {
        if (!confirm(`'${sourceId}' 소스를 삭제하시겠습니까?`)) return;
        try {
            await adminNewsApi.deleteSource(sourceId);
            load();
        } catch (e: any) {
            alert('삭제 실패: ' + e.message);
        }
    };

    const handleFetchNow = async (sourceId: string) => {
        setFetchingId(sourceId);
        setFetchResults(prev => ({ ...prev, [sourceId]: { ok: false, msg: '수집 중...' } }));
        try {
            const res = await adminNewsApi.fetchNow(sourceId);
            setFetchResults(prev => ({ ...prev, [sourceId]: { ok: true, msg: res.message || '수집 완료' } }));
            load();
        } catch (e: any) {
            setFetchResults(prev => ({ ...prev, [sourceId]: { ok: false, msg: e.message || '수집 실패' } }));
        } finally {
            setFetchingId(null);
        }
    };

    const typeColor: Record<string, string> = {
        CRYPTOPANIC: 'bg-orange-500/20 text-orange-300',
        RSS: 'bg-blue-500/20 text-blue-300',
        COINGECKO: 'bg-green-500/20 text-green-300',
    };

    const catColor: Record<string, string> = {
        crypto: 'bg-yellow-500/20 text-yellow-300',
        economy: 'bg-purple-500/20 text-purple-300',
        technology: 'bg-cyan-500/20 text-cyan-300',
        general: 'bg-slate-500/20 text-slate-300',
    };

    return (
        <div className="p-6 space-y-6 text-slate-100">
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <Newspaper className="w-6 h-6 text-indigo-400" />
                    <h1 className="text-2xl font-bold">뉴스 소스 관리</h1>
                </div>
                <div className="flex gap-2">
                    <button
                        onClick={() => setShowCache(!showCache)}
                        className="flex items-center gap-2 px-3 py-2 bg-slate-700 hover:bg-slate-600 rounded-lg text-sm transition-colors"
                    >
                        <Eye className="w-4 h-4" />
                        캐시 보기
                    </button>
                    <button
                        onClick={() => setShowAddForm(!showAddForm)}
                        className="flex items-center gap-2 px-3 py-2 bg-indigo-600 hover:bg-indigo-500 rounded-lg text-sm transition-colors"
                    >
                        <Plus className="w-4 h-4" />
                        소스 추가
                    </button>
                </div>
            </div>

            {/* 추가 폼 */}
            {showAddForm && (
                <div className="bg-slate-800 border border-slate-700 rounded-xl p-5 space-y-4">
                    <h2 className="text-lg font-semibold text-indigo-300">새 뉴스 소스</h2>
                    <div className="grid grid-cols-2 gap-4">
                        <div>
                            <label className="block text-xs text-slate-400 mb-1">소스 ID *</label>
                            <input
                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                                placeholder="예: my_rss_feed"
                                value={form.sourceId}
                                onChange={e => setForm(p => ({ ...p, sourceId: e.target.value }))}
                            />
                        </div>
                        <div>
                            <label className="block text-xs text-slate-400 mb-1">소스 타입</label>
                            <select
                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                                value={form.sourceType}
                                onChange={e => setForm(p => ({ ...p, sourceType: e.target.value }))}
                            >
                                {SOURCE_TYPES.map(t => <option key={t}>{t}</option>)}
                            </select>
                        </div>
                        <div>
                            <label className="block text-xs text-slate-400 mb-1">카테고리</label>
                            <select
                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                                value={form.category}
                                onChange={e => setForm(p => ({ ...p, category: e.target.value }))}
                            >
                                {CATEGORIES.map(c => <option key={c}>{c}</option>)}
                            </select>
                        </div>
                        <div>
                            <label className="block text-xs text-slate-400 mb-1">수집 간격 (분)</label>
                            <input
                                type="number"
                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                                value={form.fetchIntervalMin}
                                onChange={e => setForm(p => ({ ...p, fetchIntervalMin: Number(e.target.value) }))}
                            />
                        </div>
                        <div className="col-span-2">
                            <label className="block text-xs text-slate-400 mb-1">URL</label>
                            <input
                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                                placeholder="https://..."
                                value={form.url}
                                onChange={e => setForm(p => ({ ...p, url: e.target.value }))}
                            />
                        </div>
                        <div>
                            <label className="block text-xs text-slate-400 mb-1">API Key (선택)</label>
                            <input
                                type="password"
                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                                value={form.apiKey}
                                onChange={e => setForm(p => ({ ...p, apiKey: e.target.value }))}
                            />
                        </div>
                        <div>
                            <label className="block text-xs text-slate-400 mb-1">추가 설정 JSON (선택)</label>
                            <input
                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-indigo-500"
                                placeholder='{"filter": "hot"}'
                                value={form.configJson}
                                onChange={e => setForm(p => ({ ...p, configJson: e.target.value }))}
                            />
                        </div>
                        <div className="flex items-center gap-2">
                            <input
                                type="checkbox"
                                id="new-enabled"
                                checked={form.enabled}
                                onChange={e => setForm(p => ({ ...p, enabled: e.target.checked }))}
                                className="w-4 h-4 accent-indigo-500"
                            />
                            <label htmlFor="new-enabled" className="text-sm">활성화</label>
                        </div>
                    </div>
                    <div className="flex gap-2 justify-end">
                        <button
                            onClick={() => { setShowAddForm(false); setForm({ ...defaultForm }); }}
                            className="flex items-center gap-1 px-4 py-2 bg-slate-700 hover:bg-slate-600 rounded-lg text-sm"
                        >
                            <X className="w-3 h-3" /> 취소
                        </button>
                        <button
                            onClick={handleCreate}
                            className="flex items-center gap-1 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 rounded-lg text-sm"
                        >
                            <Save className="w-3 h-3" /> 저장
                        </button>
                    </div>
                </div>
            )}

            {/* 소스 목록 */}
            {loading ? (
                <div className="text-center py-12 text-slate-400">로딩 중...</div>
            ) : (
                <div className="space-y-3">
                    {sources.map(src => (
                        <div key={src.sourceId} className="bg-slate-800 border border-slate-700 rounded-xl p-4">
                            {editingId === src.sourceId ? (
                                /* 편집 모드 */
                                <div className="space-y-3">
                                    <div className="flex items-center justify-between mb-2">
                                        <span className="font-semibold text-indigo-300">{src.sourceId}</span>
                                        <div className="flex gap-2">
                                            <button onClick={() => setEditingId(null)} className="p-1 hover:text-slate-300 text-slate-400"><X className="w-4 h-4" /></button>
                                            <button onClick={() => handleUpdate(src.sourceId)} className="p-1 hover:text-green-300 text-green-400"><Save className="w-4 h-4" /></button>
                                        </div>
                                    </div>
                                    <div className="grid grid-cols-2 gap-3">
                                        <div>
                                            <label className="block text-xs text-slate-400 mb-1">소스 타입</label>
                                            <select
                                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm"
                                                value={editForm.sourceType}
                                                onChange={e => setEditForm(p => ({ ...p, sourceType: e.target.value }))}
                                            >
                                                {SOURCE_TYPES.map(t => <option key={t}>{t}</option>)}
                                            </select>
                                        </div>
                                        <div>
                                            <label className="block text-xs text-slate-400 mb-1">카테고리</label>
                                            <select
                                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm"
                                                value={editForm.category}
                                                onChange={e => setEditForm(p => ({ ...p, category: e.target.value }))}
                                            >
                                                {CATEGORIES.map(c => <option key={c}>{c}</option>)}
                                            </select>
                                        </div>
                                        <div className="col-span-2">
                                            <label className="block text-xs text-slate-400 mb-1">URL</label>
                                            <input
                                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm"
                                                value={editForm.url}
                                                onChange={e => setEditForm(p => ({ ...p, url: e.target.value }))}
                                            />
                                        </div>
                                        <div>
                                            <label className="block text-xs text-slate-400 mb-1">API Key (비어두면 유지)</label>
                                            <input
                                                type="password"
                                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm"
                                                value={editForm.apiKey}
                                                onChange={e => setEditForm(p => ({ ...p, apiKey: e.target.value }))}
                                            />
                                        </div>
                                        <div>
                                            <label className="block text-xs text-slate-400 mb-1">수집 간격 (분)</label>
                                            <input
                                                type="number"
                                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm"
                                                value={editForm.fetchIntervalMin}
                                                onChange={e => setEditForm(p => ({ ...p, fetchIntervalMin: Number(e.target.value) }))}
                                            />
                                        </div>
                                        <div className="col-span-2">
                                            <label className="block text-xs text-slate-400 mb-1">추가 설정 JSON</label>
                                            <input
                                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm"
                                                value={editForm.configJson}
                                                onChange={e => setEditForm(p => ({ ...p, configJson: e.target.value }))}
                                            />
                                        </div>
                                        <div className="flex items-center gap-2">
                                            <input
                                                type="checkbox"
                                                checked={editForm.enabled}
                                                onChange={e => setEditForm(p => ({ ...p, enabled: e.target.checked }))}
                                                className="w-4 h-4 accent-indigo-500"
                                            />
                                            <span className="text-sm">활성화</span>
                                        </div>
                                    </div>
                                </div>
                            ) : (
                                /* 보기 모드 */
                                <div className="flex items-start justify-between gap-4">
                                    <div className="flex-1 min-w-0">
                                        <div className="flex items-center gap-2 mb-1">
                                            <span className="font-semibold">{src.sourceId}</span>
                                            <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${typeColor[src.sourceType] || 'bg-slate-600 text-slate-300'}`}>
                                                {src.sourceType}
                                            </span>
                                            <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${catColor[src.category] || 'bg-slate-600 text-slate-300'}`}>
                                                {src.category}
                                            </span>
                                            {src.enabled
                                                ? <span className="flex items-center gap-1 text-xs text-green-400"><CheckCircle className="w-3 h-3" />활성</span>
                                                : <span className="flex items-center gap-1 text-xs text-slate-500"><XCircle className="w-3 h-3" />비활성</span>
                                            }
                                            {!src.supported && <span className="text-xs text-red-400 bg-red-500/10 px-2 py-0.5 rounded-full">구현 없음</span>}
                                            {src.apiKeyConfigured && <span className="text-xs text-yellow-400 bg-yellow-500/10 px-2 py-0.5 rounded-full">API Key 설정됨</span>}
                                        </div>
                                        <p className="text-sm text-slate-400 truncate">{src.url}</p>
                                        <div className="flex items-center gap-4 mt-1 text-xs text-slate-500">
                                            <span className="flex items-center gap-1"><Clock className="w-3 h-3" />{src.fetchIntervalMin}분마다 수집</span>
                                            {src.lastFetchedAt && <span>마지막 수집: {new Date(src.lastFetchedAt).toLocaleString('ko-KR')}</span>}
                                        </div>
                                        {fetchResults[src.sourceId] && (
                                            <p className={`mt-1 text-xs ${fetchResults[src.sourceId].ok ? 'text-green-400' : 'text-red-400'}`}>
                                                {fetchResults[src.sourceId].msg}
                                            </p>
                                        )}
                                    </div>
                                    <div className="flex items-center gap-1 shrink-0">
                                        <button
                                            onClick={() => handleFetchNow(src.sourceId)}
                                            disabled={fetchingId === src.sourceId}
                                            title="지금 수집"
                                            className="p-2 rounded-lg text-slate-400 hover:bg-slate-700 hover:text-blue-300 transition-colors disabled:opacity-50"
                                        >
                                            <RefreshCw className={`w-4 h-4 ${fetchingId === src.sourceId ? 'animate-spin' : ''}`} />
                                        </button>
                                        <button
                                            onClick={() => startEdit(src)}
                                            title="편집"
                                            className="p-2 rounded-lg text-slate-400 hover:bg-slate-700 hover:text-yellow-300 transition-colors"
                                        >
                                            <Edit2 className="w-4 h-4" />
                                        </button>
                                        <button
                                            onClick={() => handleDelete(src.sourceId)}
                                            title="삭제"
                                            className="p-2 rounded-lg text-slate-400 hover:bg-red-900 hover:text-red-300 transition-colors"
                                        >
                                            <Trash2 className="w-4 h-4" />
                                        </button>
                                    </div>
                                </div>
                            )}
                        </div>
                    ))}
                    {sources.length === 0 && (
                        <div className="text-center py-12 text-slate-500">
                            등록된 뉴스 소스가 없습니다.
                        </div>
                    )}
                </div>
            )}

            {/* 뉴스 캐시 뷰어 */}
            {showCache && (
                <div className="bg-slate-800 border border-slate-700 rounded-xl p-5 space-y-4">
                    <div className="flex items-center justify-between">
                        <h2 className="text-lg font-semibold text-indigo-300">뉴스 캐시</h2>
                        <div className="flex items-center gap-2">
                            <select
                                className="bg-slate-700 border border-slate-600 rounded-lg px-3 py-1.5 text-sm"
                                value={cacheCategory}
                                onChange={e => setCacheCategory(e.target.value)}
                            >
                                <option value="">전체</option>
                                {CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
                            </select>
                            <button
                                onClick={loadCache}
                                className="p-1.5 rounded-lg bg-slate-700 hover:bg-slate-600 transition-colors"
                            >
                                <RefreshCw className={`w-4 h-4 ${cacheLoading ? 'animate-spin' : ''}`} />
                            </button>
                        </div>
                    </div>
                    {cacheLoading ? (
                        <div className="text-center py-8 text-slate-400">로딩 중...</div>
                    ) : (
                        <div className="space-y-2 max-h-96 overflow-y-auto">
                            {cacheItems.map(item => (
                                <div key={item.id} className="bg-slate-700/50 rounded-lg p-3">
                                    <div className="flex items-start justify-between gap-2">
                                        <div className="flex-1 min-w-0">
                                            <a
                                                href={item.url}
                                                target="_blank"
                                                rel="noreferrer"
                                                className="text-sm font-medium text-slate-200 hover:text-indigo-300 line-clamp-2"
                                            >
                                                {item.title}
                                            </a>
                                            {item.originalSummary && (
                                                <p className="text-xs text-slate-400 mt-1 line-clamp-2">{item.originalSummary}</p>
                                            )}
                                            <div className="flex items-center gap-3 mt-1 text-xs text-slate-500">
                                                <span>{item.sourceId}</span>
                                                <span className={`px-1.5 py-0.5 rounded ${catColor[item.category] || 'bg-slate-600 text-slate-300'}`}>{item.category}</span>
                                                <span>{new Date(item.publishedAt).toLocaleString('ko-KR')}</span>
                                            </div>
                                        </div>
                                        {item.isSummarized && (
                                            <span className="shrink-0 text-xs bg-green-500/20 text-green-300 px-2 py-0.5 rounded-full">요약됨</span>
                                        )}
                                    </div>
                                </div>
                            ))}
                            {cacheItems.length === 0 && (
                                <div className="text-center py-8 text-slate-500">캐시된 뉴스가 없습니다.</div>
                            )}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
