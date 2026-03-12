import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { strategyApi } from '@/lib/api';
import type { StrategyInfo } from '@/lib/types';

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

// ─── 전략 상세 조회 ────────────────────────────────────────────────────────────
export function useStrategyDetail(name: string) {
  return useQuery({
    queryKey: strategyKeys.detail(name),
    queryFn: () => strategyApi.get(name),
    enabled: !!name,
    select: (res) => (res?.data as unknown as StrategyInfo) ?? null,
  });
}

// ─── 전략 생성 mutation ────────────────────────────────────────────────────────
export function useCreateStrategy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (config: unknown) => strategyApi.create(config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: strategyKeys.all });
    },
  });
}

// ─── 전략 수정 mutation ────────────────────────────────────────────────────────
export function useUpdateStrategy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, config }: { id: string; config: unknown }) =>
      strategyApi.update(id, config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: strategyKeys.all });
    },
  });
}

// ─── 전략 활성/비활성 토글 mutation ──────────────────────────────────────────
export function useToggleStrategy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => strategyApi.toggle(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: strategyKeys.all });
    },
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
