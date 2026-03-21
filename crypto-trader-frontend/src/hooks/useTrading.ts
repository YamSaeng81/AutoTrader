import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { tradingApi } from '@/lib/api';
import type {
  TradingStatus, Position, LiveOrder, ExchangeHealth, RiskConfig,
  PageResponse, LiveTradingSession, LiveTradingStartRequest, MultiStrategyLiveRequest,
} from '@/lib/types';

// ─── Query Keys ──────────────────────────────────────────────────────────────
export const tradingKeys = {
  all: ['trading'] as const,
  status: () => [...tradingKeys.all, 'status'] as const,
  sessions: () => [...tradingKeys.all, 'sessions'] as const,
  session: (id: number) => [...tradingKeys.all, 'session', id] as const,
  sessionPositions: (id: number) => [...tradingKeys.all, 'session', id, 'positions'] as const,
  sessionOrders: (id: number, page?: number) => [...tradingKeys.all, 'session', id, 'orders', page ?? 0] as const,
  positions: () => [...tradingKeys.all, 'positions'] as const,
  orders: (page?: number) => [...tradingKeys.all, 'orders', page ?? 0] as const,
  riskConfig: () => [...tradingKeys.all, 'risk-config'] as const,
  exchangeHealth: () => [...tradingKeys.all, 'exchange-health'] as const,
};

// ─── 전체 매매 상태 (5초 갱신) ────────────────────────────────────────────────
export function useTradingStatus() {
  return useQuery({
    queryKey: tradingKeys.status(),
    queryFn: () => tradingApi.getStatus(),
    refetchInterval: 5000,
    select: (res) => (res?.data as unknown as TradingStatus) ?? null,
  });
}

// ─── 세션 목록 (5초 갱신) ─────────────────────────────────────────────────────
export function useTradingSessions() {
  return useQuery({
    queryKey: tradingKeys.sessions(),
    queryFn: () => tradingApi.listSessions(),
    refetchInterval: 5000,
    select: (res) => (res?.data as unknown as LiveTradingSession[]) ?? [],
  });
}

// ─── 세션 상세 ────────────────────────────────────────────────────────────────
export function useTradingSession(id: number) {
  return useQuery({
    queryKey: tradingKeys.session(id),
    queryFn: () => tradingApi.getSession(id),
    enabled: id > 0,
    select: (res) => (res?.data as unknown as LiveTradingSession) ?? null,
  });
}

// ─── 세션 생성 ────────────────────────────────────────────────────────────────
export function useCreateTradingSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: LiveTradingStartRequest) => tradingApi.createSession(req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: tradingKeys.sessions() });
      qc.invalidateQueries({ queryKey: tradingKeys.status() });
    },
  });
}

// ─── 다중 세션 일괄 생성 ──────────────────────────────────────────────────────
export function useCreateMultipleTradingSessions() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: MultiStrategyLiveRequest) => tradingApi.createMulti(req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: tradingKeys.sessions() });
      qc.invalidateQueries({ queryKey: tradingKeys.status() });
    },
  });
}

// ─── 세션 시작 ────────────────────────────────────────────────────────────────
export function useStartTradingSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => tradingApi.startSession(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: tradingKeys.sessions() });
      qc.invalidateQueries({ queryKey: tradingKeys.status() });
    },
  });
}

// ─── 세션 정지 ────────────────────────────────────────────────────────────────
export function useStopTradingSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => tradingApi.stopSession(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: tradingKeys.sessions() });
      qc.invalidateQueries({ queryKey: tradingKeys.status() });
    },
  });
}

// ─── 세션 비상 정지 ───────────────────────────────────────────────────────────
export function useEmergencyStopSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => tradingApi.emergencyStopSession(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: tradingKeys.sessions() });
      qc.invalidateQueries({ queryKey: tradingKeys.status() });
    },
  });
}

// ─── 세션 삭제 ────────────────────────────────────────────────────────────────
export function useDeleteTradingSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => tradingApi.deleteSession(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: tradingKeys.sessions() });
      qc.invalidateQueries({ queryKey: tradingKeys.status() });
    },
  });
}

// ─── 전체 비상 정지 ───────────────────────────────────────────────────────────
export function useEmergencyStopAll() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => tradingApi.emergencyStopAll(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: tradingKeys.all });
    },
  });
}

// ─── 세션 포지션 목록 ─────────────────────────────────────────────────────────
export function useSessionPositions(sessionId: number) {
  return useQuery({
    queryKey: tradingKeys.sessionPositions(sessionId),
    queryFn: () => tradingApi.getSessionPositions(sessionId),
    enabled: sessionId > 0,
    refetchInterval: 5000,
    select: (res) => (res?.data as unknown as Position[]) ?? [],
  });
}

// ─── 세션 주문 내역 ───────────────────────────────────────────────────────────
export function useSessionOrders(sessionId: number, page = 0, size = 20) {
  return useQuery({
    queryKey: tradingKeys.sessionOrders(sessionId, page),
    queryFn: () => tradingApi.getSessionOrders(sessionId, page, size),
    enabled: sessionId > 0,
    refetchInterval: 5000,
    select: (res) => (res?.data as unknown as PageResponse<LiveOrder>) ?? { content: [], totalElements: 0, totalPages: 0, number: 0 },
  });
}

// ─── 전체 포지션 목록 (5초 갱신) ──────────────────────────────────────────────
export function usePositions() {
  return useQuery({
    queryKey: tradingKeys.positions(),
    queryFn: () => tradingApi.getPositions(),
    refetchInterval: 5000,
    select: (res) => (res?.data as unknown as Position[]) ?? [],
  });
}

// ─── 전체 주문 목록 (페이징, 5초 갱신) ────────────────────────────────────────
export function useOrders(page = 0, size = 20) {
  return useQuery({
    queryKey: tradingKeys.orders(page),
    queryFn: () => tradingApi.getOrders(page, size),
    refetchInterval: 5000,
    select: (res) => (res?.data as unknown as PageResponse<LiveOrder>) ?? { content: [], totalElements: 0, totalPages: 0, number: 0 },
  });
}

// ─── 주문 취소 ────────────────────────────────────────────────────────────────
export function useCancelOrder() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => tradingApi.cancelOrder(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: tradingKeys.orders() });
      qc.invalidateQueries({ queryKey: tradingKeys.status() });
    },
  });
}

// ─── 리스크 설정 조회 ─────────────────────────────────────────────────────────
export function useRiskConfig() {
  return useQuery({
    queryKey: tradingKeys.riskConfig(),
    queryFn: () => tradingApi.getRiskConfig(),
    select: (res) => (res?.data as unknown as RiskConfig) ?? null,
  });
}

// ─── 리스크 설정 수정 ─────────────────────────────────────────────────────────
export function useUpdateRiskConfig() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: Omit<RiskConfig, 'id'>) => tradingApi.updateRiskConfig(config),
    onSuccess: () => { qc.invalidateQueries({ queryKey: tradingKeys.riskConfig() }); },
  });
}

// ─── 거래소 상태 (10초 갱신) ──────────────────────────────────────────────────
export function useExchangeHealth() {
  return useQuery({
    queryKey: tradingKeys.exchangeHealth(),
    queryFn: () => tradingApi.getExchangeHealth(),
    refetchInterval: 10000,
    select: (res) => (res?.data as unknown as ExchangeHealth) ?? null,
  });
}
