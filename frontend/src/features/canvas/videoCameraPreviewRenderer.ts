import type { VideoCameraMotionProfile, VideoCameraMotionState, VideoCameraVector } from "./videoCameraMotion";
import { sampleVideoCameraMotion } from "./videoCameraMotion";

/** Resolved semantic colors, supplied by the host so the stage follows the app theme. */
export type VideoCameraPreviewPalette = Readonly<{
  background: string;
  surface: string;
  grid: string;
  border: string;
  muted: string;
  text: string;
  accent: string;
  accentLight: string;
  subject: string;
  secondary: string;
}>;

export type ProjectedWorldPoint = Readonly<{ x: number; y: number; depth: number; scale: number }>;
type Vector = VideoCameraVector;
type CameraBasis = Readonly<{ right: Vector; up: Vector; forward: Vector }>;
type Camera = Pick<VideoCameraMotionState, "position" | "target" | "fov" | "roll">;
type Surface = {
  points: readonly Vector[];
  color: string;
  alpha?: number;
  shade?: number;
  stroke?: string;
  line?: boolean;
  width?: number;
};

const NEAR_PLANE = 0.08;
const TAU = Math.PI * 2;
const SLALOM_PILLARS: readonly Vector[] = [[0, 1.5, 4], [0, 1.5, -2]];
const add = (a: Vector, b: Vector): Vector => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const subtract = (a: Vector, b: Vector): Vector => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const multiply = (a: Vector, scale: number): Vector => [a[0] * scale, a[1] * scale, a[2] * scale];
const dot = (a: Vector, b: Vector) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a: Vector, b: Vector): Vector => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const length = (a: Vector) => Math.hypot(...a);
const normalize = (a: Vector, fallback: Vector = [0, 0, -1]): Vector => length(a) > 0.00001 ? multiply(a, 1 / length(a)) : fallback;
const mix = (a: Vector, b: Vector, progress: number): Vector => add(a, multiply(subtract(b, a), progress));
const clamp = (value: number, minimum: number, maximum: number) => Math.min(maximum, Math.max(minimum, value));

/** The recorded frame is 16:9 even when its top-down companion has another aspect. */
export function videoCameraHorizontalFov(verticalFov: number, aspectRatio = 16 / 9) {
  return Math.atan(Math.tan(clamp(verticalFov, 8, 135) * Math.PI / 360) * aspectRatio) * 360 / Math.PI;
}

/** Through/pullback rehearsals must put the doorway between their camera endpoints. */
export function videoCameraDoorwayPlane(presetId: string) {
  return ["doorway-dolly-through", "doorway-pullback", "aerial-opening-flythrough", "corkscrew-flythrough"].includes(presetId) ? 6 : 2.25;
}

/** A fixed source sweeps a local beam; exposure stays separate from this illumination. */
export function videoCameraLocalLight(point: Vector, state: VideoCameraMotionState, progress: number) {
  const source: Vector = [-3.8, 4.8, 3];
  const beamTarget: Vector = [-3.5 + progress * 5.5, 1, 0];
  const axis = normalize(subtract(beamTarget, source));
  const relative = subtract(point, source);
  const distanceToAxis = length(subtract(relative, multiply(axis, dot(relative, axis))));
  return 0.1 + 0.9 * clamp(state.light, 0, 1) * Math.exp(-(distanceToAxis ** 2) / 0.9);
}

function cameraBasis(camera: Camera): CameraBasis {
  const forward = normalize(subtract(camera.target, camera.position));
  // Top-down cranes need a stable basis even when the viewing axis matches world up.
  const referenceUp: Vector = Math.abs(forward[1]) > 0.995 ? [0, 0, -1] : [0, 1, 0];
  const right = normalize(cross(forward, referenceUp), [1, 0, 0]);
  const up = normalize(cross(right, forward), [0, 1, 0]);
  return { right, up, forward };
}

function worldDepth(point: Vector, camera: Camera, basis: CameraBasis) {
  return dot(subtract(point, camera.position), basis.forward);
}

