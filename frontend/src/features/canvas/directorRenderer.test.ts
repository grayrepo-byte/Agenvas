import type { Object3D, PerspectiveCamera, Scene } from "three";
import { fireEvent, render, screen } from "@testing-library/react";
import { createElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "../../shared/ui/primitives/dialog";
import { createDirectorRenderer, type DirectorObjectTool, type DirectorRenderer, type DirectorViewMode } from "./directorRenderer";
import { cameraPosition, createDirectorScene, parseDirectorScene, type DirectorObject, type DirectorScene } from "./directorScene";

const state = vi.hoisted(() => ({
  renders: [] as { selected: boolean; center: number[]; size: number[]; color: string }[],
  views: [] as { camera: string; position: number[]; aspect: number; far: number; rigVisible: boolean; frustumVisible: boolean;
    resizeVisible: boolean; moveVisible: boolean; rotateVisible: boolean }[],
  renderCamera: null as PerspectiveCamera | null,
  world: null as Scene | null,
  frames: new Map<number, FrameRequestCallback>(),
  nextFrame: 0,
  contextLost: false,
}));

// Real Three.js geometry and bounds; only GPU submission is replaced in jsdom.
vi.mock("three", async (importOriginal) => {
  const actual = await importOriginal<typeof import("three")>();
  return { ...actual, WebGLRenderer: class {
    shadowMap = { enabled: false, type: 0 };
    debug = { onShaderError: null as (() => void) | null };
    toneMapping = 0;
    toneMappingExposure = 1;
    getContext() { return { isContextLost: () => state.contextLost }; }
    setPixelRatio() {}
    setSize() {}
    dispose() {}
    forceContextLoss() { state.contextLost = true; }
    render(world: Scene, camera: PerspectiveCamera) {
      state.world = world;
      state.renderCamera = camera;
      world.updateMatrixWorld(true);
      const helper = world.getObjectByName("director-selection");
      if (!(helper instanceof actual.LineSegments) || !(helper.material instanceof actual.LineBasicMaterial)) {
        throw new Error("Expected an editor selection helper");
      }
      state.renders.push({ selected: helper.visible, center: helper.position.toArray(), size: helper.scale.toArray(),
        color: helper.material.color.getHexString() });
      state.views.push({ camera: camera.name, position: camera.position.toArray(), aspect: camera.aspect, far: camera.far,
        rigVisible: world.getObjectByName("director-camera-rig")?.visible ?? false,
        frustumVisible: world.getObjectByName("director-camera-frustum")?.visible ?? false,
        resizeVisible: world.getObjectByName("director-resize-handles")?.visible ?? false,
        moveVisible: world.getObjectByName("director-move-handles")?.visible ?? false,
        rotateVisible: world.getObjectByName("director-rotate-handles")?.visible ?? false });
    }
  } };
});

let renderer: DirectorRenderer | undefined;
function flushFrames() {
  for (const [id, callback] of [...state.frames]) {
    state.frames.delete(id);
    callback(0);
  }
}
function setup(options: { resizing?: boolean } = {}) {
  const canvas = document.createElement("canvas");
  canvas.tabIndex = 0;
  canvas.style.setProperty("--ui-accent", "#3d9aff");
  document.body.append(canvas);
  const scene: DirectorScene = { ...createDirectorScene(), objects: [{ id: "box", name: "Box", kind: "BOX",
    position: { x: 2, y: 0, z: 0 }, rotation: 0, scale: { x: 2, y: 2, z: 2 }, color: "#D9A35F" }] };
  const onCameraChange = vi.fn(), onSelectCamera = vi.fn(), onObjectChange = vi.fn(), onSelectObject = vi.fn();
  renderer = createDirectorRenderer(canvas, scene, { onCameraChange, onSelectCamera,
    onObjectChange: options.resizing === false ? undefined : onObjectChange, onSelectObject, onUnavailable: vi.fn() });
  return { renderer, scene, canvas, onCameraChange, onSelectCamera, onObjectChange, onSelectObject };
}
function pointer(canvas: HTMLCanvasElement, type: string, point: { x: number; y: number }) {
  const event = new MouseEvent(type, { clientX: point.x, clientY: point.y, button: 0, cancelable: true });
  Object.defineProperty(event, "pointerId", { value: 1 }); canvas.dispatchEvent(event);
}
function resizeView(renderer: DirectorRenderer, scene: DirectorScene, canvas: HTMLCanvasElement) {
  renderer.resize(900, 600); renderer.setViewMode("director"); renderer.setObjectTool("resize"); renderer.update(scene, "box"); flushFrames();
  vi.spyOn(canvas, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 900, 600));
}
function handlePoint(name: string) {
  const handle = state.world?.getObjectByName(name);
  if (!handle || !state.renderCamera) throw new Error("Missing resize handle");
  const point = handle.position.clone().project(state.renderCamera);
  return { x: (point.x + 1) * 450, y: (1 - point.y) * 300 };
}
function objectGroup() {
  let group: Object3D | undefined;
  state.world?.traverse((object) => { if (object.userData.directorObjectId === "box") group = object; });
  if (!group) throw new Error("Missing selected object");
  return group;
}
function transformView(renderer: DirectorRenderer, scene: DirectorScene, canvas: HTMLCanvasElement,
  tool: DirectorObjectTool = "move", mode: DirectorViewMode = "director", selected = true) {
  renderer.resize(900, 600); renderer.setViewMode(mode); renderer.setObjectTool(tool);
  renderer.update(scene, selected ? "box" : undefined); flushFrames();
  vi.spyOn(canvas, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 900, 600));
}
function projectedPoint(point: import("three").Vector3) {
  if (!state.renderCamera) throw new Error("Missing active camera");
  const screen = point.clone().project(state.renderCamera);
  return { x: (screen.x + 1) * 450, y: (1 - screen.y) * 300 };
}
function bodyPoint(height = 0.5) {
  const group = objectGroup();
  return projectedPoint(group.localToWorld(group.position.clone().set(0, height, 0)));
}
function gizmoPoint(name: string) {
  const object = state.world?.getObjectByName(name);
  if (!object) throw new Error(`Missing gizmo ${name}`);
  return projectedPoint(object.getWorldPosition(object.position.clone()));
}
function mockCaptureContext(drawImage = vi.fn()) {
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({ drawImage } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation((callback) => callback(new Blob(["synthetic encoding"], { type: "image/png" })));
}

beforeEach(() => {
  state.renders = []; state.views = []; state.world = null; state.renderCamera = null; state.frames.clear(); state.contextLost = false;
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
    const id = ++state.nextFrame; state.frames.set(id, callback); return id;
  });
  vi.stubGlobal("cancelAnimationFrame", (id: number) => state.frames.delete(id));
});

