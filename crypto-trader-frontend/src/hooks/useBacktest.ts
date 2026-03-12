import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { backtestApi } from '@/lib/api';
import type { BacktestRequest, BacktestResult, WalkForwardRequest, WalkForwardResult, TradeRecord, PageResponse } from '@/lib/types';

// ─── Query Keys ──────────────────────────────────────────────────────────────
export const backtestKeys = {
  all: ['backtest'] as const,
  lists: () => [...backtestKeys.all, 'list'] as const,
  detail: (id: string) => [...backtestKeys.all, id] as const,
  trades: (id: string, page?: number) => [...backtestKeys.all, id, 'trades', page ?? 0] as const,
  compare: (ids: string[]) => [...backtestKeys.all, 'compare', ids.join(',')] as const,
};

// ─── 백테스트 목록 조회 ────────────────────────────────────────────────────────
export function useBacktests(page = 0) {
  return useQuery({
    queryKey: backtestKeys.lists(),
    queryFn: () => backtestApi.list(page),
    select: (res) => (res?.data as unknown as BacktestResult[]) ?? [],
  });
}

// ─── 백테스트 상세 조회 ────────────────────────────────────────────────────────
export function useBacktestDetail(id: string) {
  return useQuery({
    queryKey: backtestKeys.detail(id),
    queryFn: () => backtestApi.get(id),
    enabled: !!id,
    select: (res) => res?.data ?? null,
  });
}

// ─── 백테스트 매매 기록 조회 ──────────────────────────────────────────────────
export function useBacktestTrades(id: string, page = 0) {
  return useQuery({
    queryKey: backtestKeys.trades(id, page),
    queryFn: () => backtestApi.trades(id, page),
    enabled: !!id,
    select: (res) => (res?.data as unknown as PageResponse<TradeRecord>) ?? null,
  });
}

// ─── 백테스트 실행 mutation ────────────────────────────────────────────────────
export function useRunBacktest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (req: BacktestRequest) => backtestApi.run(req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: backtestKeys.lists() });
    },
  });
}

// ─── Walk Forward 실행 mutation ───────────────────────────────────────────────
export function useWalkForward() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (req: WalkForwardRequest) => backtestApi.walkForward(req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: backtestKeys.lists() });
    },
    select: (res: { data: WalkForwardResult | null }) => res?.data ?? null,
  });
}

// ─── 백테스트 단건 삭제 mutation ──────────────────────────────────────────────
export function useDeleteBacktest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string | number) => backtestApi.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: backtestKeys.lists() });
    },
  });
}

// ─── 백테스트 다건 삭제 mutation ──────────────────────────────────────────────
export function useBulkDeleteBacktests() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ids: (string | number)[]) => backtestApi.bulkDelete(ids),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: backtestKeys.lists() });
    },
  });
}

// ─── 전략 비교 조회 ────────────────────────────────────────────────────────────
export function useCompareBacktests(ids: string[]) {
  return useQuery({
    queryKey: backtestKeys.compare(ids),
    queryFn: () => backtestApi.compare(ids),
    enabled: ids.length >= 2,
    select: (res) => (res?.data as unknown as BacktestResult[]) ?? [],
  });
}
