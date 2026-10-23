import { describe, expect, it } from "vitest";
import { sampleVideoCameraMotion, VIDEO_CAMERA_MOTIONS, type VideoCameraMotionState, type VideoCameraVector } from "./videoCameraMotion";
import { clipWorldSegment, drawVideoCameraPreview, projectWorldPoint, videoCameraDoorwayPlane, videoCameraHorizontalFov, videoCameraLocalLight, type VideoCameraPreviewPalette } from "./videoCameraPreviewRenderer";

const camera: VideoCameraMotionState = {
  position: [0, 1, 8], target: [0, 1, 0], fov: 48, roll: 0, focus: 8,
  subject: [0, 1, 0], occluder: 0, light: 1, blur: 0,
};

const palette: VideoCameraPreviewPalette = {
  background: "Canvas", surface: "Canvas", grid: "GrayText", border: "GrayText", muted: "GrayText",
  text: "CanvasText", accent: "Highlight", accentLight: "HighlightText", subject: "Highlight", secondary: "GrayText",
};

function recordingCanvas() {
  let coordinateCount = 0;
  const filters: string[] = [];
  const rectangles: number[][] = [];
  const dashPatterns: number[][] = [];
  const coordinates = (...values: number[]) => {
    if (values.some((value) => !Number.isFinite(value))) throw new Error("Non-finite canvas coordinate");
    coordinateCount += values.length;
  };
  const nothing = () => {};
  const gradient = () => ({ addColorStop: nothing });
  const context = {
    save: nothing, restore: nothing, beginPath: nothing, closePath: nothing, clip: nothing,
    fill: nothing, stroke: nothing, setLineDash: (pattern: number[]) => { dashPatterns.push(pattern); },
    rect: coordinates, fillRect: (...values: number[]) => { coordinates(...values); rectangles.push(values); }, moveTo: coordinates, lineTo: coordinates,
    arc: coordinates, ellipse: coordinates, roundRect: coordinates,
    translate: coordinates, rotate: coordinates, scale: coordinates,
    createLinearGradient: gradient, createRadialGradient: gradient,
    set filter(value: string) { filters.push(value); },
  } as unknown as CanvasRenderingContext2D;
  return { context, filters, rectangles, dashPatterns, get coordinateCount() { return coordinateCount; } };
}

