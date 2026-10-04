import { ArrowsInSimple,ArrowsOutSimple,ArrowClockwise,MagnifyingGlassMinus,MagnifyingGlassPlus,WarningCircle,X } from "@phosphor-icons/react";
import { useCallback,useEffect,useRef,useState,type KeyboardEvent,type ReactNode,type SyntheticEvent } from "react";
import Lightbox,{ IconButton,type ControllerRef,type Slide } from "yet-another-react-lightbox";
import Fullscreen from "yet-another-react-lightbox/plugins/fullscreen";
import Video from "yet-another-react-lightbox/plugins/video";
import Zoom from "yet-another-react-lightbox/plugins/zoom";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import "yet-another-react-lightbox/styles.css";
import "./MediaPreviewDialog.css";

const PREVIEW_ICON_SIZE = 22;
const PREVIEW_FADE_DURATION_MS = 150;
const PREVIEW_SLIDE_PADDING_PX = 24;
const PREVIEW_PLUGINS = [Zoom,Fullscreen,Video];

type MediaPreviewDialogProps = {
  kind: "image" | "video";
  title: string;
  sourceUrl: string;
  posterSrc?: string;
  contentType?: string;
  width?: number;
  height?: number;
  onClose: () => void;
};

function stopPreviewPropagation(event: SyntheticEvent) {
  event.stopPropagation();
}

/** Keep canvas shortcuts outside the portal while preserving YARL's inner sensors. */
function containPreviewKeyboard(event: KeyboardEvent<HTMLDivElement>) {
  event.stopPropagation();
  if (event.key !== "Tab") return;
  const controls = Array.from(event.currentTarget.querySelectorAll<HTMLElement>(
    "button:not(:disabled), video[controls], [tabindex='0']",
  )).filter((element) => !element.closest("[hidden]"));
  const first = controls[0];
  const last = controls[controls.length - 1];
  if (event.shiftKey && (document.activeElement === first || !controls.includes(document.activeElement as HTMLElement))) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && (document.activeElement === last || !controls.includes(document.activeElement as HTMLElement))) {
    event.preventDefault();
    first?.focus();
  }
}

function PreviewLoadError({ onRetry }: { onRetry: () => void }) {
  return <div className="media-preview-error" role="alert">
    <WarningCircle size={PREVIEW_ICON_SIZE} aria-hidden="true" />
    <p>{t("media.preview.loadFailed")}</p>
    <button type="button" className="yarl__button media-preview-retry" onClick={onRetry}>
      <ArrowClockwise size={PREVIEW_ICON_SIZE} aria-hidden="true" />
      {t("media.preview.retry")}
    </button>
  </div>;
}

/** Video's native error does not reach YARL's image error placeholder. */
function PreviewSlideContainer({ kind,children,onRetry }: {
  kind: MediaPreviewDialogProps["kind"]; children: ReactNode; onRetry: () => void;
}) {
  const [status, setStatus] = useState<"LOADING" | "READY" | "FAILED">("LOADING");
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const handleContainerRef = useCallback((node: HTMLDivElement | null) => {
    // Closing or retrying removes the native player; stop any active playback first.
    if (!node && videoRef.current && !videoRef.current.paused) videoRef.current.pause();
  }, []);
  function ready(event: SyntheticEvent<HTMLDivElement>) {
    if (event.target instanceof HTMLVideoElement) videoRef.current = event.target;
    setStatus("READY");
  }
  return <div className="media-preview-slide" ref={handleContainerRef} onErrorCapture={kind === "video" ? (event) => {
    const video = event.currentTarget.querySelector("video");
    if (video && !video.paused) video.pause();
    setStatus("FAILED");
  } : undefined} onLoadedMetadataCapture={kind === "video" ? ready : undefined}
    onCanPlayCapture={kind === "video" ? ready : undefined}
    onPlayingCapture={kind === "video" ? ready : undefined}
    onPlayCapture={kind === "video" ? ready : undefined}>
    <div className="media-preview-content" hidden={kind === "video" && status === "FAILED"}>{children}</div>
    {kind === "video" && status === "FAILED" ? <PreviewLoadError onRetry={onRetry} /> : null}
    {kind === "video" && status === "LOADING" ? <div className="media-preview-loading">
      <LoadingState compact label={t("media.preview.loading")} />
    </div> : null}
  </div>;
}

