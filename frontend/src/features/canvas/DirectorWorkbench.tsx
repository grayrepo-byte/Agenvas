import { ArrowCounterClockwise,Cube,DownloadSimple,Plus,Trash,X } from "@/shared/ui/icons";
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
import { Field,FieldGroup,FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { ToggleGroup,ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { ImageFunctionConfiguration } from "./ImageFunctionConfiguration";
import { DirectorViewport } from "./DirectorViewport";
import { createDirectorScene,DIRECTOR_ASPECT_RATIOS,DIRECTOR_OBJECT_KINDS,directorCameraPrompt,directorViewAngle,
  parseDirectorScene,type DirectorObject,type DirectorScene } from "./directorScene";
import "./DirectorWorkbench.css";

const OBJECT_LABELS: Record<DirectorObject["kind"], MessageKey> = {
  ACTOR: "director.actor", BOX: "director.box", SPHERE: "director.sphere", CYLINDER: "director.cylinder",
};
const MAX_OBJECTS = 32;
const MAX_IMPORT_BYTES = 128 * 1024;
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
  return <Dialog open onOpenChange={(open) => { if (!open) close(); }}>
    <DialogContent fullscreen showCloseButton={false} className="director-workbench nodrag nopan nowheel"
      onPointerDown={(event) => event.stopPropagation()} onClick={(event) => event.stopPropagation()}
      onDoubleClick={(event) => event.stopPropagation()} onWheel={(event) => event.stopPropagation()}
      onKeyDown={(event) => event.stopPropagation()}
      onEscapeKeyDown={(event) => { event.preventDefault(); event.stopPropagation(); close(); }}
      onPointerDownOutside={(event) => event.preventDefault()}
      onCloseAutoFocus={(event) => { event.preventDefault(); if (previousFocus.current instanceof HTMLElement && previousFocus.current.isConnected) previousFocus.current.focus(); }}>
      <header className="director-header">
        <div><DialogTitle><Cube aria-hidden />{t("director.title")}</DialogTitle>
          <DialogDescription>{t("director.description")}</DialogDescription></div>
        <div className="director-actions"><Badge variant="secondary">{t("director.local")}</Badge>
          <Button variant="outline" size="sm" disabled={busy} onClick={exportScene}><DownloadSimple data-icon="inline-start" />{t("director.export")}</Button>
          <Button variant="outline" size="sm" disabled={busy} onClick={() => importInput.current?.click()}>{t("director.import")}</Button>
          <Button disabled={busy || (!ready && !attempt.current)} onClick={() => save.mutate()}>
            {save.isPending ? t("director.saving") : save.error ? t("common.retrySave") : t("director.capture")}</Button>
          <Button variant="ghost" size="icon-sm" aria-label={t("common.close")} disabled={busy} onClick={close}><X /></Button>
        </div>
        <Input ref={importInput} type="file" accept=".json,application/json" className="sr-only" aria-label={t("director.import")}
          disabled={busy} onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ""; if (file) void importScene(file); }} />
      </header>
      {confirmClose ? <Alert className="director-notice"><AlertDescription>
        <p>{t("director.unsaved")}</p><div className="director-actions">
          <Button variant="outline" size="sm" onClick={() => setConfirmClose(false)}>{t("director.keepEditing")}</Button>
          <Button variant="destructive" size="sm" onClick={source.onClose}>{t("director.discard")}</Button>
        </div></AlertDescription></Alert> : null}
      {message ? <Alert variant="destructive" className="director-notice"><AlertDescription>{message}</AlertDescription></Alert> : null}
      <div className="director-body">
        <section className="director-stage" aria-label={t("director.viewportLabel")}>
          <DirectorViewport scene={scene} disabled={busy} onCameraChange={cameraChange}
            onCaptureReady={captureReady} onError={setViewportError} onSelectObject={setSelectedId} />
        </section>
        <aside className="director-inspector">
          {source.sourceVersionId && source.sourceUrl ? <ToggleGroup type="single" value={mode} variant="outline" aria-label={t("director.mode")}
            disabled={busy} onValueChange={(value) => { if (value) setMode(value); }}>
            <ToggleGroupItem value="scene">{t("director.sceneMode")}</ToggleGroupItem>
            <ToggleGroupItem value="angle">{t("director.angleMode")}</ToggleGroupItem>
          </ToggleGroup> : null}
          <FieldGroup className="director-fields">
            <Field><FieldLabel htmlFor="director-ratio">{t("director.aspectRatio")}</FieldLabel>
              <Select id="director-ratio" value={scene.aspectRatio} disabled={busy} onChange={(event) => {
                const ratio = DIRECTOR_ASPECT_RATIOS.find((value) => value === event.target.value);
                if (ratio) change({ ...scene, aspectRatio: ratio });
              }}>{DIRECTOR_ASPECT_RATIOS.map((ratio) => <option key={ratio} value={ratio}>{ratio}</option>)}</Select></Field>
            <h3>{t("director.camera")}</h3>
            <div className="director-number-grid">
              <NumberField label={t("director.azimuth")} value={scene.camera.azimuth} min={-180} max={180} disabled={busy}
                onChange={(azimuth) => cameraChange({ ...scene.camera, azimuth })} />
              <NumberField label={t("director.elevation")} value={scene.camera.elevation} min={-80} max={80} disabled={busy}
                onChange={(elevation) => cameraChange({ ...scene.camera, elevation })} />
              <NumberField label={t("director.distance")} value={scene.camera.distance} min={1} max={50} step={0.1} disabled={busy}
                onChange={(distance) => cameraChange({ ...scene.camera, distance })} />
              <NumberField label={t("director.focalLength")} value={scene.camera.focalLength} min={18} max={120} disabled={busy}
                onChange={(focalLength) => cameraChange({ ...scene.camera, focalLength })} />
            </div>
            <div className="director-number-grid director-number-grid--three">{(["x", "y", "z"] as const).map((axis) =>
              <NumberField key={axis} label={t("director.targetAxis", { axis: axis.toUpperCase() })} value={scene.camera.target[axis]}
                min={-20} max={20} step={0.1} disabled={busy} onChange={(value) => cameraChange({ ...scene.camera, target: { ...scene.camera.target, [axis]: value } })} />)}</div>
            <Button variant="outline" size="sm" disabled={busy} onClick={() => cameraChange(createDirectorScene().camera)}>
              <ArrowCounterClockwise data-icon="inline-start" />{t("director.resetCamera")}</Button>
            {mode === "scene" ? <>
              <h3>{t("director.objects", { count: scene.objects.length, max: MAX_OBJECTS })}</h3>
              <Select aria-label={t("director.addObject")} value="" disabled={busy || scene.objects.length >= MAX_OBJECTS} onChange={(event) => {
                const kind = DIRECTOR_OBJECT_KINDS.find((value) => value === event.target.value); if (!kind) return;
                const object: DirectorObject = { id: crypto.randomUUID(), name: `${t(OBJECT_LABELS[kind])} ${scene.objects.length + 1}`,
                  kind, position: { x: 0, y: 0, z: 0 }, rotation: 0, scale: 1, color: "#94A3B8" };
                change({ ...scene, objects: [...scene.objects, object] }); setSelectedId(object.id);
              }}><option value="">{t("director.addObject")}</option>{DIRECTOR_OBJECT_KINDS.map((kind) => <option key={kind} value={kind}>{t(OBJECT_LABELS[kind])}</option>)}</Select>
              {scene.objects.length ? <Field><FieldLabel htmlFor="director-object">{t("director.selectedObject")}</FieldLabel>
                <Select id="director-object" value={selectedId} disabled={busy} onChange={(event) => setSelectedId(event.target.value)}>
                  {scene.objects.map((object) => <option key={object.id} value={object.id}>{object.name}</option>)}</Select></Field>
                : <p>{t("director.emptyScene")}</p>}
              {selected ? <>
                <Field><FieldLabel htmlFor="director-name">{t("director.objectName")}</FieldLabel><Input id="director-name" value={selected.name} maxLength={80} disabled={busy}
                  onChange={(event) => { if (event.target.value.trim()) patchObject({ name: event.target.value }); }} /></Field>
                <div className="director-number-grid director-number-grid--three">{(["x", "y", "z"] as const).map((axis) =>
                  <NumberField key={axis} label={t("director.positionAxis", { axis: axis.toUpperCase() })} value={selected.position[axis]} min={-20} max={20} step={0.1} disabled={busy}
                    onChange={(value) => patchObject({ position: { ...selected.position, [axis]: value } })} />)}</div>
                <div className="director-number-grid"><NumberField label={t("director.rotation")} value={selected.rotation} min={-180} max={180} disabled={busy} onChange={(rotation) => patchObject({ rotation })} />
                  <NumberField label={t("director.scale")} value={selected.scale} min={0.1} max={5} step={0.1} disabled={busy} onChange={(scale) => patchObject({ scale })} /></div>
                <Field><FieldLabel htmlFor="director-color">{t("director.color")}</FieldLabel><Input id="director-color" type="color" value={selected.color} disabled={busy}
                  onChange={(event) => patchObject({ color: event.target.value.toUpperCase() })} /></Field>
                <Button variant="outline" size="sm" disabled={busy} onClick={() => {
                  const objects = scene.objects.filter((object) => object.id !== selectedId); change({ ...scene, objects }); setSelectedId(objects[0]?.id ?? "");
                }}><Trash data-icon="inline-start" />{t("director.removeObject")}</Button>
              </> : null}
            </> : source.sourceVersionId ? <>
              <img className="director-source-image" src={source.sourceUrl} alt={source.sourceTitle} />
              <p>{t("director.angleHint")}</p>
              <Field><FieldLabel htmlFor="director-prompt">{t("director.cameraPrompt")}</FieldLabel>
                <Textarea id="director-prompt" readOnly value={directorCameraPrompt(scene)} rows={5} /></Field>
              <ImageFunctionConfiguration operation="VIEW_ANGLE" sourceVersionId={source.sourceVersionId} busy={busy} onClose={() => setMode("scene")}
                onSubmit={(input) => angle.mutate(input)}>{({ controls,submit,submitDisabled }) => <>
                  {controls}<Button disabled={busy || submitDisabled} onClick={() => submit({ viewAngle: directorViewAngle(scene) }, directorCameraPrompt(scene))}>
                    <Plus data-icon="inline-start" />{angle.isPending ? t("media.card.generating") : t("director.generateAngle")}</Button>
                </>}</ImageFunctionConfiguration>
            </> : null}
          </FieldGroup>
          <p className="director-save-hint">{t("director.saveHint")}</p>
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