describe("director observation and shooting cameras", () => {
  it("uses independent observer orbit, pan, zoom and keyboard controls without changing the shooting scene", () => {
    const { renderer, scene, canvas, onCameraChange } = setup();
    const original = structuredClone(scene);
    renderer.resize(900, 600);
    renderer.setViewMode("director"); flushFrames();
    expect(state.views.at(-1)).toMatchObject({ camera: "director-observer-camera", aspect: 1.5, rigVisible: true, frustumVisible: true });
    const initialObserver = state.views.at(-1)?.position;
    const pointer = (type: string, x: number, y: number, pan = false) => {
      const event = new MouseEvent(type, { clientX: x, clientY: y, button: 0, shiftKey: pan, cancelable: true });
      Object.defineProperty(event, "pointerId", { value: 1 }); canvas.dispatchEvent(event);
    };
    pointer("pointerdown", 20, 20); pointer("pointermove", 100, 50); pointer("pointerup", 100, 50);
    pointer("pointerdown", 20, 20, true); pointer("pointermove", 70, 60, true); pointer("pointerup", 70, 60, true);
    canvas.dispatchEvent(new WheelEvent("wheel", { deltaY: 100, cancelable: true }));
    canvas.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", cancelable: true }));
    flushFrames();
    const movedObserver = state.views.at(-1)?.position;
    expect(movedObserver).not.toEqual(initialObserver);
    expect(onCameraChange).not.toHaveBeenCalled();
    expect(scene).toEqual(original);
    renderer.setViewMode("camera"); flushFrames();
    const expected = cameraPosition(original.camera);
    expect(state.views.at(-1)).toMatchObject({ camera: "director-shoot-camera", rigVisible: false, frustumVisible: false,
      position: [expect.closeTo(expected.x), expect.closeTo(expected.y), expect.closeTo(expected.z)] });
    renderer.setViewMode("director"); flushFrames();
    expect(state.views.at(-1)?.position).toEqual(movedObserver);
    renderer.setViewMode("camera");
    canvas.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", cancelable: true }));
    expect(onCameraChange).toHaveBeenCalledWith({ ...original.camera, azimuth: original.camera.azimuth + 5 });
  });

  it("keeps the physical camera and target-plane frustum aligned with saved projection parameters", () => {
    const { renderer, scene } = setup();
    const changed: DirectorScene = { ...scene, aspectRatio: "9:16", camera: { ...scene.camera, azimuth: -70, elevation: 24, distance: 12, focalLength: 85 } };
    renderer.setViewMode("director"); renderer.update(changed); flushFrames();
    const rig = state.world?.getObjectByName("director-camera-rig");
    const helper = state.world?.getObjectByName("director-camera-frustum");
    const expected = cameraPosition(changed.camera);
    expect(rig?.position.toArray()).toEqual([expect.closeTo(expected.x), expect.closeTo(expected.y), expect.closeTo(expected.z)]);
    if (!helper || !("camera" in helper)) throw new Error("Missing shooting frustum");
    const frustumCamera = helper.camera as PerspectiveCamera;
    expect(frustumCamera.aspect).toBeCloseTo(9 / 16);
    expect(frustumCamera.getFocalLength()).toBeCloseTo(85);
    expect(frustumCamera.near).toBe(0.05);
    expect(frustumCamera.far).toBe(12);
    expect(frustumCamera.quaternion.toArray()).toEqual(rig?.quaternion.toArray());
    const shadowCasters: string[] = [];
    rig?.traverse((object) => { if (object.castShadow || object.receiveShadow) shadowCasters.push(object.type); });
    expect(shadowCasters).toEqual([]);
  });

  it("always captures with the shooting camera and excludes rigs, frustum and selection before restoring observation", async () => {
    mockCaptureContext();
    const { renderer, scene } = setup();
    renderer.update(scene, "box"); renderer.setViewMode("director"); flushFrames();
    const observer = state.views.at(-1);
    await renderer.capture();
    const expected = cameraPosition(scene.camera);
    expect(state.views.at(-1)).toMatchObject({ camera: "director-shoot-camera", rigVisible: false, frustumVisible: false,
      aspect: 16 / 9, far: 250, position: [expect.closeTo(expected.x), expect.closeTo(expected.y), expect.closeTo(expected.z)] });
    expect(state.renders.at(-1)?.selected).toBe(false);
    flushFrames();
    expect(state.views.at(-1)).toEqual(observer);
    expect(state.renders.at(-1)?.selected).toBe(true);
  });

  it("restores director helpers after a capture copy fails", async () => {
    mockCaptureContext(vi.fn(() => { throw new Error("Synthetic copy failure"); }));
    const { renderer } = setup();
    renderer.setViewMode("director"); flushFrames();
    await expect(renderer.capture()).rejects.toThrow("Synthetic copy failure");
    flushFrames();
    expect(state.views.at(-1)).toMatchObject({ camera: "director-observer-camera", rigVisible: true, frustumVisible: true });
  });

  it("selects the physical camera through a real raycast in director mode", () => {
    const { renderer, canvas, onSelectCamera, onCameraChange } = setup();
    renderer.resize(900, 600); renderer.setViewMode("director"); flushFrames();
    vi.spyOn(canvas, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 900, 600));
    const rig = state.world?.getObjectByName("director-camera-rig");
    if (!rig || !state.renderCamera) throw new Error("Missing physical camera or observation view");
    const projected = rig.position.clone().project(state.renderCamera);
    const x = (projected.x + 1) * 450, y = (1 - projected.y) * 300;
    for (const type of ["pointerdown", "pointerup"]) {
      const event = new MouseEvent(type, { clientX: x, clientY: y, button: 0, cancelable: true });
      Object.defineProperty(event, "pointerId", { value: 1 }); canvas.dispatchEvent(event);
    }
    expect(onSelectCamera).toHaveBeenCalledOnce();
    expect(onCameraChange).not.toHaveBeenCalled();
  });

  it("releases camera body and frustum geometry and materials on close", () => {
    const { renderer } = setup();
    renderer.setViewMode("director"); flushFrames();
    const rig = state.world?.getObjectByName("director-camera-rig");
    const helper = state.world?.getObjectByName("director-camera-frustum");
    const geometries = new Set<import("three").BufferGeometry>();
    const materials = new Set<import("three").Material>();
    for (const root of [rig, helper]) root?.traverse((object) => {
      if ("geometry" in object) geometries.add(object.geometry as import("three").BufferGeometry);
      if ("material" in object) {
        const material = object.material as import("three").Material | import("three").Material[];
        for (const item of Array.isArray(material) ? material : [material]) materials.add(item);
      }
    });
    expect(geometries.size).toBeGreaterThan(1); expect(materials.size).toBeGreaterThan(1);
    const geometryDisposals = [...geometries].map((geometry) => vi.spyOn(geometry, "dispose"));
    const materialDisposals = [...materials].map((material) => vi.spyOn(material, "dispose"));
    renderer.dispose(); renderer.dispose();
    for (const disposed of [...geometryDisposals, ...materialDisposals]) expect(disposed).toHaveBeenCalledOnce();
  });
});
afterEach(() => {
  renderer?.dispose(); renderer = undefined;
  vi.unstubAllGlobals();
});

