import { describe, expect, it } from "vitest";
import { imageAspectRatio, imageDraftAspectRatio, imageNodeResizeBounds, persistableNodeSize, projectImageNodeSize } from "./imageNodeLayout";

describe("image node layout", () => {
  it.each(["1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9"] as const)(
    "projects the generation ratio %s while preserving the long edge", (ratio) => {
      const [width, height] = ratio.split(":").map(Number);
      const size = projectImageNodeSize({ width: 225, height: 300 }, imageDraftAspectRatio(ratio));
      expect(size.width / size.height).toBeCloseTo(width! / height!);
      expect(Math.max(size.width, size.height)).toBe(300);
    });

  it("leaves AUTO and unset ratios to the existing image or stored dimensions", () => {
    expect(imageDraftAspectRatio("AUTO")).toBeUndefined();
    expect(imageDraftAspectRatio()).toBeUndefined();
  });

  it("fits the node to original pixel proportions while preserving its long edge", () => {
    const source = { width: 4001, height: 2000 };
    const size = projectImageNodeSize({ width: 225, height: 300 }, imageAspectRatio(source));
    expect(size.width).toBe(300);
    expect(size.width / size.height).toBeCloseTo(source.width / source.height);
  });

  it("does not progressively shrink when the selected result alternates portrait and landscape", () => {
    let size = { width: 225, height: 300 };
    for (const ratio of [2, 0.5, 3, 0.25, 1]) size = projectImageNodeSize(size, ratio);
    expect(size).toEqual({ width: 300, height: 300 });
  });

  it("keeps empty cards and unavailable or invalid metadata at their existing size", () => {
    const size = { width: 225, height: 300 };
    for (const asset of [undefined, { width: null, height: 10 }, { width: 10, height: 0 },
      { width: -1, height: 10 }, { width: NaN, height: 10 }]) {
      expect(projectImageNodeSize(size, imageAspectRatio(asset))).toEqual(size);
    }
  });

  it.each([20, 0.05])("restores extreme ratio %s after saving storage-safe dimensions and refreshing", (ratio) => {
    const visible = projectImageNodeSize({ width: 225, height: 300 }, ratio);
    const saved = persistableNodeSize(visible);
    expect(saved.width).toBeGreaterThanOrEqual(120);
    expect(saved.height).toBeGreaterThanOrEqual(80);
    expect(projectImageNodeSize(saved, ratio)).toEqual(visible);
  });

  it("constrains resizing by the long edge without imposing a distorting short-edge minimum", () => {
    expect(imageNodeResizeBounds(20)).toEqual({ minWidth: 120, minHeight: 6, maxWidth: 2000, maxHeight: 100 });
    expect(imageNodeResizeBounds(0.05)).toEqual({ minWidth: 6, minHeight: 120, maxWidth: 100, maxHeight: 2000 });
    expect(projectImageNodeSize({ width: 10000, height: 1000 }, 20)).toEqual({ width: 2000, height: 100 });
  });
});
