import {
  ACESFilmicToneMapping, Box3, BoxGeometry, BufferGeometry, CameraHelper, Color, CylinderGeometry, DirectionalLight, EdgesGeometry,
  Group, HemisphereLight, LineBasicMaterial, LineSegments, Material, Mesh, MeshBasicMaterial, MeshStandardMaterial, PCFShadowMap,
  PerspectiveCamera, Plane, PlaneGeometry, Raycaster, Scene, SphereGeometry, TorusGeometry, Vector2, Vector3, WebGLRenderer,
} from "three";
import { cameraPosition, DIRECTOR_CAPTURE_SIZES, type DirectorObject, type DirectorScene } from "./directorScene";
import { DIRECTOR_RESIZE_AXES, resizeDirectorObject, type DirectorLocalBounds, type DirectorResizeHandle } from "./directorResize";
import { moveDirectorObject, rotateDirectorObject, wrapDirectorRotation, type DirectorMoveAxis } from "./directorTransform";

interface DirectorRendererCallbacks {
  onCameraChange(camera: DirectorScene["camera"]): void;
  onUnavailable(): void;
  onSelectObject?(id: string): void;
  onSelectCamera?(): void;
  onObjectChange?(id: string, object: DirectorObject): void;
}
export type DirectorViewMode = "director" | "camera";
export type DirectorObjectTool = "move" | "rotate" | "resize";
export interface DirectorRenderer {
  update(scene: DirectorScene, selectedObjectId?: string): void;
  setViewMode(mode: DirectorViewMode): void;
  setObjectTool(tool: DirectorObjectTool): void;
  resize(width: number, height: number): void;
  setDisabled(disabled: boolean): void;
  capture(): Promise<File>;
  dispose(): void;
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));
const wrapAzimuth = (value: number) => ((value + 180) % 360 + 360) % 360 - 180;
type OrbitDrag = { kind: "orbit"; id: number; x: number; y: number; startX: number; startY: number; pan: boolean; moved: boolean };
type ResizeDrag = { kind: "resize"; id: number; original: DirectorObject; bounds: DirectorLocalBounds;
  handle: DirectorResizeHandle; plane: Plane; startPoint: Vector3 };
type MoveDrag = { kind: "move"; id: number; original: DirectorObject; axis: DirectorMoveAxis; plane: Plane;
  startPoint: Vector3; startX: number; startY: number; moved: boolean };
type RotateDrag = { kind: "rotate"; id: number; original: DirectorObject; plane: Plane | null; center: Vector3;
  lastAngle: number; accumulated: number; startX: number; startY: number; moved: boolean };
type ObjectDrag = ResizeDrag | MoveDrag | RotateDrag;
type ObjectInstance = { group: Group; kind: DirectorObject["kind"]; bounds: Box3 };

function disposeObjects(root: Group | Scene) {
  const geometries = new Set<BufferGeometry>();
  const materials = new Set<Material>();
  root.traverse((child) => {
    if (child instanceof Mesh || child instanceof LineSegments) {
      geometries.add(child.geometry);
      for (const material of Array.isArray(child.material) ? child.material : [child.material]) materials.add(material);
    }
    if (child instanceof DirectionalLight) child.shadow.dispose();
  });
  geometries.forEach((geometry) => geometry.dispose());
  materials.forEach((material) => material.dispose());
  root.clear();
}

/** An original camera icon in world space; editor meshes never cast or receive shadows. */
function createCameraRig(): Group {
  const rig = new Group();
  rig.name = "director-camera-rig";
  rig.userData.directorCamera = true;
  const bodyMaterial = new MeshStandardMaterial({ color: "#222B38", roughness: 0.65 });
  const accentMaterial = new MeshBasicMaterial({ toneMapped: false });
  const body = new Mesh(new BoxGeometry(0.6, 0.4, 0.42), bodyMaterial);
  body.position.z = 0.16;
  rig.add(body);
  const handle = new Mesh(new BoxGeometry(0.32, 0.07, 0.2), bodyMaterial);
  handle.position.set(0, 0.29, 0.16);
  rig.add(handle);
  const mount = new Mesh(new BoxGeometry(0.07, 0.15, 0.14), bodyMaterial);
  mount.position.set(-0.12, 0.22, 0.16);
  rig.add(mount);
  const lens = new Mesh(new CylinderGeometry(0.15, 0.19, 0.3, 24), bodyMaterial);
  lens.rotation.x = Math.PI / 2;
  lens.position.z = -0.2;
  rig.add(lens);
  const lensRim = new Mesh(new CylinderGeometry(0.17, 0.17, 0.06, 24), accentMaterial);
  lensRim.rotation.x = Math.PI / 2;
  lensRim.position.z = -0.36;
  rig.add(lensRim);
  const lensGlass = new Mesh(new CylinderGeometry(0.12, 0.12, 0.065, 24), bodyMaterial);
  lensGlass.rotation.x = Math.PI / 2;
  lensGlass.position.z = -0.375;
  rig.add(lensGlass);
  return rig;
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
  return group;
}

function applyObjectTransform(group: Group, object: DirectorObject) {
  group.position.set(object.position.x, object.position.y, object.position.z);
  group.rotation.y = object.rotation * Math.PI / 180;
  group.scale.set(object.scale.x, object.scale.y, object.scale.z);
  group.traverse((child) => {
    if (child instanceof Mesh && child.material instanceof MeshStandardMaterial) child.material.color.set(object.color);
  });
  group.updateMatrixWorld(true);
}

