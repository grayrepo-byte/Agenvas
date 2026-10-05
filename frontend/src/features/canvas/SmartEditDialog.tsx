import {
ArrowClockwise,ArrowCounterClockwise,ArrowUp,BoundingBox,Coins,Cube,Eraser,
PaintBrush,Plus,UploadSimple,X
} from "@phosphor-icons/react";
import { useQuery,useQueryClient } from "@tanstack/react-query";
import {
useMemo,useRef,useState,type FormEvent,type ReactNode,
type PointerEvent as ReactPointerEvent
} from "react";
import {
ApiError,assetContentUrl,createArtifact,listArtifacts,listCanvasItems,uploadImageAsset,
type MediaCapability
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import { estimatedMediaCost } from "../../shared/mediaPricing";
import { Button } from "../../shared/ui/primitives/button";
import { DialogContent, Dialog as DialogRoot, DialogTitle } from "../../shared/ui/primitives/dialog";
import { Dialog } from "../../shared/ui/Dialog";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Select } from "../../shared/ui/Select";
import { readContentText } from "./artifactContent";
import { mediaModelDetails } from "./mediaModelPresentation";

const MASK_LONG_EDGE = 1024;
const MASK_HISTORY_LIMIT = 20;
const MAX_ARTIFACT_TITLE_LENGTH = 120;

type Tool = "BRUSH" | "RECTANGLE";
type StrokeMode = "PAINT" | "ERASE";
type Reference = { versionId: string; label: string; thumbnailUrl: string };