function projectWithBasis(point: Vector, camera: Camera, basis: CameraBasis, width: number, height: number): ProjectedWorldPoint | null {
  const relative = subtract(point, camera.position);
  const depth = dot(relative, basis.forward);
  if (!Number.isFinite(depth) || depth < NEAR_PLANE - 0.000001) return null;
  const horizontal = dot(relative, basis.right);
  const vertical = dot(relative, basis.up);
  const cosine = Math.cos(camera.roll);
  const sine = Math.sin(camera.roll);
  const focalLength = height / (2 * Math.tan(clamp(camera.fov, 8, 135) * Math.PI / 360));
  const scale = focalLength / Math.max(NEAR_PLANE, depth);
  return {
    x: width / 2 + (horizontal * cosine + vertical * sine) * scale,
    y: height / 2 - (vertical * cosine - horizontal * sine) * scale,
    depth,
    scale,
  };
}

/** A true perspective projection: changing position and changing focal length stay distinct. */
export function projectWorldPoint(point: Vector, camera: Camera, width: number, height: number): ProjectedWorldPoint | null {
  return projectWithBasis(point, camera, cameraBasis(camera), width, height);
}

/** Clip before perspective division; a path crossing the lens never explodes or flips. */
export function clipWorldSegment(a: Vector, b: Vector, camera: Camera): readonly [Vector, Vector] | null {
  const basis = cameraBasis(camera);
  const startDepth = worldDepth(a, camera, basis);
  const endDepth = worldDepth(b, camera, basis);
  if (startDepth < NEAR_PLANE && endDepth < NEAR_PLANE) return null;
  if (startDepth >= NEAR_PLANE && endDepth >= NEAR_PLANE) return [a, b];
  const intersection = mix(a, b, (NEAR_PLANE - startDepth) / (endDepth - startDepth));
  return startDepth < NEAR_PLANE ? [intersection, b] : [a, intersection];
}

function clipPolygon(points: readonly Vector[], camera: Camera, basis: CameraBasis): Vector[] {
  const output: Vector[] = [];
  for (let index = 0; index < points.length; index += 1) {
    const a = points[index]!;
    const b = points[(index + 1) % points.length]!;
    const depthA = worldDepth(a, camera, basis);
    const depthB = worldDepth(b, camera, basis);
    if (depthA >= NEAR_PLANE) output.push(a);
    if ((depthA >= NEAR_PLANE) !== (depthB >= NEAR_PLANE)) {
      output.push(mix(a, b, (NEAR_PLANE - depthA) / (depthB - depthA)));
    }
  }
  return output;
}

function box(surfaces: Surface[], center: Vector, size: Vector, color: string, stroke: string, alpha = 1) {
  const points: Vector[] = [
    [-1, -1, -1], [1, -1, -1], [1, 1, -1], [-1, 1, -1],
    [-1, -1, 1], [1, -1, 1], [1, 1, 1], [-1, 1, 1],
  ].map((point) => add(center, [point[0]! * size[0] / 2, point[1]! * size[1] / 2, point[2]! * size[2] / 2]));
  const faces = [[0, 1, 2, 3], [5, 4, 7, 6], [4, 0, 3, 7], [1, 5, 6, 2], [3, 2, 6, 7], [4, 5, 1, 0]];
  const shades = [0.32, 0.02, 0.2, 0.11, 0, 0.36];
  faces.forEach((indices, index) => surfaces.push({ points: indices.map((vertex) => points[vertex]!), color, stroke, shade: shades[index], alpha }));
}

function ellipsoid(surfaces: Surface[], center: Vector, radius: Vector, color: string, stroke?: string) {
  const segments = 10;
  const rings = 5;
  function vertex(ring: number, segment: number): Vector {
    const latitude = Math.PI * ring / rings;
    const longitude = TAU * segment / segments;
    return add(center, [Math.sin(latitude) * Math.cos(longitude) * radius[0], Math.cos(latitude) * radius[1], Math.sin(latitude) * Math.sin(longitude) * radius[2]]);
  }
  for (let ring = 0; ring < rings; ring += 1) {
    for (let segment = 0; segment < segments; segment += 1) {
      surfaces.push({ points: [vertex(ring, segment), vertex(ring + 1, segment), vertex(ring + 1, segment + 1), vertex(ring, segment + 1)], color,
        shade: 0.06 + (1 - Math.sin(TAU * (segment + 0.5) / segments)) * 0.1 + ring * 0.025, stroke });
    }
  }
}

