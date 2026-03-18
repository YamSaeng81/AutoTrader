'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { settingsApi } from '@/lib/api';
import { UpbitCandleSummary } from '@/lib/types';
import {
    Loader2, CheckCircle2, XCircle, RefreshCw,
    Wifi, Database, Key, ShoppingCart, TestTube, ClipboardList,
} from 'lucide-react';

export default function UpbitStatusPage() {
    const [refreshKey, setRefreshKey] = useState(0);

    // 주문 가능 정보
    const [chanceMarket, setChanceMarket] = useState('KRW-ETH');
    const [chanceResult, setChanceResult] = useState<Record<string, unknown> | null>(null);
    const [chanceLoading, setChanceLoading] = useState(false);
    const [chanceError, setChanceError] = useState('');

    // 주문 생성 테스트
    const [testMarket, setTestMarket] = useState('KRW-ETH');
    const [testSide, setTestSide] = useState('bid');
    const [testAmount, setTestAmount] = useState('5000');
    const [testResult, setTestResult] = useState<Record<string, unknown> | null>(null);
    const [testLoading, setTestLoading] = useState(false);
    const [testError, setTestError] = useState('');

    // 최근 주문 이력
    const [ordersMarket, setOrdersMarket] = useState('KRW-ETH');
    const [ordersState, setOrdersState] = useState('done');
    const [ordersLimit, setOrdersLimit] = useState('10');
    const [ordersResult, setOrdersResult] = useState<Record<string, unknown>[] | null>(null);
    const [ordersLoading, setOrdersLoading] = useState(false);
    const [ordersError, setOrdersError] = useState('');

    const { data, isLoading, error } = useQuery({
        queryKey: ['upbit-status', refreshKey],
        queryFn: () => settingsApi.upbitStatus(),
        refetchInterval: false,
    });

    const status = data?.data;

    const MARKETS = ['KRW-BTC', 'KRW-ETH', 'KRW-XRP', 'KRW-SOL', 'KRW-DOGE'];

    const StatusBadge = ({ ok, label }: { ok: boolean; label: string }) => (
        <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium ${
            ok ? 'bg-green-900 text-green-300' : 'bg-red-900 text-red-300'
        }`}>
            {ok ? <CheckCircle2 className="w-3 h-3" /> : <XCircle className="w-3 h-3" />}
            {label}
        </span>
    );

    async function fetchOrderChance() {
        setChanceLoading(true);
        setChanceError('');
        setChanceResult(null);
        try {
            const res = await settingsApi.upbitOrderChance(chanceMarket);
            if (res.data && (res.data as Record<string, unknown>)['error']) {
                setChanceError(String((res.data as Record<string, unknown>)['error']));
            } else {
                setChanceResult(res.data as Record<string, unknown>);
            }
        } catch (e) {
            setChanceError(String(e));
        } finally {
            setChanceLoading(false);
        }
    }

    async function runTestOrder() {
        setTestLoading(true);
        setTestError('');
        setTestResult(null);
        try {
            const res = await settingsApi.upbitTestOrder(testMarket, testSide, Number(testAmount));
            const d = res.data as Record<string, unknown>;
            if (!d['success']) {
                setTestError(String(d['error'] ?? '알 수 없는 오류'));
            } else {
                setTestResult(d);
            }
        } catch (e) {
            setTestError(String(e));
        } finally {
            setTestLoading(false);
        }
    }

    async function fetchOrders() {
        setOrdersLoading(true);
        setOrdersError('');
        setOrdersResult(null);
        try {
            const res = await settingsApi.upbitExchangeOrders(ordersMarket, ordersState, Number(ordersLimit));
            const d = res.data as Record<string, unknown>;
            if (d['error']) {
                setOrdersError(String(d['error']));
            } else {
                setOrdersResult((d['orders'] as Record<string, unknown>[]) ?? []);
            }
        } catch (e) {
            setOrdersError(String(e));
        } finally {
            setOrdersLoading(false);
        }
    }

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

            {/* ── Upbit API 테스트 섹션 ── */}
            <div className="mt-8 space-y-4">
                <h2 className="text-base font-semibold text-white border-b border-gray-700 pb-2">Upbit API 테스트</h2>

                {/* 주문 가능 정보 조회 */}
                <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
                    <div className="flex items-center gap-2 mb-3">
                        <ShoppingCart className="w-4 h-4 text-gray-400" />
                        <span className="text-sm font-medium text-gray-200">주문 가능 정보 조회</span>
                        <span className="text-xs text-gray-500">GET /v1/orders/chance</span>
                    </div>
                    <div className="flex items-center gap-2 mb-3">
                        <select
                            value={chanceMarket}
                            onChange={e => setChanceMarket(e.target.value)}
                            className="bg-gray-700 border border-gray-600 text-gray-200 text-sm rounded px-2 py-1"
                        >
                            {MARKETS.map(m => <option key={m} value={m}>{m}</option>)}
                        </select>
                        <button
                            onClick={fetchOrderChance}
                            disabled={chanceLoading}
                            className="flex items-center gap-1.5 px-3 py-1.5 bg-blue-700 hover:bg-blue-600 disabled:opacity-50 rounded text-sm text-white"
                        >
                            {chanceLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : null}
                            조회
                        </button>
                    </div>
                    {chanceError && <p className="text-xs text-red-400 font-mono break-all mb-2">{chanceError}</p>}
                    {chanceResult && (
                        <div className="space-y-2 text-xs">
                            <div className="grid grid-cols-2 gap-2">
                                <div className="bg-gray-700/50 rounded p-2">
                                    <p className="text-gray-400 mb-1">매수 수수료</p>
                                    <p className="text-white font-mono">{chanceResult['bid_fee'] ? `${(Number(chanceResult['bid_fee']) * 100).toFixed(3)}%` : '-'}</p>
                                </div>
                                <div className="bg-gray-700/50 rounded p-2">
                                    <p className="text-gray-400 mb-1">매도 수수료</p>
                                    <p className="text-white font-mono">{chanceResult['ask_fee'] ? `${(Number(chanceResult['ask_fee']) * 100).toFixed(3)}%` : '-'}</p>
                                </div>
                            </div>
                            {chanceResult['bid_account'] && (
                                <div className="bg-gray-700/50 rounded p-2">
                                    <p className="text-gray-400 mb-1">주문 가능 KRW</p>
                                    <p className="text-white font-mono">
                                        {Number((chanceResult['bid_account'] as Record<string, unknown>)['balance'] ?? 0).toLocaleString()} KRW
                                        {(chanceResult['bid_account'] as Record<string, unknown>)['locked'] && Number((chanceResult['bid_account'] as Record<string, unknown>)['locked']) > 0 && (
                                            <span className="text-yellow-400 ml-2">(잠금: {Number((chanceResult['bid_account'] as Record<string, unknown>)['locked']).toLocaleString()})</span>
                                        )}
                                    </p>
                                </div>
                            )}
                            {chanceResult['market'] && (
                                <div className="bg-gray-700/50 rounded p-2">
                                    <p className="text-gray-400 mb-1">최소 주문 금액</p>
                                    <p className="text-white font-mono">
                                        {((chanceResult['market'] as Record<string, unknown>)['bid'] as Record<string, unknown>)?.['min_total']
                                            ? `${Number(((chanceResult['market'] as Record<string, unknown>)['bid'] as Record<string, unknown>)['min_total']).toLocaleString()} KRW`
                                            : '-'}
                                    </p>
                                </div>
                            )}
                        </div>
                    )}
                </div>

                {/* 주문 생성 테스트 */}
                <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
                    <div className="flex items-center gap-2 mb-3">
                        <TestTube className="w-4 h-4 text-gray-400" />
                        <span className="text-sm font-medium text-gray-200">주문 생성 테스트</span>
                        <span className="text-xs text-gray-500">POST /v1/orders/test — 실거래 없음</span>
                    </div>
                    <div className="flex flex-wrap items-center gap-2 mb-3">
                        <select
                            value={testMarket}
                            onChange={e => setTestMarket(e.target.value)}
                            className="bg-gray-700 border border-gray-600 text-gray-200 text-sm rounded px-2 py-1"
                        >
                            {MARKETS.map(m => <option key={m} value={m}>{m}</option>)}
                        </select>
                        <select
                            value={testSide}
                            onChange={e => setTestSide(e.target.value)}
                            className="bg-gray-700 border border-gray-600 text-gray-200 text-sm rounded px-2 py-1"
                        >
                            <option value="bid">매수 (bid)</option>
                            <option value="ask">매도 (ask)</option>
                        </select>
                        <input
                            type="number"
                            value={testAmount}
                            onChange={e => setTestAmount(e.target.value)}
                            placeholder="금액 (KRW)"
                            className="bg-gray-700 border border-gray-600 text-gray-200 text-sm rounded px-2 py-1 w-32"
                        />
                        <button
                            onClick={runTestOrder}
                            disabled={testLoading}
                            className="flex items-center gap-1.5 px-3 py-1.5 bg-blue-700 hover:bg-blue-600 disabled:opacity-50 rounded text-sm text-white"
                        >
                            {testLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : null}
                            테스트 실행
                        </button>
                    </div>
                    <p className="text-xs text-gray-500 mb-2">시장가 매수 기준 (ord_type: price). 실제 체결되지 않으며 API 권한 및 형식 검증용입니다.</p>
                    {testError && <p className="text-xs text-red-400 font-mono break-all">{testError}</p>}
                    {testResult && (
                        <div className="bg-green-900/20 border border-green-700/50 rounded p-3 text-xs space-y-1">
                            <p className="text-green-300 font-medium mb-2">✓ 테스트 주문 성공</p>
                            <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-gray-300 font-mono">
                                <span className="text-gray-500">UUID</span><span className="truncate">{String(testResult['uuid'] ?? '-')}</span>
                                <span className="text-gray-500">마켓</span><span>{String(testResult['market'] ?? '-')}</span>
                                <span className="text-gray-500">방향</span><span>{String(testResult['side'] ?? '-')}</span>
                                <span className="text-gray-500">타입</span><span>{String(testResult['ordType'] ?? '-')}</span>
                                <span className="text-gray-500">금액</span><span>{testResult['price'] ? `${Number(testResult['price']).toLocaleString()} KRW` : '-'}</span>
                                <span className="text-gray-500">상태</span><span>{String(testResult['state'] ?? '-')}</span>
                            </div>
                        </div>
                    )}
                </div>

                {/* 최근 주문 이력 */}
                <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
                    <div className="flex items-center gap-2 mb-3">
                        <ClipboardList className="w-4 h-4 text-gray-400" />
                        <span className="text-sm font-medium text-gray-200">Upbit 최근 주문 이력</span>
                        <span className="text-xs text-gray-500">GET /v1/orders — 거래소 직접 조회</span>
                    </div>
                    <div className="flex flex-wrap items-center gap-2 mb-3">
                        <select
                            value={ordersMarket}
                            onChange={e => setOrdersMarket(e.target.value)}
                            className="bg-gray-700 border border-gray-600 text-gray-200 text-sm rounded px-2 py-1"
                        >
                            {MARKETS.map(m => <option key={m} value={m}>{m}</option>)}
                        </select>
                        <select
                            value={ordersState}
                            onChange={e => setOrdersState(e.target.value)}
                            className="bg-gray-700 border border-gray-600 text-gray-200 text-sm rounded px-2 py-1"
                        >
                            <option value="done">체결완료 (done)</option>
                            <option value="cancel">취소됨 (cancel)</option>
                            <option value="wait">대기중 (wait)</option>
                        </select>
                        <select
                            value={ordersLimit}
                            onChange={e => setOrdersLimit(e.target.value)}
                            className="bg-gray-700 border border-gray-600 text-gray-200 text-sm rounded px-2 py-1"
                        >
                            {[5, 10, 20, 50].map(n => <option key={n} value={n}>{n}건</option>)}
                        </select>
                        <button
                            onClick={fetchOrders}
                            disabled={ordersLoading}
                            className="flex items-center gap-1.5 px-3 py-1.5 bg-blue-700 hover:bg-blue-600 disabled:opacity-50 rounded text-sm text-white"
                        >
                            {ordersLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : null}
                            조회
                        </button>
                    </div>
                    {ordersError && <p className="text-xs text-red-400 font-mono break-all">{ordersError}</p>}
                    {ordersResult && (
                        ordersResult.length === 0 ? (
                            <p className="text-xs text-gray-400">주문 이력 없음</p>
                        ) : (
                            <div className="overflow-x-auto">
                                <table className="w-full text-xs">
                                    <thead>
                                        <tr className="text-gray-400 border-b border-gray-700">
                                            <th className="text-left py-1.5 pr-3">시각</th>
                                            <th className="text-left py-1.5 pr-3">마켓</th>
                                            <th className="text-left py-1.5 pr-3">방향</th>
                                            <th className="text-left py-1.5 pr-3">타입</th>
                                            <th className="text-right py-1.5 pr-3">금액/수량</th>
                                            <th className="text-left py-1.5">상태</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {ordersResult.map((o, i) => {
                                            const createdAt = o['created_at'] ? new Date(String(o['created_at'])).toLocaleString('ko-KR') : '-';
                                            const side = String(o['side'] ?? '');
                                            const ordType = String(o['ord_type'] ?? '');
                                            const priceVal = o['price'] ? `${Number(o['price']).toLocaleString()} KRW` : '-';
                                            const volVal = o['volume'] ? String(o['volume']) : '-';
                                            return (
                                                <tr key={i} className="border-b border-gray-700/50">
                                                    <td className="py-1.5 pr-3 text-gray-400 whitespace-nowrap">{createdAt}</td>
                                                    <td className="py-1.5 pr-3 text-white">{String(o['market'] ?? '-')}</td>
                                                    <td className={`py-1.5 pr-3 font-medium ${side === 'bid' ? 'text-blue-400' : 'text-red-400'}`}>
                                                        {side === 'bid' ? '매수' : side === 'ask' ? '매도' : side}
                                                    </td>
                                                    <td className="py-1.5 pr-3 text-gray-300">{ordType}</td>
                                                    <td className="py-1.5 pr-3 text-right font-mono text-gray-200">
                                                        {ordType === 'price' ? priceVal : ordType === 'market' ? volVal : priceVal}
                                                    </td>
                                                    <td className="py-1.5">
                                                        <span className={`px-1.5 py-0.5 rounded text-xs ${
                                                            o['state'] === 'done' ? 'bg-green-900 text-green-300' :
                                                            o['state'] === 'cancel' ? 'bg-gray-700 text-gray-400' :
                                                            'bg-yellow-900 text-yellow-300'
                                                        }`}>{String(o['state'] ?? '-')}</span>
                                                    </td>
                                                </tr>
                                            );
                                        })}
                                    </tbody>
                                </table>
                            </div>
                        )
                    )}
                </div>
            </div>
        </div>
    );
}
