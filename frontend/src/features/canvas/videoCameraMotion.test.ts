import { describe, expect, it } from "vitest";
import { VIDEO_CAMERA_MOTIONS, sampleVideoCameraMotion, type VideoCameraMotionProfile, type VideoCameraMotionState } from "./videoCameraMotion";
import { VIDEO_PROMPT_PRESETS } from "./videoPromptPresets";

function profile(id: string): VideoCameraMotionProfile {
  const value = VIDEO_CAMERA_MOTIONS[id];
  if (!value) throw new Error(`Missing authored camera preview: ${id}`);
  return value;
}
function sample(id: string, progress: number): VideoCameraMotionState {
  return sampleVideoCameraMotion(profile(id), progress);
}
function distance(a: readonly number[], b: readonly number[]): number {
  return Math.hypot(...a.map((value, index) => value - b[index]!));
}
function framingScale(state: VideoCameraMotionState): number {
  return distance(state.position, state.subject) * Math.tan(state.fov * Math.PI / 360);
}
function viewDirection(state: VideoCameraMotionState): readonly number[] {
  return state.target.map((value, axis) => value - state.position[axis]!);
}

describe("authored video camera motion", () => {
  it("covers each authored preset explicitly, with no generic fallback or orphaned motion", () => {
    expect(Object.keys(VIDEO_CAMERA_MOTIONS).sort()).toEqual(VIDEO_PROMPT_PRESETS.map(({ id }) => id).sort());
    for (const [id, motion] of Object.entries(VIDEO_CAMERA_MOTIONS)) {
      expect(motion.id).toBe(id);
      expect(motion.durationMs).toBeGreaterThanOrEqual(5000);
      expect(motion.durationMs).toBeLessThanOrEqual(8000);
      expect(motion.keyframes[0]!.at).toBe(0);
      expect(motion.keyframes.at(-1)!.at).toBe(1);
      for (let index = 1; index < motion.keyframes.length; index += 1) {
        expect(motion.keyframes[index]!.at).toBeGreaterThan(motion.keyframes[index - 1]!.at);
      }
    }
    expect(VIDEO_CAMERA_MOTIONS["missing-preset"]).toBeUndefined();
  });

  it("samples finite, physically valid frames deterministically across every rehearsal", () => {
    for (const motion of Object.values(VIDEO_CAMERA_MOTIONS)) {
      for (let step = 0; step <= 40; step += 1) {
        const progress = step / 40;
        const state = sampleVideoCameraMotion(motion, progress);
        expect(sampleVideoCameraMotion(motion, progress)).toEqual(state);
        const values = [...state.position, ...state.target, ...state.subject, state.fov, state.roll,
          state.focus, state.occluder, state.light, state.blur];
        expect(values.every(Number.isFinite), `${motion.id} at ${progress}`).toBe(true);
        expect(distance(state.position, state.target)).toBeGreaterThan(0.1);
        expect(state.position[1]).toBeGreaterThan(0.1);
        expect(state.fov).toBeGreaterThan(10);
        expect(state.fov).toBeLessThan(100);
        expect(state.focus).toBeGreaterThanOrEqual(0);
        for (const value of [state.occluder, state.light, state.blur]) {
          expect(value).toBeGreaterThanOrEqual(0);
          expect(value).toBeLessThanOrEqual(1);
        }
      }
      expect(sampleVideoCameraMotion(motion, -1)).toEqual(sampleVideoCameraMotion(motion, 0));
      expect(sampleVideoCameraMotion(motion, 2)).toEqual(sampleVideoCameraMotion(motion, 1));
      expect(sampleVideoCameraMotion(motion, Number.NaN)).toEqual(sampleVideoCameraMotion(motion, 0));
    }
  });

  it("holds fixed framing still while an existing subject can move inside it", () => {
    const framing = ["locked-off-wide", "over-the-shoulder", "expressive-close-up", "extreme-detail-close-up",
      "medium-shot", "balanced-two-shot", "low-angle-framing", "high-angle-framing", "dutch-angle-framing", "deep-focus-wide"];
    for (const id of framing) {
      expect(profile(id).motion).toBe("locked");
      const start = sample(id, 0);
      for (const progress of [0.2, 0.5, 0.8, 1]) {
        const state = sample(id, progress);
        expect(state.position).toEqual(start.position);
        expect(state.target).toEqual(start.target);
        expect(state.roll).toBe(start.roll);
        expect(state.fov).toBe(start.fov);
      }
    }
    expect(sample("locked-off-wide", 0.5).subject).not.toEqual(sample("locked-off-wide", 0).subject);
    expect(sample("extreme-detail-close-up", 0.5).subject).toEqual(sample("extreme-detail-close-up", 0).subject);
    expect(sample("dutch-angle-framing", 0.5).roll).not.toBe(0);
    expect(sample("deep-focus-wide", 0.5).focus).toBe(0);
  });

  it("distinguishes physical dolly travel from optical zoom and conserves dolly-zoom subject scale", () => {
    const dollyStart = sample("slow-dolly-in", 0);
    const dollyEnd = sample("slow-dolly-in", 1);
    expect(dollyEnd.position[2]).toBeLessThan(dollyStart.position[2]);
    expect(dollyEnd.fov).toBe(dollyStart.fov);
    for (const id of ["slow-optical-zoom-in", "slow-optical-zoom-out", "snap-zoom-emphasis"]) {
      const start = sample(id, 0);
      const end = sample(id, 1);
      expect(end.position).toEqual(start.position);
      expect(end.target).toEqual(start.target);
      expect(end.fov).not.toBe(start.fov);
    }
    for (const id of ["forward-dolly-zoom", "reverse-dolly-zoom"]) {
      const initialScale = framingScale(sample(id, 0));
      for (const progress of [0.1, 0.25, 0.5, 0.75, 1]) {
        expect(framingScale(sample(id, progress))).toBeCloseTo(initialScale, 9);
      }
    }
    expect(sample("forward-dolly-zoom", 1).fov).toBeGreaterThan(sample("forward-dolly-zoom", 0).fov);
    expect(sample("reverse-dolly-zoom", 1).fov).toBeLessThan(sample("reverse-dolly-zoom", 0).fov);
    expect(sample("snap-zoom-emphasis", 0.3).fov).toBe(sample("snap-zoom-emphasis", 0).fov);
    expect(sample("snap-zoom-emphasis", 0.5).fov).toBe(sample("snap-zoom-emphasis", 1).fov);
  });

  it("transfers focus and reveals light or occlusion without inventing camera travel", () => {
    for (const id of ["rack-focus-near-to-far", "rack-focus-far-to-near", "macro-focus-travel",
      "moving-occluder-reveal", "defocus-to-sharp-reveal", "shadow-exposure-reveal", "motivated-light-reveal"]) {
      const start = sample(id, 0);
      for (const progress of [0.2, 0.5, 0.8, 1]) {
        const state = sample(id, progress);
        expect(state.position).toEqual(start.position);
        expect(state.target).toEqual(start.target);
        expect(state.fov).toBe(start.fov);
      }
    }
    expect(sample("rack-focus-near-to-far", 1).focus).toBeGreaterThan(sample("rack-focus-near-to-far", 0).focus);
    expect(sample("rack-focus-far-to-near", 1).focus).toBeLessThan(sample("rack-focus-far-to-near", 0).focus);
    expect(sample("macro-focus-travel", 1).focus).toBeGreaterThan(sample("macro-focus-travel", 0).focus);
    expect(sample("moving-occluder-reveal", 1).occluder).toBe(0);
    expect(sample("moving-occluder-reveal", 0).occluder).toBeGreaterThan(0.8);
    expect(sample("defocus-to-sharp-reveal", 1).blur).toBe(0);
    expect(sample("defocus-to-sharp-reveal", 0).blur).toBe(1);
    expect(sample("shadow-exposure-reveal", 1).light).toBeGreaterThan(sample("shadow-exposure-reveal", 0).light);
    expect(sample("motivated-light-reveal", 0.65).light).toBeGreaterThan(sample("motivated-light-reveal", 1).light);
  });

  it("follows a clockwise circular track with a constant radius instead of cutting polygonal corners", () => {
    const start = sample("full-orbit-shot", 0);
    const radius = Math.hypot(start.position[0], start.position[2]);
    for (let step = 0; step <= 80; step += 1) {
      const state = sample("full-orbit-shot", step / 80);
      expect(Math.hypot(state.position[0], state.position[2])).toBeCloseTo(radius, 10);
      expect(state.position[1]).toBe(start.position[1]);
      expect(state.target).toEqual(start.target);
    }
    expect(sample("full-orbit-shot", 0.25).position[0]).toBeLessThan(0);
    expect(sample("full-orbit-shot", 0.5).position[2]).toBeLessThan(0);
    expect(sample("full-orbit-shot", 0.75).position[0]).toBeGreaterThan(0);
    expect(distance(sample("full-orbit-shot", 0).position, sample("full-orbit-shot", 1).position)).toBeLessThan(1e-10);
    const horizontalRadius = (id: string, at: number) => Math.hypot(sample(id, at).position[0], sample(id, at).position[2]);
    expect(horizontalRadius("spiral-in-orbit", 1)).toBeLessThan(horizontalRadius("spiral-in-orbit", 0));
    expect(horizontalRadius("spiral-out-orbit", 1)).toBeGreaterThan(horizontalRadius("spiral-out-orbit", 0));
    expect(sample("rising-orbit", 1).position[1]).toBeGreaterThan(sample("rising-orbit", 0).position[1]);
  });

  it("separates sequential phases, including a stationary hold at the end of braking moves", () => {
    expect(sample("pan-then-push-in", 0.35).position).toEqual(sample("pan-then-push-in", 0).position);
    expect(sample("pan-then-push-in", 0.7).target).toEqual(sample("pan-then-push-in", 1).target);
    expect(sample("zoom-out-then-slide", 0.3).position).toEqual(sample("zoom-out-then-slide", 0).position);
    expect(sample("zoom-out-then-slide", 0.75).fov).toBe(sample("zoom-out-then-slide", 1).fov);
    expect(sample("push-then-pedestal-rise", 0.25).position[1]).toBe(sample("push-then-pedestal-rise", 0).position[1]);
    expect(sample("push-then-pedestal-rise", 0.75).position[2]).toBe(sample("push-then-pedestal-rise", 1).position[2]);
    expect(sample("focus-pull-then-reframe", 0.3).target).toEqual(sample("focus-pull-then-reframe", 0).target);
    expect(sample("focus-pull-then-reframe", 0.8).focus).toBe(sample("focus-pull-then-reframe", 1).focus);
    expect(sample("level-horizon-then-push", 0.25).position).toEqual(sample("level-horizon-then-push", 0).position);
    expect(sample("level-horizon-then-push", 0.8).roll).toBe(0);
    expect(sample("push-in-and-hold", 0.8).position).toEqual(sample("push-in-and-hold", 1).position);
    expect(sample("aerial-approach-brake", 0.95).position).toEqual(sample("aerial-approach-brake", 1).position);
  });

  it("keeps tracking distance and distinguishes pedestal translation from camera tilt", () => {
    for (const id of ["front-leading-tracking", "parallel-profile-tracking", "ground-level-follow", "three-quarter-front-tracking", "vertical-subject-follow"]) {
      const initialDistance = distance(sample(id, 0).position, sample(id, 0).subject);
      expect(distance(sample(id, 0.5).position, sample(id, 0.5).subject)).toBeCloseTo(initialDistance, 9);
      expect(distance(sample(id, 1).position, sample(id, 1).subject)).toBeCloseTo(initialDistance, 9);
    }
    for (const id of ["pedestal-up", "pedestal-down"]) {
      expect(viewDirection(sample(id, 1))).toEqual(viewDirection(sample(id, 0)));
      expect(sample(id, 1).position[1]).not.toBe(sample(id, 0).position[1]);
    }
    expect(sample("detail-to-subject-tilt", 1).position).toEqual(sample("detail-to-subject-tilt", 0).position);
    expect(sample("detail-to-subject-tilt", 1).target[1]).toBeGreaterThan(sample("detail-to-subject-tilt", 0).target[1]);
  });

  it("rotates a fixed camera for pan, tilt, and optical-axis roll without adding dolly travel", () => {
    for (const id of ["tripod-pan-follow", "fixed-tilt-down", "axial-barrel-roll", "pan-between-subjects"]) {
      const start = sample(id, 0);
      for (let step = 0; step <= 40; step += 1) {
        const state = sample(id, step / 40);
        expect(state.position).toEqual(start.position);
        expect(state.fov).toBe(start.fov);
      }
    }
    for (const progress of [0, 0.2, 0.5, 0.8, 1]) {
      const state = sample("tripod-pan-follow", progress);
      expect(state.target[0]).toBe(state.subject[0]);
      expect(state.subject[2]).toBe(0);
      expect(state.roll).toBe(0);
    }
    expect(sample("tripod-pan-follow", 1).target[0]).toBeGreaterThan(sample("tripod-pan-follow", 0).target[0]);
    expect(sample("fixed-tilt-down", 1).target[1]).toBeLessThan(sample("fixed-tilt-down", 0).target[1]);
    const rollStart = sample("axial-barrel-roll", 0);
    expect(sample("axial-barrel-roll", 0.5).roll).toBeCloseTo(Math.PI, 10);
    expect(sample("axial-barrel-roll", 1).roll).toBeCloseTo(2 * Math.PI, 10);
    expect(sample("axial-barrel-roll", 0.5).target).toEqual(rollStart.target);
    expect(sample("pan-between-subjects", 0.15).target).toEqual(sample("pan-between-subjects", 0).target);
    expect(sample("pan-between-subjects", 0.9).target).toEqual(sample("pan-between-subjects", 1).target);
    expect(sample("pan-between-subjects", 1).target).toEqual([-1.05, 1.4, 2.2]);
  });

  it("keeps a body-mounted camera rigid to its actor and an aerial follow at a constant offset", () => {
    for (const id of ["snorricam-body-mount", "aerial-subject-follow"]) {
      const start = sample(id, 0);
      const cameraOffset = start.position.map((value, axis) => value - start.subject[axis]!);
      const aimOffset = start.target.map((value, axis) => value - start.subject[axis]!);
      for (let step = 0; step <= 40; step += 1) {
        const state = sample(id, step / 40);
        for (let axis = 0; axis < 3; axis += 1) {
          expect(state.position[axis]! - state.subject[axis]!).toBeCloseTo(cameraOffset[axis]!, 10);
          expect(state.target[axis]! - state.subject[axis]!).toBeCloseTo(aimOffset[axis]!, 10);
          expect(viewDirection(state)[axis]).toBeCloseTo(viewDirection(start)[axis]!, 10);
        }
        expect(state.fov).toBe(start.fov);
        expect(state.roll).toBe(0);
      }
      expect(distance(sample(id, 1).subject, start.subject)).toBeGreaterThan(5);
    }
    expect(sample("snorricam-body-mount", 0).position[2]).toBeGreaterThan(sample("snorricam-body-mount", 0).subject[2]);
    expect(sample("aerial-subject-follow", 0).position[1] - sample("aerial-subject-follow", 0).subject[1]).toBe(6);
    const aerialStart = sample("aerial-subject-follow", 0);
    const aerialEnd = sample("aerial-subject-follow", 1);
    const travelX = aerialEnd.subject[0] - aerialStart.subject[0];
    const travelZ = aerialEnd.subject[2] - aerialStart.subject[2];
    const cameraX = aerialStart.position[0] - aerialStart.subject[0];
    const cameraZ = aerialStart.position[2] - aerialStart.subject[2];
    expect(cameraX * travelX + cameraZ * travelZ).toBeLessThan(0);
    expect(Math.abs(cameraX * travelZ - cameraZ * travelX)).toBeGreaterThan(1);
  });

  it("keeps the orbit circular relative to an actor who is also traveling", () => {
    const start = sample("moving-subject-orbit", 0);
    const radius = Math.hypot(start.position[0] - start.subject[0], start.position[2] - start.subject[2]);
    for (let step = 0; step <= 80; step += 1) {
      const state = sample("moving-subject-orbit", step / 80);
      expect(Math.hypot(state.position[0] - state.subject[0], state.position[2] - state.subject[2])).toBeCloseTo(radius, 10);
      expect(state.target).toEqual(state.subject);
      expect(state.position[1]).toBe(start.position[1]);
    }
    expect(distance(sample("moving-subject-orbit", 1).subject, start.subject)).toBeGreaterThan(4);
    const middle = sample("moving-subject-orbit", 0.5);
    expect(middle.position[0] - middle.subject[0]).toBeCloseTo(-radius, 10);
    expect(middle.position[2] - middle.subject[2]).toBeCloseTo(0, 10);
  });

  it("dives toward the route then pulls level above the ground and slaloms around solid pillars", () => {
    const diveStart = sample("fpv-vertical-dive", 0);
    const diveEnd = sample("fpv-vertical-dive", 1);
    expect(diveStart.position[1] - diveEnd.position[1]).toBeGreaterThan(10);
    expect(viewDirection(diveStart)[1]).toBeLessThan(-10);
    expect(viewDirection(diveEnd)[1]).toBe(0);
    for (let step = 0; step <= 2000; step += 1) {
      const progress = step / 2000;
      const dive = sample("fpv-vertical-dive", progress);
      expect(dive.position[1]).toBeGreaterThanOrEqual(1.8 - 1e-10);
      expect(dive.position[0]).toBe(4);
      if (progress >= 0.7) {
        expect(dive.position[1]).toBeCloseTo(1.8, 10);
        expect(dive.target[1]).toBe(1.8);
      }
      const slalom = sample("fpv-gap-slalom", progress);
      expect(slalom.position[1]).toBe(1.7);
      expect(slalom.target[2]).toBeLessThan(slalom.position[2]);
      for (const pillarZ of [4, -2]) {
        // The renderer's 1.1 × 1.1 pillar footprint is solid, with clearance for the camera rig.
        const dx = Math.max(0, Math.abs(slalom.position[0]) - 0.55);
        const dz = Math.max(0, Math.abs(slalom.position[2] - pillarZ) - 0.55);
        expect(Math.hypot(dx, dz)).toBeGreaterThan(0.35);
      }
    }
    expect(sample("fpv-gap-slalom", 0.32).position).toEqual([-2, 1.7, 4]);
    expect(sample("fpv-gap-slalom", 0.72).position).toEqual([2, 1.7, -2]);
  });

  it("flies continuously through FPV waypoints while preserving deliberate stops in the other rehearsals", () => {
    const epsilon = 1e-5;
    for (const id of ["fpv-vertical-dive", "fpv-gap-slalom"]) {
      for (const { at } of profile(id).keyframes.slice(1, -1)) {
        const before = sample(id, at - epsilon);
        const waypoint = sample(id, at);
        const after = sample(id, at + epsilon);
        for (const field of ["position", "target"] as const) {
          const leftVelocity = waypoint[field].map((value, axis) => (value - before[field][axis]!) / epsilon);
          const rightVelocity = after[field].map((value, axis) => (value - waypoint[field][axis]!) / epsilon);
          expect(Math.hypot(...leftVelocity), `${id} ${field} arriving at ${at}`).toBeGreaterThan(5);
          expect(Math.hypot(...rightVelocity), `${id} ${field} leaving ${at}`).toBeGreaterThan(5);
          expect(distance(leftVelocity, rightVelocity), `${id} ${field} shared tangent at ${at}`).toBeLessThan(0.05);
        }
        const leftRollSpeed = (waypoint.roll - before.roll) / epsilon;
        const rightRollSpeed = (after.roll - waypoint.roll) / epsilon;
        expect(Math.abs(leftRollSpeed - rightRollSpeed)).toBeLessThan(0.01);
      }
      for (let step = 1; step <= 2000; step += 1) {
        expect(sample(id, step / 2000).position[2]).toBeLessThan(sample(id, (step - 1) / 2000).position[2]);
      }
    }
    expect(sample("aerial-flyover-lookback", 0.95)).toEqual(sample("aerial-flyover-lookback", 1));
    expect(sample("push-in-and-hold", 0.8).position).toEqual(sample("push-in-and-hold", 1).position);
  });

  it("passes beside the actor, looks back after flying over, and rolls through an actual opening", () => {
    const passStart = sample("push-past-subject", 0);
    for (let step = 0; step <= 80; step += 1) {
      const state = sample("push-past-subject", step / 80);
      expect(state.position[0]).toBe(1.8);
      expect(Math.hypot(state.position[0] - state.subject[0], state.position[2] - state.subject[2])).toBeGreaterThanOrEqual(1.8);
      for (let axis = 0; axis < 3; axis += 1) {
        expect(viewDirection(state)[axis]).toBeCloseTo(viewDirection(passStart)[axis]!, 10);
      }
    }
    expect(passStart.position[2]).toBeGreaterThan(0);
    expect(sample("push-past-subject", 1).position[2]).toBeLessThan(0);
    expect(sample("aerial-flyover-lookback", 0.5).position[2]).toBeLessThan(0);
    expect(viewDirection(sample("aerial-flyover-lookback", 0.5))[2]).toBeLessThan(0);
    expect(viewDirection(sample("aerial-flyover-lookback", 1))[2]).toBeGreaterThan(0);
    for (const progress of [0.5, 0.65, 0.82, 0.95, 1]) {
      expect(sample("aerial-flyover-lookback", progress).position).toEqual(sample("aerial-flyover-lookback", 1).position);
    }
    expect(sample("aerial-flyover-lookback", 0.95)).toEqual(sample("aerial-flyover-lookback", 1));
    const corkStart = sample("corkscrew-flythrough", 0);
    const corkEnd = sample("corkscrew-flythrough", 1);
    expect(corkStart.position[2]).toBeGreaterThan(6);
    expect(corkEnd.position[2]).toBeLessThan(6);
    for (let step = 0; step <= 40; step += 1) {
      const state = sample("corkscrew-flythrough", step / 40);
      expect(state.position[0]).toBe(0);
      expect(state.position[1]).toBe(1.8);
      expect(state.target[1]).toBe(1.8);
    }
    expect(sample("corkscrew-flythrough", 0.5).roll).toBeCloseTo(Math.PI, 10);
    expect(corkEnd.roll).toBeCloseTo(2 * Math.PI, 10);
  });
});
