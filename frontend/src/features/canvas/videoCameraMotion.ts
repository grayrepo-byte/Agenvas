/** World-space camera rehearsals. They illustrate a prompt; they are not generated model footage. */
export type VideoCameraVector = readonly [number, number, number];

export type VideoCameraMotionKeyframe = Readonly<{
  at: number;
  position: VideoCameraVector;
  target: VideoCameraVector;
  /** Vertical field of view in degrees, rather than focal length. */
  fov: number;
  /** Camera roll, in radians. */
  roll?: number;
  /** Focus-plane distance from the camera, in world units. */
  focus?: number;
  subject?: VideoCameraVector;
  occluder?: number;
  light?: number;
  blur?: number;
}>;

export type VideoCameraMotionState = Readonly<{
  position: VideoCameraVector;
  target: VideoCameraVector;
  fov: number;
  roll: number;
  focus: number;
  subject: VideoCameraVector;
  occluder: number;
  light: number;
  blur: number;
}>;

export type VideoCameraState = VideoCameraMotionState;

export type VideoCameraMotionProfile = Readonly<{
  id: string;
  durationMs: number;
  keyframes: readonly VideoCameraMotionKeyframe[];
  scene: "stage" | "dialogue" | "macro" | "doorway" | "foreground" | "aerial" | "group" | "slalom";
  /** Fixed position and orientation can still contain a zoom, focus pull, or moving subject. */
  motion: "moving" | "locked";
  /** Analytic arcs avoid cutting across a circular track between authored keyframes. */
  orbit?: Readonly<{
    sweep: number; startAngle: number; startRadius: number; endRadius: number; startHeight: number; endHeight: number;
    /** Traveling orbits keep their circular track centered on the sampled moving actor. */
    relativeToSubject?: boolean;
  }>;
  /** Deterministic operator sway, applied around the authored path and absent at its endpoints. */
  sway?: Readonly<{ amplitude: number; frequency: number; roll: number }>;
  /** Dolly zooms compensate the lens continuously, including between the authored keyframes. */
  preserveSubjectScale?: boolean;
  /** Continuous flight passes through waypoints without the deliberate stops used by staged moves. */
  interpolation?: "continuous";
}>;

type MotionDefinition = Omit<VideoCameraMotionProfile, "id">;
type FrameExtras = Omit<VideoCameraMotionKeyframe, "at" | "position" | "target" | "fov">;
const SUBJECT: VideoCameraVector = [0, 1, 0];
const FRONT: VideoCameraVector = [0, 1.6, 7];
const TAU = Math.PI * 2;

function frame(at: number, position: VideoCameraVector, target: VideoCameraVector = SUBJECT, fov = 48,
  extras: FrameExtras = {}): VideoCameraMotionKeyframe {
  return { at, position, target, fov, ...extras };
}

function moving(scene: VideoCameraMotionProfile["scene"], keyframes: readonly VideoCameraMotionKeyframe[],
  extras: Partial<Pick<VideoCameraMotionProfile, "durationMs" | "sway" | "preserveSubjectScale" | "interpolation">> = {}): MotionDefinition {
  return { scene, motion: "moving", durationMs: 6500, keyframes, ...extras };
}

function locked(scene: VideoCameraMotionProfile["scene"], position: VideoCameraVector, target = SUBJECT, fov = 48,
  extras: FrameExtras = {}, breathe = true): MotionDefinition {
  return { scene, motion: "locked", durationMs: 6000, keyframes: [
    frame(0, position, target, fov, extras),
    frame(0.5, position, target, fov, { ...extras, subject: breathe ? [0.08, 1, 0] : SUBJECT }),
    frame(1, position, target, fov, extras),
  ] };
}

function lens(scene: VideoCameraMotionProfile["scene"], keyframes: readonly VideoCameraMotionKeyframe[]): MotionDefinition {
  return { scene, motion: "locked", durationMs: 6500, keyframes };
}

/** Authored arcs run clockwise when the stage is viewed from above with +Z at the bottom. */
function orbit(scene: VideoCameraMotionProfile["scene"], sweepAngle: number, radius: number, height: number,
  fov = 48, endRadius = radius, endHeight = height, startAngle = 0): MotionDefinition {
  const sweep = -sweepAngle;
  const arc = { sweep, startAngle, startRadius: radius, endRadius, startHeight: height, endHeight };
  const keyframes = Array.from({ length: 17 }, (_, index) => {
    const at = index / 16;
    const angle = startAngle + sweep * at;
    const distance = mix(radius, endRadius, at);
    return frame(at, [Math.sin(angle) * distance, mix(height, endHeight, at), Math.cos(angle) * distance], SUBJECT, fov);
  });
  return { scene, motion: "moving", durationMs: Math.abs(sweep) >= Math.PI ? 8000 : 6500, keyframes, orbit: arc };
}

