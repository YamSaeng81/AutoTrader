import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { dynamicSessionApi } from '@/lib/api';

export const dynamicKeys = {
  all: ['dynamic'] as const,
  sessions: () => [...dynamicKeys.all, 'sessions'] as const,
  session: (id: number) => [...dynamicKeys.all, 'session', id] as const,
};

export function useDynamicSessions() {
  return useQuery({
    queryKey: dynamicKeys.sessions(),
    queryFn: () => dynamicSessionApi.list(),
    refetchInterval: 5000,
    select: (res) => (res?.data as Record<string, unknown>[] | null) ?? [],
  });
}

export function useDynamicSession(id: number) {
  return useQuery({
    queryKey: dynamicKeys.session(id),
    queryFn: () => dynamicSessionApi.get(id),
    enabled: id > 0,
    refetchInterval: 5000,
    select: (res) => (res?.data as Record<string, unknown> | null) ?? null,
  });
}

export function useCreateDynamicSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: dynamicSessionApi.create,
    onSuccess: () => qc.invalidateQueries({ queryKey: dynamicKeys.sessions() }),
  });
}

export function useStartDynamicSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => dynamicSessionApi.start(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: dynamicKeys.sessions() }),
  });
}

export function useStopDynamicSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => dynamicSessionApi.stop(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: dynamicKeys.sessions() }),
  });
}

export function useEmergencyStopDynamicSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => dynamicSessionApi.emergencyStop(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: dynamicKeys.sessions() }),
  });
}

export function useDeleteDynamicSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => dynamicSessionApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: dynamicKeys.sessions() }),
  });
}
