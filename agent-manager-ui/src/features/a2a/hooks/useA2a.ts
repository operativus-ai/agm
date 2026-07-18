import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { A2aApi } from '../api/a2aApi';
import type { RegisterPeerRequest } from '../api/a2aApi';

const KEYS = {
  peers: ['a2a', 'peers'] as const,
  cards: ['a2a', 'cards'] as const,
  card: (agentId: string) => ['a2a', 'cards', agentId] as const,
};

export function usePeers() {
  return useQuery({
    queryKey: KEYS.peers,
    queryFn: A2aApi.listPeers,
  });
}

export function useCards() {
  return useQuery({
    queryKey: KEYS.cards,
    queryFn: A2aApi.listCards,
  });
}

export function useAgentCard(agentId: string | null) {
  return useQuery({
    queryKey: KEYS.card(agentId!),
    queryFn: () => A2aApi.getCard(agentId!),
    enabled: !!agentId,
  });
}

export function useRegisterPeer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: RegisterPeerRequest) => A2aApi.registerPeer(request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.peers });
    },
  });
}

export function useDeregisterPeer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (alias: string) => A2aApi.deregisterPeer(alias),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.peers });
    },
  });
}
