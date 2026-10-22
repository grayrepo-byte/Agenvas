import {
  ACESFilmicToneMapping, BoxGeometry, BufferGeometry, Color, CylinderGeometry, DirectionalLight,
  Group, HemisphereLight, Material, Mesh, MeshStandardMaterial, PCFShadowMap,
  PerspectiveCamera, PlaneGeometry, Raycaster, Scene, SphereGeometry, Vector2, Vector3, WebGLRenderer,
} from "three";
import { cameraPosition, DIRECTOR_CAPTURE_SIZES, type DirectorObject, type DirectorScene } from "./directorScene";

interface DirectorRendererCallbacks {
  onCameraChange(camera: DirectorScene["camera"]): void;
  onUnavailable(): void;
  onSelectObject?(id: string): void;
}
export interface DirectorRenderer {
  update(scene: DirectorScene): void;
  resize(width: number, height: number): void;
  setDisabled(disabled: boolean): void;
  capture(): Promise<File>;
  dispose(): void;
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));
const wrapAzimuth = (value: number) => ((value + 180) % 360 + 360) % 360 - 180;

function disposeObjects(root: Group | Scene) {
  const geometries = new Set<BufferGeometry>();
  const materials = new Set<Material>();
  root.traverse((child) => {
    if (child instanceof Mesh) {
      geometries.add(child.geometry);
      for (const material of Array.isArray(child.material) ? child.material : [child.material]) materials.add(material);
    }
    if (child instanceof DirectionalLight) child.shadow.dispose();
  });
  geometries.forEach((geometry) => geometry.dispose());
  materials.forEach((material) => material.dispose());
  root.clear();
}

/** Original neutral mannequin; +Z is its front, made visible by its nose and feet. */
function createActor(material: MeshStandardMaterial): Group {
  const actor = new Group();
  const add = (geometry: BufferGeometry, x: number, y: number, z = 0) => {
    const part = new Mesh(geometry, material);
    part.position.set(x, y, z);
    part.castShadow = true;
    part.receiveShadow = true;
    actor.add(part);
    return part;
  };
  add(new SphereGeometry(0.15, 20, 16), 0, 1.65);
  add(new SphereGeometry(0.045, 12, 8), 0, 1.65, 0.145);
  add(new CylinderGeometry(0.07, 0.07, 0.12, 12), 0, 1.45);
  add(new CylinderGeometry(0.21, 0.16, 0.49, 16), 0, 1.17);
  add(new SphereGeometry(0.17, 16, 12), 0, 0.89).scale.set(1.2, 0.8, 0.8);
  for (const side of [-1, 1]) {
    add(new SphereGeometry(0.085, 12, 10), side * 0.24, 1.34);
    add(new CylinderGeometry(0.063, 0.052, 0.32, 12), side * 0.29, 1.15).rotation.z = side * 0.12;
    add(new SphereGeometry(0.06, 12, 10), side * 0.31, 0.98);
    add(new CylinderGeometry(0.05, 0.04, 0.29, 12), side * 0.32, 0.82);
    add(new SphereGeometry(0.05, 12, 10), side * 0.32, 0.65);
    add(new CylinderGeometry(0.085, 0.065, 0.38, 12), side * 0.105, 0.66);
    add(new SphereGeometry(0.07, 12, 10), side * 0.105, 0.45);
    add(new CylinderGeometry(0.065, 0.045, 0.36, 12), side * 0.105, 0.25);
    add(new BoxGeometry(0.13, 0.1, 0.25), side * 0.105, 0.05, 0.065);
  }
  return actor;
}

function createObject(object: DirectorObject): Group {
  const group = new Group();
  group.userData.directorObjectId = object.id;
  const material = new MeshStandardMaterial({ color: object.color, roughness: 0.72, metalness: 0 });
  if (object.kind === "ACTOR") {
    group.add(createActor(material));
  } else {
    const geometry = object.kind === "BOX" ? new BoxGeometry(1, 1, 1)
      : object.kind === "SPHERE" ? new SphereGeometry(0.5, 32, 24)
      : new CylinderGeometry(0.5, 0.5, 1, 32);
    const mesh = new Mesh(geometry, material);
    mesh.position.y = 0.5;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    group.add(mesh);
  }
  group.position.set(object.position.x, object.position.y, object.position.z);
  group.rotation.y = object.rotation * Math.PI / 180;
  group.scale.setScalar(object.scale);
  return group;
}

