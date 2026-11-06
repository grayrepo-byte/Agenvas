import { create } from "zustand";
import type { ApplyCharacterNodeBindings, ConnectCharacterNode, CreateCharacterNode } from "../../shared/api/characterNodes";

export type CharacterNodeRecovery = { kind: "connection"; request: ConnectCharacterNode }
  | { kind: "apply"; request: ApplyCharacterNodeBindings };

export type CharacterPlacementRecovery = { id: string; request: CreateCharacterNode };

/** Interaction recovery survives closing a dialog; it never authorizes a new submission. */
export const useCharacterNodeRecovery = create<{
  pending: Record<string, CharacterNodeRecovery>;
  placements: Record<string, CharacterPlacementRecovery>;
  retainPlacement: (projectId: string, recovery: CharacterPlacementRecovery) => void;
  clearPlacement: (projectId: string) => void;
  retain: (key: string, recovery: CharacterNodeRecovery) => void;
  clear: (key: string) => void;
}>((set) => ({
  pending: {},
  placements: {},
  retainPlacement: (projectId, recovery) => set((state) => ({ placements: { ...state.placements, [projectId]: recovery } })),
  clearPlacement: (projectId) => set((state) => {
    const placements = { ...state.placements };
    delete placements[projectId];
    return { placements };
  }),
  retain: (key, recovery) => set((state) => ({ pending: { ...state.pending, [key]: recovery } })),
  clear: (key) => set((state) => {
    const pending = { ...state.pending };
    delete pending[key];
    return { pending };
  }),
}));
