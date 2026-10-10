/** Compiled official MiniMax H3 protocol presentation. */
export const MINIMAX_H3_ADAPTER = "MINIMAX_H3";
export const MINIMAX_H3_RESOLUTIONS = ["768p", "1440p"] as const;
export const MINIMAX_H3_DEFAULT_RESOLUTION = "768p";
export const minimaxResolutionLabel = (tier: string) => tier === "1440p" ? "2K" : "768P";