const definitions = {
  // Framing is deliberately stationary: the framing itself, rather than a fabricated move, is the lesson.
  "locked-off-wide": locked("stage", [0, 1.7, 10], SUBJECT, 58),
  "over-the-shoulder": locked("dialogue", [-0.8, 1.65, 5], [0.25, 1.2, 0], 42),
  "expressive-close-up": locked("stage", [0, 1.7, 3.2], [0, 1.55, 0], 34),
  "extreme-detail-close-up": locked("macro", [0.12, 1.15, 1.65], [0, 1.05, 0], 23, {}, false),
  "medium-shot": locked("stage", [0, 1.65, 4.8], [0, 1.25, 0], 39),
  "balanced-two-shot": locked("dialogue", [0, 1.7, 7], [0.2, 1.1, 0], 49),
  "low-angle-framing": locked("stage", [0, 0.38, 4.2], [0, 1.2, 0], 47),
  "high-angle-framing": locked("stage", [0, 5, 5.3], [0, 0.9, 0], 47),
  "dutch-angle-framing": locked("stage", FRONT, SUBJECT, 43, { roll: -0.19 }),
  "deep-focus-wide": locked("foreground", [0, 1.8, 9], SUBJECT, 58, { focus: 0 }),

  "slow-dolly-in": moving("foreground", [frame(0, [0, 1.6, 9]), frame(1, [0, 1.6, 4])]),
  "slow-dolly-out": moving("foreground", [frame(0, [0, 1.6, 4]), frame(1, [0, 1.6, 10])]),
  "tabletop-dolly-in": moving("macro", [frame(0, [0, 1.15, 5], [0, 1, 0], 42), frame(1, [0, 1.15, 2.2], [0, 1, 0], 42)]),
  "doorway-pullback": moving("doorway", [frame(0, [0, 1.6, 3.8]), frame(1, [0, 1.6, 11])]),
  "diagonal-dolly-in": moving("foreground", [frame(0, [-3, 1.6, 9]), frame(1, [0.5, 1.6, 4.5])]),
  "diagonal-dolly-out": moving("foreground", [frame(0, [-0.4, 1.6, 4.5]), frame(1, [3, 1.6, 9])]),
  "lateral-dolly-reveal": moving("foreground", [frame(0, [-3.2, 1.6, 7], SUBJECT, 48, { occluder: 0.8 }), frame(1, [1.8, 1.6, 7], SUBJECT, 48, { occluder: 0 })]),
  "doorway-dolly-through": moving("doorway", [frame(0, [0, 1.6, 11]), frame(1, [0, 1.6, 3.8])]),
  "push-in-and-hold": moving("stage", [frame(0, [0, 1.6, 8]), frame(0.62, [0, 1.6, 4.5]), frame(1, [0, 1.6, 4.5])]),
  "low-dolly-pass": moving("foreground", [frame(0, [-5, 0.3, 3.6], [-1.5, 0.8, 0]), frame(0.5, [0, 0.3, 3.6], SUBJECT), frame(1, [5, 0.3, 3.6], [1.5, 0.8, 0])]),

  "steadicam-follow": moving("stage", [frame(0, [0, 1.65, -5.5]), frame(1, [0, 1.65, -1.5], [0, 1, 4], 48, { subject: [0, 1, 4] })], { sway: { amplitude: 0.025, frequency: 1, roll: 0.002 } }),
  "lateral-tracking": moving("foreground", [frame(0, [-4, 1.6, 7]), frame(1, [4, 1.6, 7])]),
  "front-leading-tracking": moving("stage", [frame(0, [0, 1.65, 6]), frame(1, [0, 1.65, 10], [0, 1, 4], 48, { subject: [0, 1, 4] })]),
  "parallel-profile-tracking": moving("stage", [frame(0, [5.8, 1.6, 0]), frame(1, [5.8, 1.6, 4], [0, 1, 4], 48, { subject: [0, 1, 4] })]),
  "corner-turning-tracking": moving("doorway", [frame(0, [0, 1.7, -5]), frame(0.45, [0, 1.7, -2], [0, 1, 3], 48, { subject: [0, 1, 3] }), frame(0.72, [0.9, 1.7, -0.7], [2, 1, 4], 48, { subject: [2, 1, 4] }), frame(1, [2.8, 1.7, 0.2], [5, 1, 4], 48, { subject: [5, 1, 4] })], { durationMs: 8000 }),
  "two-shot-tracking": moving("dialogue", [frame(0, [0, 1.7, 7], [0.3, 1, 0], 52), frame(1, [3.5, 1.7, 7], [3.8, 1, 0], 52, { subject: [3.5, 1, 0] })]),
  "vehicle-mounted-tracking": moving("doorway", [frame(0, [0, 1.35, 7], [0, 1.2, -1], 58), frame(1, [0, 1.35, 2], [0, 1.2, -6], 58, { subject: [0, 1, -5] })], { sway: { amplitude: 0.018, frequency: 16, roll: 0.002 } }),
  "ground-level-follow": moving("stage", [frame(0, [0, 0.32, -4.5], [0, 0.6, 0]), frame(1, [0, 0.32, -0.5], [0, 0.6, 4], 48, { subject: [0, 1, 4] })]),
  "three-quarter-front-tracking": moving("stage", [frame(0, [3.5, 1.65, 5]), frame(1, [3.5, 1.65, 9], [0, 1, 4], 48, { subject: [0, 1, 4] })]),
  "overtaking-tracking": moving("stage", [frame(0, [5.5, 1.65, -2]), frame(0.5, [5.5, 1.65, 2], [0, 1, 1.7], 48, { subject: [0, 1, 1.7] }), frame(1, [5.5, 1.65, 7], [0, 1, 3.4], 48, { subject: [0, 1, 3.4] })]),

  "quarter-arc-shot": orbit("stage", Math.PI / 2, 6.5, 1.6),
  "half-orbit-shot": orbit("stage", Math.PI, 6.5, 1.6),
  "full-orbit-shot": orbit("stage", TAU, 6.5, 1.6),
  "tight-orbit-shot": orbit("stage", Math.PI / 3, 3.2, 1.6, 35),
  "wide-orbit-shot": orbit("foreground", Math.PI * 0.75, 10, 2, 58),
  "low-angle-orbit": orbit("stage", Math.PI / 2, 5, 0.45, 49),
  "high-angle-orbit": orbit("stage", Math.PI / 2, 6, 5.5, 49),
  "spiral-in-orbit": orbit("foreground", Math.PI * 0.8, 9, 1.8, 48, 4),
  "spiral-out-orbit": orbit("foreground", Math.PI * 0.8, 4, 1.8, 48, 10),
  "group-orbit-shot": orbit("group", Math.PI * 0.65, 8, 2, 52),

  "crane-rise": moving("stage", [frame(0, [0, 1.6, 7]), frame(1, [0, 8, 7])]),
  "crane-descent": moving("stage", [frame(0, [0, 8, 7]), frame(1, [0, 1.6, 7])]),
  "pedestal-up": moving("stage", [frame(0, [0, 0.5, 7], [0, 0.5, 0]), frame(1, [0, 3.1, 7], [0, 3.1, 0])]),
  "pedestal-down": moving("stage", [frame(0, [0, 3.1, 7], [0, 3.1, 0]), frame(1, [0, 0.5, 7], [0, 0.5, 0])]),
  "jib-to-overhead": moving("stage", [frame(0, [0, 2, 7]), frame(0.5, [0, 5.5, 4.5]), frame(1, [0, 9, 0.01])]),
  "top-down-rise": moving("stage", [frame(0, [0, 5, 0.01]), frame(1, [0, 13, 0.01])]),
  "top-down-descent": moving("stage", [frame(0, [0, 13, 0.01]), frame(1, [0, 5, 0.01])]),
  "vertical-subject-follow": moving("stage", [frame(0, [0, 1.6, 7]), frame(1, [0, 5.1, 7], [0, 4.5, 0], 48, { subject: [0, 4.5, 0] })]),
  "rising-orbit": orbit("stage", Math.PI * 0.7, 6.5, 1.5, 48, 6.5, 6),
  "vertical-foreground-reveal": moving("foreground", [frame(0, [0, 0.45, 7], SUBJECT, 48, { occluder: 0.85 }), frame(1, [0, 3.8, 7], SUBJECT, 48, { occluder: 0 })]),

  // Lens-only changes share one fixed viewpoint, so they cannot produce dolly parallax.
  "slow-optical-zoom-in": lens("foreground", [frame(0, FRONT, SUBJECT, 62), frame(1, FRONT, SUBJECT, 26)]),
  "slow-optical-zoom-out": lens("foreground", [frame(0, FRONT, SUBJECT, 26), frame(1, FRONT, SUBJECT, 62)]),
  "snap-zoom-emphasis": lens("stage", [frame(0, FRONT, SUBJECT, 58), frame(0.34, FRONT, SUBJECT, 58), frame(0.43, FRONT, SUBJECT, 23), frame(1, FRONT, SUBJECT, 23)]),
  "rack-focus-near-to-far": lens("foreground", [frame(0, FRONT, SUBJECT, 48, { focus: 3 }), frame(0.22, FRONT, SUBJECT, 48, { focus: 3 }), frame(0.8, FRONT, SUBJECT, 48, { focus: 7 }), frame(1, FRONT, SUBJECT, 48, { focus: 7 })]),
  "rack-focus-far-to-near": lens("foreground", [frame(0, FRONT, SUBJECT, 48, { focus: 7 }), frame(0.22, FRONT, SUBJECT, 48, { focus: 7 }), frame(0.8, FRONT, SUBJECT, 48, { focus: 3 }), frame(1, FRONT, SUBJECT, 48, { focus: 3 })]),
  "forward-dolly-zoom": moving("foreground", [frame(0, [0, 1, 9], SUBJECT, 34), frame(1, [0, 1, 4.5], SUBJECT, 62.88)], { preserveSubjectScale: true }),
  "reverse-dolly-zoom": moving("foreground", [frame(0, [0, 1, 4.5], SUBJECT, 62.88), frame(1, [0, 1, 9], SUBJECT, 34)], { preserveSubjectScale: true }),
  "telephoto-compressed-framing": locked("foreground", [0, 1.65, 17], SUBJECT, 15, {}, false),
  "wide-angle-proximity": locked("foreground", [0, 1.6, 2.8], SUBJECT, 82, {}, false),
  "macro-focus-travel": lens("macro", [frame(0, [0.1, 1.2, 2.4], SUBJECT, 23, { focus: 1.8 }), frame(1, [0.1, 1.2, 2.4], SUBJECT, 23, { focus: 2.85 })]),

  "observational-handheld": moving("stage", [frame(0, FRONT), frame(1, FRONT)], { sway: { amplitude: 0.065, frequency: 1.6, roll: 0.012 } }),
  "shoulder-mounted-documentary": moving("stage", [frame(0, [-0.35, 1.65, 6.5], [-0.15, 1, 0]), frame(0.6, [0.25, 1.65, 6.4], [0.15, 1, 0], 48, { roll: 0.018 }), frame(1, [0.1, 1.65, 6.4])], { sway: { amplitude: 0.045, frequency: 1.2, roll: 0.016 } }),
  "handheld-pursuit": moving("stage", [frame(0, [0, 1.55, -5]), frame(1, [0, 1.55, 0], [0, 1, 5], 48, { subject: [0, 1, 5] })], { sway: { amplitude: 0.12, frequency: 6, roll: 0.015 } }),
  "handheld-backward-lead": moving("stage", [frame(0, [0, 1.6, 5.5]), frame(1, [0, 1.6, 9], [0, 1, 3.5], 48, { subject: [0, 1, 3.5] })], { sway: { amplitude: 0.07, frequency: 4.5, roll: 0.01 } }),
  "walking-first-person-pov": moving("foreground", [frame(0, [0, 1.7, 9], [0, 1.7, 0], 60), frame(1, [0, 1.7, 3.8], [0, 1.7, -5.2], 60)], { sway: { amplitude: 0.09, frequency: 5, roll: 0.007 } }),
  "subjective-head-turn": moving("stage", [frame(0, FRONT, [-4.3, 1, 0], 50), frame(0.55, FRONT, [0.3, 1, 0], 50), frame(0.72, FRONT, [-0.08, 1, 0], 50), frame(1, FRONT, SUBJECT, 50)]),
  "crouched-low-handheld": moving("foreground", [frame(0, [0, 0.55, 5]), frame(1, [0.1, 0.6, 5])], { sway: { amplitude: 0.05, frequency: 1.3, roll: 0.012 } }),
  "embedded-handheld-observation": moving("group", [frame(0, [-1, 1.6, 5.5], SUBJECT, 48, { occluder: 0.25 }), frame(0.5, [-0.5, 1.6, 5.5], [0.15, 1, 0], 48, { occluder: 0.08 }), frame(1, [-0.7, 1.6, 5.5], SUBJECT, 48, { occluder: 0.15 })], { sway: { amplitude: 0.06, frequency: 1.7, roll: 0.009 } }),
  "handheld-reactive-reframe": moving("stage", [frame(0, FRONT), frame(0.3, FRONT, SUBJECT, 48, { subject: [0.6, 1, 0] }), frame(0.55, FRONT, [0.85, 1, 0], 48, { subject: [0.6, 1, 0] }), frame(0.75, FRONT, [0.55, 1, 0], 48, { subject: [0.6, 1, 0] }), frame(1, FRONT, [0.6, 1, 0], 48, { subject: [0.6, 1, 0] })], { sway: { amplitude: 0.035, frequency: 2, roll: 0.005 } }),
  "passenger-handheld": moving("doorway", [frame(0, [0, 1.4, 7], [5, 1.2, 0], 60), frame(1, [0, 1.4, 2], [5, 1.2, -5], 60, { subject: [0, 1, -5] })], { sway: { amplitude: 0.04, frequency: 12, roll: 0.009 } }),

  "aerial-nadir-hover": locked("aerial", [0, 13, 0.01], SUBJECT, 58, {}, false),
  "aerial-nadir-drift": moving("aerial", [frame(0, [-5, 13, 0.01], [-5, 0, 0], 58), frame(1, [5, 13, 0.01], [5, 0, 0], 58)]),
  "aerial-vertical-ascent": moving("aerial", [frame(0, [0, 5, 8], SUBJECT, 58), frame(1, [0, 16, 8], SUBJECT, 58)]),
  "aerial-controlled-descent": moving("aerial", [frame(0, [0, 15, 11], SUBJECT, 58), frame(1, [0, 5, 11], SUBJECT, 58)]),
  "aerial-terrain-follow": moving("aerial", [frame(0, [0, 3.5, 12], [0, 1, 2], 62), frame(0.35, [0, 4.8, 8], [0, 2.3, -2], 62), frame(0.68, [0, 4, 4], [0, 1.5, -6], 62), frame(1, [0, 3.5, 0], [0, 1, -10], 62)]),
  "aerial-diagonal-transect": moving("aerial", [frame(0, [-7, 10, 9], [-3, 1, -1], 60), frame(1, [5, 10, 1], [9, 1, -9], 60)]),
  "aerial-gentle-bank": moving("aerial", [frame(0, [-5, 9, 10], [-5, 3, 0], 60), frame(0.5, [-3, 9, 4], [3, 3, -4], 60, { roll: -0.1 }), frame(1, [3, 9, 0], [11, 3, -5], 60)]),
  "aerial-boundary-follow": moving("aerial", [frame(0, [-5, 8, 10], [-1, 0, 2], 58), frame(0.5, [-4, 8, 5], [0, 0, -3], 58), frame(1, [-1, 8, 0], [4, 0, -7], 58)]),
  "aerial-opening-flythrough": moving("doorway", [frame(0, [0, 3.6, 13], [0, 3.6, 0], 65), frame(1, [0, 3.6, 2.2], [0, 3.6, -10.8], 65)]),
  "aerial-approach-brake": moving("aerial", [frame(0, [0, 7, 15], SUBJECT, 58), frame(0.65, [0, 7, 8.5], SUBJECT, 58), frame(0.85, [0, 7, 8], SUBJECT, 58), frame(1, [0, 7, 8], SUBJECT, 58)]),

  "moving-occluder-reveal": lens("foreground", [frame(0, FRONT, SUBJECT, 48, { occluder: 0.9 }), frame(0.2, FRONT, SUBJECT, 48, { occluder: 0.9 }), frame(0.82, FRONT, SUBJECT, 48, { occluder: 0 }), frame(1, FRONT)]),
  "defocus-to-sharp-reveal": lens("stage", [frame(0, FRONT, SUBJECT, 48, { blur: 1 }), frame(0.2, FRONT, SUBJECT, 48, { blur: 1 }), frame(0.85, FRONT, SUBJECT, 48, { blur: 0 }), frame(1, FRONT)]),
  "architectural-corner-peek": moving("doorway", [frame(0, [-2.7, 1.6, 6], SUBJECT, 48, { occluder: 0.8 }), frame(1, [0.3, 1.6, 6], SUBJECT, 48, { occluder: 0.08 })]),
  "detail-to-subject-tilt": moving("stage", [frame(0, [0, 1.6, 5.8], [0, -0.8, 0], 44), frame(1, [0, 1.6, 5.8], [0, 1.45, 0], 44)]),
  "negative-space-pan-reveal": moving("stage", [frame(0, FRONT, [-5.5, 1, 0]), frame(0.8, FRONT, [-0.55, 1, 0]), frame(1, FRONT, [-0.55, 1, 0])]),
  "backlight-flare-clear-reveal": moving("stage", [frame(0, FRONT, [-1.5, 1.1, 0], 48, { blur: 0.55 }), frame(1, FRONT, SUBJECT, 48, { blur: 0 })]),
  "reflection-to-direct-reveal": moving("doorway", [frame(0, [3.8, 1.65, 4.5], [-2.2, 1, 0], 44), frame(0.5, [3.8, 1.65, 4.5], [-1, 1, 0], 44), frame(1, [3.8, 1.65, 4.5], SUBJECT, 44)]),
  "shadow-exposure-reveal": lens("stage", [frame(0, FRONT, SUBJECT, 48, { light: 0.13 }), frame(1, FRONT, SUBJECT, 48, { light: 1 })]),
  "motivated-light-reveal": lens("stage", [frame(0, FRONT, SUBJECT, 48, { light: 0.12 }), frame(0.32, FRONT, SUBJECT, 48, { light: 0.25 }), frame(0.65, FRONT, SUBJECT, 48, { light: 1 }), frame(1, FRONT, SUBJECT, 48, { light: 0.35 })]),
  "whip-pan-reveal": moving("stage", [frame(0, FRONT, [-8, 1, 0]), frame(0.32, FRONT, [-8, 1, 0]), frame(0.43, FRONT, [-3, 1, 0], 48, { blur: 0.85 }), frame(0.55, FRONT, [0.35, 1, 0]), frame(0.65, FRONT, SUBJECT), frame(1, FRONT)]),

  // Intermediate frames retain explicit phases: e.g. zoom first, then slide, never both together.
  "pan-then-push-in": moving("stage", [frame(0, FRONT, [-4.5, 1, 0]), frame(0.48, FRONT), frame(1, [0, 1.6, 4])]),
  "push-then-pedestal-rise": moving("stage", [frame(0, [0, 0.7, 8]), frame(0.5, [0, 0.7, 4.8]), frame(1, [0, 1.7, 4.8])]),
  "slide-then-square-up": moving("foreground", [frame(0, [-3, 1.6, 7], [-1.5, 1, 0]), frame(0.5, [1.5, 1.6, 7], [3, 1, 0]), frame(1, [1.5, 1.6, 7], SUBJECT)]),
  "pullback-then-tilt-up": moving("stage", [frame(0, [0, 1.6, 4.2]), frame(0.5, [0, 1.6, 8]), frame(1, [0, 1.6, 8], [0, 3.2, 0])]),
  "tilt-down-then-advance": moving("stage", [frame(0, FRONT, [0, 4, 0]), frame(0.5, FRONT, [0, 0.8, 0]), frame(1, [0, 1.6, 4.2], [0, 0.8, 0])]),
  "focus-pull-then-reframe": moving("foreground", [frame(0, FRONT, [-0.8, 1, 0], 48, { focus: 3 }), frame(0.55, FRONT, [-0.8, 1, 0], 48, { focus: 7 }), frame(1, FRONT, SUBJECT, 48, { focus: 7 })]),
  "zoom-out-then-slide": moving("foreground", [frame(0, FRONT, SUBJECT, 27), frame(0.5, FRONT, SUBJECT, 56), frame(1, [3.2, 1.6, 7], SUBJECT, 56)]),
  "approach-then-side-step": moving("foreground", [frame(0, [0, 1.6, 8]), frame(0.55, [0, 1.6, 4.8]), frame(1, [1.6, 1.6, 4.8])]),
  "foreground-pass-then-follow": moving("foreground", [frame(0, [-3, 1.6, 6], SUBJECT, 48, { occluder: 0.8 }), frame(0.4, [0, 1.6, 6], SUBJECT, 48, { occluder: 0 }), frame(1, [3.5, 1.6, 6], [3.5, 1, 0], 48, { subject: [3.5, 1, 0] })]),
  "level-horizon-then-push": moving("stage", [frame(0, FRONT, SUBJECT, 48, { roll: -0.2 }), frame(0.48, FRONT), frame(1, [0, 1.6, 4.2])]),

  // Rotation is a change of aim around a stationary camera, rather than an orbit around the subject.
  "tripod-pan-follow": moving("stage", [frame(0, FRONT, [-3, 1.2, 0], 40, { subject: [-3, 1, 0] }), frame(1, FRONT, [3, 1.2, 0], 40, { subject: [3, 1, 0] })]),
  "fixed-tilt-down": moving("stage", [frame(0, FRONT, [0, 4.3, 0], 40), frame(1, FRONT, [0, 0.3, 0], 40)]),
  "axial-barrel-roll": moving("stage", [frame(0, FRONT), frame(1, FRONT, SUBJECT, 48, { roll: TAU })], { durationMs: 8000 }),
  "pan-between-subjects": moving("dialogue", [
    frame(0, [3.8, 1.7, 4.5], [0, 1.4, 0], 26, { focus: Math.hypot(3.8, 0.7, 4.5) }),
    frame(0.24, [3.8, 1.7, 4.5], [0, 1.4, 0], 26, { focus: Math.hypot(3.8, 0.7, 4.5) }),
    frame(0.76, [3.8, 1.7, 4.5], [-1.05, 1.4, 2.2], 26, { focus: Math.hypot(4.85, 0.7, 2.3) }),
    frame(1, [3.8, 1.7, 4.5], [-1.05, 1.4, 2.2], 26, { focus: Math.hypot(4.85, 0.7, 2.3) }),
  ]),
  // The front-mounted camera travels rigidly with the actor: body size and placement stay fixed.
  "snorricam-body-mount": moving("foreground", [
    frame(0, [0, 1.6, 1.55], [0, 1.45, 0], 58),
    frame(1, [0, 1.6, 7.55], [0, 1.45, 6], 58, { subject: [0, 1, 6] }),
  ]),
  "fpv-vertical-dive": moving("aerial", [
    frame(0, [4, 16, 10], [4, 4, 6], 68),
    frame(0.42, [4, 6, 6], [4, 1.8, -1], 68),
    frame(0.7, [4, 1.8, 0], [4, 1.8, -8], 68),
    frame(1, [4, 1.8, -7], [4, 1.8, -15], 68),
  ], { durationMs: 8000, interpolation: "continuous" }),
  // Pillars are centered at (0, 4) and (0, -2); the path passes their left and right sides.
  "fpv-gap-slalom": moving("slalom", [
    frame(0, [-2, 1.7, 11], [-2, 1.7, 3], 68),
    frame(0.32, [-2, 1.7, 4], [2, 1.7, -1], 68, { roll: -0.14 }),
    frame(0.52, [2, 1.7, 1], [2, 1.7, -7], 68, { roll: 0.12 }),
    frame(0.72, [2, 1.7, -2], [0, 1.7, -10], 68, { roll: 0.1 }),
    frame(1, [0, 1.7, -8], [0, 1.7, -16], 68),
  ], { durationMs: 8000, interpolation: "continuous" }),
  "aerial-subject-follow": moving("aerial", [
    frame(0, [-7, 7, -8], [-3, 1, -1], 58, { subject: [-3, 1, -1] }),
    frame(1, [-1, 7, -2], [3, 1, 5], 58, { subject: [3, 1, 5] }),
  ]),
  "moving-subject-orbit": {
    scene: "stage", motion: "moving", durationMs: 8000,
    keyframes: [frame(0, [0, 2, 6]), frame(1, [4, 2, -4], [4, 1, 2], 48, { subject: [4, 1, 2] })],
    orbit: { sweep: -Math.PI, startAngle: 0, startRadius: 6, endRadius: 6, startHeight: 2, endHeight: 2, relativeToSubject: true },
  },
  // The lens keeps facing forward and passes beside the actor with a clear 1.8-unit lateral offset.
  "push-past-subject": moving("foreground", [
    frame(0, [1.8, 1.6, 8], [1.8, 1.4, 0], 60),
    frame(1, [1.8, 1.6, -5], [1.8, 1.4, -13], 60),
  ]),
  "aerial-flyover-lookback": moving("aerial", [
    frame(0, [0, 7, 11], [0, 1, 1], 58),
    frame(0.5, [0, 7, -5], [0, 1, -15], 58),
    frame(0.65, [0, 7, -5], [8, 1, -7], 58),
    frame(0.82, [0, 7, -5], SUBJECT, 58),
    frame(1, [0, 7, -5], SUBJECT, 58),
  ], { durationMs: 8000 }),
  "corkscrew-flythrough": moving("doorway", [
    frame(0, [0, 1.8, 11], [0, 1.8, 0], 65),
    frame(1, [0, 1.8, 2], [0, 1.8, -9], 65, { roll: TAU }),
  ], { durationMs: 8000 }),
} as const satisfies Readonly<Record<string, MotionDefinition>>;

