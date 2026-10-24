import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { t, useLocale } from "../../shared/i18n";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { directorFrameSize, type DirectorObject, type DirectorScene } from "./directorScene";
import type { DirectorObjectTool, DirectorRenderer, DirectorViewMode } from "./directorRenderer";
import "./DirectorViewport.css";

export interface DirectorViewportProps {
  scene: DirectorScene;
  onCameraChange: (camera: DirectorScene["camera"]) => void;
  onCaptureReady: (capture: (() => Promise<File>) | null) => void;
  onError: (message: string | null) => void;
  selectedObjectId?: string;
  showGuides?: boolean;
  viewMode?: DirectorViewMode;
  objectTool?: DirectorObjectTool;
  disabled?: boolean;
  onSelectObject?: (id: string) => void;
  onSelectCamera?: () => void;
  onObjectChange?: (id: string, object: DirectorObject) => void;
}

function frameSize(stage: HTMLDivElement, scene: DirectorScene, viewMode: DirectorViewMode) {
  return viewMode === "director" ? { width: stage.clientWidth, height: stage.clientHeight }
    : directorFrameSize(stage.clientWidth, stage.clientHeight, scene.aspectRatio);
}

/** Mount only inside the opened director dialog; Three.js itself is loaded on mount. */
export function DirectorViewport(props: DirectorViewportProps) {
  useLocale();
  const stageRef = useRef<HTMLDivElement>(null);
  const frameRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const rendererRef = useRef<DirectorRenderer | null>(null);
  const latest = useRef(props);
  const hintId = useId();
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState<"loading" | "ready" | "unavailable">("loading");
  const viewMode = props.viewMode ?? "camera";
  const objectTool = props.objectTool ?? "move";

  useLayoutEffect(() => {
    latest.current = props;
    rendererRef.current?.update(props.scene, props.selectedObjectId);
    rendererRef.current?.setViewMode(viewMode);
    rendererRef.current?.setObjectTool(objectTool);
    rendererRef.current?.setDisabled(props.disabled ?? false);
    const stage = stageRef.current;
    const frame = frameRef.current;
    if (stage && frame) {
      const size = frameSize(stage, props.scene, viewMode);
      frame.style.width = `${size.width}px`;
      frame.style.height = `${size.height}px`;
      rendererRef.current?.resize(size.width, size.height);
    }
  });

  useEffect(() => {
    const canvas = canvasRef.current;
    const stage = stageRef.current;
    const frame = frameRef.current;
    if (!canvas || !stage || !frame) return;
    let active = true;
    let renderer: DirectorRenderer | null = null;
    const unavailable = () => {
      if (!active) return;
      setStatus("unavailable");
      latest.current.onCaptureReady(null);
      latest.current.onError(t("director.viewportUnavailable"));
    };
    const resize = () => {
      const size = frameSize(stage, latest.current.scene, latest.current.viewMode ?? "camera");
      frame.style.width = `${size.width}px`;
      frame.style.height = `${size.height}px`;
      renderer?.resize(size.width, size.height);
    };
    setStatus("loading");
    latest.current.onCaptureReady(null);
    latest.current.onError(null);
    const observer = new ResizeObserver(resize);
    observer.observe(stage);
    window.addEventListener("resize", resize);
    void import("./directorRenderer").then(({ createDirectorRenderer }) => {
      if (!active) return;
      renderer = createDirectorRenderer(canvas, latest.current.scene, {
        onCameraChange: (camera) => latest.current.onCameraChange(camera),
        onSelectObject: (id) => latest.current.onSelectObject?.(id),
        onSelectCamera: () => latest.current.onSelectCamera?.(),
        onObjectChange: latest.current.onObjectChange ? (id, object) => latest.current.onObjectChange?.(id, object) : undefined,
        onUnavailable: unavailable,
      });
      rendererRef.current = renderer;
      renderer.update(latest.current.scene, latest.current.selectedObjectId);
      renderer.setViewMode(latest.current.viewMode ?? "camera");
      renderer.setObjectTool(latest.current.objectTool ?? "move");
      renderer.setDisabled(latest.current.disabled ?? false);
      resize();
      setStatus("ready");
      latest.current.onCaptureReady(async () => {
        if (!active || !renderer) throw new Error(t("director.viewportUnavailable"));
        try {
          const file = await renderer.capture();
          latest.current.onError(null);
          return file;
        } catch {
          const message = t("director.captureFailed");
          if (active) latest.current.onError(message);
          throw new Error(message);
        }
      });
    }).catch(unavailable);
    return () => {
      active = false;
      observer.disconnect();
      window.removeEventListener("resize", resize);
      latest.current.onCaptureReady(null);
      rendererRef.current = null;
      renderer?.dispose();
    };
  }, [attempt]);

  return <div className="director-viewport" data-view-mode={viewMode}>
    <div ref={stageRef} className="director-viewport__stage" aria-busy={status === "loading"}>
      <div ref={frameRef} className="director-viewport__frame" data-view-mode={viewMode} aria-hidden={status !== "ready"}>
        <canvas key={attempt} ref={canvasRef} tabIndex={status === "ready" && !props.disabled ? 0 : -1}
          aria-label={t("director.viewportLabel")} aria-describedby={hintId}
          aria-disabled={props.disabled} className="director-viewport__canvas ui-focusable" />
        {status === "ready" && viewMode === "camera" && props.showGuides !== false && <div className="director-viewport__thirds" aria-hidden="true" />}
      </div>
      {status === "loading" && <p className="director-viewport__loading" role="status">{t("common.loading")}</p>}
      {status === "unavailable" && <div className="director-viewport__failure">
        <Alert variant="destructive"><AlertDescription>{t("director.viewportUnavailable")}</AlertDescription></Alert>
        <Button type="button" variant="outline" disabled={props.disabled}
          onClick={() => setAttempt((current) => current + 1)}>{t("director.retryViewport")}</Button>
      </div>}
      <p id={hintId} className="director-viewport__hint" hidden={status !== "ready"}>
        {t(objectTool === "move" ? "director.moveHint" : objectTool === "rotate" ? "director.rotateHint" : "director.resizeHint")}
      </p>
    </div>
  </div>;
}

export default DirectorViewport;
