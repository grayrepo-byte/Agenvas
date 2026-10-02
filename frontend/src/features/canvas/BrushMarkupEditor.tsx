import {
ArrowClockwise,
ArrowCounterClockwise,
ArrowUpRight,Eraser,PencilSimple,
Rectangle,TextT,X
} from "@phosphor-icons/react";
import { useMutation,useQueryClient } from "@tanstack/react-query";
import { useEffect,useRef,useState,type PointerEvent as ReactPointerEvent } from "react";
import { createPortal } from "react-dom";
import {
HTTP_STATUS,
ApiError,
uploadCanvasItemVersion,
uploadImageAsset,
type UploadCanvasItemVersionRequest
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { exportMarkup,renderMarkup,type MarkupPoint,type MarkupStroke,type MarkupTool } from "./brushMarkup";
import "./BrushMarkupEditor.css";

const PREVIEW_LONG_EDGE = 1600;
const DEFAULT_STROKE_SIZE = 18;
const STROKE_SIZE_SCALE = 1000;
const MIN_STROKE_SIZE = 4;
const MAX_STROKE_SIZE = 40;
const MAX_TEXT_LENGTH = 200;
const COLORS = [
  { get name() { return t("image.markupEditor.red"); }, value: "#ff3038" }, { get name() { return t("image.markupEditor.orange"); }, value: "#ff8a00" },
  { get name() { return t("image.markupEditor.yellow"); }, value: "#ffe51a" }, { get name() { return t("image.markupEditor.green"); }, value: "#05d66d" },
  { get name() { return t("image.markupEditor.cyan"); }, value: "#00d7ee" }, { get name() { return t("image.markupEditor.blue"); }, value: "#2477ff" },
  { get name() { return t("image.markupEditor.purple"); }, value: "#843dff" }, { get name() { return t("image.markupEditor.pink"); }, value: "#ff26bf" },
] as const;
const TOOLS = [
  { tool: "BRUSH", get label() { return t("image.markupEditor.brush"); }, icon: PencilSimple },
  { tool: "ERASER", get label() { return t("image.markupEditor.eraser"); }, icon: Eraser },
  { tool: "RECTANGLE", get label() { return t("image.markupEditor.rectangle"); }, icon: Rectangle },
  { tool: "ARROW", get label() { return t("image.markupEditor.arrow"); }, icon: ArrowUpRight },
  { tool: "TEXT", get label() { return t("common.text"); }, icon: TextT },
] as const;

/** Local image editor; saving archives user bytes and a derived node, without a generation task. */
export function BrushMarkupEditor(props: {
  projectId: string; canvasItemId: string; sourceVersionId: string; expectedVersion: number;
  sourceUrl: string; sourceTitle: string; onClose: () => void;
}) {
  useLocale();
  // Keep the exact editing source even if SSE refreshes the underlying node mid-edit.
  const [{ projectId, canvasItemId, sourceVersionId, expectedVersion,
    sourceUrl, sourceTitle, onClose }] = useState(props);
  const client = useQueryClient();
  const imageRef = useRef<HTMLImageElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const textRef = useRef<HTMLInputElement>(null);
  const activeStroke = useRef<MarkupStroke | null>(null);
  const pending = useRef<{ file: File; assetId?: string; request?: UploadCanvasItemVersionRequest } | null>(null);
  const [tool, setTool] = useState<MarkupTool>("BRUSH");
  const [color, setColor] = useState<string>(COLORS[0].value);
  const [paletteOpen, setPaletteOpen] = useState(true);
  const [sizeOpen, setSizeOpen] = useState(false);
  const [strokeSize, setStrokeSize] = useState(DEFAULT_STROKE_SIZE);
  const [strokes, setStrokes] = useState<MarkupStroke[]>([]);
  const [cursor, setCursor] = useState(0);
  const [ready, setReady] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const [text, setText] = useState<{ point: MarkupPoint; value: string } | null>(null);
  const [reload, setReload] = useState(0);
  const [imageSize, setImageSize] = useState<{ width: number; height: number } | null>(null);
  const save = useMutation({
    mutationFn: async () => {
      const image = imageRef.current;
      if (!image || !ready) throw new Error(t("image.markupEditor.waitForImage"));
      if (!pending.current) pending.current = { file: await exportMarkup(image, strokes.slice(0, cursor)) };
      const attempt = pending.current;
      if (!attempt.assetId) attempt.assetId = (await uploadImageAsset(projectId, attempt.file)).id;
      if (!attempt.request) attempt.request = { targetItemId: crypto.randomUUID(), expectedVersion,
        purpose: "BRUSH_MARKUP", sourceVersionId,
        content: { sourceType: "UPLOAD", assetId: attempt.assetId } };
      // The stable target ID and payload also recover a lost response after a successful commit.
      return uploadCanvasItemVersion(projectId, canvasItemId, attempt.request);
    },
    onSuccess: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ["canvas", projectId] }),
        client.invalidateQueries({ queryKey: ["canvas-connections", projectId] }),
        client.invalidateQueries({ queryKey: ["snapshot", projectId] }),
        client.invalidateQueries({ queryKey: ["artifact-versions", projectId] }),
      ]);
      onClose();
    },
  });

  useEffect(() => {
    const previousFocus = document.activeElement;
    dialogRef.current?.focus();
    return () => { if (previousFocus instanceof HTMLElement) previousFocus.focus(); };
  }, []);
  useEffect(() => {
    if (text) textRef.current?.focus();
    else dialogRef.current?.focus();
  }, [text]);
  useEffect(() => {
    if (ready && canvasRef.current) renderMarkup(canvasRef.current, strokes.slice(0, cursor));
  }, [strokes, cursor, ready]);

  function changed() { pending.current = null; save.reset(); setLocalError(null); }
  function commit(stroke: MarkupStroke) {
    changed(); setStrokes([...strokes.slice(0, cursor), stroke]); setCursor(cursor + 1);
  }
  function finishText() {
    if (!text) return;
    if (text.value.trim()) commit({ tool: "TEXT", color, width: strokeSize / STROKE_SIZE_SCALE,
      points: [text.point], text: text.value.trim() });
    setText(null);
  }
  function point(event: ReactPointerEvent<HTMLCanvasElement>): MarkupPoint | null {
    const bounds = canvasRef.current?.getBoundingClientRect();
    if (!bounds?.width || !bounds.height) return null;
    return { x: Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width)),
      y: Math.max(0, Math.min(1, (event.clientY - bounds.top) / bounds.height)) };
  }
  function pointerDown(event: ReactPointerEvent<HTMLCanvasElement>) {
    if (!ready || save.isPending || activeStroke.current || event.button !== 0) return;
    const start = point(event); if (!start) return;
    event.preventDefault();
    if (tool === "TEXT") { finishText(); setText({ point: start, value: "" }); return; }
    event.currentTarget.setPointerCapture(event.pointerId);
    activeStroke.current = { tool, color, width: strokeSize / STROKE_SIZE_SCALE, points: [start] };
    renderMarkup(event.currentTarget, [...strokes.slice(0, cursor), activeStroke.current]);
  }
  function pointerMove(event: ReactPointerEvent<HTMLCanvasElement>) {
    const stroke = activeStroke.current; const next = point(event);
    if (!stroke || !next) return;
    if (stroke.tool === "BRUSH" || stroke.tool === "ERASER") stroke.points.push(next);
    else stroke.points = [stroke.points[0]!, next];
    renderMarkup(event.currentTarget, [...strokes.slice(0, cursor), stroke]);
  }
  function pointerUp(event: ReactPointerEvent<HTMLCanvasElement>) {
    if (!activeStroke.current) return;
    pointerMove(event); commit(activeStroke.current); activeStroke.current = null;
    event.currentTarget.releasePointerCapture(event.pointerId);
  }
  function restore(next: number) {
    if (next < 0 || next > strokes.length || save.isPending) return;
    changed(); setText(null); setCursor(next);
  }
  const error = localError ?? (save.error instanceof ApiError && save.error.status === HTTP_STATUS.CONFLICT
    ? t("image.markupEditor.sourceConflict")
    : save.error?.message);

  return createPortal(<div className="markup-backdrop nodrag nowheel nopan"
    onPointerDown={(event) => event.stopPropagation()} onClick={(event) => event.stopPropagation()}>
    <div ref={dialogRef} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("image.markupEditor.title")}
      className="markup-dialog" onKeyDown={(event) => {
        event.stopPropagation();
        if (event.key === "Tab") {
          const controls = dialogRef.current?.querySelectorAll<HTMLElement>("button:not(:disabled), input:not(:disabled)");
          const first = controls?.[0]; const last = controls?.[controls.length - 1];
          if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current)) {
            event.preventDefault(); last?.focus();
          } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault(); first?.focus();
          }
        }
        if (event.key === "Escape" && !save.isPending) { if (text) setText(null); else onClose(); }
        if (event.target instanceof HTMLInputElement) return;
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") {
          event.preventDefault(); restore(cursor + (event.shiftKey ? 1 : -1));
        }
      }}>
      <div className="markup-tools-shell">
        {paletteOpen ? <div className="markup-palette" aria-label={t("image.markupEditor.color")}>
          {COLORS.map((entry) => <Button variant="ghost" type="button" key={entry.value} title={entry.name}
            aria-label={entry.name} aria-pressed={color === entry.value} disabled={save.isPending}
            style={{ backgroundColor: entry.value }} onClick={() => setColor(entry.value)} />)}
        </div> : null}
        <div className="markup-toolbar" role="toolbar" aria-label={t("image.markupEditor.tools")}>
          <Button variant="ghost" type="button" className="markup-close" aria-label={t("image.markupEditor.close")} disabled={save.isPending}
            onClick={onClose}><X size={17} /><span>{t("image.markupEditor.title")}</span></Button>
          <div className="markup-tool-group">
            <Button variant="ghost" type="button" className="markup-color" aria-label={t("image.markupEditor.chooseColor")}
              aria-expanded={paletteOpen} onClick={() => setPaletteOpen(!paletteOpen)}>
              <span style={{ backgroundColor: color }} /></Button>
            {TOOLS.map((entry) => <Button variant="ghost" type="button" key={entry.tool}
              title={entry.tool === "BRUSH" || entry.tool === "ERASER" ? t("image.markupEditor.toolSizeHint", { "0": entry.label }) : entry.label}
              aria-label={entry.label} aria-pressed={tool === entry.tool} disabled={save.isPending}
              onClick={() => {
                finishText();
                setSizeOpen(tool === entry.tool && (entry.tool === "BRUSH" || entry.tool === "ERASER") && !sizeOpen);
                setTool(entry.tool);
              }}>
              <entry.icon size={20} /></Button>)}
          </div>
        </div>
        {sizeOpen ? <label className="markup-stroke-size">{t("image.brushSize")}<input aria-label={t("image.brushSize")} type="range"
          min={MIN_STROKE_SIZE} max={MAX_STROKE_SIZE} value={strokeSize} disabled={save.isPending}
          onChange={(event) => setStrokeSize(Number(event.target.value))} /></label> : null}
      </div>
      <div className="markup-image-area">
        <div className="markup-image-title"><span>{sourceTitle}</span>
          {imageSize ? <small>{imageSize.width} × {imageSize.height}</small> : null}</div>
        <div className={`markup-image-shell${tool === "TEXT" ? " is-text" : ""}`}>
          <img ref={imageRef} key={reload} src={sourceUrl} alt={t("image.markupEditor.sourceImage")} draggable={false}
            onError={() => { setReady(false); setLocalError(t("image.markupEditor.imageLoadFailed")); }}
            onLoad={(event) => {
              const image = event.currentTarget; const canvas = canvasRef.current;
              if (!canvas || !image.naturalWidth || !image.naturalHeight) return;
              const scale = Math.min(1, PREVIEW_LONG_EDGE / Math.max(image.naturalWidth, image.naturalHeight));
              canvas.width = Math.round(image.naturalWidth * scale);
              canvas.height = Math.round(image.naturalHeight * scale);
              try {
                renderMarkup(canvas, strokes.slice(0, cursor));
                setImageSize({ width: image.naturalWidth, height: image.naturalHeight });
                setReady(true); setLocalError(null);
              } catch (failure) { setLocalError(failure instanceof Error ? failure.message : t("image.markupEditor.startFailed")); }
            }} />
          <canvas ref={canvasRef} aria-label={t("image.markupEditor.canvasLabel")} onPointerDown={pointerDown}
            onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={() => {
              activeStroke.current = null;
              if (canvasRef.current) renderMarkup(canvasRef.current, strokes.slice(0, cursor));
            }} />
          {text ? <Input ref={textRef} className="markup-text-input" aria-label={t("image.markupEditor.text")}
            maxLength={MAX_TEXT_LENGTH} placeholder={t("image.markupEditor.textPlaceholder")} value={text.value}
            style={{ left: `${text.point.x * 100}%`, top: `${text.point.y * 100}%`, color }}
            onChange={(event) => setText({ ...text, value: event.target.value })}
            onBlur={finishText} onKeyDown={(event) => {
              event.stopPropagation();
              if (event.key === "Enter" && !event.nativeEvent.isComposing) { event.preventDefault(); finishText(); }
              if (event.key === "Escape") setText(null);
            }} /> : null}
        </div>
      </div>
      {!ready && !error ? <p role="status" className="markup-status">{t("image.markupEditor.imageLoading")}</p> : null}
      {error ? <p role="alert" className="markup-error">{error}
        {!ready ? <Button variant="ghost" type="button" onClick={() => { setLocalError(null); setReload(reload + 1); }}>{t("image.markupEditor.retryLoad")}</Button> : null}
      </p> : null}
      <div className="markup-save-toolbar">
        <Button variant="ghost" type="button" aria-label={t("image.markupEditor.undo")} title={t("image.markupEditor.undo")} disabled={!cursor || save.isPending}
          onClick={() => restore(cursor - 1)}><ArrowCounterClockwise size={20} /></Button>
        <Button variant="ghost" type="button" aria-label={t("image.markupEditor.redo")} title={t("image.markupEditor.redo")} disabled={cursor >= strokes.length || save.isPending}
          onClick={() => restore(cursor + 1)}><ArrowClockwise size={20} /></Button>
        <span className="markup-divider" />
        <Button variant="ghost" type="button" className="markup-save" disabled={!ready || !cursor || save.isPending || Boolean(text)}
          onClick={() => save.mutate()}>{save.isPending ? t("common.saving") : save.error ? t("common.retrySave") : t("image.markupEditor.save")}</Button>
      </div>
    </div>
  </div>, document.body);
}
