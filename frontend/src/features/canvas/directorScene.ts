import type { RunImageOperationRequest } from "../../shared/api/client";

export const DIRECTOR_OBJECT_KINDS = ["ACTOR", "BOX", "SPHERE", "CYLINDER"] as const;
export const DIRECTOR_ASPECT_RATIOS = ["16:9", "9:16", "1:1", "4:3"] as const;
export const DIRECTOR_SCENE_SCHEMA_VERSION = 2;
export const DIRECTOR_SCALE_LIMITS = { min: 0.1, max: 5 } as const;

export interface DirectorVector { x: number; y: number; z: number }
export interface DirectorObject {
  id: string;
  name: string;
  kind: typeof DIRECTOR_OBJECT_KINDS[number];
  position: DirectorVector;
  /** Rotation around the world Y axis, in degrees. Characters face +Z at zero. */
  rotation: number;
  /** Independent local X width, Y height and Z depth ratios; 1 preserves the original model. */
  scale: DirectorVector;
  color: string;
}
export interface DirectorScene {
  schemaVersion: typeof DIRECTOR_SCENE_SCHEMA_VERSION;
  aspectRatio: typeof DIRECTOR_ASPECT_RATIOS[number];
  camera: {
    /** Zero is +Z; positive azimuth moves the camera toward +X. */
    azimuth: number;
    elevation: number;
    distance: number;
    target: DirectorVector;
    focalLength: number;
  };
  objects: DirectorObject[];
}

export const DIRECTOR_CAPTURE_SIZES: Record<DirectorScene["aspectRatio"], { width: number; height: number }> = {
  "16:9": { width: 1600, height: 900 },
  "9:16": { width: 900, height: 1600 },
  "1:1": { width: 1600, height: 1600 },
  "4:3": { width: 1600, height: 1200 },
};

/** An original local blocking scene, with no model download or provider call. */
export function createDirectorScene(): DirectorScene {
  return {
    schemaVersion: DIRECTOR_SCENE_SCHEMA_VERSION,
    aspectRatio: "16:9",
    camera: { azimuth: 25, elevation: 12, distance: 7, target: { x: 0, y: 0.9, z: 0 }, focalLength: 35 },
    objects: [{ id: crypto.randomUUID(), name: "Actor 1", kind: "ACTOR", position: { x: 0, y: 0, z: 0 },
      rotation: 0, scale: { x: 1, y: 1, z: 1 }, color: "#D9A35F" }],
  };
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function finite(value: unknown, min: number, max: number): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= min && value <= max;
}
function keys(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  return Object.keys(value).every((key) => allowed.includes(key));
}
function vector(value: unknown): value is DirectorVector {
  return record(value) && keys(value, ["x", "y", "z"])
    && finite(value.x, -20, 20) && finite(value.y, -20, 20) && finite(value.z, -20, 20);
}
type LegacyDirectorObject = Omit<DirectorObject, "scale"> & { scale: number };
function scaleVector(value: unknown): value is DirectorVector {
  return record(value) && keys(value, ["x", "y", "z"])
    && finite(value.x, DIRECTOR_SCALE_LIMITS.min, DIRECTOR_SCALE_LIMITS.max)
    && finite(value.y, DIRECTOR_SCALE_LIMITS.min, DIRECTOR_SCALE_LIMITS.max)
    && finite(value.z, DIRECTOR_SCALE_LIMITS.min, DIRECTOR_SCALE_LIMITS.max);
}
function object(value: unknown, version: 1 | 2): value is DirectorObject | LegacyDirectorObject {
  return record(value) && keys(value, ["id", "name", "kind", "position", "rotation", "scale", "color"])
    && typeof value.id === "string" && value.id.trim().length > 0 && value.id.length <= 64
    && typeof value.name === "string" && value.name.trim().length > 0 && value.name.length <= 80
    && DIRECTOR_OBJECT_KINDS.some((kind) => kind === value.kind) && vector(value.position)
    && finite(value.rotation, -180, 180)
    && (version === 1 ? finite(value.scale, DIRECTOR_SCALE_LIMITS.min, DIRECTOR_SCALE_LIMITS.max) : scaleVector(value.scale))
    && typeof value.color === "string" && /^#[\da-f]{6}$/i.test(value.color);
}

