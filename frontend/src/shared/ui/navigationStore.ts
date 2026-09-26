import { create } from "zustand";

/** Presentation preference only; survives navigation without storing server or credential data. */
export const useNavigationStore = create<{ collapsed: boolean; toggle: () => void }>((set) => ({
  collapsed: false,
  toggle: () => set((state) => ({ collapsed: !state.collapsed })),
}));