/** Every preset has an authored entry; absent IDs are intentionally not replaced by a generic move. */
export const VIDEO_CAMERA_MOTIONS: Readonly<Record<string, VideoCameraMotionProfile>> = Object.fromEntries(
  Object.entries(definitions).map(([id, definition]) => [id, { id, ...definition }]),
);

function mix(a: number, b: number, amount: number): number { return a + (b - a) * amount; }
function mixVector(a: VideoCameraVector, b: VideoCameraVector, amount: number): VideoCameraVector {
  return [mix(a[0], b[0], amount), mix(a[1], b[1], amount), mix(a[2], b[2], amount)];
}
function distance(a: VideoCameraVector, b: VideoCameraVector): number {
  return Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
}
function smoothstep(value: number): number { return value * value * (3 - 2 * value); }

type ReadFrameValue = (keyframe: VideoCameraMotionKeyframe) => number;

/** PCHIP keeps a shared waypoint tangent while preventing overshoot at turns and level ground clearance. */
function continuousTangent(keyframes: readonly VideoCameraMotionKeyframe[], index: number, read: ReadFrameValue): number {
  const last = keyframes.length - 1;
  if (last < 1) return 0;
  const slope = (left: number, right: number) =>
    (read(keyframes[right]!) - read(keyframes[left]!)) / (keyframes[right]!.at - keyframes[left]!.at);
  if (last === 1) return slope(0, 1);
  if (index > 0 && index < last) {
    const before = slope(index - 1, index);
    const after = slope(index, index + 1);
    if (before * after <= 0) return 0;
    const beforeSpan = keyframes[index]!.at - keyframes[index - 1]!.at;
    const afterSpan = keyframes[index + 1]!.at - keyframes[index]!.at;
    const beforeWeight = 2 * afterSpan + beforeSpan;
    const afterWeight = afterSpan + 2 * beforeSpan;
    return (beforeWeight + afterWeight) / (beforeWeight / before + afterWeight / after);
  }
  const endpoint = index === 0 ? 0 : last;
  const neighbor = index === 0 ? 1 : last - 1;
  const next = index === 0 ? 2 : last - 2;
  const span = Math.abs(keyframes[neighbor]!.at - keyframes[endpoint]!.at);
  const nextSpan = Math.abs(keyframes[next]!.at - keyframes[neighbor]!.at);
  const delta = slope(Math.min(endpoint, neighbor), Math.max(endpoint, neighbor));
  const nextDelta = slope(Math.min(neighbor, next), Math.max(neighbor, next));
  const tangent = ((2 * span + nextSpan) * delta - span * nextDelta) / (span + nextSpan);
  if (Math.sign(tangent) !== Math.sign(delta)) return 0;
  return Math.sign(delta) !== Math.sign(nextDelta) && Math.abs(tangent) > 3 * Math.abs(delta) ? 3 * delta : tangent;
}