describe("director editor selection", () => {
  it("follows the selected object's world bounds and theme color without changing scene data", () => {
    const { renderer, scene } = setup();
    const original = structuredClone(scene);
    renderer.update(scene, "box"); flushFrames();
    expect(state.renders.at(-1)).toEqual({ selected: true,
      center: [expect.closeTo(2), expect.closeTo(1), expect.closeTo(0)],
      size: [expect.closeTo(2.06), expect.closeTo(2.06), expect.closeTo(2.06)], color: "3d9aff" });
    expect(scene).toEqual(original);
    renderer.update({ ...scene, objects: scene.objects.map((object) => ({ ...object, position: { x: 4, y: 0, z: 0 }, scale: { x: 3, y: 3, z: 3 } })) }, "box");
    flushFrames();
    expect(state.renders.at(-1)?.center).toEqual([expect.closeTo(4), expect.closeTo(1.5), expect.closeTo(0)]);
    expect(state.renders.at(-1)?.size[0]).toBeCloseTo(3.09);
  });

  it("clears selection when the object is removed or selection is unset", () => {
    const { renderer, scene } = setup();
    renderer.update(scene, "box"); flushFrames();
    expect(state.renders.at(-1)?.selected).toBe(true);
    renderer.update(scene); flushFrames();
    expect(state.renders.at(-1)?.selected).toBe(false);
    renderer.update({ ...scene, objects: [] }, "box"); flushFrames();
    expect(state.renders.at(-1)?.selected).toBe(false);
  });

  it("renders captured images without selection and restores selection afterward", async () => {
    mockCaptureContext();
    const { renderer, scene } = setup();
    renderer.update(scene, "box"); flushFrames();
    const file = await renderer.capture();
    expect(file.type).toBe("image/png");
    expect(state.renders.at(-1)?.selected).toBe(false);
    flushFrames();
    expect(state.renders.at(-1)?.selected).toBe(true);
  });

  it("restores selection even when copying the capture fails", async () => {
    mockCaptureContext(vi.fn(() => { throw new Error("Synthetic copy failure"); }));
    const { renderer, scene } = setup();
    renderer.update(scene, "box"); flushFrames();
    await expect(renderer.capture()).rejects.toThrow("Synthetic copy failure");
    flushFrames();
    expect(state.renders.at(-1)?.selected).toBe(true);
  });

  it("disposes selection resources exactly once and cancels queued work", () => {
    const { renderer, scene } = setup();
    renderer.update(scene, "box"); flushFrames();
    const helper = state.world?.getObjectByName("director-selection");
    if (!helper || !("geometry" in helper) || !("material" in helper)) throw new Error("Missing selection helper");
    const geometry = helper.geometry as import("three").BufferGeometry;
    const material = helper.material as import("three").LineBasicMaterial;
    const geometryDisposed = vi.spyOn(geometry, "dispose");
    const materialDisposed = vi.spyOn(material, "dispose");
    renderer.update(scene, "box");
    renderer.dispose(); renderer.dispose();
    expect(geometryDisposed).toHaveBeenCalledOnce();
    expect(materialDisposed).toHaveBeenCalledOnce();
    expect(state.frames.size).toBe(0);
  });
});