function disc(surfaces: Surface[], center: Vector, radius: number, color: string, alpha = 1, stroke?: string) {
  surfaces.push({ points: Array.from({ length: 36 }, (_, index) => add(center, [Math.cos(index / 36 * TAU) * radius, 0, Math.sin(index / 36 * TAU) * radius])), color, alpha, stroke });
}

function line(surfaces: Surface[], points: readonly Vector[], color: string, alpha = 1, width = 1) {
  surfaces.push({ points, color, line: true, alpha, width });
}

function mannequin(surfaces: Surface[], origin: Vector, color: string, palette: VideoCameraPreviewPalette, progress: number, animate: boolean, yaw = 0) {
  const floor = add(origin, [0, -1, 0]);
  const cosine = Math.cos(yaw);
  const sine = Math.sin(yaw);
  const at = (x: number, y: number, z: number): Vector => add(floor, [x * cosine + z * sine, y, -x * sine + z * cosine]);
  const gesture = animate ? Math.sin(progress * TAU) * 0.035 : 0;
  disc(surfaces, at(0, 0.014, 0), 0.66, palette.accent, 0.1, palette.border);
  disc(surfaces, at(0, 0.018, 0), 0.37, palette.background, 0.15);
  box(surfaces, at(-0.115, 0.42, 0), [0.16, 0.76, 0.19], color, palette.border);
  box(surfaces, at(0.115, 0.42, 0), [0.16, 0.76, 0.19], color, palette.border);
  box(surfaces, at(-0.115, 0.065, 0.055), [0.185, 0.13, 0.3], palette.muted, palette.border);
  box(surfaces, at(0.115, 0.065, 0.055), [0.185, 0.13, 0.3], palette.muted, palette.border);
  box(surfaces, at(0, 0.86, 0), [0.39, 0.22, 0.24], color, palette.border);
  const torso: Vector[] = [[-0.19, 0.94, -0.12], [0.19, 0.94, -0.12], [0.25, 1.4, -0.13], [-0.25, 1.4, -0.13],
    [-0.19, 0.94, 0.12], [0.19, 0.94, 0.12], [0.25, 1.4, 0.13], [-0.25, 1.4, 0.13]].map(([x, y, z]) => at(x!, y!, z!));
  [[0, 1, 2, 3], [5, 4, 7, 6], [4, 0, 3, 7], [1, 5, 6, 2], [3, 2, 6, 7]].forEach((indices, index) => {
    surfaces.push({ points: indices.map((vertex) => torso[vertex]!), color, shade: index === 0 ? 0.27 : index === 1 ? 0 : 0.14, stroke: palette.border });
  });
  box(surfaces, at(-0.315, 1.095 + gesture, 0), [0.13, 0.56, 0.15], color, palette.border);
  box(surfaces, at(0.315, 1.095 - gesture, 0), [0.13, 0.56, 0.15], color, palette.border);
  ellipsoid(surfaces, at(-0.315, 0.795 + gesture, 0), [0.074, 0.088, 0.08], palette.accentLight);
  ellipsoid(surfaces, at(0.315, 0.795 - gesture, 0), [0.074, 0.088, 0.08], palette.accentLight);
  box(surfaces, at(0, 1.455, 0), [0.13, 0.15, 0.13], palette.accentLight, palette.border);
  ellipsoid(surfaces, at(0, 1.68, 0), [0.18, 0.225, 0.165], palette.accentLight);
  // Face and chest markings are fixed in world space: the back really differs from the front.
  box(surfaces, at(-0.063, 1.72, 0.153), [0.033, 0.027, 0.023], palette.text, palette.text);
  box(surfaces, at(0.063, 1.72, 0.153), [0.033, 0.027, 0.023], palette.text, palette.text);
  box(surfaces, at(0, 1.655, 0.172), [0.025, 0.056, 0.041], color, palette.border);
  line(surfaces, [at(-0.045, 1.595, 0.156), at(0.045, 1.595, 0.156)], palette.text, 0.7, 1.2);
  line(surfaces, [at(-0.12, 1.31, 0.137), at(0, 1.17, 0.137), at(0.12, 1.31, 0.137)], palette.accentLight, 0.9, 1.6);
  line(surfaces, [at(0, 1.12, -0.128), at(0, 1.35, -0.128)], palette.background, 0.6, 1.4);
}

