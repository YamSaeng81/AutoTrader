'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { settingsApi } from '@/lib/api';
import { UpbitCandleSummary } from '@/lib/types';
import {
    Loader2, CheckCircle2, XCircle, RefreshCw,
    Wifi, Database, Key,
} from 'lucide-react';

export default function UpbitStatusPage() {
    const [refreshKey, setRefreshKey] = useState(0);

    const { data, isLoading, error } = useQuery({
        queryKey: ['upbit-status', refreshKey],
        queryFn: () => settingsApi.upbitStatus(),
        refetchInterval: false,
    });

    const status = data?.data;

    const StatusBadge = ({ ok, label }: { ok: boolean; label: string }) => (
        <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium ${
            ok ? 'bg-green-900 text-green-300' : 'bg-red-900 text-red-300'
        }`}>
            {ok ? <CheckCircle2 className="w-3 h-3" /> : <XCircle className="w-3 h-3" />}
            {label}
        </span>
    );

    return (
        <div className="p-6 max-w-3xl">
            <div className="flex items-center justify-between mb-6">
                <div>
                    <h1 className="text-xl font-bold text-white">Upbit 연동 상태</h1>
                    <p className="text-sm text-gray-400 mt-1">API 키, 잔고 조회, 캔들 데이터 동기화 상태를 점검합니다.</p>
                </div>
                <button
                    onClick={() => setRefreshKey(k => k + 1)}
                    disabled={isLoading}
                    className="flex items-center gap-2 px-3 py-2 bg-gray-700 hover:bg-gray-600 rounded text-sm text-gray-200"
                >
                    {isLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
                    다시 확인
                </button>
            </div>

            {isLoading && (
                <div className="flex items-center gap-2 text-gray-400 py-8">
                    <Loader2 className="w-5 h-5 animate-spin" />
                    점검 중...
                </div>
            )}

            {error && (
                <div className="bg-red-900/30 border border-red-700 rounded p-4 text-red-300 text-sm">
                    서버 연결 실패: {String(error)}
                </div>
            )}

            {status && !isLoading && (
                <div className="space-y-4">
                    {/* API Key */}
                    <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
                        <div className="flex items-center gap-2 mb-3">
                            <Key className="w-4 h-4 text-gray-400" />
                            <span className="text-sm font-medium text-gray-200">API Key 설정</span>
                            <StatusBadge ok={status.apiKeyConfigured} label={status.apiKeyConfigured ? '설정됨' : '미설정'} />
                        </div>
                        {!status.apiKeyConfigured && (
                            <p className="text-xs text-red-400">
                                UPBIT_ACCESS_KEY / UPBIT_SECRET_KEY 환경변수가 설정되지 않았습니다.<br />
                                docker-compose.prod.yml의 backend environment에서 확인하세요.
                            </p>
                        )}
                    </div>

                    {/* Account Query */}
                    <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
                        <div className="flex items-center gap-2 mb-3">
                            <Wifi className="w-4 h-4 text-gray-400" />
                            <span className="text-sm font-medium text-gray-200">잔고 조회 (인증 API)</span>
                            <StatusBadge ok={status.accountQueryOk} label={status.accountQueryOk ? '성공' : '실패'} />
                        </div>
                        {status.accountQueryOk && status.totalAssetKrw !== undefined && (
                            <p className="text-xs text-gray-400">
                                총 자산: <span className="text-white font-mono">{Number(status.totalAssetKrw).toLocaleString()} KRW</span>
                            </p>
                        )}
                        {!status.accountQueryOk && status.accountError && (
                            <p className="text-xs text-red-400 font-mono break-all">{status.accountError}</p>
                        )}
                    </div>

                    {/* Candle Cache */}
                    <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
                        <div className="flex items-center gap-2 mb-3">
                            <Database className="w-4 h-4 text-gray-400" />
                            <span className="text-sm font-medium text-gray-200">캔들 데이터 캐시 (market_data_cache)</span>
                            <StatusBadge ok={status.candleQueryOk} label={status.candleQueryOk ? '조회됨' : '오류'} />
                        </div>

                        {status.candleQueryOk && status.candleSummary && (
                            status.candleSummary.length === 0 ? (
                                <div className="bg-yellow-900/30 border border-yellow-700 rounded p-3">
                                    <p className="text-xs text-yellow-300 font-medium">⚠️ 캐시 데이터 없음</p>
                                    <p className="text-xs text-yellow-400 mt-1">
                                        실전매매 세션이 RUNNING 상태일 때 MarketDataSyncService가 60초마다 캔들을 동기화합니다.<br />
                                        세션이 실행 중인데도 비어 있다면 UpbitRestClient 연결 문제일 수 있습니다.
                                    </p>
                                </div>
                            ) : (
                                <table className="w-full text-xs mt-1">
                                    <thead>
                                        <tr className="text-gray-400 border-b border-gray-700">
                                            <th className="text-left py-1.5">코인</th>
                                            <th className="text-left py-1.5">타임프레임</th>
                                            <th className="text-right py-1.5">캔들 수</th>
                                            <th className="text-right py-1.5">최신 시각</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {status.candleSummary.map((row: UpbitCandleSummary) => {
                                            const isRecent = row.to
                                                ? (Date.now() - new Date(row.to).getTime()) < 3 * 60 * 1000
                                                : false;
                                            return (
                                                <tr key={`${row.coinPair}-${row.timeframe}`} className="border-b border-gray-700/50">
                                                    <td className="py-1.5 text-white">{row.coinPair}</td>
                                                    <td className="py-1.5 text-gray-300">{row.timeframe}</td>
                                                    <td className={`py-1.5 text-right font-mono ${row.count >= 10 ? 'text-green-400' : 'text-red-400'}`}>
                                                        {row.count}
                                                    </td>
                                                    <td className={`py-1.5 text-right font-mono ${isRecent ? 'text-green-400' : 'text-yellow-400'}`}>
                                                        {row.to ? new Date(row.to).toLocaleTimeString('ko-KR') : '-'}
                                                    </td>
                                                </tr>
                                            );
                                        })}
                                    </tbody>
                                </table>
                            )
                        )}

                        {!status.candleQueryOk && status.candleError && (
                            <p className="text-xs text-red-400 font-mono break-all">{status.candleError}</p>
                        )}
                    </div>

                    {/* 진단 가이드 */}
                    {(!status.apiKeyConfigured || !status.accountQueryOk ||
                        (status.candleSummary && status.candleSummary.length === 0)) && (
                        <div className="bg-blue-900/20 border border-blue-700/50 rounded-lg p-4">
                            <p className="text-xs font-medium text-blue-300 mb-2">진단 가이드</p>
                            <ul className="text-xs text-blue-400 space-y-1 list-disc list-inside">
                                {!status.apiKeyConfigured && (
                                    <li>docker-compose.prod.yml → backend environment에 API_AUTH_TOKEN, UPBIT_ACCESS_KEY, UPBIT_SECRET_KEY 추가 후 재빌드</li>
                                )}
                                {status.apiKeyConfigured && !status.accountQueryOk && (
                                    <li>API 키가 등록은 됐지만 잔고 조회 실패 → 키 만료 또는 IP 제한 확인</li>
                                )}
                                {status.apiKeyConfigured && status.candleSummary && status.candleSummary.length === 0 && (
                                    <li>캔들 캐시 비어 있음 → 실전매매 세션이 RUNNING이어야 자동 동기화됩니다. 세션을 시작하고 60초 후 재확인하세요.</li>
                                )}
                            </ul>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