describe("camera rehearsal perspective projection", () => {
  it("centers the look-at target while retaining the world up and right directions", () => {
    expect(projectWorldPoint(camera.target, camera, 640, 360)).toMatchObject({ x: 320, y: 180, depth: 8 });
    expect(projectWorldPoint([1, 1, 0], camera, 640, 360)!.x).toBeGreaterThan(320);
    expect(projectWorldPoint([0, 2, 0], camera, 640, 360)!.y).toBeLessThan(180);
    const tilted = { ...camera, position: [3, 6, 7] as VideoCameraVector };
    expect(projectWorldPoint(tilted.target, tilted, 640, 360)!.x).toBeCloseTo(320);
    expect(projectWorldPoint(tilted.target, tilted, 640, 360)!.y).toBeCloseTo(180);
  });

  it("moves foreground farther across the frame than background during a lateral truck", () => {
    const moved = { ...camera, position: [1, 1, 8] as VideoCameraVector, target: [1, 1, 0] as VideoCameraVector };
    const foreground: VideoCameraVector = [0, 1, 4];
    const background: VideoCameraVector = [0, 1, -4];
    const foregroundShift = projectWorldPoint(foreground, camera, 640, 360)!.x - projectWorldPoint(foreground, moved, 640, 360)!.x;
    const backgroundShift = projectWorldPoint(background, camera, 640, 360)!.x - projectWorldPoint(background, moved, 640, 360)!.x;
    expect(foregroundShift).toBeGreaterThan(0);
    expect(foregroundShift / backgroundShift).toBeCloseTo(3);
  });

  it("distinguishes dolly perspective compression from a lens-only zoom", () => {
    const zoom = { ...camera, fov: 25 };
    const dolly = { ...camera, position: [0, 1, 4] as VideoCameraVector };
    const subject: VideoCameraVector = [0, 1, 0];
    const background: VideoCameraVector = [0, 1, -4];
    const relativeBackgroundScale = (state: VideoCameraMotionState) => projectWorldPoint(background, state, 640, 360)!.scale / projectWorldPoint(subject, state, 640, 360)!.scale;
    expect(relativeBackgroundScale(zoom)).toBeCloseTo(relativeBackgroundScale(camera));
    expect(relativeBackgroundScale(dolly)).toBeCloseTo(0.5);
    expect(relativeBackgroundScale(dolly)).toBeLessThan(relativeBackgroundScale(camera));
  });

  it("rotates the image about its optical center when rolling the camera", () => {
    const rolled = { ...camera, roll: Math.PI / 2 };
    const original = projectWorldPoint([1, 1, 0], camera, 640, 360)!;
    const rotated = projectWorldPoint([1, 1, 0], rolled, 640, 360)!;
    expect(rotated.x).toBeCloseTo(320);
    expect(rotated.y - 180).toBeCloseTo(original.x - 320);
    expect(projectWorldPoint(camera.target, rolled, 640, 360)).toMatchObject({ x: 320, y: 180 });
  });

  it("clips crossing segments before projection and rejects geometry behind the lens", () => {
    expect(projectWorldPoint([0, 1, 9], camera, 640, 360)).toBeNull();
    expect(clipWorldSegment([0, 1, 9], [0, 1, 10], camera)).toBeNull();
    const clipped = clipWorldSegment([0, 1, 9], [1, 1, 0], camera)!;
    const projection = clipped.map((point) => projectWorldPoint(point, camera, 640, 360)!);
    expect(projection[0]!.depth).toBeCloseTo(0.08);
    expect(projection[1]!.depth).toBeCloseTo(8);
    for (const point of projection) {
      expect(Object.values(point).every(Number.isFinite)).toBe(true);
      expect(point.scale).toBeGreaterThan(0);
    }
  });

  it("keeps top-down, near-pole and coincident look-at bases finite", () => {
    for (const position of [[0, 20, 0], [0, 20, 0.001], [0, 1, 0]] as const) {
      const state = { ...camera, position };
      const point: VideoCameraVector = position[1] === 20 ? [1, 0, 1] : [0, 1, -1];
      expect(Object.values(projectWorldPoint(point, state, 640, 360)!).every(Number.isFinite)).toBe(true);
    }
  });

  it("projects every authored rehearsal without non-finite camera transforms", () => {
    for (const profile of Object.values(VIDEO_CAMERA_MOTIONS)) {
      for (const progress of [0, 0.25, 0.5, 0.75, 1]) {
        const state = sampleVideoCameraMotion(profile, progress);
        const projected = projectWorldPoint(state.target, state, 640, 360);
        expect(projected, `${profile.id} at ${progress}`).not.toBeNull();
        expect(Object.values(projected!).every(Number.isFinite), profile.id).toBe(true);
        expect(projected!.x, profile.id).toBeCloseTo(320);
        expect(projected!.y, profile.id).toBeCloseTo(180);
      }
    }
  });

  it("draws the frame and plan for all scenes, including paths through the near plane", () => {
    const recording = recordingCanvas();
    for (const profile of Object.values(VIDEO_CAMERA_MOTIONS)) {
      for (const progress of [0, 0.5, 1]) {
        const state = sampleVideoCameraMotion(profile, progress);
        for (const view of ["frame", "plan"] as const) {
          drawVideoCameraPreview(recording.context, 480, 270, state, profile, progress, palette, view);
        }
      }
    }
    expect(recording.coordinateCount).toBeGreaterThan(100_000);
  });

  it("uses depth focus for an over-the-shoulder shot and preserves the deep-focus preset", () => {
    const recording = recordingCanvas();
    const dialogue = VIDEO_CAMERA_MOTIONS["over-the-shoulder"]!;
    drawVideoCameraPreview(recording.context, 480, 270, sampleVideoCameraMotion(dialogue, 0), dialogue, 0, palette, "frame");
    expect(recording.filters.some((filter) => Number.parseFloat(filter.slice(5)) > 0.5)).toBe(true);
    recording.filters.length = 0;
    const deepFocus = VIDEO_CAMERA_MOTIONS["deep-focus-wide"]!;
    drawVideoCameraPreview(recording.context, 480, 270, sampleVideoCameraMotion(deepFocus, 0), deepFocus, 0, palette, "frame");
    expect(recording.filters.length).toBeGreaterThan(0);
    expect(recording.filters.every((filter) => filter === "blur(0px)")).toBe(true);
  });

  it("puts the doorway between the endpoints of a through or pullback move", () => {
    for (const id of ["doorway-dolly-through", "doorway-pullback", "aerial-opening-flythrough", "corkscrew-flythrough"]) {
      const profile = VIDEO_CAMERA_MOTIONS[id]!;
      const doorwayZ = videoCameraDoorwayPlane(id);
      const start = sampleVideoCameraMotion(profile, 0).position[2];
      const end = sampleVideoCameraMotion(profile, 1).position[2];
      expect((start - doorwayZ) * (end - doorwayZ), id).toBeLessThan(0);
    }
    expect(videoCameraDoorwayPlane("architectural-corner-peek")).toBe(2.25);
  });

  it("uses the horizontal 16:9 camera angle for the plan's view cone", () => {
    const vertical = 48;
    const horizontal = videoCameraHorizontalFov(vertical);
    expect(horizontal).toBeGreaterThan(vertical);
    expect(Math.tan(horizontal * Math.PI / 360) / Math.tan(vertical * Math.PI / 360)).toBeCloseTo(16 / 9);
    expect(videoCameraHorizontalFov(vertical, 1)).toBeCloseTo(vertical);
  });

  it("distinguishes the traveling subject route from the camera route without inventing one for a stationary orbit", () => {
    const traveling = recordingCanvas();
    const follow = VIDEO_CAMERA_MOTIONS["moving-subject-orbit"]!;
    drawVideoCameraPreview(traveling.context, 480, 270, sampleVideoCameraMotion(follow, 0.5), follow, 0.5, palette, "plan");
    expect(traveling.dashPatterns).toContainEqual([1, 3]);
    expect(traveling.dashPatterns).toContainEqual([3, 3]);
    const stationary = recordingCanvas();
    const orbit = VIDEO_CAMERA_MOTIONS["half-orbit-shot"]!;
    drawVideoCameraPreview(stationary.context, 480, 270, sampleVideoCameraMotion(orbit, 0.5), orbit, 0.5, palette, "plan");
    expect(stationary.dashPatterns).not.toContainEqual([1, 3]);
  });

  it("keeps a body-mounted face anchored while background points move through the frame", () => {
    const profile = VIDEO_CAMERA_MOTIONS["snorricam-body-mount"]!;
    const first = sampleVideoCameraMotion(profile, 0);
    const last = sampleVideoCameraMotion(profile, 1);
    const face = (state: VideoCameraMotionState): VideoCameraVector => [state.subject[0], state.subject[1] + 0.68, state.subject[2] + 0.16];
    const firstFace = projectWorldPoint(face(first), first, 640, 360)!;
    const lastFace = projectWorldPoint(face(last), last, 640, 360)!;
    expect(lastFace.x).toBeCloseTo(firstFace.x);
    expect(lastFace.y).toBeCloseTo(firstFace.y);
    expect(lastFace.scale).toBeCloseTo(firstFace.scale);
    const background: VideoCameraVector = [3, 2, -8];
    expect(projectWorldPoint(background, first, 640, 360)!.x).not.toBeCloseTo(projectWorldPoint(background, last, 640, 360)!.x, 0);
  });

  it("clears a low foreground obstacle from the bottom during a rising reveal", () => {
    const recording = recordingCanvas();
    const profile = VIDEO_CAMERA_MOTIONS["vertical-foreground-reveal"]!;
    drawVideoCameraPreview(recording.context, 480, 270, sampleVideoCameraMotion(profile, 0), profile, 0, palette, "frame");
    expect(recording.rectangles.some(([x, y, width, height]) => x === 0 && y! > 0 && width === 480 && height! < 270)).toBe(true);
  });

  it("sweeps a local light over the actor without changing illumination everywhere", () => {
    const fixedExposure = { ...camera, light: 1 };
    const center = videoCameraLocalLight([0.075, 1, 0], fixedExposure, 0.65);
    expect(center).toBeCloseTo(1);
    expect(videoCameraLocalLight([4, 1, 0], fixedExposure, 0.65)).toBeLessThan(0.12);
    expect(videoCameraLocalLight([0.075, 1, 0], fixedExposure, 0)).toBeLessThan(center / 2);
    expect(videoCameraLocalLight([0.075, 1, 0], fixedExposure, 1)).toBeLessThan(center / 2);
  });
});