export function SmartEditDialog({ projectId, sourceVersionId, sourceTitle, sourceUrl, capabilities, busy, error, extraControls, submitDisabled = false, configuredMethod = false,
  onClose, onSubmit }: {
  projectId: string;
  sourceVersionId: string;
  sourceTitle: string;
  sourceUrl: string;
  capabilities: MediaCapability[];
  extraControls?: ReactNode; submitDisabled?: boolean; configuredMethod?: boolean;
  busy: boolean;
  error: Error | null;
  onClose: () => void;
  onSubmit: (input: { instruction: string; capabilityId: string;
    referenceVersionIds: string[]; maskAssetId?: string }) => void;
}) {
  useLocale();
  const queryClient = useQueryClient();
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const uploadRef = useRef<HTMLInputElement>(null);
  const drawing = useRef(false);
  const pointerStart = useRef<{ x: number; y: number } | null>(null);
  const rectangleBase = useRef<ImageData | null>(null);
  const history = useRef<ImageData[]>([]);
  const historyIndex = useRef(-1);
  const [tool, setTool] = useState<Tool>("BRUSH");
  const [strokeMode, setStrokeMode] = useState<StrokeMode>("PAINT");
  const [brushSize, setBrushSize] = useState(42);
  const [hasMask, setHasMask] = useState(false);
  const [historyState, setHistoryState] = useState({ canUndo: false, canRedo: false });
  const [instruction, setInstruction] = useState("");
  const [capabilityId, setCapabilityId] = useState(capabilities[0]?.id ?? "");
  const [references, setReferences] = useState<Reference[]>([]);
  const [referencePickerOpen, setReferencePickerOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  // The fullscreen editor owns the popup's stacking context, above its backdrop.
  const [menuContainer, setMenuContainer] = useState<HTMLElement | null>(null);
  const referenceSources = useQuery({ queryKey: ["smart-edit-references", projectId],
    queryFn: async () => {
      const [artifacts, canvas] = await Promise.all([listArtifacts(projectId), listCanvasItems(projectId)]);
      return { artifacts: artifacts.items, canvasItems: canvas.items };
    }, enabled: referencePickerOpen });

  const eligibleCapabilities = useMemo(() => capabilities.filter((capability) =>
    !hasMask || capability.supportsImageMask), [capabilities, hasMask]);
  const selectedCapability = eligibleCapabilities.find((capability) => capability.id === capabilityId)
    ?? eligibleCapabilities[0];
  const selectedCapabilityId = selectedCapability?.id ?? "";
  const referenceCapacity = Math.max(0, (selectedCapability?.maxReferenceImages ?? 1) - 1);
  const canSubmit = !busy && !submitDisabled && !uploading && instruction.trim().length > 0
    && Boolean(selectedCapabilityId) && references.length <= referenceCapacity;

  const availableReferences = useMemo(() => {
    const candidates: Reference[] = [];
    for (const item of referenceSources.data?.canvasItems ?? []) {
      if (item.subjectType !== "ARTIFACT" || item.artifact?.kind !== "IMAGE"
          || !item.selectedVersion || item.selectedVersion.id === sourceVersionId) continue;
      const assetId = readContentText(item.selectedVersion.content, "assetId");
      if (assetId) candidates.push({ versionId: item.selectedVersion.id, label: item.title,
        thumbnailUrl: assetContentUrl(projectId, assetId) });
    }
    for (const artifact of referenceSources.data?.artifacts ?? []) {
      if (artifact.kind !== "IMAGE" || artifact.resourceDefaultVersionId === sourceVersionId
          || !artifact.resourceDefaultVersionId || !artifact.resourceDefaultVersion) continue;
      const assetId = readContentText(artifact.resourceDefaultVersion.content, "assetId");
      if (assetId) candidates.push({ versionId: artifact.resourceDefaultVersionId,
        label: artifact.title, thumbnailUrl: assetContentUrl(projectId, assetId) });
    }
    return candidates.filter((candidate, index) =>
      candidates.findIndex((entry) => entry.versionId === candidate.versionId) === index);
  }, [projectId, referenceSources.data, sourceVersionId]);

  const previousFocus = useRef(document.activeElement);
  const [confirmExit, setConfirmExit] = useState(false);
  function requestClose() {
    if (busy || uploading) return;
    if (instruction.trim() || hasMask || references.length) setConfirmExit(true);
    else onClose();
  }

  function updateHistoryState() {
    setHistoryState({ canUndo: historyIndex.current > 0,
      canRedo: historyIndex.current >= 0 && historyIndex.current < history.current.length - 1 });
  }

  function selectionExists(image: ImageData) {
    for (let index = 3; index < image.data.length; index += 4) {
      if (image.data[index]! > 0) return true;
    }
    return false;
  }

  function initializeMask(image: HTMLImageElement) {
    const canvas = canvasRef.current;
    if (!canvas || image.naturalWidth < 1 || image.naturalHeight < 1) return;
    const scale = Math.min(1, MASK_LONG_EDGE / Math.max(image.naturalWidth, image.naturalHeight));
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (!context) return;
    context.clearRect(0, 0, canvas.width, canvas.height);
    const initial = context.getImageData(0, 0, canvas.width, canvas.height);
    history.current = [initial];
    historyIndex.current = 0;
    setHasMask(false);
    updateHistoryState();
  }

  function canvasPoint(event: ReactPointerEvent<HTMLCanvasElement>) {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    const bounds = canvas.getBoundingClientRect();
    return { x: (event.clientX - bounds.left) * canvas.width / bounds.width,
      y: (event.clientY - bounds.top) * canvas.height / bounds.height };
  }

  function configureStroke(context: CanvasRenderingContext2D) {
    context.globalCompositeOperation = strokeMode === "ERASE" ? "destination-out" : "source-over";
    context.strokeStyle = "rgba(255, 0, 92, .62)";
    context.fillStyle = "rgba(255, 0, 92, .48)";
    context.lineWidth = brushSize;
    context.lineCap = "round";
    context.lineJoin = "round";
  }

  function commitHistory() {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d", { willReadFrequently: true });
    if (!canvas || !context) return;
    const snapshot = context.getImageData(0, 0, canvas.width, canvas.height);
    history.current = history.current.slice(0, historyIndex.current + 1);
    history.current.push(snapshot);
    if (history.current.length > MASK_HISTORY_LIMIT) history.current.shift();
    historyIndex.current = history.current.length - 1;
    setHasMask(selectionExists(snapshot));
    updateHistoryState();
  }

  function restoreHistory(nextIndex: number) {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d", { willReadFrequently: true });
    const snapshot = history.current[nextIndex];
    if (!context || !snapshot) return;
    context.putImageData(snapshot, 0, 0);
    historyIndex.current = nextIndex;
    setHasMask(selectionExists(snapshot));
    updateHistoryState();
  }

  function pointerDown(event: ReactPointerEvent<HTMLCanvasElement>) {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d", { willReadFrequently: true });
    const point = canvasPoint(event);
    if (!canvas || !context || !point) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    drawing.current = true;
    pointerStart.current = point;
    configureStroke(context);
    if (tool === "RECTANGLE") {
      rectangleBase.current = context.getImageData(0, 0, canvas.width, canvas.height);
      return;
    }
    context.beginPath();
    context.moveTo(point.x, point.y);
    context.lineTo(point.x + .01, point.y + .01);
    context.stroke();
  }

  function pointerMove(event: ReactPointerEvent<HTMLCanvasElement>) {
    if (!drawing.current) return;
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d", { willReadFrequently: true });
    const point = canvasPoint(event);
    const start = pointerStart.current;
    if (!canvas || !context || !point || !start) return;
    configureStroke(context);
    if (tool === "RECTANGLE") {
      if (rectangleBase.current) context.putImageData(rectangleBase.current, 0, 0);
      const x = Math.min(start.x, point.x);
      const y = Math.min(start.y, point.y);
      const width = Math.abs(point.x - start.x);
      const height = Math.abs(point.y - start.y);
      if (strokeMode === "ERASE") context.clearRect(x, y, width, height);
      else context.fillRect(x, y, width, height);
      return;
    }
    context.lineTo(point.x, point.y);
    context.stroke();
  }

  function pointerUp(event: ReactPointerEvent<HTMLCanvasElement>) {
    if (!drawing.current) return;
    drawing.current = false;
    pointerStart.current = null;
    rectangleBase.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    commitHistory();
  }

  function exportMask(): Promise<File | null> {
    const selection = canvasRef.current;
    if (!selection || !hasMask) return Promise.resolve(null);
    const selectionContext = selection.getContext("2d", { willReadFrequently: true });
    if (!selectionContext) return Promise.resolve(null);
    const selected = selectionContext.getImageData(0, 0, selection.width, selection.height);
    const mask = document.createElement("canvas");
    mask.width = selection.width;
    mask.height = selection.height;
    const maskContext = mask.getContext("2d");
    if (!maskContext) return Promise.resolve(null);
    const pixels = maskContext.createImageData(mask.width, mask.height);
    for (let index = 0; index < pixels.data.length; index += 4) {
      pixels.data[index] = 255;
      pixels.data[index + 1] = 255;
      pixels.data[index + 2] = 255;
      pixels.data[index + 3] = selected.data[index + 3]! > 0 ? 0 : 255;
    }
    maskContext.putImageData(pixels, 0, 0);
    return new Promise((resolve) => mask.toBlob((blob) => resolve(blob
      ? new File([blob], "smart-edit-mask.png", { type: "image/png" }) : null), "image/png"));
  }

  async function submit() {
    if (!canSubmit) return;
    setLocalError(null);
    setUploading(true);
    try {
      const mask = await exportMask();
      const archivedMask = mask ? await uploadImageAsset(projectId, mask) : null;
      onSubmit({ instruction: instruction.trim(), capabilityId: selectedCapabilityId,
        referenceVersionIds: references.map((reference) => reference.versionId),
        ...(archivedMask ? { maskAssetId: archivedMask.id } : {}) });
    } catch (failure) {
      setLocalError(failure instanceof ApiError ? failure.message : t("image.smartEdit.maskUploadFailed"));
    } finally {
      setUploading(false);
    }
  }

  async function uploadReferenceFiles(files: File[]) {
    if (!files.length) return;
    setUploading(true);
    setLocalError(null);
    try {
      const room = Math.max(0, referenceCapacity - references.length);
      if (files.length > room) throw new Error(t("image.smartEdit.remainingReferences", { "0": room }));
      const uploaded: Reference[] = [];
      for (const file of files) {
        const asset = await uploadImageAsset(projectId, file);
        const baseName = file.name.replace(/\.[^.]+$/, "").trim() || t("image.smartEdit.referenceImages");
        const artifact = await createArtifact(projectId, { kind: "IMAGE",
          title: baseName.slice(0, MAX_ARTIFACT_TITLE_LENGTH),
          content: { sourceType: "UPLOAD", assetId: asset.id } }, crypto.randomUUID());
        if (!artifact.resourceDefaultVersionId) throw new Error(t("image.smartEdit.uploadedVersionPending"));
        uploaded.push({ versionId: artifact.resourceDefaultVersionId, label: artifact.title,
          thumbnailUrl: assetContentUrl(projectId, asset.id) });
      }
      setReferences((current) => [...current, ...uploaded]);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["artifacts", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["smart-edit-references", projectId] }),
      ]);
    } catch (failure) {
      setLocalError(failure instanceof ApiError ? failure.message
        : failure instanceof Error ? failure.message : t("image.smartEdit.referenceUploadFailed"));
    } finally {
      setUploading(false);
    }
  }

  function handleUpload(event: FormEvent<HTMLInputElement>) {
    const files = Array.from(event.currentTarget.files ?? []);
    event.currentTarget.value = "";
    void uploadReferenceFiles(files);
  }

  return <DialogRoot open onOpenChange={(open) => { if (!open) requestClose(); }}>
    <DialogContent ref={setMenuContainer} className="smart-edit-dialog nodrag nowheel nopan" showCloseButton={false} fullscreen
      aria-describedby={undefined}
      onKeyDown={(event) => event.stopPropagation()}
      onEscapeKeyDown={(event) => { event.stopPropagation(); if (busy || uploading) event.preventDefault(); }}
      onPointerDownOutside={(event) => event.preventDefault()}
      onCloseAutoFocus={(event) => {
        event.preventDefault();
        if (previousFocus.current instanceof HTMLElement && previousFocus.current.isConnected) previousFocus.current.focus();
      }}>
      <DialogTitle className="sr-only">{t("image.smartEdit.sourceImage")}</DialogTitle>
      <div className="smart-edit-toolbar">
        <Button variant="ghost" type="button" className="smart-edit-close" disabled={busy || uploading} onClick={requestClose}
          aria-label={t("image.smartEdit.exit")}><X size={19} />{t("media.card.smartEdit")}</Button>
        <span className="smart-edit-divider" />
        <Button variant="ghost" type="button" className={tool === "BRUSH" ? "is-active" : ""}
          aria-pressed={tool === "BRUSH"} onClick={() => setTool("BRUSH")}>
          <PaintBrush size={17} />{t("image.smartEdit.brushMask")}</Button>
        <Button variant="ghost" type="button" className={tool === "RECTANGLE" ? "is-active" : ""}
          aria-pressed={tool === "RECTANGLE"} onClick={() => setTool("RECTANGLE")}>
          <BoundingBox size={17} />{t("image.smartEdit.rectangleMask")}</Button>
        <span className="smart-edit-divider" />
        <Button variant="ghost" type="button" className={strokeMode === "PAINT" ? "is-active is-icon" : "is-icon"}
          aria-label={t("image.smartEdit.addMask")} aria-pressed={strokeMode === "PAINT"}
          onClick={() => setStrokeMode("PAINT")}><PaintBrush size={18} /></Button>
        <Button variant="ghost" type="button" className={strokeMode === "ERASE" ? "is-active is-icon" : "is-icon"}
          aria-label={t("image.smartEdit.eraseMask")} aria-pressed={strokeMode === "ERASE"}
          onClick={() => setStrokeMode("ERASE")}><Eraser size={18} /></Button>
        <label className="smart-edit-brush-size"><span className="sr-only">{t("image.brushSize")}</span>
          <input type="range" min={8} max={120} value={brushSize}
            onChange={(event) => setBrushSize(Number(event.target.value))} /></label>
        <Button variant="ghost" type="button" className="is-icon" aria-label={t("image.smartEdit.undoMask")} disabled={!historyState.canUndo}
          onClick={() => restoreHistory(historyIndex.current - 1)}><ArrowCounterClockwise size={18} /></Button>
        <Button variant="ghost" type="button" className="is-icon" aria-label={t("image.smartEdit.redoMask")} disabled={!historyState.canRedo}
          onClick={() => restoreHistory(historyIndex.current + 1)}><ArrowClockwise size={18} /></Button>
      </div>

      <div className="smart-edit-stage" aria-label={t("image.smartEdit.maskEditor")}>
        <div className="smart-edit-image-wrap">
          <img src={sourceUrl} alt={t("image.smartEdit.previewLabel", { "0": sourceTitle })}
            onLoad={(event) => initializeMask(event.currentTarget)} />
          <canvas ref={canvasRef} aria-label={t("image.smartEdit.maskCanvas")}
            onPointerDown={pointerDown} onPointerMove={pointerMove}
            onPointerUp={pointerUp} onPointerCancel={pointerUp} />
        </div>
      </div>

      <div className="smart-edit-composer">
        <div className="smart-edit-reference-actions">
          <Button variant="ghost" type="button" aria-expanded={referencePickerOpen}
            onClick={() => setReferencePickerOpen((open) => !open)}><Plus size={17} />{t("image.smartEdit.references")}</Button>
          <Button variant="ghost" type="button" onClick={() => uploadRef.current?.click()}><UploadSimple size={17} />{t("image.smartEdit.upload")}</Button>
          <Input ref={uploadRef} className="sr-only" type="file" accept={MEDIA_FILE_ACCEPT.IMAGE}
            multiple aria-label={t("image.smartEdit.uploadReference")} onChange={handleUpload} />
          {referencePickerOpen ? <div className="smart-edit-reference-picker" role="dialog"
            aria-label={t("image.smartEdit.chooseReference")}>
            <strong>{t("image.smartEdit.projectImages")}</strong><small>{t("image.smartEdit.referenceOrderHint")}</small>
            <div>{referenceSources.isLoading ? <span>{t("image.smartEdit.loading")}</span>
              : availableReferences.length ? availableReferences.map((reference) => {
                const selected = references.some((item) => item.versionId === reference.versionId);
                return <Button variant="ghost" key={reference.versionId} type="button" className={selected ? "is-selected" : ""}
                  disabled={!selected && references.length >= referenceCapacity}
                  onClick={() => setReferences((current) => selected
                    ? current.filter((item) => item.versionId !== reference.versionId)
                    : [...current, reference])}>
                  <img src={reference.thumbnailUrl} alt="" /><span>{reference.label}</span>
                </Button>;
              }) : <span>{t("image.smartEdit.referencesEmpty")}</span>}</div>
          </div> : null}
        </div>
        {references.length ? <div className="smart-edit-references" aria-label={t("image.smartEdit.selectedReferences")}>
          {references.map((reference, index) => <div key={reference.versionId}>
            <img src={reference.thumbnailUrl} alt="" /><span>{index + 2}</span>
            <Button variant="ghost" type="button" aria-label={t("image.smartEdit.removeReference", { "0": reference.label })}
              onClick={() => setReferences((current) => current.filter(
                (item) => item.versionId !== reference.versionId))}><X size={12} /></Button>
          </div>)}</div> : null}
        <Textarea value={instruction} maxLength={4000}
          aria-label={t("image.smartEdit.prompt")}
          placeholder={t("image.smartEdit.promptPlaceholder")}
          onChange={(event) => setInstruction(event.target.value)} />
        <span className="smart-edit-mask-hint">{hasMask ? t("image.smartEdit.transparencyHint") : t("image.smartEdit.noMaskHint")}</span>
        {extraControls}
        <div className="smart-edit-footer">
          <label className="smart-edit-model"><span className="sr-only">{t("media.imageCapability")}</span>
            <Select variant="ghost" density="compact" icon={<Cube size={17} />} value={selectedCapabilityId} disabled={configuredMethod} portalContainer={menuContainer}
              optionDetails={Object.fromEntries(eligibleCapabilities.map((capability) => [capability.id, mediaModelDetails(capability)]))}
              onChange={(event) => setCapabilityId(event.target.value)}>
              {eligibleCapabilities.length ? eligibleCapabilities.map((capability) =>
                <option key={capability.id} value={capability.id}>{capability.name}</option>)
                : <option value="">{hasMask ? t("image.smartEdit.configureMaskCapability") : t("image.smartEdit.configureCapability")}</option>}
            </Select></label>
          <span className="smart-edit-cost" title={t("media.editor.estimateHint")}>
            <Coins size={16} /><span>{estimatedMediaCost(selectedCapability, 1, null)}</span>
          </span>
          <Button variant="ghost" type="button" className="smart-edit-submit" aria-label={t("image.smartEdit.start")}
            disabled={!canSubmit} onClick={() => void submit()}>
            {busy || uploading ? <span className="smart-edit-submit-progress">…</span>
              : <ArrowUp size={21} weight="bold" />}</Button>
        </div>
        {references.length > referenceCapacity ? <p role="alert">{t("image.smartEdit.extraReferenceLimit", { "0": referenceCapacity })}</p> : null}
        {localError ? <p role="alert">{localError}</p> : null}
        {error ? <p role="alert">{error instanceof ApiError ? error.message : t("image.smartEdit.submitFailed")}</p> : null}
      </div>
    </DialogContent>
    {confirmExit ? <Dialog title={t("common.unsavedChanges")} onClose={() => setConfirmExit(false)}
      onSubmit={(event) => { event.preventDefault(); onClose(); }}
      footer={<><Button variant="outline" type="button" onClick={() => setConfirmExit(false)}>{t("common.continueEditing")}</Button>
        <Button variant="destructive" type="submit">{t("common.discardChanges")}</Button></>}>
      <p>{t("image.smartEdit.discardHint")}</p>
    </Dialog> : null}
  </DialogRoot>;
}