function sampleContinuousValue(keyframes: readonly VideoCameraMotionKeyframe[], fromIndex: number, toIndex: number,
  progress: number, read: ReadFrameValue): number {
  const from = keyframes[fromIndex]!;
  const to = keyframes[toIndex]!;
  const fromValue = read(from);
  const toValue = read(to);
  if (fromIndex === toIndex || fromValue === toValue) return fromValue;
  const span = to.at - from.at;
  const t = (progress - from.at) / span;
  if (t === 0) return fromValue;
  if (t === 1) return toValue;
  const t2 = t * t;
  const t3 = t2 * t;
  return (2 * t3 - 3 * t2 + 1) * fromValue
    + (t3 - 2 * t2 + t) * span * continuousTangent(keyframes, fromIndex, read)
    + (-2 * t3 + 3 * t2) * toValue
    + (t3 - t2) * span * continuousTangent(keyframes, toIndex, read);
}

function sampleContinuousVector(keyframes: readonly VideoCameraMotionKeyframe[], fromIndex: number, toIndex: number,
  progress: number, field: "position" | "target"): VideoCameraVector {
  return [
    sampleContinuousValue(keyframes, fromIndex, toIndex, progress, (keyframe) => keyframe[field][0]),
    sampleContinuousValue(keyframes, fromIndex, toIndex, progress, (keyframe) => keyframe[field][1]),
    sampleContinuousValue(keyframes, fromIndex, toIndex, progress, (keyframe) => keyframe[field][2]),
  ];
}