function subjectHeading(profile: VideoCameraMotionProfile, progress: number) {
  const first = profile.keyframes[0]?.subject ?? [0, 1, 0];
  const last = profile.keyframes[profile.keyframes.length - 1]?.subject ?? [0, 1, 0];
  // A traveling actor faces along the route. Tiny framing gestures and stationary
  // orbit subjects keep their authored front, so a camera orbit still reveals a back.
  if (profile.motion === "locked" || Math.hypot(last[0]! - first[0]!, last[2]! - first[2]!) < 1) return 0;
  const before = sampleVideoCameraMotion(profile, Math.max(0, progress - 0.02)).subject;
  const after = sampleVideoCameraMotion(profile, Math.min(1, progress + 0.02)).subject;
  const direction = subtract(after, before);
  return Math.hypot(direction[0], direction[2]) > 0.0001 ? Math.atan2(direction[0], direction[2]) : 0;
}

function buildStage(state: VideoCameraMotionState, profile: VideoCameraMotionProfile, palette: VideoCameraPreviewPalette, progress: number): Surface[] {
  const surfaces: Surface[] = [];
  // Anchors span near, middle and far depth; their parallax makes truck/dolly/zoom easy to compare.
  for (let grid = -18; grid <= 18; grid += 1) {
    const major = grid % 4 === 0;
    line(surfaces, [[grid, 0, -18], [grid, 0, 18]], palette.grid, major ? 0.4 : 0.17, major ? 0.85 : 0.6);
    line(surfaces, [[-18, 0, grid], [18, 0, grid]], palette.grid, major ? 0.4 : 0.17, major ? 0.85 : 0.6);
  }
  const back = -12;
  box(surfaces, [-4.2, 1.5, back - 0.35], [4.3, 3, 0.12], palette.surface, palette.border, 0.97);
  box(surfaces, [4.2, 1.5, back - 0.35], [4.3, 3, 0.12], palette.surface, palette.border, 0.97);
  for (const x of [-6.4, -2.1, 2.1, 6.4]) {
    box(surfaces, [x, 1.5, back], [0.17, 3, 0.26], palette.muted, palette.border, 0.7);
    line(surfaces, [[x - 0.3, 2.6, back + 0.14], [x + 0.3, 2.6, back + 0.14]], palette.accentLight, 0.4, 1.3);
  }
  box(surfaces, [-3.45, 0.5, -0.85], [0.8, 1, 0.8], palette.secondary, palette.border, 0.9);
  box(surfaces, [3.3, 0.9, -2.2], [0.55, 1.8, 0.55], palette.muted, palette.border, 0.8);
  box(surfaces, [2.15, 0.31, 1.9], [0.6, 0.62, 0.6], palette.secondary, palette.border);
  box(surfaces, [-2.5, 0.53, 2.7], [0.46, 1.06, 0.46], palette.muted, palette.border);
  ellipsoid(surfaces, [-2.5, 1.22, 2.7], [0.29, 0.29, 0.29], palette.secondary);
  if (profile.scene === "macro") {
    box(surfaces, [0.65, 0.46, 0.7], [0.36, 0.92, 0.36], palette.secondary, palette.border);
    ellipsoid(surfaces, [0.65, 1.025, 0.7], [0.19, 0.19, 0.19], palette.accentLight);
  }
  if (profile.scene === "foreground") {
    box(surfaces, [-1.03, 0.58, 3.75], [0.55, 1.16, 0.55], palette.secondary, palette.border);
    ellipsoid(surfaces, [-1.03, 1.36, 3.75], [0.25, 0.25, 0.25], palette.accentLight);
  }
  if (profile.scene === "dialogue") {
    mannequin(surfaces, add(state.subject, [-1.05, 0, 2.2]), palette.secondary, palette, progress, false, Math.atan2(1.05, -2.2));
  }
  if (profile.scene === "group") {
    mannequin(surfaces, add(state.subject, [-1.6, 0, -0.5]), palette.secondary, palette, progress, false);
    mannequin(surfaces, add(state.subject, [1.6, 0, -0.7]), palette.secondary, palette, progress, false);
  }
  if (profile.scene === "slalom") {
    for (const center of SLALOM_PILLARS) {
      box(surfaces, center, [1.1, 3, 1.1], palette.secondary, palette.border);
      box(surfaces, add(center, [0, 1.05, 0]), [1.15, 0.18, 1.15], palette.accentLight, palette.border);
    }
  }
  if (profile.id === "fpv-vertical-dive") {
    // A tall, fixed landmark makes a descent legible before the camera levels off.
    box(surfaces, [1, 8, 6], [1.6, 16, 1.6], palette.secondary, palette.border);
    for (let height = 2; height < 16; height += 2) {
      box(surfaces, [1, height, 6], [1.65, 0.12, 1.65], palette.accentLight, palette.border);
    }
  }
  if (profile.scene === "doorway") {
    const openingHeight = ["aerial-opening-flythrough", "corkscrew-flythrough"].includes(profile.id) ? 5 : 3.6;
    const doorwayZ = videoCameraDoorwayPlane(profile.id);
    box(surfaces, [-1.65, openingHeight / 2, doorwayZ], [0.45, openingHeight, 0.44], palette.muted, palette.border);
    box(surfaces, [1.65, openingHeight / 2, doorwayZ], [0.45, openingHeight, 0.44], palette.muted, palette.border);
    box(surfaces, [0, openingHeight - 0.2, doorwayZ], [3.75, 0.4, 0.44], palette.muted, palette.border);
  }
  if (profile.id === "reflection-to-direct-reveal") {
    // A second, faint figure lives inside a framed mirror, separate from the direct subject.
    box(surfaces, [-2.25, 1.05, -0.14], [1.25, 2.1, 0.12], palette.muted, palette.border);
    box(surfaces, [-2.25, 1.05, -0.065], [1.08, 1.94, 0.035], palette.surface, palette.accentLight);
    mannequin(surfaces, [-2.25, 1, 0.02], palette.secondary, palette, progress, false);
    line(surfaces, [[-2.75, 0.1, 0.26], [-1.75, 1.96, 0.26]], palette.accentLight, 0.28, 1.5);
  }
  if (profile.id === "motivated-light-reveal") {
    disc(surfaces, [-3.5 + progress * 5.5, 0.022, 0], 1, palette.accentLight, 0.14);
  }
  mannequin(surfaces, state.subject, palette.subject, palette, progress, profile.motion === "locked", subjectHeading(profile, progress));
  return surfaces;
}

