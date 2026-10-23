import type { DirectorObject, DirectorVector } from "./directorScene";

export type DirectorMoveAxis = "x" | "y" | "z" | "xz";
const clampPosition = (value: number) => Math.max(-20, Math.min(20, value));

/** Translation uses world axes; body dragging keeps the original elevation. */
export function moveDirectorObject(original: DirectorObject, delta: DirectorVector, axis: DirectorMoveAxis): DirectorObject {
  if (![delta.x, delta.y, delta.z].every(Number.isFinite)) return original;
  return { ...original, position: {
    x: axis === "x" || axis === "xz" ? clampPosition(original.position.x + delta.x) : original.position.x,
    y: axis === "y" ? clampPosition(original.position.y + delta.y) : original.position.y,
    z: axis === "z" || axis === "xz" ? clampPosition(original.position.z + delta.z) : original.position.z,
  } };
}

export function wrapDirectorRotation(degrees: number): number {
  return ((degrees + 180) % 360 + 360) % 360 - 180;
}

/** Rotation remains the saved local Y heading, with no change to position or dimensions. */
export function rotateDirectorObject(original: DirectorObject, deltaDegrees: number): DirectorObject {
  if (!Number.isFinite(deltaDegrees)) return original;
  return { ...original, rotation: wrapDirectorRotation(original.rotation + deltaDegrees) };
}
