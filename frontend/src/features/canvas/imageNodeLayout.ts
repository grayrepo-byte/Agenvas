import type { Asset, ImageGenerationParameters } from "../../shared/api/client";

type NodeSize = { width: number; height: number };

// These storage limits mirror CanvasItem/UPDATE_LAYOUT in contracts/openapi.yaml.
export const CANVAS_MIN_WIDTH = 120;
export const CANVAS_MIN_HEIGHT = 80;
export const CANVAS_MAX_SIZE = 2000;
const IMAGE_MIN_LONG_EDGE = CANVAS_MIN_WIDTH;

export function imageDraftAspectRatio(ratio?: ImageGenerationParameters["aspectRatio"]): number | undefined {
  if (!ratio || ratio === "AUTO") return undefined;
  const [width, height] = ratio.split(":").map(Number);
  return imageAspectRatio({ width: width ?? null, height: height ?? null });
}

export function imageAspectRatio(asset?: Pick<Asset, "width" | "height">): number | undefined {
  const { width, height } = asset ?? {};
  return typeof width === "number" && typeof height === "number" &&
    Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0
    ? width / height : undefined;
}

/** Preserve the user's long edge when replacing a portrait with a landscape (or back). */
export function projectImageNodeSize(size: NodeSize, aspectRatio?: number): NodeSize {
  if (aspectRatio === undefined) return size;
  const longEdge = Math.min(CANVAS_MAX_SIZE, Math.max(IMAGE_MIN_LONG_EDGE, size.width, size.height));
  return aspectRatio >= 1
    ? { width: longEdge, height: longEdge / aspectRatio }
    : { width: longEdge * aspectRatio, height: longEdge };
}

/** Very narrow pictures may display below the storage minimum; their long edge restores the ratio. */
export function persistableNodeSize(size: NodeSize): NodeSize {
  return {
    width: Math.min(CANVAS_MAX_SIZE, Math.max(CANVAS_MIN_WIDTH, size.width)),
    height: Math.min(CANVAS_MAX_SIZE, Math.max(CANVAS_MIN_HEIGHT, size.height)),
  };
}

export function imageNodeResizeBounds(aspectRatio: number) {
  const minimum = projectImageNodeSize({ width: IMAGE_MIN_LONG_EDGE, height: IMAGE_MIN_LONG_EDGE }, aspectRatio);
  const maximum = projectImageNodeSize({ width: CANVAS_MAX_SIZE, height: CANVAS_MAX_SIZE }, aspectRatio);
  return { minWidth: minimum.width, minHeight: minimum.height,
    maxWidth: maximum.width, maxHeight: maximum.height };
}