/** All renderer resources and DOM listeners belong to this one viewport lifetime. */
export function createDirectorRenderer(canvas: HTMLCanvasElement, initialScene: DirectorScene,
  callbacks: DirectorRendererCallbacks): DirectorRenderer {
  const renderer = new WebGLRenderer({ canvas, antialias: true, alpha: false });
  const world = new Scene();
  world.background = new Color("#E8E4DC");
  const objects = new Group();
  world.add(objects);
  const floor = new Mesh(new PlaneGeometry(200, 200), new MeshStandardMaterial({ color: "#D6D2CA", roughness: 1 }));
  floor.rotation.x = -Math.PI / 2;
  floor.position.y = -0.015;
  floor.receiveShadow = true;
  world.add(floor);
  world.add(new HemisphereLight("#FFFFFF", "#9A9286", 2.3));
  const keyLight = new DirectionalLight("#FFF8EC", 3.2);
  keyLight.position.set(4, 8, 6);
  keyLight.castShadow = true;
  keyLight.shadow.mapSize.set(2048, 2048);
  keyLight.shadow.camera.left = -25;
  keyLight.shadow.camera.right = 25;
  keyLight.shadow.camera.top = 25;
  keyLight.shadow.camera.bottom = -25;
  keyLight.shadow.camera.far = 80;
  keyLight.shadow.normalBias = 0.025;
  world.add(keyLight);
  const fill = new DirectionalLight("#FFFFFF", 1.2);
  fill.position.set(-6, 4, -4);
  world.add(fill);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = PCFShadowMap;
  renderer.toneMapping = ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1;
  const camera = new PerspectiveCamera(50, 16 / 9, 0.05, 250);
  const raycaster = new Raycaster();
  let scene = initialScene;
  let objectsKey = "";
  let width = 1;
  let height = 1;
  let pixelRatio = 1;
  let disposed = false;
  let unavailable = false;
  let disabled = false;
  let frame: number | null = null;
  let drag: { id: number; x: number; y: number; startX: number; startY: number; pan: boolean; moved: boolean } | null = null;

  const applyCamera = () => {
    const position = cameraPosition(scene.camera);
    camera.position.set(position.x, position.y, position.z);
    camera.lookAt(scene.camera.target.x, scene.camera.target.y, scene.camera.target.z);
    const size = DIRECTOR_CAPTURE_SIZES[scene.aspectRatio];
    camera.aspect = size.width / size.height;
    camera.setFocalLength(scene.camera.focalLength);
    camera.updateProjectionMatrix();
    camera.updateMatrixWorld();
  };
  const fail = () => {
    if (disposed || unavailable) return;
    unavailable = true;
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
    if (drag && canvas.hasPointerCapture(drag.id)) canvas.releasePointerCapture(drag.id);
    drag = null;
    callbacks.onUnavailable();
  };
  renderer.debug.onShaderError = fail;
  const renderNow = () => {
    if (disposed || unavailable || renderer.getContext().isContextLost()) throw new Error("DIRECTOR_VIEWPORT_UNAVAILABLE");
    applyCamera();
    renderer.render(world, camera);
    if (unavailable) throw new Error("DIRECTOR_VIEWPORT_UNAVAILABLE");
  };
  const invalidate = () => {
    if (disposed || unavailable || frame !== null) return;
    frame = requestAnimationFrame(() => {
      frame = null;
      try { renderNow(); } catch { fail(); }
    });
  };
  const update = (nextScene: DirectorScene) => {
    if (disposed || unavailable) return;
    scene = nextScene;
    const nextObjectsKey = JSON.stringify(nextScene.objects);
    if (nextObjectsKey !== objectsKey) {
      disposeObjects(objects);
      for (const object of nextScene.objects) objects.add(createObject(object));
      objectsKey = nextObjectsKey;
    }
    applyCamera();
    invalidate();
  };
  const changeCamera = (nextCamera: DirectorScene["camera"]) => {
    scene = { ...scene, camera: nextCamera };
    applyCamera();
    invalidate();
    callbacks.onCameraChange(nextCamera);
  };
  const pointerDown = (event: PointerEvent) => {
    if (disabled || unavailable || (event.button !== 0 && event.button !== 1 && event.button !== 2) || drag) return;
    event.preventDefault();
    canvas.focus({ preventScroll: true });
    canvas.setPointerCapture(event.pointerId);
    drag = { id: event.pointerId, x: event.clientX, y: event.clientY, startX: event.clientX, startY: event.clientY,
      pan: event.shiftKey || event.button !== 0, moved: false };
  };
  const pointerMove = (event: PointerEvent) => {
    if (!drag || drag.id !== event.pointerId || disabled || unavailable) return;
    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    drag.x = event.clientX;
    drag.y = event.clientY;
    drag.moved ||= Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) > 4;
    if (!drag.moved) return;
    if (drag.pan) {
      const units = 2 * scene.camera.distance * Math.tan(camera.fov * Math.PI / 360) / Math.max(height, 1);
      const offset = new Vector3().setFromMatrixColumn(camera.matrixWorld, 0).multiplyScalar(-dx * units)
        .add(new Vector3().setFromMatrixColumn(camera.matrixWorld, 1).multiplyScalar(dy * units));
      changeCamera({ ...scene.camera, target: {
        x: clamp(scene.camera.target.x + offset.x, -20, 20), y: clamp(scene.camera.target.y + offset.y, -20, 20),
        z: clamp(scene.camera.target.z + offset.z, -20, 20),
      } });
    } else {
      changeCamera({ ...scene.camera, azimuth: wrapAzimuth(scene.camera.azimuth - dx * 0.35),
        elevation: clamp(scene.camera.elevation + dy * 0.25, -80, 80) });
    }
  };
  const pointerUp = (event: PointerEvent) => {
    if (!drag || drag.id !== event.pointerId) return;
    const select = !drag.moved && !drag.pan && event.type === "pointerup" && !disabled && !unavailable;
    drag = null;
    if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
    if (!select || !callbacks.onSelectObject) return;
    const bounds = canvas.getBoundingClientRect();
    if (bounds.width <= 0 || bounds.height <= 0) return;
    raycaster.setFromCamera(new Vector2((event.clientX - bounds.left) / bounds.width * 2 - 1,
      1 - (event.clientY - bounds.top) / bounds.height * 2), camera);
    world.updateMatrixWorld(true);
    let hit = raycaster.intersectObjects(objects.children, true)[0]?.object;
    while (hit) {
      const id: unknown = hit.userData.directorObjectId;
      if (typeof id === "string") { callbacks.onSelectObject(id); break; }
      hit = hit.parent ?? undefined;
    }
  };
  const wheel = (event: WheelEvent) => {
    if (disabled || unavailable) return;
    event.preventDefault();
    const delta = event.deltaY * (event.deltaMode === WheelEvent.DOM_DELTA_LINE ? 16 : event.deltaMode === WheelEvent.DOM_DELTA_PAGE ? height : 1);
    changeCamera({ ...scene.camera, distance: clamp(scene.camera.distance * Math.exp(clamp(delta * 0.0015, -1, 1)), 1, 50) });
  };
  const keyDown = (event: KeyboardEvent) => {
    if (disabled || unavailable || event.altKey || event.ctrlKey || event.metaKey) return;
    let nextCamera: DirectorScene["camera"];
    switch (event.key) {
      case "ArrowLeft": nextCamera = { ...scene.camera, azimuth: wrapAzimuth(scene.camera.azimuth - 5) }; break;
      case "ArrowRight": nextCamera = { ...scene.camera, azimuth: wrapAzimuth(scene.camera.azimuth + 5) }; break;
      case "ArrowUp": nextCamera = { ...scene.camera, elevation: clamp(scene.camera.elevation + 5, -80, 80) }; break;
      case "ArrowDown": nextCamera = { ...scene.camera, elevation: clamp(scene.camera.elevation - 5, -80, 80) }; break;
      case "+": case "=": nextCamera = { ...scene.camera, distance: clamp(scene.camera.distance * 0.9, 1, 50) }; break;
      case "-": nextCamera = { ...scene.camera, distance: clamp(scene.camera.distance * 1.1, 1, 50) }; break;
      default: return;
    }
    event.preventDefault();
    event.stopPropagation();
    changeCamera(nextCamera);
  };
  const contextMenu = (event: Event) => event.preventDefault();
  const contextLost = (event: Event) => { event.preventDefault(); fail(); };
  canvas.addEventListener("pointerdown", pointerDown);
  canvas.addEventListener("pointermove", pointerMove);
  canvas.addEventListener("pointerup", pointerUp);
  canvas.addEventListener("pointercancel", pointerUp);
  canvas.addEventListener("lostpointercapture", pointerUp);
  canvas.addEventListener("wheel", wheel, { passive: false });
  canvas.addEventListener("keydown", keyDown);
  canvas.addEventListener("contextmenu", contextMenu);
  canvas.addEventListener("webglcontextlost", contextLost);

  const dispose = () => {
    if (disposed) return;
    disposed = true;
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
    if (drag && canvas.hasPointerCapture(drag.id)) canvas.releasePointerCapture(drag.id);
    drag = null;
    canvas.removeEventListener("pointerdown", pointerDown);
    canvas.removeEventListener("pointermove", pointerMove);
    canvas.removeEventListener("pointerup", pointerUp);
    canvas.removeEventListener("pointercancel", pointerUp);
    canvas.removeEventListener("lostpointercapture", pointerUp);
    canvas.removeEventListener("wheel", wheel);
    canvas.removeEventListener("keydown", keyDown);
    canvas.removeEventListener("contextmenu", contextMenu);
    canvas.removeEventListener("webglcontextlost", contextLost);
    disposeObjects(world);
    renderer.dispose();
    if (!renderer.getContext().isContextLost()) renderer.forceContextLoss();
  };
  try {
    update(initialScene);
    renderNow();
  } catch (error) { dispose(); throw error; }

  return {
    update,
    resize(nextWidth, nextHeight) {
      if (disposed || unavailable) return;
      const nextPixelRatio = Math.min(window.devicePixelRatio || 1, 2);
      const roundedWidth = Math.max(1, Math.round(nextWidth));
      const roundedHeight = Math.max(1, Math.round(nextHeight));
      if (width === roundedWidth && height === roundedHeight && pixelRatio === nextPixelRatio) return;
      width = roundedWidth;
      height = roundedHeight;
      pixelRatio = nextPixelRatio;
      renderer.setPixelRatio(pixelRatio);
      renderer.setSize(width, height, false);
      invalidate();
    },
    setDisabled(nextDisabled) {
      disabled = nextDisabled;
      if (disabled && drag) {
        if (canvas.hasPointerCapture(drag.id)) canvas.releasePointerCapture(drag.id);
        drag = null;
      }
    },
    async capture() {
      if (disposed || unavailable || renderer.getContext().isContextLost()) throw new Error("DIRECTOR_VIEWPORT_UNAVAILABLE");
      const size = DIRECTOR_CAPTURE_SIZES[scene.aspectRatio];
      // Copy synchronously before restoring the preview; no preserveDrawingBuffer or continuous loop is needed.
      const output = document.createElement("canvas");
      output.width = size.width;
      output.height = size.height;
      const context = output.getContext("2d");
      if (!context) throw new Error("DIRECTOR_CAPTURE_FAILED");
      try {
        renderer.setPixelRatio(1);
        renderer.setSize(size.width, size.height, false);
        renderNow();
        context.drawImage(canvas, 0, 0);
      } finally {
        if (!disposed && !unavailable) {
          renderer.setPixelRatio(pixelRatio);
          renderer.setSize(width, height, false);
          invalidate();
        }
      }
      const blob = await new Promise<Blob>((resolve, reject) => output.toBlob((value) => {
        if (value) resolve(value); else reject(new Error("DIRECTOR_CAPTURE_FAILED"));
      }, "image/png"));
      if (disposed || unavailable) throw new Error("DIRECTOR_VIEWPORT_UNAVAILABLE");
      return new File([blob], "director-frame.png", { type: "image/png" });
    },
    dispose,
  };
}
