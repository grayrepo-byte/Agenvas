import { DIRECTOR_SCALE_LIMITS, type DirectorObject, type DirectorVector } from "./directorScene";

export const DIRECTOR_RESIZE_AXES = ["x", "y", "z"] as const;
export interface DirectorLocalBounds { min: DirectorVector; max: DirectorVector }
/** Nonzero signs identify the dragged face, edge or corner in the object's local axes. */
export type DirectorResizeHandle = DirectorVector;
const clampScale = (value: number) => Math.max(DIRECTOR_SCALE_LIMITS.min, Math.min(DIRECTOR_SCALE_LIMITS.max, value));
const clampPosition = (value: number) => Math.max(-20, Math.min(20, value));

function rotated(vector: DirectorVector, degrees: number): DirectorVector {
  const angle = degrees * Math.PI / 180;
  return { x: Math.cos(angle) * vector.x + Math.sin(angle) * vector.z,
    y: vector.y, z: -Math.sin(angle) * vector.x + Math.cos(angle) * vector.z };
}

/** Resize in local axes while anchoring the opposite face/edge/corner in world space. */
export function resizeDirectorObject(original: DirectorObject, bounds: DirectorLocalBounds,
  handle: DirectorResizeHandle, worldDelta: DirectorVector): DirectorObject {
  if (!DIRECTOR_RESIZE_AXES.every((axis) => Number.isFinite(worldDelta[axis]))) return original;
  const delta = rotated(worldDelta, -original.rotation);
  const scale = { ...original.scale };
  const anchor = { x: 0, y: 0, z: 0 };
  for (const axis of DIRECTOR_RESIZE_AXES) {
    const size = bounds.max[axis] - bounds.min[axis];
    const sign = Math.sign(handle[axis]);
    anchor[axis] = sign > 0 ? bounds.min[axis] : sign < 0 ? bounds.max[axis] : (bounds.min[axis] + bounds.max[axis]) / 2;
    if (sign && size > 0.000001) scale[axis] = Math.max(DIRECTOR_SCALE_LIMITS.min,
      Math.min(DIRECTOR_SCALE_LIMITS.max, original.scale[axis] + sign * delta[axis] / size));
  }
  const positionDelta = rotated({ x: (original.scale.x - scale.x) * anchor.x,
    y: (original.scale.y - scale.y) * anchor.y, z: (original.scale.z - scale.z) * anchor.z }, original.rotation);
  // Constrain the whole gesture together so the fixed anchor is preserved at project position limits.
  let fraction = 1;
  for (const axis of DIRECTOR_RESIZE_AXES) {
    if (positionDelta[axis] > 0) fraction = Math.min(fraction, (20 - original.position[axis]) / positionDelta[axis]);
    if (positionDelta[axis] < 0) fraction = Math.min(fraction, (-20 - original.position[axis]) / positionDelta[axis]);
  }
  fraction = Math.max(0, Math.min(1, fraction));
  return { ...original, position: {
    x: clampPosition(original.position.x + positionDelta.x * fraction),
    y: clampPosition(original.position.y + positionDelta.y * fraction),
    z: clampPosition(original.position.z + positionDelta.z * fraction),
  }, scale: {
    x: clampScale(original.scale.x + (scale.x - original.scale.x) * fraction),
    y: clampScale(original.scale.y + (scale.y - original.scale.y) * fraction),
    z: clampScale(original.scale.z + (scale.z - original.scale.z) * fraction),
  } };
}
