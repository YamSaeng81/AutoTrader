import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { paperTradingApi } from '@/lib/api';
import type {
  PaperSession,
  PaperTradingBalance,
  PaperPosition,
  PaperTradingStartRequest,
  PageResponse,
} from '@/lib/types';

// ─── Query Keys ──────────────────────────────────────────────────────────────
export const paperKeys = {
  all: ['paper-trading'] as const,
  sessions: () => [...paperKeys.all, 'sessions'] as const,
  session: (id: string | number) => [...paperKeys.all, 'session', String(id)] as const,
  positions: (id: string | number) => [...paperKeys.all, 'session', String(id), 'positions'] as const,
  orders: (id: string | number, page?: number) =>
    [...paperKeys.all, 'session', String(id), 'orders', page ?? 0] as const,
  history: () => [...paperKeys.all, 'history'] as const,
};

// ─── 세션 목록 조회 ────────────────────────────────────────────────────────────
export function usePaperSessions() {
  return useQuery({
    queryKey: paperKeys.sessions(),
    queryFn: () => paperTradingApi.sessions(),
    refetchInterval: 10000,
    select: (res) => (res?.data as unknown as PaperSession[]) ?? [],
  });
}

// ─── 세션 상세 조회 ────────────────────────────────────────────────────────────
export function usePaperSessionDetail(sessionId: string | number) {
  return useQuery({
    queryKey: paperKeys.session(sessionId),
    queryFn: () => paperTradingApi.getSession(sessionId),
    enabled: !!sessionId,
    refetchInterval: 5000,
    select: (res) => (res?.data as unknown as PaperTradingBalance) ?? null,
  });
}

// ─── 세션 포지션 조회 ──────────────────────────────────────────────────────────
export function usePaperPositions(sessionId: string | number) {
  return useQuery({
    queryKey: paperKeys.positions(sessionId),
    queryFn: () => paperTradingApi.positions(sessionId),
    enabled: !!sessionId,
    refetchInterval: 5000,
    select: (res) => (res?.data as unknown as PaperPosition[]) ?? [],
  });
}

// ─── 세션 주문 이력 조회 ──────────────────────────────────────────────────────
export function usePaperOrders(sessionId: string | number, page = 0) {
  return useQuery({
    queryKey: paperKeys.orders(sessionId, page),
    queryFn: () => paperTradingApi.orders(sessionId, page),
    enabled: !!sessionId,
    select: (res) => (res?.data as unknown as PageResponse<unknown>) ?? null,
  });
}

// ─── 세션 시작 mutation ────────────────────────────────────────────────────────
export function useStartPaperSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (req: PaperTradingStartRequest) => paperTradingApi.start(req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: paperKeys.all });
    },
  });
}

// ─── 전체 세션 일괄 정지 mutation ─────────────────────────────────────────────
export function useStopAllPaperSessions() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => paperTradingApi.stopAll(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: paperKeys.all });
    },
  });
}

// ─── 세션 중단 mutation ────────────────────────────────────────────────────────
export function useStopPaperSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => paperTradingApi.stop(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: paperKeys.all });
    },
  });
}

// ─── 세션 이력 단건 삭제 mutation ────────────────────────────────────────────
export function useDeletePaperSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string | number) => paperTradingApi.deleteHistory(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: paperKeys.all });
    },
  });
}

// ─── 세션 이력 다건 삭제 mutation ────────────────────────────────────────────
export function useBulkDeletePaperSessions() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ids: (string | number)[]) => paperTradingApi.bulkDeleteHistory(ids),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: paperKeys.all });
    },
  });
}

// ─── 이력 조회 (완료된 세션 목록) ────────────────────────────────────────────
export function usePaperHistory() {
  return useQuery({
    queryKey: paperKeys.history(),
    queryFn: () => paperTradingApi.sessions(),
    select: (res) => {
      const sessions = (res?.data as unknown as PaperSession[]) ?? [];
      return sessions.filter((s) => s.status === 'STOPPED');
    },
  });
}