/** Pure sampling allows playback, scrubbing, paused frames and path diagrams to share exactly one motion. */
export function sampleVideoCameraMotion(profile: VideoCameraMotionProfile, progress: number): VideoCameraMotionState {
  const bounded = Number.isFinite(progress) ? Math.min(1, Math.max(0, progress)) : 0;
  const first = profile.keyframes[0];
  if (!first) throw new Error(`Camera motion ${profile.id} has no keyframes`);
  let from = first;
  let to = first;
  let fromIndex = 0;
  let toIndex = 0;
  for (const [index, keyframe] of profile.keyframes.entries()) {
    to = keyframe;
    toIndex = index;
    if (keyframe.at >= bounded) break;
    from = keyframe;
    fromIndex = index;
  }
  const fraction = to.at === from.at ? 0 : smoothstep((bounded - from.at) / (to.at - from.at));
  let position = mixVector(from.position, to.position, fraction);
  let target = mixVector(from.target, to.target, fraction);
  const subject = mixVector(from.subject ?? SUBJECT, to.subject ?? SUBJECT, fraction);
  let roll = mix(from.roll ?? 0, to.roll ?? 0, fraction);
  let fov = mix(from.fov, to.fov, fraction);
  if (profile.interpolation === "continuous") {
    position = sampleContinuousVector(profile.keyframes, fromIndex, toIndex, bounded, "position");
    target = sampleContinuousVector(profile.keyframes, fromIndex, toIndex, bounded, "target");
    roll = sampleContinuousValue(profile.keyframes, fromIndex, toIndex, bounded, (keyframe) => keyframe.roll ?? 0);
  }
  if (profile.orbit) {
    const { startAngle, sweep, startRadius, endRadius, startHeight, endHeight, relativeToSubject } = profile.orbit;
    const phase = smoothstep(bounded);
    const angle = startAngle + sweep * phase;
    const radius = mix(startRadius, endRadius, phase);
    const centerX = relativeToSubject ? subject[0] : 0;
    const centerZ = relativeToSubject ? subject[2] : 0;
    position = [centerX + Math.sin(angle) * radius, mix(startHeight, endHeight, phase), centerZ + Math.cos(angle) * radius];
  }
  if (profile.sway) {
    const { amplitude, frequency, roll: rollAmplitude } = profile.sway;
    const envelope = Math.sin(Math.PI * bounded);
    const phase = TAU * bounded * frequency;
    position = [position[0] + Math.sin(phase * 0.71) * amplitude * envelope,
      position[1] + Math.sin(phase) * amplitude * envelope,
      position[2] + Math.sin(phase * 0.43) * amplitude * envelope * 0.3];
    roll += Math.sin(phase * 0.83) * rollAmplitude * envelope;
  }
  if (profile.preserveSubjectScale) {
    const initialDistance = distance(first.position, first.subject ?? SUBJECT);
    const framingScale = initialDistance * Math.tan(first.fov * Math.PI / 360);
    fov = Math.atan(framingScale / distance(position, subject)) * 360 / Math.PI;
  }
  return {
    position, target, subject, fov, roll,
    focus: from.focus === undefined && to.focus === undefined ? distance(position, subject)
      : mix(from.focus ?? distance(from.position, from.subject ?? SUBJECT), to.focus ?? distance(to.position, to.subject ?? SUBJECT), fraction),
    occluder: mix(from.occluder ?? 0, to.occluder ?? 0, fraction),
    light: mix(from.light ?? 1, to.light ?? 1, fraction),
    blur: mix(from.blur ?? 0, to.blur ?? 0, fraction),
  };
}