function drawFrame(context: CanvasRenderingContext2D, width: number, height: number, state: VideoCameraMotionState, profile: VideoCameraMotionProfile, progress: number, palette: VideoCameraPreviewPalette) {
  const basis = cameraBasis(state);
  const gradient = context.createLinearGradient(0, 0, 0, height);
  gradient.addColorStop(0, palette.background);
  gradient.addColorStop(1, palette.surface);
  context.fillStyle = gradient;
  context.fillRect(0, 0, width, height);
  const surfaces = buildStage(state, profile, palette, progress).map((surface) => ({
    ...surface,
    center: multiply(surface.points.reduce((total, point) => add(total, point), [0, 0, 0] as Vector), 1 / surface.points.length),
    depth: surface.points.reduce((total, point) => total + worldDepth(point, state, basis), 0) / surface.points.length,
  })).sort((a, b) => b.depth - a.depth);
  for (const surface of surfaces) {
    const clipped = surface.line ? clipWorldSegment(surface.points[0]!, surface.points[surface.points.length - 1]!, state) : clipPolygon(surface.points, state, basis);
    if (!clipped || clipped.length < 2) continue;
    const points = clipped.map((point) => projectWithBasis(point, state, basis, width, height));
    if (points.some((point) => !point)) continue;
    const projected = points as ProjectedWorldPoint[];
    const shallowFocus = profile.scene === "macro" || profile.scene === "foreground" || profile.scene === "dialogue";
    const focusBlur = state.focus > 0 && shallowFocus ? Math.min(4, Math.abs(surface.depth - state.focus) * (profile.scene === "dialogue" ? 0.9 : 0.65)) : 0;
    const motionBlur = profile.id === "backlight-flare-clear-reveal" ? 0 : state.blur * 3;
    const illumination = profile.id === "motivated-light-reveal" ? videoCameraLocalLight(surface.center, state, progress) : 1;
    context.save();
    context.globalAlpha = surface.alpha ?? 1;
    context.filter = `blur(${(focusBlur + motionBlur) * height / 260}px)`;
    context.beginPath();
    context.moveTo(projected[0]!.x, projected[0]!.y);
    projected.slice(1).forEach((point) => context.lineTo(point.x, point.y));
    if (surface.line) {
      // Polyline joints also clip individually, preserving chest/direction details near the lens.
      if (surface.points.length > 2) {
        context.beginPath();
        for (let index = 1; index < surface.points.length; index += 1) {
          const segment = clipWorldSegment(surface.points[index - 1]!, surface.points[index]!, state);
          if (!segment) continue;
          const a = projectWithBasis(segment[0], state, basis, width, height);
          const b = projectWithBasis(segment[1], state, basis, width, height);
          if (a && b) { context.moveTo(a.x, a.y); context.lineTo(b.x, b.y); }
        }
      }
      context.strokeStyle = surface.color;
      context.lineWidth = surface.width ?? 1;
      context.globalAlpha *= illumination;
      context.stroke();
    } else {
      context.closePath();
      context.fillStyle = surface.color;
      context.fill();
      if (surface.shade) {
        context.globalAlpha = (surface.alpha ?? 1) * surface.shade;
        context.fillStyle = palette.background;
        context.fill();
      }
      if (surface.stroke) {
        context.globalAlpha = (surface.alpha ?? 1) * 0.5;
        context.strokeStyle = surface.stroke;
        context.lineWidth = 0.6;
        context.stroke();
      }
      if (illumination < 1) {
        context.globalAlpha = (surface.alpha ?? 1) * (1 - illumination);
        context.fillStyle = palette.background;
        context.fill();
      }
    }
    context.restore();
  }
  if (profile.id === "backlight-flare-clear-reveal" && state.blur > 0) {
    const glow = context.createRadialGradient(width * 0.3, height * 0.2, 1, width * 0.3, height * 0.2, width * 0.85);
    glow.addColorStop(0, palette.accentLight);
    glow.addColorStop(1, palette.background);
    context.fillStyle = glow; context.globalAlpha = state.blur * 1.3;
    context.fillRect(0, 0, width, height); context.globalAlpha = 1;
  }
  // These physical obstruction/light cues complement camera poses in reveal profiles.
  if (state.occluder > 0) {
    const bottomObstacle = profile.id === "vertical-foreground-reveal";
    const coverWidth = width * clamp(state.occluder, 0, 1);
    const top = height * (1 - clamp(state.occluder, 0, 1));
    context.fillStyle = palette.muted;
    if (bottomObstacle) context.fillRect(0, top, width, height - top);
    else context.fillRect(0, 0, coverWidth, height);
    context.strokeStyle = palette.border;
    context.lineWidth = 1.5;
    context.beginPath();
    if (bottomObstacle) { context.moveTo(0, top); context.lineTo(width, top); }
    else { context.moveTo(coverWidth, 0); context.lineTo(coverWidth, height); }
    context.stroke();
    context.globalAlpha = 0.22;
    context.strokeStyle = palette.background;
    for (let x = 12; x < (bottomObstacle ? width : coverWidth); x += 15) {
      context.beginPath(); context.moveTo(x, bottomObstacle ? top : 0); context.lineTo(x, height); context.stroke();
    }
    context.globalAlpha = 1;
  }
  if (state.light < 1 && profile.id !== "motivated-light-reveal") {
    context.fillStyle = palette.background;
    context.globalAlpha = 1 - clamp(state.light, 0.04, 1);
    context.fillRect(0, 0, width, height);
    context.globalAlpha = 1;
  }
  const inset = Math.max(12, width * 0.035);
  const corner = Math.max(9, width * 0.026);
  context.strokeStyle = palette.accentLight;
  context.globalAlpha = 0.55;
  context.lineWidth = 1;
  for (const [x, y, directionX, directionY] of [[inset, inset, 1, 1], [width - inset, inset, -1, 1], [inset, height - inset, 1, -1], [width - inset, height - inset, -1, -1]]) {
    context.beginPath(); context.moveTo(x!, y! + corner * directionY!); context.lineTo(x!, y!); context.lineTo(x! + corner * directionX!, y!); context.stroke();
  }
  context.globalAlpha = 1;
}