/** Validate before allocating geometry. Upgrade a detached v1 draft without rewriting archived scene data. */
export function parseDirectorScene(value: unknown): DirectorScene | null {
  if (!record(value) || !keys(value, ["schemaVersion", "aspectRatio", "camera", "objects"])
    || (value.schemaVersion !== 1 && value.schemaVersion !== DIRECTOR_SCENE_SCHEMA_VERSION)
    || !DIRECTOR_ASPECT_RATIOS.some((ratio) => ratio === value.aspectRatio)
    || !record(value.camera) || !Array.isArray(value.objects) || value.objects.length > 32) return null;
  const version = value.schemaVersion;
  const camera = value.camera;
  if (!keys(camera, ["azimuth", "elevation", "distance", "target", "focalLength"])
    || !finite(camera.azimuth, -180, 180) || !finite(camera.elevation, -80, 80)
    || !finite(camera.distance, 1, 50) || !vector(camera.target) || !finite(camera.focalLength, 18, 120)
    || !value.objects.every((item): item is DirectorObject | LegacyDirectorObject => object(item, version))
    || new Set(value.objects.map((item) => item.id)).size !== value.objects.length) return null;
  const aspectRatio = DIRECTOR_ASPECT_RATIOS.find((ratio) => ratio === value.aspectRatio);
  if (!aspectRatio) return null;
  return {
    schemaVersion: DIRECTOR_SCENE_SCHEMA_VERSION,
    aspectRatio,
    camera: { azimuth: camera.azimuth, elevation: camera.elevation, distance: camera.distance,
      target: { x: camera.target.x, y: camera.target.y, z: camera.target.z }, focalLength: camera.focalLength },
    objects: value.objects.map((item) => ({ id: item.id, name: item.name, kind: item.kind,
      position: { ...item.position }, rotation: item.rotation,
      scale: typeof item.scale === "number" ? { x: item.scale, y: item.scale, z: item.scale } : { ...item.scale }, color: item.color })),
  };
}

/** Match the existing image operation's vocabulary to the actual +Z / +X camera orbit. */
export function directorViewAngle(scene: DirectorScene): NonNullable<RunImageOperationRequest["parameters"]["viewAngle"]> {
  const { azimuth, elevation } = scene.camera;
  if (elevation >= 35) return "HIGH_ANGLE";
  if (elevation <= -15) return "LOW_ANGLE";
  if (Math.abs(azimuth) >= 135) return "BACK";
  if (azimuth <= -65) return "LEFT_PROFILE";
  if (azimuth >= 65) return "RIGHT_PROFILE";
  if (azimuth <= -20) return "LEFT_THREE_QUARTER";
  if (azimuth >= 20) return "RIGHT_THREE_QUARTER";
  return "FRONT";
}

export function cameraPosition(camera: DirectorScene["camera"]): DirectorVector {
  const azimuth = camera.azimuth * Math.PI / 180;
  const elevation = camera.elevation * Math.PI / 180;
  const horizontalDistance = Math.cos(elevation) * camera.distance;
  return {
    x: camera.target.x + Math.sin(azimuth) * horizontalDistance,
    y: camera.target.y + Math.sin(elevation) * camera.distance,
    z: camera.target.z + Math.cos(azimuth) * horizontalDistance,
  };
}

/** Fit the composition inside the viewport; the bars are UI and never captured. */
export function directorFrameSize(width: number, height: number, aspectRatio: DirectorScene["aspectRatio"]): { width: number; height: number } {
  const size = DIRECTOR_CAPTURE_SIZES[aspectRatio];
  const scale = Math.max(0, Math.min(width / size.width, height / size.height));
  return { width: size.width * scale, height: size.height * scale };
}

/** Stable English camera guidance supplements the frame and never includes object names. */
export function directorCameraPrompt(scene: DirectorScene): string {
  const camera = scene.camera;
  const position = cameraPosition(camera);
  const point = (value: DirectorVector) => `(${value.x.toFixed(2)}, ${value.y.toFixed(2)}, ${value.z.toFixed(2)})`;
  return `Use the following camera direction and composition guidance. `
    + `Output aspect ratio: ${scene.aspectRatio}. Camera focal length: ${camera.focalLength.toFixed(1)} mm on a 35 mm film gauge. `
    + `Camera azimuth: ${camera.azimuth.toFixed(1)} degrees; elevation: ${camera.elevation.toFixed(1)} degrees; `
    + `distance to target: ${camera.distance.toFixed(2)} scene units. Camera position: ${point(position)}; look-at target: ${point(camera.target)}. `
    + `Coordinates use +Y up; zero azimuth is +Z and positive azimuth turns toward +X. `
    + `This is a direction reference. Preserve the intended subject identity and appearance from the source image.`;
}