describe("director resize gestures", () => {
  it.each(["pointerup", "pointercancel", "lostpointercapture", "disabled", "view", "removed", "deselected", "failure", "dispose", "capture"])(
    "clears the dialog integration marker on %s", async (ending) => {
      const { renderer, scene, canvas } = setup();
      resizeView(renderer, scene, canvas);
      expect(canvas.dataset.directorResizing).toBeUndefined();
      const point = handlePoint("director-resize-0-1-0");
      pointer(canvas, "pointerdown", point);
      expect(canvas.dataset.directorResizing).toBe("true");
      pointer(canvas, "pointermove", { x: point.x, y: point.y - 35 });
      switch (ending) {
        case "disabled": renderer.setDisabled(true); break;
        case "view": renderer.setViewMode("camera"); break;
        case "removed": renderer.update({ ...scene, objects: [] }); break;
        case "deselected": renderer.update(scene); break;
        case "failure": canvas.dispatchEvent(new Event("webglcontextlost", { cancelable: true })); break;
        case "dispose": renderer.dispose(); break;
        case "capture": mockCaptureContext(); await renderer.capture(); break;
        default: pointer(canvas, ending, point);
      }
      expect(canvas.dataset.directorResizing).toBeUndefined();
      expect(canvas.style.cursor).toBe("");
    },
  );

  it("lets Escape cancel an active resize through a real modal even when its capture handler prevents default", () => {
    const close = vi.fn();
    render(createElement(Dialog, { open: true }, createElement(DialogContent, { onEscapeKeyDown: (event) => {
      event.preventDefault();
      if (event.target instanceof HTMLCanvasElement && event.target.dataset.directorResizing === "true") return;
      event.stopPropagation(); close();
    } }, createElement(DialogTitle, null, "Synthetic resize modal"), createElement(DialogDescription, null, "Native Escape event regression"))));
    const { renderer, scene, canvas, onObjectChange } = setup();
    screen.getByRole("dialog").append(canvas);
    resizeView(renderer, scene, canvas);
    const point = handlePoint("director-resize-0-1-0");
    pointer(canvas, "pointerdown", point); pointer(canvas, "pointermove", { x: point.x, y: point.y - 35 });
    expect(canvas.dataset.directorResizing).toBe("true");
    const escape = new KeyboardEvent("keydown", { key: "Escape", code: "Escape", bubbles: true, cancelable: true });
    canvas.dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    expect(canvas.dataset.directorResizing).toBeUndefined();
    expect(onObjectChange.mock.lastCall?.[1]).toEqual(scene.objects[0]);
    expect(close).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog").isConnected).toBe(true);
    fireEvent.keyDown(canvas, { key: "Escape", code: "Escape", cancelable: true });
    expect(close).toHaveBeenCalledOnce();
  });

  it("prioritizes a top-face handle, updates only height, fixes the bottom and leaves observer orbit untouched", () => {
    const { renderer, scene, canvas, onObjectChange, onCameraChange, onSelectObject } = setup();
    resizeView(renderer, scene, canvas);
    const observer = state.views.at(-1)?.position;
    const point = handlePoint("director-resize-0-1-0");
    pointer(canvas, "pointerdown", point);
    pointer(canvas, "pointermove", { x: point.x, y: point.y - 55 });
    const changed = onObjectChange.mock.lastCall?.[1] as DirectorObject;
    expect(changed.scale.y).toBeGreaterThan(2);
    expect(changed.scale.x).toBe(2); expect(changed.scale.z).toBe(2); expect(changed.position.y).toBe(0);
    expect(onSelectObject).toHaveBeenCalledWith("box"); expect(onCameraChange).not.toHaveBeenCalled();
    flushFrames(); expect(state.views.at(-1)?.position).toEqual(observer);
    pointer(canvas, "pointerup", { x: point.x, y: point.y - 55 });
    expect(parseDirectorScene({ ...scene, objects: [changed] })).not.toBeNull();
    expect(scene.objects[0]?.scale).toEqual({ x: 2, y: 2, z: 2 });
  });

  it("changes multiple local dimensions from a rotated corner with valid bounded output", () => {
    const { renderer, scene, canvas, onObjectChange, onCameraChange } = setup();
    scene.objects[0]!.rotation = 45;
    resizeView(renderer, scene, canvas);
    const point = handlePoint("director-resize-1-1-1");
    pointer(canvas, "pointerdown", point);
    pointer(canvas, "pointermove", { x: point.x + 65, y: point.y - 40 });
    const changed = onObjectChange.mock.lastCall?.[1] as DirectorObject;
    expect(Object.values(changed.scale).filter((value) => Math.abs(value - 2) > 0.01).length).toBeGreaterThan(1);
    expect(changed.rotation).toBe(45); expect(changed.position.y).toBe(0);
    expect(parseDirectorScene({ ...scene, objects: [changed] })).not.toBeNull();
    pointer(canvas, "pointermove", { x: point.x - 1000000, y: point.y + 1000000 });
    const bounded = onObjectChange.mock.lastCall?.[1] as DirectorObject;
    expect(parseDirectorScene({ ...scene, objects: [bounded] })).not.toBeNull();
    expect(onCameraChange).not.toHaveBeenCalled();
    pointer(canvas, "pointerup", { x: point.x - 1000000, y: point.y + 1000000 });
  });

  it.each(["pointercancel", "lostpointercapture"])("rolls back a canceled gesture on %s", (event) => {
    const { renderer, scene, canvas, onObjectChange } = setup();
    resizeView(renderer, scene, canvas);
    const point = handlePoint("director-resize-0-1-0");
    pointer(canvas, "pointerdown", point); pointer(canvas, "pointermove", { x: point.x, y: point.y - 50 });
    expect(onObjectChange).toHaveBeenCalled();
    pointer(canvas, event, point);
    expect(onObjectChange.mock.lastCall?.[1]).toEqual(scene.objects[0]);
    expect(canvas.style.cursor).toBe("");
  });

  it("disables resizing while busy and excludes handles from captures", async () => {
    mockCaptureContext();
    const { renderer, scene, canvas, onObjectChange } = setup();
    resizeView(renderer, scene, canvas);
    const point = handlePoint("director-resize-0-1-0");
    renderer.setDisabled(true); flushFrames();
    expect(state.views.at(-1)?.resizeVisible).toBe(false);
    pointer(canvas, "pointerdown", point); pointer(canvas, "pointermove", { x: point.x, y: point.y - 50 });
    expect(onObjectChange).not.toHaveBeenCalled();
    renderer.setDisabled(false); flushFrames();
    expect(state.views.at(-1)?.resizeVisible).toBe(true);
    await renderer.capture(); expect(state.views.at(-1)?.resizeVisible).toBe(false);
    flushFrames(); expect(state.views.at(-1)?.resizeVisible).toBe(true);
  });

  it("does not offer resize handles when no object callback is provided", () => {
    const { renderer, scene, canvas } = setup({ resizing: false });
    resizeView(renderer, scene, canvas);
    expect(state.views.at(-1)?.resizeVisible).toBe(false);
  });

  it.each(["ACTOR", "BOX", "SPHERE", "CYLINDER"] as const)("applies independent local scale and oriented bounds to %s", (kind) => {
    const { renderer, scene } = setup();
    const object: DirectorObject = { ...scene.objects[0]!, kind, rotation: 37, scale: { x: 0.7, y: 1.9, z: 2.8 } };
    renderer.update({ ...scene, objects: [object] }, "box"); flushFrames();
    const group = objectGroup();
    expect(group.scale.toArray()).toEqual([0.7, 1.9, 2.8]);
    const box = state.world?.getObjectByName("director-selection");
    expect(box?.quaternion.toArray()).toEqual(group.quaternion.toArray());
    expect(group.rotation.y).toBeCloseTo(37 * Math.PI / 180);
    const corner = state.world?.getObjectByName("director-resize-1-1-1");
    expect(corner?.position.y).toBeGreaterThan(group.position.y);
  });

  it("reuses object geometry through continuous resize and frees shared handle resources once", () => {
    const { renderer, scene, canvas, onObjectChange } = setup();
    resizeView(renderer, scene, canvas);
    const group = objectGroup();
    const first = group.children[0];
    if (!first || !("geometry" in first)) throw new Error("Missing geometry");
    const disposed = vi.spyOn(first.geometry as import("three").BufferGeometry, "dispose");
    const point = handlePoint("director-resize-0-1-0");
    pointer(canvas, "pointerdown", point); pointer(canvas, "pointermove", { x: point.x, y: point.y - 30 });
    const changed = onObjectChange.mock.lastCall?.[1] as DirectorObject;
    renderer.update({ ...scene, objects: [changed] }, "box"); flushFrames();
    expect(objectGroup()).toBe(group); expect(disposed).not.toHaveBeenCalled();
    const handles = state.world?.getObjectByName("director-resize-handles");
    const handle = handles?.children[0];
    if (!handle || !("geometry" in handle) || !("material" in handle)) throw new Error("Missing resize resources");
    const handleGeometry = vi.spyOn(handle.geometry as import("three").BufferGeometry, "dispose");
    const handleMaterial = vi.spyOn(handle.material as import("three").Material, "dispose");
    renderer.dispose(); renderer.dispose();
    expect(disposed).toHaveBeenCalledOnce(); expect(handleGeometry).toHaveBeenCalledOnce(); expect(handleMaterial).toHaveBeenCalledOnce();
  });
});

