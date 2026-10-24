import { describe, expect, it } from "vitest";
import { resizeDirectorObject } from "./directorResize";
import type { DirectorObject } from "./directorScene";

const bounds = { min: { x: -0.5, y: 0, z: -0.5 }, max: { x: 0.5, y: 1, z: 0.5 } };
const original = (patch: Partial<DirectorObject> = {}): DirectorObject => ({ id: "box", name: "Box", kind: "BOX",
  position: { x: 0, y: 0, z: 0 }, rotation: 0, scale: { x: 1, y: 1, z: 1 }, color: "#D9A35F", ...patch });

describe("director local resizing", () => {
  it("changes only a face's axis while preserving the opposite face", () => {
    const result = resizeDirectorObject(original(), bounds, { x: 1, y: 0, z: 0 }, { x: 1, y: 4, z: 5 });
    expect(result.scale).toEqual({ x: 2, y: 1, z: 1 });
    expect(result.position).toEqual({ x: 0.5, y: 0, z: 0 });
    expect(result.position.x + bounds.min.x * result.scale.x).toBe(-0.5);
  });
  it("keeps the bottom fixed when increasing height from the top face", () => {
    const result = resizeDirectorObject(original(), bounds, { x: 0, y: 1, z: 0 }, { x: 0, y: 2, z: 0 });
    expect(result.scale).toEqual({ x: 1, y: 3, z: 1 });
    expect(result.position.y).toBe(0);
  });
  it("moves the bottom deliberately when dragging the bottom face, keeping the top fixed", () => {
    const result = resizeDirectorObject(original(), bounds, { x: 0, y: -1, z: 0 }, { x: 0, y: -1, z: 0 });
    expect(result.scale.y).toBe(2); expect(result.position.y).toBe(-1);
    expect(result.position.y + bounds.max.y * result.scale.y).toBe(1);
  });
  it("resizes an edge in two axes and a corner in all affected local axes", () => {
    expect(resizeDirectorObject(original(), bounds, { x: 1, y: 1, z: 0 }, { x: 1, y: 2, z: 3 }).scale).toEqual({ x: 2, y: 3, z: 1 });
    expect(resizeDirectorObject(original(), bounds, { x: 1, y: 1, z: -1 }, { x: 1, y: 2, z: -3 }).scale).toEqual({ x: 2, y: 3, z: 4 });
  });
  it("uses a rotated object's local axes, not world-aligned bounds", () => {
    const result = resizeDirectorObject(original({ rotation: 90 }), bounds, { x: 1, y: 0, z: 0 }, { x: 0, y: 0, z: -1 });
    expect(result.scale.x).toBeCloseTo(2); expect(result.scale.z).toBe(1);
    expect(result.position.x).toBeCloseTo(0); expect(result.position.z).toBeCloseTo(-0.5);
  });
  it("honors actual model bounds rather than treating a mannequin as a unit cube", () => {
    const actorBounds = { min: { x: -0.37, y: 0, z: -0.15 }, max: { x: 0.37, y: 1.8, z: 0.19 } };
    const result = resizeDirectorObject(original({ kind: "ACTOR" }), actorBounds, { x: 0, y: 1, z: 0 }, { x: 0, y: 1.8, z: 0 });
    expect(result.scale.y).toBeCloseTo(2); expect(result.position.y).toBe(0);
  });
  it("clamps scale without allowing inversion and keeps the opposite anchor stable", () => {
    const result = resizeDirectorObject(original(), bounds, { x: 1, y: 0, z: 0 }, { x: -100, y: 0, z: 0 });
    expect(result.scale.x).toBeCloseTo(0.1);
    expect(result.position.x + bounds.min.x * result.scale.x).toBeCloseTo(-0.5);
    expect(resizeDirectorObject(original(), bounds, { x: 1, y: 0, z: 0 }, { x: 100, y: 0, z: 0 }).scale.x).toBe(5);
  });
  it("limits resize at position boundaries while preserving anchoring and the gesture ratio", () => {
    const result = resizeDirectorObject(original({ position: { x: 19.9, y: 0, z: 0 } }), bounds,
      { x: 1, y: 1, z: 0 }, { x: 2, y: 2, z: 0 });
    expect(result.position.x).toBeCloseTo(20);
    expect(result.scale.x).toBeCloseTo(1.2); expect(result.scale.y).toBeCloseTo(1.2);
    expect(result.position.x + bounds.min.x * result.scale.x).toBeCloseTo(19.4);
  });
  it("does not mutate the scene or accept nonfinite pointer math", () => {
    const object = original(), snapshot = structuredClone(object);
    resizeDirectorObject(object, bounds, { x: 1, y: 1, z: 1 }, { x: 1, y: 1, z: 1 });
    expect(object).toEqual(snapshot);
    expect(resizeDirectorObject(object, bounds, { x: 1, y: 0, z: 0 }, { x: NaN, y: 0, z: 0 })).toBe(object);
  });
});
