import { describe, expect, it } from "vitest";
import { createDirectorScene, parseDirectorScene, type DirectorScene } from "./directorScene";
import { directorCameraAtTime, directorCameraPath, resizeDirectorMotion } from "./directorMotion";

function movingScene(): DirectorScene {
  const scene = createDirectorScene();
  scene.motion = { durationSeconds: 10, keyframes: [
    { id: "start", name: "Front", timeSeconds: 0, transition: "LINEAR", camera: { ...scene.camera, azimuth: 170, distance: 10 } },
    { id: "end", name: "Back", timeSeconds: 10, transition: "LINEAR", camera: { ...scene.camera, azimuth: -170, distance: 4, focalLength: 85, target: { x: 4, y: 2, z: -2 } } },
  ] };
  return scene;
}

describe("local 3D camera motion", () => {
  it("roundtrips named timed cameras and samples the shortest orbit without changing the saved scene", () => {
    const scene = movingScene();
    expect(parseDirectorScene(JSON.parse(JSON.stringify(scene)))).toEqual(scene);
    const saved = structuredClone(scene);
    const middle = directorCameraAtTime(scene, 5);
    expect(Math.abs(middle.azimuth)).toBe(180);
    expect(middle.distance).toBe(7);
    expect(middle.focalLength).toBe(60);
    expect(middle.target).toMatchObject({ x: 2, z: -1 });
    expect(middle.target.y).toBeCloseTo(1.45);
    expect(scene).toEqual(saved);
    middle.target.x = 19;
    expect(scene.motion.keyframes[0]!.camera.target.x).toBe(0);
  });

  it("holds endpoint poses, eases smoothly, and cuts at the destination time", () => {
    const scene = movingScene();
    expect(directorCameraAtTime(scene, -5)).toEqual(scene.motion.keyframes[0]!.camera);
    expect(directorCameraAtTime(scene, 99)).toEqual(scene.motion.keyframes[1]!.camera);
    scene.motion.keyframes[1]!.transition = "SMOOTH";
    expect(directorCameraAtTime(scene, 2.5).distance).toBeCloseTo(9.0625);
    scene.motion.keyframes[1]!.transition = "CUT";
    expect(directorCameraAtTime(scene, 9.999)).toEqual(scene.motion.keyframes[0]!.camera);
    expect(directorCameraAtTime(scene, 10)).toEqual(scene.motion.keyframes[1]!.camera);
  });

  it("rescales all keyframe times when changing duration and falls back to the editable camera for an empty track", () => {
    const scene = movingScene();
    const motion = resizeDirectorMotion(scene.motion, 4);
    expect(motion.durationSeconds).toBe(4);
    expect(motion.keyframes.map((frame) => frame.timeSeconds)).toEqual([0, 4]);
    expect(scene.motion.durationSeconds).toBe(10);
    scene.motion.keyframes = [];
    expect(directorCameraAtTime(scene, 1)).toEqual(scene.camera);
  });
  it("does not draw camera movement across a cut", () => {
    const scene = movingScene();
    expect(directorCameraPath(scene)[0]).toHaveLength(33);
    scene.motion.keyframes[1]!.transition = "CUT";
    expect(directorCameraPath(scene)).toEqual([]);
  });

  it("upgrades v2 into an empty motion track without modifying its source", () => {
    const old: Partial<DirectorScene> = createDirectorScene(); delete old.motion;
    const legacy = { ...old, schemaVersion: 2 };
    const original = structuredClone(legacy);
    expect(parseDirectorScene(legacy)?.motion).toEqual({ durationSeconds: 5, keyframes: [] });
    expect(legacy).toEqual(original);
  });

  it("rejects unknown fields, duplicate or unordered times, camera URLs, and unbounded tracks before rendering", () => {
    const scene = movingScene();
    const frame = scene.motion.keyframes[0]!;
    for (const motion of [
      { ...scene.motion, unknown: true }, { ...scene.motion, durationSeconds: 0 }, { ...scene.motion, durationSeconds: 121 },
      { ...scene.motion, keyframes: [frame, frame] },
      { ...scene.motion, keyframes: [...scene.motion.keyframes].reverse() },
      { ...scene.motion, keyframes: [{ ...frame, timeSeconds: NaN }] },
      { ...scene.motion, keyframes: [{ ...frame, timeSeconds: 10.1 }] },
      { ...scene.motion, keyframes: [{ ...frame, name: " " }] },
      { ...scene.motion, keyframes: [{ ...frame, camera: { ...frame.camera, url: "https://example.invalid/model" } }] },
      { ...scene.motion, keyframes: [{ ...frame, transition: "SCRIPT" }] },
      { ...scene.motion, keyframes: Array.from({ length: 25 }, (_, i) => ({ ...frame, id: String(i), timeSeconds: i / 10 })) },
    ]) expect(parseDirectorScene({ ...scene, motion })).toBeNull();
    const parsed = parseDirectorScene(scene)!;
    parsed.motion.keyframes[0]!.camera.target.x = 15;
    expect(scene.motion.keyframes[0]!.camera.target.x).toBe(0);
  });

});
