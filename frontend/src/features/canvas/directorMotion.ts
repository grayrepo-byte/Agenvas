import { cameraPosition, type DirectorMotion, type DirectorScene, type DirectorVector } from "./directorScene";

/** Orbit interpolation acts on actual 3D camera poses, never on a flattened source image. */
export function directorCameraAtTime(scene: DirectorScene, timeSeconds: number): DirectorScene["camera"] {
  const frames = scene.motion.keyframes;
  if (!frames.length) return structuredClone(scene.camera);
  const first = frames[0]!;
  if (timeSeconds <= first.timeSeconds) return structuredClone(first.camera);
  const nextIndex = frames.findIndex((frame) => frame.timeSeconds > timeSeconds);
  if (nextIndex < 0) return structuredClone(frames.at(-1)!.camera);
  const start = frames[nextIndex - 1]!, end = frames[nextIndex]!;
  if (end.transition === "CUT") return structuredClone(start.camera);
  const progress = (timeSeconds - start.timeSeconds) / (end.timeSeconds - start.timeSeconds);
  const weight = end.transition === "SMOOTH" ? progress * progress * (3 - 2 * progress) : progress;
  const mix = (a: number, b: number) => a + (b - a) * weight;
  // Choose the short orbit across ±180; intermediate keys can describe longer arcs/full circles.
  const angle = start.camera.azimuth + (((end.camera.azimuth - start.camera.azimuth + 540) % 360) - 180) * weight;
  return {
    azimuth: ((angle + 180) % 360 + 360) % 360 - 180,
    elevation: mix(start.camera.elevation, end.camera.elevation),
    distance: mix(start.camera.distance, end.camera.distance),
    focalLength: mix(start.camera.focalLength, end.camera.focalLength),
    target: { x: mix(start.camera.target.x, end.camera.target.x), y: mix(start.camera.target.y, end.camera.target.y),
      z: mix(start.camera.target.z, end.camera.target.z) },
  };
}

/** Preserve the timing proportions instead of dropping keys when shortening a take. */
export function resizeDirectorMotion(motion: DirectorMotion, durationSeconds: number): DirectorMotion {
  return { durationSeconds, keyframes: motion.keyframes.map((frame) => ({ ...frame,
    timeSeconds: frame.timeSeconds / motion.durationSeconds * durationSeconds })) };
}

/** Separate segments avoid showing a cut as a physical camera move. */
export function directorCameraPath(scene: DirectorScene): DirectorVector[][] {
  return scene.motion.keyframes.slice(1).flatMap((end, index) => {
    if (end.transition === "CUT") return [];
    const start = scene.motion.keyframes[index]!;
    return [Array.from({ length: 33 }, (_, sample) => cameraPosition(directorCameraAtTime(scene,
      start.timeSeconds + (end.timeSeconds - start.timeSeconds) * sample / 32)))];
  });
}