export function MediaPreviewDialog({ kind,title,sourceUrl,posterSrc,contentType,width,height,onClose }: MediaPreviewDialogProps) {
  useLocale();
  const controllerRef = useRef<ControllerRef>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const previousFocus = useRef(document.activeElement);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => () => {
    // Canvas pointer handlers may prevent YARL from observing a relatedTarget.
    // Its portal has removed inert by this passive cleanup, so restore explicitly.
    if (previousFocus.current instanceof HTMLElement && previousFocus.current.isConnected) previousFocus.current.focus();
  }, []);
  const previewLabel = kind === "image"
    ? t("media.preview.imageLabel", { "0": title })
    : t("media.preview.videoLabel", { "0": title });
  const slides: Slide[] = [kind === "image" ? {
    type: "image", src: sourceUrl, alt: title, width, height,
  } : {
    type: "video", poster: posterSrc, width, height,
    // An absent MIME lets the browser inspect the source instead of guessing MP4.
    sources: [{ src: sourceUrl, type: contentType ?? "" }],
    autoPlay: false, controls: true, preload: "metadata", playsInline: true,
  }];
  function retry() {
    closeRef.current?.focus();
    setAttempt((value) => value + 1);
  }
  function stopVideoPlayback() {
    const video = closeRef.current?.closest(".media-preview-dialog")?.querySelector("video");
    if (video && !video.paused) video.pause();
  }

  return <Lightbox open close={onClose} slides={slides} plugins={PREVIEW_PLUGINS}
    className="media-preview-dialog nodrag nowheel nopan"
    carousel={{ finite: true, preload: 0, padding: PREVIEW_SLIDE_PADDING_PX }}
    controller={{ ref: controllerRef, closeOnBackdropClick: true, disableSwipeNavigation: true }}
    animation={{ fade: PREVIEW_FADE_DURATION_MS }}
    zoom={{ scrollToZoom: true }}
    video={{ autoPlay: false, controls: true, preload: "metadata", playsInline: true }}
    toolbar={{ buttons: kind === "image" ? ["zoom", "fullscreen", "close"] : ["fullscreen", "close"] }}
    labels={{
      Lightbox: previewLabel, "Photo gallery": previewLabel, Carousel: previewLabel, Slide: previewLabel,
      "{index} of {total}": previewLabel,
      Close: t("media.preview.close"), "Zoom in": t("media.preview.zoomIn"), "Zoom out": t("media.preview.zoomOut"),
      "Enter Fullscreen": t("media.preview.enterFullscreen"), "Exit Fullscreen": t("media.preview.exitFullscreen"),
    }}
    portal={{ container: {
      onKeyDown: containPreviewKeyboard, onKeyUp: stopPreviewPropagation,
      onPointerDown: stopPreviewPropagation, onPointerUp: stopPreviewPropagation, onPointerMove: stopPreviewPropagation,
      onMouseDown: stopPreviewPropagation, onMouseUp: stopPreviewPropagation,
      onWheel: stopPreviewPropagation, onClick: stopPreviewPropagation, onDoubleClick: stopPreviewPropagation,
    } }}
    on={{ entered: () => closeRef.current?.focus(), exiting: stopVideoPlayback }}
    render={{
      buttonPrev: () => null, buttonNext: () => null,
      buttonZoom: kind === "video" ? () => null : undefined,
      buttonClose: () => <IconButton key="close" ref={closeRef} label="Close" icon={X}
        onClick={() => controllerRef.current?.close()} />,
      iconZoomIn: () => <MagnifyingGlassPlus size={PREVIEW_ICON_SIZE} aria-hidden="true" />,
      iconZoomOut: () => <MagnifyingGlassMinus size={PREVIEW_ICON_SIZE} aria-hidden="true" />,
      iconEnterFullscreen: () => <ArrowsOutSimple size={PREVIEW_ICON_SIZE} aria-hidden="true" />,
      iconExitFullscreen: () => <ArrowsInSimple size={PREVIEW_ICON_SIZE} aria-hidden="true" />,
      iconLoading: () => <LoadingState compact label={t("media.preview.loading")} />,
      iconError: () => <PreviewLoadError onRetry={retry} />,
      slideContainer: ({ children }) => <PreviewSlideContainer key={`${sourceUrl}:${attempt}`}
        kind={kind} onRetry={retry}>{children}</PreviewSlideContainer>,
    }} />;
}