/** All renderer resources and DOM listeners belong to this one viewport lifetime. */
export function createDirectorRenderer(canvas: HTMLCanvasElement, initialScene: DirectorScene,
  callbacks: DirectorRendererCallbacks): DirectorRenderer {
  const renderer = new WebGLRenderer({ canvas, antialias: true, alpha: false });
  const world = new Scene();
  world.background = new Color("#E8E4DC");
  const objects = new Group();
  world.add(objects);
  const instances = new Map<string, ObjectInstance>();
  const unitBox = new BoxGeometry(1, 1, 1);
  const selection = new LineSegments(new EdgesGeometry(unitBox), new LineBasicMaterial({
    depthTest: false, depthWrite: false, transparent: true, opacity: 0.85, toneMapped: false,
  }));
  unitBox.dispose();
  selection.name = "director-selection";
  selection.visible = false;
  selection.renderOrder = 1;
  selection.frustumCulled = false;
  world.add(selection);
  const resizeHandles = new Group();
  resizeHandles.name = "director-resize-handles";
  resizeHandles.visible = false;
  const handleGeometry = new BoxGeometry(1, 1, 1);
  const handleMaterial = new MeshBasicMaterial({ depthTest: false, depthWrite: false, toneMapped: false });
  const handles: { mesh: Mesh; signs: DirectorResizeHandle }[] = [];
  for (const x of [-1, 0, 1]) for (const y of [-1, 0, 1]) for (const z of [-1, 0, 1]) {
    const axisCount = [x, y, z].filter(Boolean).length;
    if (axisCount !== 1 && axisCount !== 3) continue;
    const mesh = new Mesh(handleGeometry, handleMaterial);
    mesh.name = `director-resize-${x}-${y}-${z}`;
    mesh.renderOrder = 2;
    mesh.frustumCulled = false;
    resizeHandles.add(mesh);
    handles.push({ mesh, signs: { x, y, z } });
  }
  world.add(resizeHandles);
  const moveHandles = new Group();
  moveHandles.name = "director-move-handles";
  moveHandles.visible = false;
  const axisStem = new CylinderGeometry(0.012, 0.012, 0.82, 10);
  const axisTip = new CylinderGeometry(0, 0.06, 0.18, 12);
  const axisHandles = (["x", "y", "z"] as const).map((axis) => {
    const root = new Group(); root.name = `director-move-${axis}`;
    const material = new MeshBasicMaterial({ depthTest: false, depthWrite: false, toneMapped: false });
    const stem = new Mesh(axisStem, material); stem.position.y = 0.41; stem.renderOrder = 2;
    const tip = new Mesh(axisTip, material); tip.position.y = 0.91; tip.renderOrder = 2;
    tip.name = `director-move-${axis}-tip`;
    root.add(stem, tip);
    if (axis === "x") root.rotation.z = -Math.PI / 2;
    if (axis === "z") root.rotation.x = Math.PI / 2;
    moveHandles.add(root);
    return { axis, root, material };
  });
  const rotateHandles = new Group();
  rotateHandles.name = "director-rotate-handles";
  rotateHandles.visible = false;
  const rotationMaterial = new MeshBasicMaterial({ depthTest: false, depthWrite: false, toneMapped: false });
  const rotationRing = new Mesh(new TorusGeometry(1, 0.012, 8, 80), rotationMaterial);
  rotationRing.name = "director-rotate-y";
  rotationRing.rotation.x = Math.PI / 2; rotationRing.renderOrder = 2;
  const rotationGrip = new Mesh(new SphereGeometry(1, 12, 8), rotationMaterial);
  rotationGrip.name = "director-rotate-y-grip"; rotationGrip.renderOrder = 3;
  rotateHandles.add(rotationRing, rotationGrip);
  world.add(moveHandles, rotateHandles);
  let axisLength = 1;
  let rotationRadius = 1;
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
  camera.name = "director-shoot-camera";
  const observerCamera = new PerspectiveCamera(50, 1, 0.05, 500);
  observerCamera.name = "director-observer-camera";
  const cameraRig = createCameraRig();
  const frustumCamera = camera.clone();
  const cameraFrustum = new CameraHelper(frustumCamera);
  cameraFrustum.name = "director-camera-frustum";
  cameraFrustum.renderOrder = 1;
  cameraRig.visible = false;
  cameraFrustum.visible = false;
  for (const material of Array.isArray(cameraFrustum.material) ? cameraFrustum.material : [cameraFrustum.material]) {
    material.transparent = true;
    material.opacity = 0.45;
    material.depthTest = false;
    material.depthWrite = false;
  }
  world.add(cameraRig, cameraFrustum);
  const raycaster = new Raycaster();
  let scene = initialScene;
  let viewMode: DirectorViewMode = "camera";
  let objectTool: DirectorObjectTool = "move";
  const initialCameraPosition = cameraPosition(initialScene.camera);
  // The observer frames the subject and physical camera together, without entering saved scene data.
  let observerPose: DirectorScene["camera"] = { azimuth: 55, elevation: 30,
    distance: Math.max(14, initialScene.camera.distance * 1.7), focalLength: 35,
    target: { x: (initialScene.camera.target.x + initialCameraPosition.x) / 2,
      y: (initialScene.camera.target.y + initialCameraPosition.y) / 2,
      z: (initialScene.camera.target.z + initialCameraPosition.z) / 2 } };
  let selectedId: string | undefined;
  let capturing = false;
  let width = 1;
  let height = 1;
  let pixelRatio = 1;
  let disposed = false;
  let unavailable = false;
  let disabled = false;
  let frame: number | null = null;
  let drag: OrbitDrag | ObjectDrag | null = null;

  const applyCamera = () => {
    const position = cameraPosition(scene.camera);
    camera.position.set(position.x, position.y, position.z);
    camera.lookAt(scene.camera.target.x, scene.camera.target.y, scene.camera.target.z);
    const size = DIRECTOR_CAPTURE_SIZES[scene.aspectRatio];
    camera.aspect = size.width / size.height;
    camera.setFocalLength(scene.camera.focalLength);
    camera.updateProjectionMatrix();
    camera.updateMatrixWorld();
    cameraRig.position.copy(camera.position);
    cameraRig.quaternion.copy(camera.quaternion);
    frustumCamera.copy(camera);
    // Visualize the real shooting cone up to its look-at plane; its angular projection remains exact.
    frustumCamera.far = scene.camera.distance;
    frustumCamera.updateProjectionMatrix();
    frustumCamera.updateMatrixWorld();
    cameraFrustum.update();
    const themeAccent = getComputedStyle(canvas).getPropertyValue("--ui-accent").trim();
    const accent = new Color().setStyle(themeAccent || getComputedStyle(canvas).color);
    cameraFrustum.setColors(accent, accent, accent, accent, accent);
    cameraRig.traverse((object) => {
      if (object instanceof Mesh && object.material instanceof MeshBasicMaterial) object.material.color.copy(accent);
    });
    const observerPosition = cameraPosition(observerPose);
    observerCamera.position.set(observerPosition.x, observerPosition.y, observerPosition.z);
    observerCamera.lookAt(observerPose.target.x, observerPose.target.y, observerPose.target.z);
    observerCamera.aspect = width / Math.max(height, 1);
    observerCamera.updateProjectionMatrix();
    observerCamera.updateMatrixWorld();
  };
  const activeCamera = () => viewMode === "director" ? observerCamera : camera;
  const activePose = () => viewMode === "director" ? observerPose : scene.camera;
  const syncSelection = () => {
    const instance = selectedId ? instances.get(selectedId) : undefined;
    const object = scene.objects.find((item) => item.id === selectedId);
    selection.visible = Boolean(instance && object) && !capturing;
    const editable = Boolean(instance && object && callbacks.onObjectChange) && !disabled && !capturing;
    resizeHandles.visible = editable && objectTool === "resize";
    moveHandles.visible = editable && objectTool === "move";
    rotateHandles.visible = editable && objectTool === "rotate";
    if (!instance || !object) return;
    const { group, bounds } = instance;
    const center = bounds.getCenter(new Vector3());
    const size = bounds.getSize(new Vector3());
    selection.position.copy(group.localToWorld(center.clone()));
    selection.quaternion.copy(group.quaternion);
    selection.scale.set(size.x * object.scale.x * 1.03, size.y * object.scale.y * 1.03, size.z * object.scale.z * 1.03);
    const themeAccent = getComputedStyle(canvas).getPropertyValue("--ui-accent").trim();
    selection.material.color.setStyle(themeAccent || getComputedStyle(canvas).color);
    handleMaterial.color.copy(selection.material.color);
    rotationMaterial.color.copy(selection.material.color);
    const viewingCamera = activeCamera();
    const pivotDepth = -selection.position.clone().applyMatrix4(viewingCamera.matrixWorldInverse).z;
    const pivotVisible = Number.isFinite(pivotDepth) && pivotDepth > viewingCamera.near && pivotDepth < viewingCamera.far;
    moveHandles.visible &&= pivotVisible;
    rotateHandles.visible &&= pivotVisible;
    const pivotUnitsPerPixel = 2 * Math.max(pivotDepth, viewingCamera.near) * Math.tan(viewingCamera.fov * Math.PI / 360) / Math.max(height, 1);
    axisLength = pivotUnitsPerPixel * 72;
    moveHandles.position.copy(selection.position);
    const style = getComputedStyle(canvas);
    for (const handle of axisHandles) {
      const token = handle.axis === "x" ? "--ui-accent" : handle.axis === "y" ? "--ui-success" : "--ui-warning";
      handle.material.color.setStyle(style.getPropertyValue(token).trim() || themeAccent || style.color);
      handle.root.scale.setScalar(axisLength);
      const axis = new Vector3(handle.axis === "x" ? 1 : 0, handle.axis === "y" ? 1 : 0, handle.axis === "z" ? 1 : 0);
      const tipDepth = -moveHandles.position.clone().addScaledVector(axis, axisLength).applyMatrix4(viewingCamera.matrixWorldInverse).z;
      handle.root.visible = Math.abs(axis.dot(viewingCamera.getWorldDirection(new Vector3()))) < 0.985
        && tipDepth > viewingCamera.near && tipDepth < viewingCamera.far;
    }
    moveHandles.updateMatrixWorld(true);
    rotationRadius = Math.hypot(size.x * object.scale.x, size.z * object.scale.z) / 2 + pivotUnitsPerPixel * 18;
    rotateHandles.position.set(object.position.x, selection.position.y, object.position.z);
    rotationRing.scale.setScalar(rotationRadius);
    const heading = object.rotation * Math.PI / 180;
    rotationGrip.position.set(Math.sin(heading) * rotationRadius, 0, Math.cos(heading) * rotationRadius);
    rotationGrip.scale.setScalar(pivotUnitsPerPixel * 5);
    rotateHandles.updateMatrixWorld(true);
    for (const handle of handles) {
      const point = new Vector3(...DIRECTOR_RESIZE_AXES.map((axis) => handle.signs[axis] > 0 ? bounds.max[axis]
        : handle.signs[axis] < 0 ? bounds.min[axis] : center[axis]) as [number, number, number]);
      handle.mesh.position.copy(group.localToWorld(point));
      handle.mesh.quaternion.copy(viewingCamera.quaternion);
      const depth = -handle.mesh.position.clone().applyMatrix4(viewingCamera.matrixWorldInverse).z;
      handle.mesh.visible = depth > viewingCamera.near && depth < viewingCamera.far;
      const axes = DIRECTOR_RESIZE_AXES.filter((axis) => handle.signs[axis]);
      if (axes.length === 1) {
        const axis = new Vector3(axes[0] === "x" ? 1 : 0, axes[0] === "y" ? 1 : 0, axes[0] === "z" ? 1 : 0).applyQuaternion(group.quaternion);
        // An end-on axis has no meaningful screen motion; corners or an observer orbit remain available.
        if (Math.abs(axis.dot(viewingCamera.getWorldDirection(new Vector3()))) > 0.985) handle.mesh.visible = false;
      }
      const unitsPerPixel = 2 * Math.max(depth, viewingCamera.near) * Math.tan(viewingCamera.fov * Math.PI / 360) / Math.max(height, 1);
      handle.mesh.scale.setScalar(unitsPerPixel * 7);
    }
  };
  const fail = () => {
    if (disposed || unavailable) return;
    unavailable = true;
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
    const pointerId = drag?.id; drag = null;
    delete canvas.dataset.directorResizing;
    delete canvas.dataset.directorTransforming;
    if (pointerId !== undefined && canvas.hasPointerCapture(pointerId)) canvas.releasePointerCapture(pointerId);
    canvas.style.removeProperty("cursor");
    callbacks.onUnavailable();
  };
  renderer.debug.onShaderError = fail;
  const renderNow = (shooting = false) => {
    if (disposed || unavailable || renderer.getContext().isContextLost()) throw new Error("DIRECTOR_VIEWPORT_UNAVAILABLE");
    applyCamera();
    syncSelection();
    renderer.render(world, shooting ? camera : activeCamera());
    if (unavailable) throw new Error("DIRECTOR_VIEWPORT_UNAVAILABLE");
  };
  const invalidate = () => {
    if (disposed || unavailable || frame !== null) return;
    frame = requestAnimationFrame(() => {
      frame = null;
      try { renderNow(); } catch { fail(); }
    });
  };
  const update = (nextScene: DirectorScene, selectedObjectId?: string) => {
    if (disposed || unavailable) return;
    scene = nextScene;
    const transformingId = drag && drag.kind !== "orbit" ? drag.original.id : undefined;
    if (drag && transformingId && (selectedObjectId !== transformingId || !nextScene.objects.some((object) => object.id === transformingId))) {
      const pointerId = drag.id; drag = null;
      delete canvas.dataset.directorResizing;
      delete canvas.dataset.directorTransforming;
      canvas.style.removeProperty("cursor");
      if (canvas.hasPointerCapture(pointerId)) canvas.releasePointerCapture(pointerId);
    }
    selectedId = selectedObjectId;
    const wanted = new Set(nextScene.objects.map((object) => object.id));
    for (const [id, instance] of instances) if (!wanted.has(id)) {
      objects.remove(instance.group); disposeObjects(instance.group); instances.delete(id);
    }
    for (const object of nextScene.objects) {
      let instance = instances.get(object.id);
      if (instance && instance.kind !== object.kind) {
        objects.remove(instance.group); disposeObjects(instance.group); instances.delete(object.id); instance = undefined;
      }
      if (!instance) {
        const group = createObject(object);
        instance = { group, kind: object.kind, bounds: new Box3().setFromObject(group) };
        instances.set(object.id, instance); objects.add(group);
      }
      applyObjectTransform(instance.group, object);
    }
    applyCamera();
    syncSelection();
    invalidate();
  };
  const changeObject = (object: DirectorObject) => {
    scene = { ...scene, objects: scene.objects.map((item) => item.id === object.id ? object : item) };
    const instance = instances.get(object.id);
    if (instance) applyObjectTransform(instance.group, object);
    syncSelection(); invalidate(); callbacks.onObjectChange?.(object.id, object);
  };
  const finishObjectTransform = (revert: boolean) => {
    if (!drag || drag.kind === "orbit") return;
    const current = drag; drag = null;
    delete canvas.dataset.directorResizing;
    delete canvas.dataset.directorTransforming;
    if (canvas.hasPointerCapture(current.id)) canvas.releasePointerCapture(current.id);
    canvas.style.removeProperty("cursor");
    if (revert && scene.objects.some((object) => object.id === current.original.id)) changeObject(current.original);
  };
  const setPointerRay = (event: { clientX: number; clientY: number }) => {
    const bounds = canvas.getBoundingClientRect();
    if (bounds.width <= 0 || bounds.height <= 0) return false;
    raycaster.setFromCamera(new Vector2((event.clientX - bounds.left) / bounds.width * 2 - 1,
      1 - (event.clientY - bounds.top) / bounds.height * 2), activeCamera());
    return true;
  };
  const pickHandle = (event: { clientX: number; clientY: number }) => {
    if (!resizeHandles.visible || disabled || !callbacks.onObjectChange) return undefined;
    const bounds = canvas.getBoundingClientRect();
    if (bounds.width <= 0 || bounds.height <= 0) return undefined;
    let picked: typeof handles[number] | undefined;
    let shortest = 10;
    for (const handle of handles) {
      if (!handle.mesh.visible) continue;
      const point = handle.mesh.position.clone().project(activeCamera());
      const distance = Math.hypot((point.x + 1) * bounds.width / 2 + bounds.left - event.clientX,
        (1 - point.y) * bounds.height / 2 + bounds.top - event.clientY);
      if (distance < shortest) { picked = handle; shortest = distance; }
    }
    return picked;
  };
  const beginResize = (event: PointerEvent) => {
    if (objectTool !== "resize" || event.button !== 0 || event.shiftKey) return false;
    const handle = pickHandle(event);
    const object = scene.objects.find((item) => item.id === selectedId);
    const instance = selectedId ? instances.get(selectedId) : undefined;
    if (!handle || !object || !instance || !setPointerRay(event)) return false;
    const axes = DIRECTOR_RESIZE_AXES.filter((axis) => handle.signs[axis]);
    const normal = activeCamera().getWorldDirection(new Vector3());
    if (axes.length === 1) {
      const axis = new Vector3(axes[0] === "x" ? 1 : 0, axes[0] === "y" ? 1 : 0, axes[0] === "z" ? 1 : 0).applyQuaternion(instance.group.quaternion);
      normal.addScaledVector(axis, -normal.dot(axis));
      if (normal.lengthSq() < 0.0001) return false;
    }
    const plane = new Plane().setFromNormalAndCoplanarPoint(normal.normalize(), handle.mesh.position);
    const startPoint = raycaster.ray.intersectPlane(plane, new Vector3());
    if (!startPoint) return false;
    drag = { kind: "resize", id: event.pointerId, original: structuredClone(object),
      bounds: { min: { x: instance.bounds.min.x, y: instance.bounds.min.y, z: instance.bounds.min.z },
        max: { x: instance.bounds.max.x, y: instance.bounds.max.y, z: instance.bounds.max.z } },
      handle: handle.signs, plane, startPoint };
    // The owning dialog lets Escape reach the viewport while this local gesture is active.
    canvas.dataset.directorResizing = "true";
    canvas.dataset.directorTransforming = "true";
    canvas.style.cursor = "nwse-resize";
    callbacks.onSelectObject?.(object.id);
    return true;
  };
  const screenPoint = (point: Vector3) => {
    const bounds = canvas.getBoundingClientRect();
    const projected = point.clone().project(activeCamera());
    return new Vector2((projected.x + 1) * bounds.width / 2 + bounds.left,
      (1 - projected.y) * bounds.height / 2 + bounds.top);
  };
  const distanceToSegment = (point: Vector2, start: Vector2, end: Vector2) => {
    const direction = end.clone().sub(start);
    const length = direction.lengthSq();
    const fraction = length ? clamp(point.clone().sub(start).dot(direction) / length, 0, 1) : 0;
    return point.distanceTo(start.clone().addScaledVector(direction, fraction));
  };
  const pickMoveAxis = (event: { clientX: number; clientY: number }) => {
    if (!moveHandles.visible || disabled || !callbacks.onObjectChange) return undefined;
    const pointer = new Vector2(event.clientX, event.clientY);
    let selected: "x" | "y" | "z" | undefined;
    let closest = 10;
    for (const handle of axisHandles) {
      if (!handle.root.visible) continue;
      const direction = new Vector3(handle.axis === "x" ? 1 : 0, handle.axis === "y" ? 1 : 0, handle.axis === "z" ? 1 : 0);
      const start = screenPoint(moveHandles.position.clone().addScaledVector(direction, axisLength * 0.3));
      const end = screenPoint(moveHandles.position.clone().addScaledVector(direction, axisLength));
      const distance = distanceToSegment(pointer, start, end);
      if (distance < closest) { selected = handle.axis; closest = distance; }
    }
    return selected;
  };
  const pickRotationRing = (event: { clientX: number; clientY: number }) => {
    if (!rotateHandles.visible || disabled || !callbacks.onObjectChange) return false;
    const pointer = new Vector2(event.clientX, event.clientY);
    const pointAt = (angle: number) => {
      const point = rotateHandles.position.clone().add(new Vector3(Math.sin(angle) * rotationRadius, 0, Math.cos(angle) * rotationRadius));
      const depth = -point.clone().applyMatrix4(activeCamera().matrixWorldInverse).z;
      return { screen: screenPoint(point), visible: depth > activeCamera().near && depth < activeCamera().far };
    };
    let previous = pointAt(0);
    for (let index = 1; index <= 80; index++) {
      const point = pointAt(index / 80 * Math.PI * 2);
      if (previous.visible && point.visible && distanceToSegment(pointer, previous.screen, point.screen) < 10) return true;
      previous = point;
    }
    return false;
  };
  const pickScene = (event: { clientX: number; clientY: number }):
    { kind: "object"; object: DirectorObject; point: Vector3 } | { kind: "camera" } | undefined => {
    if (!setPointerRay(event)) return undefined;
    world.updateMatrixWorld(true);
    const hit = raycaster.intersectObjects(viewMode === "director" ? [...objects.children, cameraRig] : objects.children, true)[0];
    let node = hit?.object;
    while (node) {
      if (node.userData.directorCamera === true) return { kind: "camera" };
      const id: unknown = node.userData.directorObjectId;
      const object = scene.objects.find((item) => item.id === id);
      if (object && hit) return { kind: "object", object, point: hit.point };
      node = node.parent ?? undefined;
    }
    return undefined;
  };
  const activateObjectDrag = (next: MoveDrag | RotateDrag) => {
    drag = next;
    selectedId = next.original.id;
    canvas.dataset.directorTransforming = "true";
    delete canvas.dataset.directorResizing;
    canvas.style.cursor = next.kind === "move" ? "grabbing" : "ew-resize";
    syncSelection(); invalidate(); callbacks.onSelectObject?.(next.original.id);
  };
  const beginMove = (event: PointerEvent, object: DirectorObject, axis: DirectorMoveAxis, point: Vector3) => {
    if (!callbacks.onObjectChange || !setPointerRay(event)) return false;
    const normal = new Vector3(0, 1, 0);
    if (axis !== "xz") {
      normal.copy(activeCamera().getWorldDirection(new Vector3()));
      const direction = new Vector3(axis === "x" ? 1 : 0, axis === "y" ? 1 : 0, axis === "z" ? 1 : 0);
      normal.addScaledVector(direction, -normal.dot(direction));
      if (normal.lengthSq() < 0.0001) return false;
    } else if (Math.abs(activeCamera().getWorldDirection(new Vector3()).y) < 0.2 || Math.abs(raycaster.ray.direction.y) < 0.05) {
      // At eye level the ground plane is edge-on; screen-plane motion still preserves world height.
      normal.copy(activeCamera().getWorldDirection(new Vector3()));
    }
    const plane = new Plane().setFromNormalAndCoplanarPoint(normal.normalize(), point);
    const startPoint = raycaster.ray.intersectPlane(plane, new Vector3());
    if (!startPoint) return false;
    activateObjectDrag({ kind: "move", id: event.pointerId, original: structuredClone(object), axis, plane, startPoint,
      startX: event.clientX, startY: event.clientY, moved: false });
    return true;
  };
  const beginRotate = (event: PointerEvent, object: DirectorObject, ring: boolean) => {
    if (!callbacks.onObjectChange || !setPointerRay(event)) return false;
    const center = rotateHandles.position.clone();
    let plane: Plane | null = ring ? new Plane().setFromNormalAndCoplanarPoint(new Vector3(0, 1, 0), center) : null;
    const intersection = plane && Math.abs(raycaster.ray.direction.y) > 0.03
      ? raycaster.ray.intersectPlane(plane, new Vector3()) : null;
    if (!intersection || intersection.distanceToSquared(center) < 0.0001) plane = null;
    const angle = intersection ? Math.atan2(intersection.x - center.x, intersection.z - center.z) * 180 / Math.PI : 0;
    activateObjectDrag({ kind: "rotate", id: event.pointerId, original: structuredClone(object), plane, center,
      lastAngle: angle, accumulated: 0, startX: event.clientX, startY: event.clientY, moved: false });
    return true;
  };
  const beginObjectTransform = (event: PointerEvent) => {
    if (event.button !== 0 || event.shiftKey || !callbacks.onObjectChange) return false;
    if (beginResize(event)) return true;
    const selected = scene.objects.find((object) => object.id === selectedId);
    if (objectTool === "move") {
      const axis = pickMoveAxis(event);
      if (axis && selected && beginMove(event, selected, axis, moveHandles.position)) return true;
    }
    if (objectTool === "rotate" && selected && pickRotationRing(event) && beginRotate(event, selected, true)) return true;
    if (objectTool === "resize") return false;
    const hit = pickScene(event);
    if (hit?.kind !== "object") return false;
    return objectTool === "move" ? beginMove(event, hit.object, "xz", hit.point) : beginRotate(event, hit.object, false);
  };
  const hoverCursor = (event: { clientX: number; clientY: number }) => {
    if (pickHandle(event)) return "nwse-resize";
    if (pickMoveAxis(event)) return "move";
    if (pickRotationRing(event)) return "ew-resize";
    if (callbacks.onObjectChange && objectTool !== "resize" && pickScene(event)?.kind === "object") {
      return objectTool === "move" ? "grab" : "ew-resize";
    }
    return "";
  };
  const changeCamera = (nextCamera: DirectorScene["camera"]) => {
    if (viewMode === "director") observerPose = nextCamera;
    else scene = { ...scene, camera: nextCamera };
    applyCamera();
    invalidate();
    if (viewMode === "camera") callbacks.onCameraChange(nextCamera);
  };
  const pointerDown = (event: PointerEvent) => {
    if (disabled || unavailable || (event.button !== 0 && event.button !== 1 && event.button !== 2) || drag) return;
    event.preventDefault();
    canvas.focus({ preventScroll: true });
    canvas.setPointerCapture(event.pointerId);
    if (beginObjectTransform(event)) return;
    drag = { kind: "orbit", id: event.pointerId, x: event.clientX, y: event.clientY, startX: event.clientX, startY: event.clientY,
      pan: event.shiftKey || event.button !== 0, moved: false };
  };
  const pointerMove = (event: PointerEvent) => {
    if (disabled || unavailable) return;
    if (!drag) { canvas.style.cursor = hoverCursor(event); return; }
    if (drag.id !== event.pointerId) return;
    if (drag.kind === "resize") {
      if (!setPointerRay(event)) return;
      const point = raycaster.ray.intersectPlane(drag.plane, new Vector3());
      if (!point) return;
      const delta = point.sub(drag.startPoint);
      changeObject(resizeDirectorObject(drag.original, drag.bounds, drag.handle, delta));
      return;
    }
    if (drag.kind === "move" || drag.kind === "rotate") {
      drag.moved ||= Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) > 3;
      if (!drag.moved || !setPointerRay(event)) return;
      if (drag.kind === "move") {
        const point = raycaster.ray.intersectPlane(drag.plane, new Vector3());
        if (point) changeObject(moveDirectorObject(drag.original, point.sub(drag.startPoint), drag.axis));
      } else {
        if (drag.plane) {
          const point = raycaster.ray.intersectPlane(drag.plane, new Vector3());
          if (!point || point.distanceToSquared(drag.center) < 0.0001) return;
          const angle = Math.atan2(point.x - drag.center.x, point.z - drag.center.z) * 180 / Math.PI;
          drag.accumulated += wrapDirectorRotation(angle - drag.lastAngle); drag.lastAngle = angle;
        } else drag.accumulated = (event.clientX - drag.startX) * 0.7;
        changeObject(rotateDirectorObject(drag.original, drag.accumulated));
      }
      return;
    }
    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    drag.x = event.clientX;
    drag.y = event.clientY;
    drag.moved ||= Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) > 4;
    if (!drag.moved) return;
    const pose = activePose();
    const viewingCamera = activeCamera();
    if (drag.pan) {
      const units = 2 * pose.distance * Math.tan(viewingCamera.fov * Math.PI / 360) / Math.max(height, 1);
      const offset = new Vector3().setFromMatrixColumn(viewingCamera.matrixWorld, 0).multiplyScalar(-dx * units)
        .add(new Vector3().setFromMatrixColumn(viewingCamera.matrixWorld, 1).multiplyScalar(dy * units));
      const targetLimit = viewMode === "director" ? 100 : 20;
      changeCamera({ ...pose, target: {
        x: clamp(pose.target.x + offset.x, -targetLimit, targetLimit), y: clamp(pose.target.y + offset.y, -targetLimit, targetLimit),
        z: clamp(pose.target.z + offset.z, -targetLimit, targetLimit),
      } });
    } else {
      changeCamera({ ...pose, azimuth: wrapAzimuth(pose.azimuth - dx * 0.35),
        elevation: clamp(pose.elevation + dy * 0.25, -80, 80) });
    }
  };
  const pointerUp = (event: PointerEvent) => {
    if (!drag || drag.id !== event.pointerId) return;
    if (drag.kind !== "orbit") { finishObjectTransform(event.type !== "pointerup"); return; }
    const select = !drag.moved && !drag.pan && event.type === "pointerup" && !disabled && !unavailable;
    drag = null;
    if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
    if (!select || (!callbacks.onSelectObject && !callbacks.onSelectCamera)) return;
    const bounds = canvas.getBoundingClientRect();
    if (bounds.width <= 0 || bounds.height <= 0) return;
    raycaster.setFromCamera(new Vector2((event.clientX - bounds.left) / bounds.width * 2 - 1,
      1 - (event.clientY - bounds.top) / bounds.height * 2), activeCamera());
    world.updateMatrixWorld(true);
    let hit = raycaster.intersectObjects(viewMode === "director" ? [...objects.children, cameraRig] : objects.children, true)[0]?.object;
    while (hit) {
      if (hit.userData.directorCamera === true) { callbacks.onSelectCamera?.(); break; }
      const id: unknown = hit.userData.directorObjectId;
      if (typeof id === "string") { callbacks.onSelectObject?.(id); break; }
      hit = hit.parent ?? undefined;
    }
  };
  const wheel = (event: WheelEvent) => {
    if (disabled || unavailable || (drag && drag.kind !== "orbit")) return;
    event.preventDefault();
    const delta = event.deltaY * (event.deltaMode === WheelEvent.DOM_DELTA_LINE ? 16 : event.deltaMode === WheelEvent.DOM_DELTA_PAGE ? height : 1);
    const pose = activePose();
    changeCamera({ ...pose, distance: clamp(pose.distance * Math.exp(clamp(delta * 0.0015, -1, 1)), 1, viewMode === "director" ? 200 : 50) });
  };
  const keyDown = (event: KeyboardEvent) => {
    if (drag && drag.kind !== "orbit") {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); finishObjectTransform(true); }
      return;
    }
    if (disabled || unavailable || event.altKey || event.ctrlKey || event.metaKey) return;
    const pose = activePose();
    const maxDistance = viewMode === "director" ? 200 : 50;
    let nextCamera: DirectorScene["camera"];
    switch (event.key) {
      case "ArrowLeft": nextCamera = { ...pose, azimuth: wrapAzimuth(pose.azimuth - 5) }; break;
      case "ArrowRight": nextCamera = { ...pose, azimuth: wrapAzimuth(pose.azimuth + 5) }; break;
      case "ArrowUp": nextCamera = { ...pose, elevation: clamp(pose.elevation + 5, -80, 80) }; break;
      case "ArrowDown": nextCamera = { ...pose, elevation: clamp(pose.elevation - 5, -80, 80) }; break;
      case "+": case "=": nextCamera = { ...pose, distance: clamp(pose.distance * 0.9, 1, maxDistance) }; break;
      case "-": nextCamera = { ...pose, distance: clamp(pose.distance * 1.1, 1, maxDistance) }; break;
      default: return;
    }
    event.preventDefault();
    event.stopPropagation();
    changeCamera(nextCamera);
  };
  const contextMenu = (event: Event) => event.preventDefault();
  const pointerLeave = () => { if (!drag) canvas.style.removeProperty("cursor"); };
  const contextLost = (event: Event) => { event.preventDefault(); fail(); };
  canvas.addEventListener("pointerdown", pointerDown);
  canvas.addEventListener("pointermove", pointerMove);
  canvas.addEventListener("pointerup", pointerUp);
  canvas.addEventListener("pointercancel", pointerUp);
  canvas.addEventListener("pointerleave", pointerLeave);
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
    const pointerId = drag?.id; drag = null;
    delete canvas.dataset.directorResizing;
    delete canvas.dataset.directorTransforming;
    if (pointerId !== undefined && canvas.hasPointerCapture(pointerId)) canvas.releasePointerCapture(pointerId);
    canvas.removeEventListener("pointerdown", pointerDown);
    canvas.removeEventListener("pointermove", pointerMove);
    canvas.removeEventListener("pointerup", pointerUp);
    canvas.removeEventListener("pointercancel", pointerUp);
    canvas.removeEventListener("pointerleave", pointerLeave);
    canvas.removeEventListener("lostpointercapture", pointerUp);
    canvas.removeEventListener("wheel", wheel);
    canvas.removeEventListener("keydown", keyDown);
    canvas.removeEventListener("contextmenu", contextMenu);
    canvas.removeEventListener("webglcontextlost", contextLost);
    selection.geometry.dispose();
    selection.material.dispose();
    world.remove(selection);
    disposeObjects(world);
    instances.clear();
    canvas.style.removeProperty("cursor");
    renderer.dispose();
    if (!renderer.getContext().isContextLost()) renderer.forceContextLoss();
  };
  try {
    update(initialScene);
    renderNow();
  } catch (error) { dispose(); throw error; }

  return {
    update,
    setViewMode(mode) {
      if (disposed || unavailable || mode === viewMode) return;
      finishObjectTransform(false);
      if (drag && canvas.hasPointerCapture(drag.id)) canvas.releasePointerCapture(drag.id);
      drag = null;
      viewMode = mode;
      cameraRig.visible = mode === "director";
      cameraFrustum.visible = mode === "director";
      invalidate();
    },
    setObjectTool(tool) {
      if (disposed || unavailable || tool === objectTool) return;
      finishObjectTransform(false);
      const pointerId = drag?.id; drag = null;
      if (pointerId !== undefined && canvas.hasPointerCapture(pointerId)) canvas.releasePointerCapture(pointerId);
      canvas.style.removeProperty("cursor");
      objectTool = tool;
      syncSelection(); invalidate();
    },
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
      if (disabled) finishObjectTransform(false);
      if (disabled && drag) {
        if (canvas.hasPointerCapture(drag.id)) canvas.releasePointerCapture(drag.id);
        drag = null;
      }
      syncSelection(); invalidate();
    },
    async capture() {
      if (disposed || unavailable || renderer.getContext().isContextLost()) throw new Error("DIRECTOR_VIEWPORT_UNAVAILABLE");
      finishObjectTransform(false);
      const size = DIRECTOR_CAPTURE_SIZES[scene.aspectRatio];
      // Copy synchronously before restoring the preview; no preserveDrawingBuffer or continuous loop is needed.
      const output = document.createElement("canvas");
      output.width = size.width;
      output.height = size.height;
      const context = output.getContext("2d");
      if (!context) throw new Error("DIRECTOR_CAPTURE_FAILED");
      const selectionVisible = selection.visible;
      const rigVisible = cameraRig.visible;
      const frustumVisible = cameraFrustum.visible;
      const handlesVisible = resizeHandles.visible;
      const moveVisible = moveHandles.visible;
      const rotateVisible = rotateHandles.visible;
      try {
        // No editor helpers enter archived PNGs; restore even if drawing or encoding fails.
        capturing = true;
        selection.visible = false;
        resizeHandles.visible = false;
        moveHandles.visible = false;
        rotateHandles.visible = false;
        cameraRig.visible = false;
        cameraFrustum.visible = false;
        renderer.setPixelRatio(1);
        renderer.setSize(size.width, size.height, false);
        renderNow(true);
        context.drawImage(canvas, 0, 0);
      } finally {
        capturing = false;
        selection.visible = selectionVisible;
        cameraRig.visible = rigVisible;
        cameraFrustum.visible = frustumVisible;
        resizeHandles.visible = handlesVisible;
        moveHandles.visible = moveVisible;
        rotateHandles.visible = rotateVisible;
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
