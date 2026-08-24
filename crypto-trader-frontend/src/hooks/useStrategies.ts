import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { strategyApi } from '@/lib/api';

// ─── Query Keys ──────────────────────────────────────────────────────────────
export const strategyKeys = {
  all: ['strategies'] as const,
  lists: () => [...strategyKeys.all, 'list'] as const,
  detail: (name: string) => [...strategyKeys.all, name] as const,
};

// ─── 전략 목록 조회 ────────────────────────────────────────────────────────────
export function useStrategies() {
  return useQuery({
    queryKey: strategyKeys.all,
    queryFn: () => strategyApi.list(),
    select: (res) => res?.data ?? [],
  });
}

// ─── 전략 타입 활성화 여부 토글 mutation ──────────────────────────────────────
export function useToggleStrategyActive() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => strategyApi.toggleActive(name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: strategyKeys.all });
    },
  });
}
