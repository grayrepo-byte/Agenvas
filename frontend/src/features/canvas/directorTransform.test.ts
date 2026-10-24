import { describe, expect, it } from "vitest";
import { moveDirectorObject, rotateDirectorObject, wrapDirectorRotation } from "./directorTransform";
import type { DirectorObject } from "./directorScene";

const original: DirectorObject = { id: "box", name: "Box", kind: "BOX", position: { x: 2, y: 3, z: -1 },
  scale: { x: 0.7, y: 1.2, z: 2 }, rotation: 45, color: "#D9A35F" };

describe("director world translation", () => {
  it("moves the body along XZ without changing height, heading or dimensions", () => {
    const moved = moveDirectorObject(original, { x: 3, y: 100, z: -2 }, "xz");
    expect(moved).toEqual({ ...original, position: { x: 5, y: 3, z: -3 } });
    expect(original.position).toEqual({ x: 2, y: 3, z: -1 });
  });
  it.each(["x", "y", "z"] as const)("moves only the world %s axis even if the object is rotated", (axis) => {
    const moved = moveDirectorObject(original, { x: 2, y: 2, z: 2 }, axis);
    expect(moved.position).toEqual({ ...original.position, [axis]: original.position[axis] + 2 });
    expect(moved.rotation).toBe(45);
  });
  it("caps at the project position limits", () => {
    expect(moveDirectorObject(original, { x: 1000, y: 0, z: -1000 }, "xz").position).toEqual({ x: 20, y: 3, z: -20 });
    expect(moveDirectorObject(original, { x: 0, y: -1000, z: 0 }, "y").position.y).toBe(-20);
  });
  it("rejects nonfinite pointer math", () => {
    expect(moveDirectorObject(original, { x: NaN, y: 0, z: 0 }, "x")).toBe(original);
    expect(moveDirectorObject(original, { x: 0, y: Infinity, z: 0 }, "y")).toBe(original);
  });
});

describe("director Y rotation", () => {
  it.each([[180, -180], [-181, 179], [540, -180], [725, 5], [-725, -5]])("wraps %s to %s", (degrees, expected) => {
    expect(wrapDirectorRotation(degrees)).toBe(expected);
  });
  it("preserves position and dimensions while changing the heading", () => {
    expect(rotateDirectorObject(original, 170)).toEqual({ ...original, rotation: -145 });
    expect(original.rotation).toBe(45);
  });
  it("rejects nonfinite angle math", () => {
    expect(rotateDirectorObject(original, Infinity)).toBe(original);
    expect(rotateDirectorObject(original, NaN)).toBe(original);
  });
});
