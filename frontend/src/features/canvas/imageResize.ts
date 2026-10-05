import type { RunImageOperationRequest } from "../../shared/api/client";

export type ImageResizeMode = NonNullable<RunImageOperationRequest["parameters"]["resizeMode"]>;
export const MAX_RESIZE_PIXELS = 40_000_000;

/** Matches server rounding; the source is the archived image, never the card's layout. */
export function imageResizeDimensions(width: number, height: number, mode: ImageResizeMode, value: number) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1 || !Number.isFinite(value)
    || (mode === "PERCENTAGE" ? value < 0.01 || value > 1000 : !Number.isInteger(value) || value < 1 || value > 40000)) return null;
  const factor = mode === "PERCENTAGE" ? value / 100 : value / Math.max(width, height);
  return { width: Math.max(1, Math.round(width * factor)), height: Math.max(1, Math.round(height * factor)) };
}