describe("director move and rotate gestures", () => {
  it("defaults to move and drags the selected body in world XZ without changing height or orbit", () => {
    const { renderer, scene, canvas, onObjectChange, onCameraChange, onSelectObject } = setup();
    transformView(renderer, scene, canvas);
    const observer = state.views.at(-1)?.position;
    expect(state.views.at(-1)).toMatchObject({ moveVisible: true, rotateVisible: false, resizeVisible: false });
    const point = bodyPoint();
    pointer(canvas, "pointerdown", point);
    expect(canvas.dataset.directorTransforming).toBe("true"); expect(canvas.dataset.directorResizing).toBeUndefined();
    pointer(canvas, "pointermove", { x: point.x + 60, y: point.y + 20 });
    const changed = onObjectChange.mock.lastCall?.[1] as DirectorObject;
    expect(changed.position.y).toBe(0);
    expect(Math.hypot(changed.position.x - 2, changed.position.z)).toBeGreaterThan(0.1);
    expect(changed.scale).toEqual(scene.objects[0]?.scale); expect(changed.rotation).toBe(0);
    expect(onSelectObject).toHaveBeenCalledWith("box"); expect(onCameraChange).not.toHaveBeenCalled();
    flushFrames(); expect(state.views.at(-1)?.position).toEqual(observer);
    pointer(canvas, "pointerup", { x: point.x + 60, y: point.y + 20 });
    expect(canvas.dataset.directorTransforming).toBeUndefined();
    expect(parseDirectorScene({ ...scene, objects: [changed] })).not.toBeNull();
    expect(scene.objects[0]?.position).toEqual({ x: 2, y: 0, z: 0 });
  });

  it.each([0, -10, 12])("moves an upper-body hit safely with shooting elevation %s", (elevation) => {
    const { renderer, scene, canvas, onObjectChange, onCameraChange } = setup();
    scene.camera.elevation = elevation;
    transformView(renderer, scene, canvas, "move", "camera", false);
    const cameraPosition = state.views.at(-1)?.position;
    const point = bodyPoint(0.85);
    pointer(canvas, "pointerdown", point);
    pointer(canvas, "pointermove", { x: point.x + 45, y: point.y + 12 });
    const changed = onObjectChange.mock.lastCall?.[1] as DirectorObject;
    expect(changed).toBeDefined();
    expect(changed.position.y).toBe(0);
    expect(Math.hypot(changed.position.x - 2, changed.position.z)).toBeGreaterThan(0.05);
    expect(parseDirectorScene({ ...scene, objects: [changed] })).not.toBeNull();
    expect(onCameraChange).not.toHaveBeenCalled();
    flushFrames(); expect(state.views.at(-1)?.position).toEqual(cameraPosition);
    pointer(canvas, "pointerup", { x: point.x + 45, y: point.y + 12 });
  });

  it.each(["move", "rotate"] as const)("hides and refuses projected %s helpers behind the shooting camera", (tool) => {
    const { renderer, scene, canvas, onObjectChange } = setup();
    scene.objects[0]!.position.z = 15;
    transformView(renderer, scene, canvas, tool, "camera");
    expect(state.views.at(-1)).toMatchObject({ moveVisible: false, rotateVisible: false });
    const ghost = gizmoPoint(tool === "move" ? "director-move-y-tip" : "director-rotate-y-grip");
    pointer(canvas, "pointerdown", ghost); pointer(canvas, "pointermove", { x: ghost.x + 40, y: ghost.y });
    expect(canvas.dataset.directorTransforming).toBeUndefined();
    expect(onObjectChange).not.toHaveBeenCalled();
    pointer(canvas, "pointerup", { x: ghost.x + 40, y: ghost.y });
  });

  it.each(["x", "y", "z"] as const)("moves only world %s with its arrow while preserving a rotated object's other transforms", (axis) => {
    const { renderer, scene, canvas, onObjectChange, onCameraChange } = setup();
    scene.objects[0]!.rotation = 45;
    transformView(renderer, scene, canvas);
    const point = gizmoPoint(`director-move-${axis}-tip`);
    const center = gizmoPoint("director-move-handles");
    const length = Math.hypot(point.x - center.x, point.y - center.y);
    const end = { x: point.x + (point.x - center.x) / length * 35, y: point.y + (point.y - center.y) / length * 35 };
    pointer(canvas, "pointerdown", point); pointer(canvas, "pointermove", end);
    const changed = onObjectChange.mock.lastCall?.[1] as DirectorObject;
    expect(changed.position[axis]).toBeGreaterThan(scene.objects[0]!.position[axis]);
    for (const other of ["x", "y", "z"] as const) if (other !== axis) expect(changed.position[other]).toBe(scene.objects[0]!.position[other]);
    expect(changed.rotation).toBe(45); expect(changed.scale).toEqual(scene.objects[0]?.scale);
    expect(onCameraChange).not.toHaveBeenCalled();
    pointer(canvas, "pointerup", end);
  });

  it("keeps a gesture active when repeated layout updates set the same tool", () => {
    const { renderer, scene, canvas, onObjectChange } = setup();
    transformView(renderer, scene, canvas);
    const point = bodyPoint();
    pointer(canvas, "pointerdown", point); pointer(canvas, "pointermove", { x: point.x + 25, y: point.y });
    const first = onObjectChange.mock.lastCall?.[1] as DirectorObject;
    renderer.update({ ...scene, objects: [first] }, "box"); renderer.setObjectTool("move");
    expect(canvas.dataset.directorTransforming).toBe("true");
    pointer(canvas, "pointermove", { x: point.x + 65, y: point.y });
    expect(onObjectChange.mock.lastCall?.[1].position).not.toEqual(first.position);
    pointer(canvas, "pointerup", { x: point.x + 65, y: point.y });
  });

  it("rotates from the Y ring and preserves position and dimensions", () => {
    const { renderer, scene, canvas, onObjectChange, onCameraChange } = setup();
    transformView(renderer, scene, canvas, "rotate");
    expect(state.views.at(-1)).toMatchObject({ moveVisible: false, rotateVisible: true, resizeVisible: false });
    const ring = state.world?.getObjectByName("director-rotate-y");
    const root = state.world?.getObjectByName("director-rotate-handles");
    if (!ring || !root) throw new Error("Missing rotation ring");
    const radius = ring.scale.x;
    const start = projectedPoint(root.position.clone().add(root.position.clone().set(0, 0, radius)));
    const angle = Math.PI / 3;
    const end = projectedPoint(root.position.clone().add(root.position.clone().set(Math.sin(angle) * radius, 0, Math.cos(angle) * radius)));
    pointer(canvas, "pointerdown", start); pointer(canvas, "pointermove", end);
    const changed = onObjectChange.mock.lastCall?.[1] as DirectorObject;
    expect(changed.rotation).toBeCloseTo(60);
    expect(changed.position).toEqual(scene.objects[0]?.position); expect(changed.scale).toEqual(scene.objects[0]?.scale);
    expect(onCameraChange).not.toHaveBeenCalled(); pointer(canvas, "pointerup", end);
  });

  it("supports horizontal body rotation and wraps its Y heading at the boundary", () => {
    const { renderer, scene, canvas, onObjectChange, onCameraChange } = setup();
    scene.objects[0]!.rotation = 170;
    transformView(renderer, scene, canvas, "rotate");
    const point = bodyPoint();
    pointer(canvas, "pointerdown", point); pointer(canvas, "pointermove", { x: point.x + 100, y: point.y });
    const changed = onObjectChange.mock.lastCall?.[1] as DirectorObject;
    expect(changed.rotation).toBeCloseTo(-120);
    expect(changed.position).toEqual(scene.objects[0]?.position); expect(changed.scale).toEqual(scene.objects[0]?.scale);
    expect(onCameraChange).not.toHaveBeenCalled(); pointer(canvas, "pointerup", { x: point.x + 100, y: point.y });
  });

  it.each((["move", "rotate"] as const).flatMap((tool) =>
    ["escape", "pointercancel", "lostpointercapture", "disabled", "view", "tool", "removed", "deselected", "failure", "dispose", "capture"].map((ending) => ({ tool, ending }))))(
    "ends $tool safely on $ending and clears the shared dialog marker", async ({ tool, ending }) => {
      const { renderer, scene, canvas, onObjectChange, onCameraChange } = setup();
      transformView(renderer, scene, canvas, tool);
      const point = bodyPoint();
      pointer(canvas, "pointerdown", point); pointer(canvas, "pointermove", { x: point.x + 45, y: point.y });
      expect(canvas.dataset.directorTransforming).toBe("true");
      expect(onObjectChange).toHaveBeenCalled();
      switch (ending) {
        case "escape": canvas.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true })); break;
        case "disabled": renderer.setDisabled(true); break;
        case "view": renderer.setViewMode("camera"); break;
        case "tool": renderer.setObjectTool("resize"); break;
        case "removed": renderer.update({ ...scene, objects: [] }); break;
        case "deselected": renderer.update(scene); break;
        case "failure": canvas.dispatchEvent(new Event("webglcontextlost", { cancelable: true })); break;
        case "dispose": renderer.dispose(); break;
        case "capture": mockCaptureContext(); await renderer.capture(); break;
        default: pointer(canvas, ending, point);
      }
      expect(canvas.dataset.directorTransforming).toBeUndefined(); expect(canvas.dataset.directorResizing).toBeUndefined();
      expect(canvas.style.cursor).toBe(""); expect(onCameraChange).not.toHaveBeenCalled();
      if (["escape", "pointercancel", "lostpointercapture"].includes(ending)) expect(onObjectChange.mock.lastCall?.[1]).toEqual(scene.objects[0]);
    },
  );

  it.each(["move", "rotate"] as const)("cancels $tool through the shared modal Escape marker", (tool) => {
    const close = vi.fn();
    render(createElement(Dialog, { open: true }, createElement(DialogContent, { onEscapeKeyDown: (event) => {
      event.preventDefault();
      if (event.target instanceof HTMLCanvasElement && event.target.dataset.directorTransforming === "true") return;
      event.stopPropagation(); close();
    } }, createElement(DialogTitle, null, "Synthetic transform modal"), createElement(DialogDescription, null, "Shared native Escape regression"))));
    const { renderer, scene, canvas, onObjectChange } = setup();
    screen.getByRole("dialog").append(canvas);
    transformView(renderer, scene, canvas, tool);
    const point = bodyPoint();
    pointer(canvas, "pointerdown", point); pointer(canvas, "pointermove", { x: point.x + 40, y: point.y });
    expect(canvas.dataset.directorTransforming).toBe("true");
    canvas.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true }));
    expect(canvas.dataset.directorTransforming).toBeUndefined();
    expect(onObjectChange.mock.lastCall?.[1]).toEqual(scene.objects[0]);
    expect(close).not.toHaveBeenCalled();
    fireEvent.keyDown(canvas, { key: "Escape", cancelable: true }); expect(close).toHaveBeenCalledOnce();
  });

  it.each(["move", "rotate"] as const)("disables $tool while busy and hides its helpers from capture before restoring them", async (tool) => {
    mockCaptureContext();
    const { renderer, scene, canvas, onObjectChange } = setup();
    transformView(renderer, scene, canvas, tool);
    const point = bodyPoint();
    renderer.setDisabled(true); flushFrames();
    pointer(canvas, "pointerdown", point); pointer(canvas, "pointermove", { x: point.x + 35, y: point.y });
    expect(onObjectChange).not.toHaveBeenCalled();
    renderer.setDisabled(false); flushFrames();
    await renderer.capture();
    expect(state.views.at(-1)).toMatchObject({ moveVisible: false, rotateVisible: false, resizeVisible: false, camera: "director-shoot-camera" });
    flushFrames();
    expect(state.views.at(-1)).toMatchObject({ moveVisible: tool === "move", rotateVisible: tool === "rotate" });
  });

  it("releases shared axis and rotation geometry and materials once", () => {
    const { renderer, scene, canvas } = setup();
    transformView(renderer, scene, canvas);
    const geometries = new Set<import("three").BufferGeometry>(), materials = new Set<import("three").Material>();
    for (const name of ["director-move-handles", "director-rotate-handles"]) state.world?.getObjectByName(name)?.traverse((object) => {
      if ("geometry" in object) geometries.add(object.geometry as import("three").BufferGeometry);
      if ("material" in object) materials.add(object.material as import("three").Material);
    });
    const geometryDisposals = [...geometries].map((geometry) => vi.spyOn(geometry, "dispose"));
    const materialDisposals = [...materials].map((material) => vi.spyOn(material, "dispose"));
    expect(geometries.size).toBeGreaterThan(0); expect(materials.size).toBeGreaterThan(0);
    renderer.dispose(); renderer.dispose();
    for (const disposed of [...geometryDisposals, ...materialDisposals]) expect(disposed).toHaveBeenCalledOnce();
  });
});
