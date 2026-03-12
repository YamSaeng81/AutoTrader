import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { dataApi } from '@/lib/api';

// ─── Query Keys ──────────────────────────────────────────────────────────────
export const dataKeys = {
  all: ['data'] as const,
  summary: () => [...dataKeys.all, 'summary'] as const,
  coins: () => [...dataKeys.all, 'coins'] as const,
};

// ─── 수집 현황 조회 ────────────────────────────────────────────────────────────
export function useDataStatus() {
  return useQuery({
    queryKey: dataKeys.summary(),
    queryFn: () => dataApi.summary(),
    select: (res) => res?.data ?? [],
  });
}

// ─── 사용 가능한 코인 목록 조회 ──────────────────────────────────────────────
export function useAvailableCoins() {
  return useQuery({
    queryKey: dataKeys.coins(),
    queryFn: () => dataApi.coins(),
    select: (res) => res?.data ?? [],
  });
}

// ─── 데이터 수집 요청 mutation ────────────────────────────────────────────────
export function useCollectData() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (req: {
      coinPair: string;
      timeframe: string;
      startDate: string;
      endDate: string;
    }) => dataApi.collect(req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: dataKeys.summary() });
      queryClient.invalidateQueries({ queryKey: dataKeys.coins() });
    },
  });
}