function cameraGlyph(context: CanvasRenderingContext2D, x: number, y: number, angle: number, color: string, scale = 1) {
  context.save(); context.translate(x, y); context.rotate(angle); context.scale(scale, scale);
  context.fillStyle = color;
  context.beginPath(); context.roundRect(-5, -4, 10, 8, 2); context.fill();
  context.beginPath(); context.moveTo(5, -2); context.lineTo(9, -5); context.lineTo(9, 5); context.lineTo(5, 2); context.closePath(); context.fill();
  context.restore();
}

const planRoutes = new WeakMap<VideoCameraMotionProfile, readonly VideoCameraMotionState[]>();

function planRoute(profile: VideoCameraMotionProfile) {
  const cached = planRoutes.get(profile);
  if (cached) return cached;
  const route = Array.from({ length: 65 }, (_, index) => sampleVideoCameraMotion(profile, index / 64));
  planRoutes.set(profile, route);
  return route;
}

function drawPlan(context: CanvasRenderingContext2D, width: number, height: number, state: VideoCameraMotionState, profile: VideoCameraMotionProfile, palette: VideoCameraPreviewPalette) {
  const keyframes = profile.keyframes;
  const route = planRoute(profile);
  const positions = route.map((frame) => frame.position);
  const subjects = route.map((frame) => frame.subject);
  // Scale from the entire route, never from the current frame; the stage cannot "breathe".
  const anchors = [...positions, ...subjects];
  const minX = Math.min(-2.2, ...anchors.map((point) => point[0])) - 1;
  const maxX = Math.max(2.2, ...anchors.map((point) => point[0])) + 1;
  const minZ = Math.min(-2, ...anchors.map((point) => point[2])) - 0.8;
  const maxZ = Math.max(2, ...anchors.map((point) => point[2])) + 1;
  const padding = 17;
  const barWidth = 28;
  const scale = Math.min((width - padding * 2 - barWidth) / (maxX - minX), (height - padding * 2) / (maxZ - minZ));
  const offsetX = (width - barWidth) / 2 - (minX + maxX) / 2 * scale;
  const offsetY = height / 2 - (minZ + maxZ) / 2 * scale;
  const point = (position: Vector) => [offsetX + position[0] * scale, offsetY + position[2] * scale] as const;
  context.fillStyle = palette.background; context.fillRect(0, 0, width, height);
  context.lineWidth = 0.6; context.strokeStyle = palette.grid; context.globalAlpha = 0.2;
  const spacing = Math.max(10, scale);
  for (let x = offsetX % spacing; x < width - barWidth; x += spacing) { context.beginPath(); context.moveTo(x, 0); context.lineTo(x, height); context.stroke(); }
  for (let y = offsetY % spacing; y < height; y += spacing) { context.beginPath(); context.moveTo(0, y); context.lineTo(width - barWidth, y); context.stroke(); }
  context.globalAlpha = 1;
  if (profile.scene === "slalom") {
    context.fillStyle = palette.secondary; context.strokeStyle = palette.border;
    for (const center of SLALOM_PILLARS) {
      const [x, y] = point(center);
      context.beginPath(); context.rect(x - 0.55 * scale, y - 0.55 * scale, 1.1 * scale, 1.1 * scale);
      context.fill(); context.stroke();
    }
  }
  // Subject movement is separate from the camera route: a traveling orbit has a moving center.
  const subjectStart = subjects[0]!;
  if (subjects.some((subject) => Math.hypot(subject[0] - subjectStart[0], subject[2] - subjectStart[2]) > 0.5)) {
    context.strokeStyle = palette.subject; context.lineWidth = 1; context.setLineDash([1, 3]); context.globalAlpha = 0.7;
    context.beginPath(); subjects.forEach((subject, index) => {
      const [x, y] = point(subject); if (index === 0) context.moveTo(x, y); else context.lineTo(x, y);
    }); context.stroke(); context.setLineDash([]); context.globalAlpha = 1;
  }
  const [subjectX, subjectY] = point(state.subject);
  context.fillStyle = palette.surface; context.strokeStyle = palette.border;
  context.beginPath(); context.ellipse(subjectX, subjectY, Math.max(9, scale * 0.7), Math.max(9, scale * 0.7), 0, 0, TAU); context.fill(); context.stroke();
  context.fillStyle = palette.subject;
  context.beginPath(); context.arc(subjectX, subjectY, 4.3, 0, TAU); context.fill();
  context.strokeStyle = palette.accentLight; context.lineWidth = 2;
  context.beginPath(); context.moveTo(subjectX - 3, subjectY + 7); context.lineTo(subjectX, subjectY + 10); context.lineTo(subjectX + 3, subjectY + 7); context.stroke();
  if (positions.length > 1) {
    context.strokeStyle = palette.accent; context.lineWidth = 1.5; context.setLineDash([3, 3]); context.globalAlpha = 0.65;
    context.beginPath(); positions.forEach((position, index) => { const [x, y] = point(position); if (index === 0) context.moveTo(x, y); else context.lineTo(x, y); }); context.stroke();
    context.setLineDash([]); context.globalAlpha = 1;
  }
  const start = keyframes[0]!;
  const end = keyframes[keyframes.length - 1]!;
  for (const frame of [start, end]) {
    const [x, y] = point(frame.position);
    const direction = subtract(frame.target, frame.position);
    context.globalAlpha = 0.3;
    cameraGlyph(context, x, y, Math.atan2(direction[2], direction[0]), palette.muted, 0.7);
  }
  context.globalAlpha = 1;
  const [x, y] = point(state.position);
  const direction = subtract(state.target, state.position);
  const angle = Math.atan2(direction[2], direction[0]);
  const wedgeLength = Math.max(18, Math.min(60, Math.hypot(subjectX - x, subjectY - y)));
  const halfAngle = videoCameraHorizontalFov(state.fov) * Math.PI / 360;
  context.fillStyle = palette.accent; context.globalAlpha = 0.12;
  context.beginPath(); context.moveTo(x, y); context.arc(x, y, wedgeLength, angle - halfAngle, angle + halfAngle); context.closePath(); context.fill();
  context.globalAlpha = 0.45; context.strokeStyle = palette.accent; context.lineWidth = 0.8;
  context.beginPath(); context.moveTo(x + Math.cos(angle - halfAngle) * wedgeLength, y + Math.sin(angle - halfAngle) * wedgeLength); context.lineTo(x, y); context.lineTo(x + Math.cos(angle + halfAngle) * wedgeLength, y + Math.sin(angle + halfAngle) * wedgeLength); context.stroke();
  context.globalAlpha = 1;
  cameraGlyph(context, x, y, angle, palette.accent, 0.9);
  const maxHeight = Math.max(4, ...positions.map((position) => position[1]));
  const heightX = width - 14;
  const heightTop = 18;
  const heightBottom = height - 18;
  context.strokeStyle = palette.border; context.lineWidth = 2;
  context.beginPath(); context.moveTo(heightX, heightTop); context.lineTo(heightX, heightBottom); context.stroke();
  context.strokeStyle = palette.muted; context.lineWidth = 1;
  for (const tick of [0, 0.5, 1]) {
    const heightY = heightBottom - tick * (heightBottom - heightTop);
    context.beginPath(); context.moveTo(heightX - 3, heightY); context.lineTo(heightX + 3, heightY); context.stroke();
  }
  const cameraHeight = heightBottom - clamp(state.position[1] / maxHeight, 0, 1) * (heightBottom - heightTop);
  context.strokeStyle = palette.accent; context.lineWidth = 3;
  context.beginPath(); context.moveTo(heightX, heightBottom); context.lineTo(heightX, cameraHeight); context.stroke();
  context.fillStyle = palette.accent; context.beginPath(); context.arc(heightX, cameraHeight, 3.5, 0, TAU); context.fill();
}

/** Canvas dimensions are CSS pixels; the player owns device-pixel scaling and scheduling. */
export function drawVideoCameraPreview(context: CanvasRenderingContext2D, width: number, height: number, state: VideoCameraMotionState, profile: VideoCameraMotionProfile, progress: number, palette: VideoCameraPreviewPalette, view: "frame" | "plan") {
  if (width <= 0 || height <= 0 || !Number.isFinite(width + height)) return;
  context.save();
  context.beginPath(); context.rect(0, 0, width, height); context.clip();
  context.lineJoin = "round"; context.lineCap = "round";
  if (view === "plan") drawPlan(context, width, height, state, profile, palette);
  else drawFrame(context, width, height, state, profile, clamp(progress, 0, 1), palette);
  context.restore();
}
