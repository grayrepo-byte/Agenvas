import { ArrowClockwise,ArrowCounterClockwise,ArrowsOut,ArrowsOutSimple,Camera,CaretDown,Circle,Cube,DownloadSimple,FolderSimple,ImageSquare,Plus,PersonSimple,SelectionAll,SlidersHorizontal,Sparkle,Trash,UploadSimple,X } from "@/shared/ui/icons";
import { useMutation,useQueryClient } from "@tanstack/react-query";
import { useCallback,useRef,useState } from "react";
import { HTTP_STATUS,ApiError,runImageOperation,uploadCanvasItemVersion,uploadImageAsset,
  type RunImageOperationRequest,type UploadCanvasItemVersionRequest } from "../../shared/api/client";
import { t,useLocale,type MessageKey } from "../../shared/i18n";
import { CreatorNumberInput } from "../../shared/ui/CreatorNumberInput";
import { Select } from "../../shared/ui/Select";
import { Alert,AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { Button } from "../../shared/ui/primitives/button";
import { Dialog,DialogContent,DialogDescription,DialogTitle } from "../../shared/ui/primitives/dialog";
import { DropdownMenu,DropdownMenuContent,DropdownMenuGroup,DropdownMenuItem,DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";
import { Field,FieldGroup,FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { ToggleGroup,ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { Tabs,TabsContent,TabsList,TabsTrigger } from "../../shared/ui/primitives/tabs";
import { ImageFunctionConfiguration } from "./ImageFunctionConfiguration";
import { DirectorViewport } from "./DirectorViewport";
import type { DirectorObjectTool } from "./directorRenderer";
import { createDirectorScene,DIRECTOR_ASPECT_RATIOS,DIRECTOR_OBJECT_KINDS,directorCameraPrompt,directorViewAngle,
  parseDirectorScene,type DirectorObject,type DirectorScene } from "./directorScene";
import "./DirectorWorkbench.css";

const OBJECT_LABELS: Record<DirectorObject["kind"], MessageKey> = {
  ACTOR: "director.actor", BOX: "director.box", SPHERE: "director.sphere", CYLINDER: "director.cylinder",
};
const MAX_OBJECTS = 32;
const MAX_IMPORT_BYTES = 128 * 1024;
const OBJECT_ICONS = { ACTOR: PersonSimple, BOX: Cube, SPHERE: Circle, CYLINDER: Cube } as const;
const CAMERA_PRESETS = [
  { value: "front", label: "media.card.front", caption: "director.presetFront", azimuth: 0 },
  { value: "quarter", label: "media.card.rightThreeQuarter", caption: "director.presetQuarter", azimuth: 45 },
  { value: "side", label: "media.card.rightProfile", caption: "director.presetSide", azimuth: 90 },
  { value: "back", label: "media.card.back", caption: "director.presetBack", azimuth: 180 },
] as const;
const LENS_PRESETS = [24, 35, 50, 85] as const;
type AngleSubmission = Pick<RunImageOperationRequest,"parameters"|"instruction"|"expectedFunctionVersion"|"expectedCapabilityVersion">;
type Props = {
  projectId: string; artifactId: string; canvasItemId: string; expectedVersion: number; sourceVersionId: string | null;
  sourceTitle: string; sourceUrl?: string; initialScene?: unknown; onClose: () => void;
};

/** One photo pins the exact source, scene and command identity through upload and response-loss retries. */
export function DirectorWorkbench(props: Props) {
  useLocale();
  const [source] = useState(props);
  const [initial] = useState(() => {
    const saved = parseDirectorScene(source.initialScene);
    if (saved) return saved;
    const fresh = createDirectorScene();
    return { ...fresh, objects: fresh.objects.map((object, index) => ({ ...object, name: `${t(OBJECT_LABELS[object.kind])} ${index + 1}` })) };
  });
  const [scene, setScene] = useState(initial);
  const [selectedId, setSelectedId] = useState(initial.objects[0]?.id ?? "");
  const [mode, setMode] = useState("scene");
  const [inspectorTab, setInspectorTab] = useState("camera");
  const [advancedCamera, setAdvancedCamera] = useState(false);
  const [showGuides, setShowGuides] = useState(true);
  const [viewMode, setViewMode] = useState<"director" | "camera">("director");
  const [objectTool, setObjectTool] = useState<DirectorObjectTool>("move");
  const [showPrompt, setShowPrompt] = useState(false);
  const [ready, setReady] = useState(false);
  const [viewportError, setViewportError] = useState<string | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const [confirmClose, setConfirmClose] = useState(false);
  const [importing, setImporting] = useState(false);
  const importInput = useRef<HTMLInputElement>(null);
  const capture = useRef<(() => Promise<File>) | null>(null);
  const attempt = useRef<{ file: File; scene: DirectorScene; assetId?: string; request?: UploadCanvasItemVersionRequest } | null>(null);
  const angleAttempt = useRef<{ fingerprint: string; key: string; request: RunImageOperationRequest } | null>(null);
  const previousFocus = useRef(document.activeElement);
  const client = useQueryClient();
  const refresh = async () => {
    await Promise.all(["canvas", "canvas-connections", "snapshot", "artifact-versions", "artifacts", "canvas-media-versions", "media-draft", "direct-media-tasks"]
      .map((key) => client.invalidateQueries({ queryKey: [key, source.projectId] })));
  };
  const save = useMutation({
    mutationFn: async () => {
      if (!attempt.current) {
        if (!capture.current) throw new Error(t("director.viewportUnavailable"));
        attempt.current = { file: await capture.current(), scene: structuredClone(scene) };
      }
      const pending = attempt.current;
      if (!pending.assetId) pending.assetId = (await uploadImageAsset(source.projectId, pending.file)).id;
      if (!pending.request) pending.request = {
        targetItemId: source.sourceVersionId ? crypto.randomUUID() : source.canvasItemId,
        expectedVersion: source.expectedVersion, sourceVersionId: source.sourceVersionId,
        purpose: "DIRECTOR_3D", directorScene: pending.scene,
        content: { sourceType: "UPLOAD", assetId: pending.assetId },
      };
      return uploadCanvasItemVersion(source.projectId, source.canvasItemId, pending.request);
    },
    onSuccess: async () => { await refresh(); source.onClose(); },
  });
  const angle = useMutation({
    mutationFn: async (input: AngleSubmission) => {
      if (!source.sourceVersionId) throw new Error(t("director.imageRequired"));
      const fingerprint = JSON.stringify(input);
      if (angleAttempt.current?.fingerprint !== fingerprint) angleAttempt.current = { fingerprint, key: crypto.randomUUID(),
        request: { ...input, operation: "VIEW_ANGLE", canvasItemId: source.canvasItemId,
          sourceVersionId: source.sourceVersionId, expectedCanvasItemVersion: source.expectedVersion } };
      return runImageOperation(source.projectId, source.artifactId, angleAttempt.current.request, angleAttempt.current.key);
    },
    onSuccess: async () => { await refresh(); source.onClose(); },
  });
  const busy = save.isPending || angle.isPending || importing;
  const selected = scene.objects.find((object) => object.id === selectedId);
  const activeObjectTool = selected ? objectTool : "move";
  const dirty = JSON.stringify(initial) !== JSON.stringify(scene);
  const error = save.error ?? angle.error;
  const message = localError ?? (error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT
    ? t("director.sourceConflict") : error?.message) ?? (ready ? viewportError : null);
  const captureReady = useCallback((fn: (() => Promise<File>) | null) => { capture.current = fn; setReady(Boolean(fn)); }, []);
  function change(next: DirectorScene) {
    if (busy) return;
    attempt.current = null; angleAttempt.current = null;
    save.reset(); angle.reset(); setLocalError(null); setConfirmClose(false); setScene(next);
  }
  function cameraChange(camera: DirectorScene["camera"]) { change({ ...scene, camera }); }
  function patchObject(patch: Partial<DirectorObject>) {
    change({ ...scene, objects: scene.objects.map((object) => object.id === selectedId ? { ...object, ...patch } : object) });
  }
  /** A renderer drag belongs to its pinned object, even if the inspector selection has changed. */
  function objectChange(id: string, next: DirectorObject) {
    if (busy || next.id !== id || !scene.objects.some((object) => object.id === id)) return;
    const candidate = parseDirectorScene({ ...scene, objects: scene.objects.map((object) => object.id === id ? next : object) });
    if (candidate) change(candidate);
  }
  function selectObject(id: string) { setSelectedId(id); setInspectorTab("object"); }
  function addObject(kind: DirectorObject["kind"]) {
    if (busy || scene.objects.length >= MAX_OBJECTS) return;
    const index = scene.objects.length;
    const object: DirectorObject = { id: crypto.randomUUID(), name: `${t(OBJECT_LABELS[kind])} ${index + 1}`,
      kind, position: { x: index % 2 ? 1.5 : -1.5, y: 0, z: Math.floor(index / 2) * 0.8 },
      rotation: 0, scale: { x: 1, y: 1, z: 1 }, color: "#94A3B8" };
    change({ ...scene, objects: [...scene.objects, object] }); selectObject(object.id);
  }
  function focusSelected() {
    if (!selected) return;
    cameraChange({ ...scene.camera, target: { ...selected.position,
      y: Math.min(20, selected.position.y + (selected.kind === "ACTOR" ? 0.9 : 0.5) * selected.scale.y) } });
  }
  function close() { if (!busy) { if (dirty || save.error) setConfirmClose(true); else source.onClose(); } }
  async function importScene(file: File) {
    setImporting(true); setLocalError(null);
    try {
      if (file.size > MAX_IMPORT_BYTES) throw new Error(t("director.invalidScene"));
      const imported = parseDirectorScene(JSON.parse(await file.text()));
      if (!imported) throw new Error(t("director.invalidScene"));
      attempt.current = null; angleAttempt.current = null; save.reset(); angle.reset();
      setScene(imported); setSelectedId(imported.objects[0]?.id ?? "");
    } catch { setLocalError(t("director.invalidScene")); }
    finally { setImporting(false); }
  }
  function exportScene() {
    const url = URL.createObjectURL(new Blob([JSON.stringify(scene, null, 2)], { type: "application/json" }));
    const link = document.createElement("a"); link.href = url; link.download = "director-scene.json"; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }
  const hasReference = Boolean(source.sourceVersionId && source.sourceUrl);
  const SelectedIcon = selected ? OBJECT_ICONS[selected.kind] : Cube;
  const activePreset = CAMERA_PRESETS.find((preset) => Math.abs((scene.camera.azimuth - preset.azimuth + 540) % 360 - 180) < 0.5 && Math.abs(scene.camera.elevation - 12) < 0.5)?.value ?? "";
  const sceneStatus = dirty ? t("director.sceneStatusModified") : source.initialScene ? t("director.sceneStatusRestored") : t("director.sceneStatusReady");
  return <Dialog open onOpenChange={(open) => { if (!open) close(); }}>
    <DialogContent fullscreen showCloseButton={false} className="director-workbench nodrag nopan nowheel"
      onPointerDown={(event) => event.stopPropagation()} onClick={(event) => event.stopPropagation()}
      onDoubleClick={(event) => event.stopPropagation()} onWheel={(event) => event.stopPropagation()}
      onKeyDown={(event) => event.stopPropagation()}
      onEscapeKeyDown={(event) => {
        event.preventDefault();
        // Radix observes Escape before the canvas; let its active drag consume and roll back the gesture.
        if (event.target instanceof HTMLCanvasElement && (event.target.dataset.directorTransforming === "true"
          || event.target.dataset.directorResizing === "true")) return;
        event.stopPropagation(); close();
      }}
      onPointerDownOutside={(event) => event.preventDefault()}
      onCloseAutoFocus={(event) => { event.preventDefault(); if (previousFocus.current instanceof HTMLElement && previousFocus.current.isConnected) previousFocus.current.focus(); }}>
      <header className="director-header">
        <div className="director-brand"><span className="director-brand-icon"><Cube /></span>
          <div><DialogTitle>{t("director.title")}</DialogTitle><DialogDescription title={source.sourceTitle}>{source.sourceTitle}</DialogDescription></div>
        </div>
        {hasReference ? <ToggleGroup type="single" value={mode} variant="outline" size="sm" className="director-modes" aria-label={t("director.mode")}
          disabled={busy} onValueChange={(value) => { if (value) { setMode(value); setInspectorTab("camera"); } }}>
          <ToggleGroupItem value="scene"><Cube />{t("director.sceneMode")}</ToggleGroupItem>
          <ToggleGroupItem value="angle"><ImageSquare />{t("director.angleMode")}</ToggleGroupItem>
        </ToggleGroup> : <span className="director-mode-label">{t("director.sceneMode")}</span>}
        <div className="director-actions">
          <DropdownMenu><DropdownMenuTrigger asChild><Button variant="ghost" size="sm" disabled={busy} aria-label={t("director.fileMenu")}>
            <FolderSimple data-icon="inline-start" /><span className="director-file-label">{t("director.fileMenu")}</span><CaretDown data-icon="inline-end" />
          </Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuGroup>
            <DropdownMenuItem disabled={busy} onSelect={() => importInput.current?.click()}><UploadSimple />{t("director.import")}</DropdownMenuItem>
            <DropdownMenuItem disabled={busy} onSelect={exportScene}><DownloadSimple />{t("director.export")}</DropdownMenuItem>
          </DropdownMenuGroup></DropdownMenuContent></DropdownMenu>
          {mode === "scene" ? <Button data-director-action="capture" disabled={busy || (!ready && !attempt.current)} onClick={() => save.mutate()}>
            <Camera data-icon="inline-start" />{save.isPending ? t("director.saving") : save.error ? t("common.retrySave") : t("director.capture")}</Button>
            : <Badge variant="secondary" className="director-ai-badge"><Sparkle />{t("director.angleActionHint")}</Badge>}
          <Button variant="ghost" size="icon-sm" aria-label={t("common.close")} disabled={busy} onClick={close}><X /></Button>
        </div>
        <Input ref={importInput} type="file" accept=".json,application/json" hidden aria-label={t("director.import")}
          disabled={busy} onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ""; if (file) void importScene(file); }} />
      </header>
      {confirmClose ? <Alert className="director-notice"><AlertDescription>
        <p>{t("director.unsaved")}</p><div className="director-actions">
          <Button variant="outline" size="sm" onClick={() => setConfirmClose(false)}>{t("director.keepEditing")}</Button>
          <Button variant="destructive" size="sm" onClick={source.onClose}>{t("director.discard")}</Button>
        </div></AlertDescription></Alert> : null}
      {message ? <Alert variant="destructive" className="director-notice"><AlertDescription>{message}</AlertDescription></Alert> : null}
      <div className="director-body">
        <aside className="director-scene-panel" aria-label={mode === "scene" ? t("director.sceneHeading") : t("director.referenceHeading")}>
          <div className="director-panel-heading"><div><span className="director-eyebrow">{mode === "scene" ? t("director.sceneMode") : t("director.angleMode")}</span>
            <h3>{mode === "scene" ? t("director.sceneHeading") : t("director.referenceHeading")}</h3></div>
            {mode === "scene" ? <Badge variant="outline">{scene.objects.length}/{MAX_OBJECTS}</Badge> : <ImageSquare aria-hidden />}
          </div>
          {mode === "scene" ? <>
            <DropdownMenu><DropdownMenuTrigger asChild><Button variant="outline" size="sm" className="director-add-object" aria-label={t("director.addObject")}
              disabled={busy || scene.objects.length >= MAX_OBJECTS}><Plus data-icon="inline-start" />{t("director.addObject")}<CaretDown data-icon="inline-end" /></Button></DropdownMenuTrigger>
              <DropdownMenuContent align="start"><DropdownMenuGroup>{DIRECTOR_OBJECT_KINDS.map((kind) => {
                const Icon = OBJECT_ICONS[kind];
                return <DropdownMenuItem key={kind} disabled={busy || scene.objects.length >= MAX_OBJECTS} onSelect={() => addObject(kind)}><Icon />{t(OBJECT_LABELS[kind])}</DropdownMenuItem>;
              })}</DropdownMenuGroup></DropdownMenuContent>
            </DropdownMenu>
            <div className="director-object-list" role="group" aria-label={t("director.selectedObject")}>
              {scene.objects.map((object) => { const Icon = OBJECT_ICONS[object.kind]; return <Button key={object.id} variant="ghost"
                className="director-object-row" disabled={busy} aria-pressed={selectedId === object.id} onClick={() => selectObject(object.id)}>
                <span className="director-object-icon"><Icon /></span><span className="director-object-copy"><strong>{object.name}</strong><small>{t(OBJECT_LABELS[object.kind])}</small></span>
                <span className="director-material-dot" style={{ backgroundColor: object.color }} aria-hidden />
              </Button>; })}
              {!scene.objects.length ? <p className="director-empty-text">{t("director.emptyScene")}</p> : null}
            </div>
            <div className="director-scene-status"><span className={dirty ? "director-status-dot is-modified" : "director-status-dot"} aria-hidden /><span>{sceneStatus}</span></div>
          </> : <div className="director-reference-panel">
            <div className="director-reference-image"><img src={source.sourceUrl} alt={source.sourceTitle} /></div>
            <strong className="director-reference-title">{source.sourceTitle}</strong><p>{t("director.referenceDescription")}</p>
            <div className="director-reference-note"><ImageSquare aria-hidden /><p>{t("director.angleHint")}</p></div>
          </div>}
          <p className="director-panel-footnote">{mode === "scene" ? t("director.sceneDescription") : t("director.angleActionHint")}</p>
        </aside>
        <section className="director-stage" aria-label={t("director.viewportLabel")}>
          <div className="director-stage-toolbar"><ToggleGroup type="single" value={viewMode} size="sm" className="director-view-switch" aria-label={t("director.viewMode")} disabled={busy}
            onValueChange={(value) => { if (value === "director" || value === "camera") setViewMode(value); }}>
            <ToggleGroupItem value="director"><Cube />{t("director.directorView")}</ToggleGroupItem>
            <ToggleGroupItem value="camera"><Camera />{t("director.viewportCamera")}</ToggleGroupItem>
          </ToggleGroup>
            <ToggleGroup type="single" value={activeObjectTool} size="sm" spacing={1} className="director-object-tools"
              aria-label={t("director.objectTools")} disabled={busy} onValueChange={(value) => {
                if (value === "move" || value === "rotate" || value === "resize") setObjectTool(value);
              }}>
              <ToggleGroupItem value="move" title={t("director.moveHint")}><ArrowsOut />{t("director.moveTool")}</ToggleGroupItem>
              <ToggleGroupItem value="rotate" disabled={!selected} title={t("director.rotateHint")}><ArrowClockwise />{t("director.rotateTool")}</ToggleGroupItem>
              <ToggleGroupItem value="resize" disabled={!selected} title={t("director.resizeHint")}><ArrowsOutSimple />{t("director.resizeTool")}</ToggleGroupItem>
            </ToggleGroup>
            <div className="director-stage-tools">
              <Select id="director-ratio" aria-label={t("director.aspectRatio")} density="compact" value={scene.aspectRatio} disabled={busy} onChange={(event) => {
                const ratio = DIRECTOR_ASPECT_RATIOS.find((value) => value === event.target.value); if (ratio) change({ ...scene, aspectRatio: ratio });
              }}>{DIRECTOR_ASPECT_RATIOS.map((ratio) => <option key={ratio} value={ratio}>{ratio}</option>)}</Select>
              <Button variant="ghost" size="icon-sm" aria-label={t("director.guides")} title={t("director.guides")} aria-pressed={showGuides} disabled={busy || viewMode !== "camera"} onClick={() => setShowGuides(!showGuides)}><SelectionAll /></Button>
              <Button variant="ghost" size="icon-sm" aria-label={t("director.focusSelected")} title={t("director.focusSelected")} disabled={busy || !selected} onClick={focusSelected}><SlidersHorizontal /></Button>
              <Button variant="ghost" size="icon-sm" aria-label={t("director.resetCamera")} title={t("director.resetCamera")} disabled={busy} onClick={() => cameraChange(createDirectorScene().camera)}><ArrowCounterClockwise /></Button>
            </div>
          </div>
          <DirectorViewport scene={scene} disabled={busy} selectedObjectId={selectedId} showGuides={showGuides} viewMode={viewMode} objectTool={activeObjectTool} onCameraChange={cameraChange}
            onCaptureReady={captureReady} onError={setViewportError} onSelectObject={selectObject} onObjectChange={objectChange}
            onSelectCamera={() => { setSelectedId(""); setInspectorTab("camera"); }} />
          <div className="director-camera-dock">
            <div className="director-dock-group"><span className="director-eyebrow">{t("director.cameraPresets")}</span>
              <ToggleGroup type="single" value={activePreset} size="sm" spacing={1} aria-label={t("director.cameraPresets")} disabled={busy}
                onValueChange={(value) => { const preset = CAMERA_PRESETS.find((item) => item.value === value); if (preset) cameraChange({ ...scene.camera, azimuth: preset.azimuth, elevation: 12 }); }}>
                {CAMERA_PRESETS.map((preset) => <ToggleGroupItem key={preset.value} value={preset.value} aria-label={t(preset.label)} title={t(preset.label)}>{t(preset.caption)}</ToggleGroupItem>)}
              </ToggleGroup>
            </div>
            <div className="director-dock-group"><span className="director-eyebrow">{t("director.lensPresets")}</span>
              <ToggleGroup type="single" value={String(scene.camera.focalLength)} size="sm" spacing={1} aria-label={t("director.lensPresets")} disabled={busy}
                onValueChange={(value) => { const focalLength = LENS_PRESETS.find((lens) => String(lens) === value); if (focalLength) cameraChange({ ...scene.camera, focalLength }); }}>
                {LENS_PRESETS.map((lens) => <ToggleGroupItem key={lens} value={String(lens)}>{lens}<span className="director-lens-unit">mm</span></ToggleGroupItem>)}
              </ToggleGroup>
            </div>
          </div>
        </section>
        <aside className="director-inspector" aria-label={t(mode === "scene" && inspectorTab === "object" ? "director.objectProperties" : "director.cameraProperties")}>
          <Tabs value={mode === "angle" ? "camera" : inspectorTab} onValueChange={setInspectorTab} className="director-property-tabs">
            <TabsList className="director-property-tab-list">
              <TabsTrigger value="camera" disabled={busy}><Camera />{t("director.camera")}</TabsTrigger>
              {mode === "scene" ? <TabsTrigger value="object" disabled={busy}><Cube />{t("director.objectTab")}</TabsTrigger> : null}
            </TabsList>
            <TabsContent value="camera" className="director-property-content">
              <div className="director-property-title"><span className="director-property-icon"><Camera /></span><div><h3>{t("director.cameraProperties")}</h3><p>{t("director.cameraDescription")}</p></div></div>
              <FieldGroup className="director-fields">
                <div className="director-number-grid"><NumberField label={t("director.azimuth")} value={scene.camera.azimuth} min={-180} max={180} disabled={busy}
                  onChange={(azimuth) => cameraChange({ ...scene.camera, azimuth })} />
                  <NumberField label={t("director.elevation")} value={scene.camera.elevation} min={-80} max={80} disabled={busy} onChange={(elevation) => cameraChange({ ...scene.camera, elevation })} /></div>
                <div className="director-number-grid"><NumberField label={t("director.distance")} value={scene.camera.distance} min={1} max={50} step={0.1} disabled={busy}
                  onChange={(distance) => cameraChange({ ...scene.camera, distance })} />
                  <NumberField label={t("director.focalLength")} value={scene.camera.focalLength} min={18} max={120} disabled={busy} onChange={(focalLength) => cameraChange({ ...scene.camera, focalLength })} /></div>
                <div className="director-advanced-group"><Button variant="ghost" size="sm" disabled={busy} aria-expanded={advancedCamera} onClick={() => setAdvancedCamera(!advancedCamera)}>
                  <CaretDown data-icon="inline-start" />{t("director.advancedCamera")}</Button>
                  {advancedCamera ? <div className="director-number-grid director-number-grid--three">{(["x", "y", "z"] as const).map((axis) =>
                    <NumberField key={axis} label={t("director.targetAxis", { axis: axis.toUpperCase() })} value={scene.camera.target[axis]} min={-20} max={20} step={0.1} disabled={busy}
                      onChange={(value) => cameraChange({ ...scene.camera, target: { ...scene.camera.target, [axis]: value } })} />)}</div> : null}
                </div>
              </FieldGroup>
              {mode === "angle" ? <div className="director-prompt-section"><Button variant="ghost" size="sm" aria-expanded={showPrompt} disabled={busy} onClick={() => setShowPrompt(!showPrompt)}>
                <CaretDown data-icon="inline-start" />{t("director.showPrompt")}</Button>{showPrompt ? <Field><FieldLabel htmlFor="director-prompt">{t("director.cameraPrompt")}</FieldLabel>
                  <Textarea id="director-prompt" readOnly value={directorCameraPrompt(scene)} rows={5} /></Field> : null}</div> : null}
            </TabsContent>
            <TabsContent value="object" className="director-property-content">
              {selected ? <>
                <div className="director-property-title"><span className="director-property-icon"><SelectedIcon /></span><div><h3>{selected.name}</h3><p>{t(OBJECT_LABELS[selected.kind])}</p></div></div>
                <FieldGroup className="director-fields">
                  <Field><FieldLabel htmlFor="director-name">{t("director.objectName")}</FieldLabel><Input id="director-name" value={selected.name} maxLength={80} disabled={busy}
                    onChange={(event) => { if (event.target.value.trim()) patchObject({ name: event.target.value }); }} /></Field>
                  <div className="director-property-section"><h4>{t("director.position")}</h4><div className="director-number-grid director-number-grid--three">{(["x", "y", "z"] as const).map((axis) =>
                    <NumberField key={axis} label={t("director.positionAxis", { axis: axis.toUpperCase() })} value={selected.position[axis]} min={-20} max={20} step={0.1} disabled={busy}
                      onChange={(value) => patchObject({ position: { ...selected.position, [axis]: value } })} />)}</div></div>
                  <div className="director-property-section"><h4>{t("director.size")}</h4>
                    <div className="director-number-grid director-number-grid--three">{(["x", "y", "z"] as const).map((axis) =>
                      <NumberField key={axis} label={t(axis === "x" ? "director.widthScale" : axis === "y" ? "director.heightScale" : "director.depthScale")}
                        value={selected.scale[axis]} min={0.1} max={5} step={0.1} disabled={busy}
                        onChange={(value) => patchObject({ scale: { ...selected.scale, [axis]: value } })} />)}</div>
                    <p className="director-size-hint">{t("director.sizeHint")}</p>
                  </div>
                  <div className="director-property-section"><h4>{t("director.transform")}</h4><div className="director-number-grid"><NumberField label={t("director.rotation")} value={selected.rotation} min={-180} max={180} disabled={busy} onChange={(rotation) => patchObject({ rotation })} /></div></div>
                  <div className="director-property-section"><h4>{t("director.appearance")}</h4><Field orientation="horizontal"><FieldLabel htmlFor="director-color">{t("director.color")}</FieldLabel>
                    <div className="director-material-control"><span>{selected.color}</span><Input id="director-color" type="color" value={selected.color} disabled={busy} onChange={(event) => patchObject({ color: event.target.value.toUpperCase() })} /></div></Field></div>
                  <Button variant="outline" size="sm" disabled={busy} onClick={() => { const objects = scene.objects.filter((object) => object.id !== selectedId); change({ ...scene, objects }); setSelectedId(objects[0]?.id ?? ""); }}>
                    <Trash data-icon="inline-start" />{t("director.removeObject")}</Button>
                </FieldGroup>
              </> : <div className="director-inspector-empty"><Cube aria-hidden /><p>{t("director.inspectorHint")}</p></div>}
            </TabsContent>
          </Tabs>
          <div className="director-output-panel">
            {mode === "scene" ? <><div className="director-output-heading"><Camera aria-hidden /><strong>{t("director.captureDescription")}</strong></div>
              <p>{t("director.localCaptureHint")}</p><p className="director-save-hint">{t("director.saveHint")}</p></>
              : source.sourceVersionId ? <><div className="director-output-heading"><Sparkle aria-hidden /><strong>{t("director.generateAngle")}</strong></div>
                <ImageFunctionConfiguration operation="VIEW_ANGLE" sourceVersionId={source.sourceVersionId} busy={busy} onClose={() => setMode("scene")}
                  onSubmit={(input) => angle.mutate(input)}>{({ controls,submit,submitDisabled }) => <>{controls}
                    <Button data-director-action="generate-angle" disabled={busy || submitDisabled} onClick={() => submit({ viewAngle: directorViewAngle(scene) }, directorCameraPrompt(scene))}>
                      <Sparkle data-icon="inline-start" />{angle.isPending ? t("media.card.generating") : t("director.generateAngle")}</Button>
                  </>}</ImageFunctionConfiguration>
              </> : null}
          </div>
        </aside>
      </div>
    </DialogContent>
  </Dialog>;
}

function NumberField({ label,value,min,max,step = 1,disabled,onChange }: {
  label: string; value: number; min: number; max: number; step?: number; disabled: boolean; onChange: (value: number) => void;
}) {
  return <Field><FieldLabel>{label}<CreatorNumberInput aria-label={label} value={Math.round(value * 100) / 100}
    min={min} max={max} step={step} disabled={disabled} onValueChange={(next) => {
      if (next !== undefined && Number.isFinite(next) && next >= min && next <= max) onChange(next);
    }} /></FieldLabel></Field>;
}
