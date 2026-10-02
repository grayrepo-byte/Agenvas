import { Check,Crop,X } from "@phosphor-icons/react";
import {
useEffect,useRef,useState,type CSSProperties,
type KeyboardEvent,type PointerEvent as ReactPointerEvent
} from "react";
import { createPortal } from "react-dom";
import { ApiError,type RunImageOperationRequest } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Select } from "../../shared/ui/Select";
import "./CropPanel.css";

type CropParameters = RunImageOperationRequest["parameters"];
type CropRect = { x: number; y: number; width: number; height: number };
type CropHandle = "move" | "n" | "ne" | "e" | "se" | "s" | "sw" | "w" | "nw";
type CropRatio = "original" | "free" | "1:1" | "4:3" | "3:4" | "16:9" | "9:16";

const DEFAULT_INSET = 0.08;
const MIN_CROP_SIZE = 0.08;
const KEYBOARD_STEP = 0.01;
const COORDINATE_PRECISION = 4;
const FALLBACK_IMAGE_WIDTH = 3;
const FALLBACK_IMAGE_HEIGHT = 2;

const RATIO_OPTIONS: ReadonlyArray<{ value: CropRatio; label: string; ratio?: number }> = [
  { value: "original", get label() { return t("image.crop.originalRatio"); } },
  { value: "free", get label() { return t("image.crop.freeRatio"); } },
  { value: "1:1", label: "1:1", ratio: 1 },
  { value: "4:3", label: "4:3", ratio: 4 / 3 },
  { value: "3:4", label: "3:4", ratio: 3 / 4 },
  { value: "16:9", label: "16:9", ratio: 16 / 9 },
  { value: "9:16", label: "9:16", ratio: 9 / 16 },
];

const HANDLE_LABELS: Record<Exclude<CropHandle, "move">, string> = {
  get n() { return t("image.crop.resizeTop"); }, get ne() { return t("image.crop.resizeTopRight"); }, get e() { return t("image.crop.resizeRight"); },
  get se() { return t("image.crop.resizeBottomRight"); }, get s() { return t("image.crop.resizeBottom"); }, get sw() { return t("image.crop.resizeBottomLeft"); },
  get w() { return t("image.crop.resizeLeft"); }, get nw() { return t("image.crop.resizeTopLeft"); },
};

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function rounded(value: number) {
  return Number(value.toFixed(COORDINATE_PRECISION));
}

function fitRatio(ratio: number, stageRatio: number): CropRect {
  const available = 1 - DEFAULT_INSET * 2;
  const heightForWidth = available * stageRatio / ratio;
  const width = heightForWidth <= available ? available : available * ratio / stageRatio;
  const height = heightForWidth <= available ? heightForWidth : available;
  return { x: (1 - width) / 2, y: (1 - height) / 2, width, height };
}

function resizeCrop(start: CropRect, handle: CropHandle, dx: number, dy: number,
    stageWidth: number, stageHeight: number, lockedRatio: number | null): CropRect {
  if (handle === "move") return {
    ...start,
    x: clamp(start.x + dx, 0, 1 - start.width),
    y: clamp(start.y + dy, 0, 1 - start.height),
  };

  const east = handle.includes("e");
  const west = handle.includes("w");
  const north = handle.includes("n");
  const south = handle.includes("s");

  if (!lockedRatio) {
    const left = west ? clamp(start.x + dx, 0, start.x + start.width - MIN_CROP_SIZE) : start.x;
    const right = east ? clamp(start.x + start.width + dx, start.x + MIN_CROP_SIZE, 1)
      : start.x + start.width;
    const top = north ? clamp(start.y + dy, 0, start.y + start.height - MIN_CROP_SIZE) : start.y;
    const bottom = south ? clamp(start.y + start.height + dy, start.y + MIN_CROP_SIZE, 1)
      : start.y + start.height;
    return { x: left, y: top, width: right - left, height: bottom - top };
  }

  const normalizedHeightPerWidth = stageWidth / (lockedRatio * stageHeight);
  const normalizedWidthPerHeight = 1 / normalizedHeightPerWidth;
  if ((east || west) && (north || south)) {
    const anchorX = east ? start.x : start.x + start.width;
    const anchorY = south ? start.y : start.y + start.height;
    const pointerX = (east ? start.x + start.width : start.x) + dx;
    const pointerY = (south ? start.y + start.height : start.y) + dy;
    const widthFromX = Math.abs(pointerX - anchorX);
    const widthFromY = Math.abs(pointerY - anchorY) * normalizedWidthPerHeight;
    const preferHorizontal = Math.abs(dx * stageWidth) >= Math.abs(dy * stageHeight);
    const requestedWidth = preferHorizontal ? widthFromX : widthFromY;
    const maxWidthByX = east ? 1 - anchorX : anchorX;
    const maxHeight = south ? 1 - anchorY : anchorY;
    const maxWidth = Math.min(maxWidthByX, maxHeight * normalizedWidthPerHeight);
    const width = clamp(requestedWidth, MIN_CROP_SIZE, maxWidth);
    const height = width * normalizedHeightPerWidth;
    return {
      x: east ? anchorX : anchorX - width,
      y: south ? anchorY : anchorY - height,
      width,
      height,
    };
  }

  if (east || west) {
    const anchorX = east ? start.x : start.x + start.width;
    const pointerX = (east ? start.x + start.width : start.x) + dx;
    const centerY = start.y + start.height / 2;
    const maxHeight = 2 * Math.min(centerY, 1 - centerY);
    const maxWidth = Math.min(east ? 1 - anchorX : anchorX,
      maxHeight * normalizedWidthPerHeight);
    const width = clamp(Math.abs(pointerX - anchorX), MIN_CROP_SIZE, maxWidth);
    const height = width * normalizedHeightPerWidth;
    return { x: east ? anchorX : anchorX - width, y: centerY - height / 2, width, height };
  }

  const anchorY = south ? start.y : start.y + start.height;
  const pointerY = (south ? start.y + start.height : start.y) + dy;
  const centerX = start.x + start.width / 2;
  const maxWidth = 2 * Math.min(centerX, 1 - centerX);
  const maxHeight = Math.min(south ? 1 - anchorY : anchorY,
    maxWidth * normalizedHeightPerWidth);
  const height = clamp(Math.abs(pointerY - anchorY),
    MIN_CROP_SIZE * normalizedHeightPerWidth, maxHeight);
  const width = height * normalizedWidthPerHeight;
  return { x: centerX - width / 2, y: south ? anchorY : anchorY - height, width, height };
}

/** Direct-manipulation crop workspace; values remain normalized for the server command. */
export function CropPanel({ sourceUrl, sourceWidth, sourceHeight, busy, error, onClose, onSubmit }: {
  sourceUrl: string;
  sourceWidth?: number | null;
  sourceHeight?: number | null;
  busy: boolean;
  error: Error | null;
  onClose: () => void;
  onSubmit: (parameters: CropParameters) => void;
}) {
  useLocale();
  const [imageSize, setImageSize] = useState({
    width: sourceWidth ?? FALLBACK_IMAGE_WIDTH,
    height: sourceHeight ?? FALLBACK_IMAGE_HEIGHT,
  });
  const [ratio, setRatio] = useState<CropRatio>("original");
  const [crop, setCrop] = useState<CropRect>({
    x: DEFAULT_INSET, y: DEFAULT_INSET,
    width: 1 - DEFAULT_INSET * 2, height: 1 - DEFAULT_INSET * 2,
  });
  const [drag, setDrag] = useState<{
    handle: CropHandle; startX: number; startY: number; startCrop: CropRect;
    stageWidth: number; stageHeight: number;
  } | null>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const stageRatio = imageSize.width / imageSize.height;
  const selectedOption = RATIO_OPTIONS.find((option) => option.value === ratio);
  const lockedRatio = ratio === "free" ? null : selectedOption?.ratio ?? stageRatio;

  useEffect(() => {
    if (!drag) return;
    const activeDrag = drag;
    function move(event: PointerEvent) {
      event.preventDefault();
      const dx = (event.clientX - activeDrag.startX) / activeDrag.stageWidth;
      const dy = (event.clientY - activeDrag.startY) / activeDrag.stageHeight;
      setCrop(resizeCrop(activeDrag.startCrop, activeDrag.handle, dx, dy,
        activeDrag.stageWidth, activeDrag.stageHeight, lockedRatio));
    }
    function finish() { setDrag(null); }
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", finish, { once: true });
    const previousUserSelect = document.body.style.userSelect;
    document.body.style.userSelect = "none";
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", finish);
      document.body.style.userSelect = previousUserSelect;
    };
  }, [drag, lockedRatio]);

  function startDrag(handle: CropHandle, event: ReactPointerEvent<HTMLElement>) {
    const bounds = stageRef.current?.getBoundingClientRect();
    if (!bounds?.width || !bounds.height) return;
    event.preventDefault();
    event.stopPropagation();
    setDrag({ handle, startX: event.clientX, startY: event.clientY,
      startCrop: crop, stageWidth: bounds.width, stageHeight: bounds.height });
  }

  function selectRatio(nextRatio: CropRatio) {
    setRatio(nextRatio);
    if (nextRatio === "free") return;
    const option = RATIO_OPTIONS.find((candidate) => candidate.value === nextRatio);
    setCrop(fitRatio(option?.ratio ?? stageRatio, stageRatio));
  }

  function adjustWithKeyboard(handle: CropHandle, event: KeyboardEvent<HTMLButtonElement>) {
    const directions: Record<string, readonly [number, number]> = {
      ArrowLeft: [-KEYBOARD_STEP, 0], ArrowRight: [KEYBOARD_STEP, 0],
      ArrowUp: [0, -KEYBOARD_STEP], ArrowDown: [0, KEYBOARD_STEP],
    };
    const direction = directions[event.key];
    if (!direction) return;
    event.preventDefault();
    const bounds = stageRef.current?.getBoundingClientRect();
    const stageWidth = bounds?.width || imageSize.width;
    const stageHeight = bounds?.height || imageSize.height;
    setCrop(resizeCrop(crop, handle, direction[0], direction[1],
      stageWidth, stageHeight, lockedRatio));
  }

  function submit() {
    if (busy) return;
    onSubmit({ x: rounded(crop.x), y: rounded(crop.y),
      width: rounded(crop.width), height: rounded(crop.height) });
  }

  const cropStyle = {
    left: `${crop.x * 100}%`, top: `${crop.y * 100}%`,
    width: `${crop.width * 100}%`, height: `${crop.height * 100}%`,
  } satisfies CSSProperties;

  return createPortal(<div className="crop-backdrop nodrag nowheel nopan"
    onPointerDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <div className="crop-dialog" role="dialog" aria-label={t("image.crop.title")}
      onKeyDown={(event) => { if (event.key === "Escape") onClose(); }}>
      <div className="crop-image-shell" ref={stageRef} aria-label={t("image.crop.workspace")}>
        <img src={sourceUrl} alt={t("image.crop.previewLabel")} draggable={false}
          onLoad={(event) => {
            if (sourceWidth && sourceHeight) return;
            const image = event.currentTarget;
            if (image.naturalWidth && image.naturalHeight) {
              setImageSize({ width: image.naturalWidth, height: image.naturalHeight });
            }
          }} />
        <div className={`crop-selection${drag ? " is-dragging" : ""}`} style={cropStyle}>
          <Button variant="ghost" type="button" className="crop-selection-surface"
            aria-label={t("image.crop.moveFrame")} onPointerDown={(event) => startDrag("move", event)}
            onKeyDown={(event) => adjustWithKeyboard("move", event)} />
          {(Object.keys(HANDLE_LABELS) as Array<Exclude<CropHandle, "move">>).map((handle) =>
            <Button variant="ghost" type="button" key={handle} className={`crop-handle crop-handle-${handle}`}
              aria-label={HANDLE_LABELS[handle]} onPointerDown={(event) => startDrag(handle, event)}
              onKeyDown={(event) => adjustWithKeyboard(handle, event)} />)}
        </div>
      </div>

      {error ? <p className="crop-error" role="alert">
        {error instanceof ApiError ? error.message : t("image.crop.submitFailed")}</p> : null}

      <footer className="crop-toolbar">
        <Button variant="ghost" type="button" className="crop-cancel" onClick={onClose}>
          <X size={18} weight="bold" />{t("common.cancel")}</Button>
        <span className="crop-toolbar-divider" aria-hidden="true" />
        <label className="crop-ratio-control"><Crop size={19} />
          <Select variant="ghost" density="compact" aria-label={t("image.crop.aspectRatio")} value={ratio}
            onChange={(event) => selectRatio(event.target.value as CropRatio)}>
            {RATIO_OPTIONS.map((option) => <option key={option.value}
              value={option.value}>{option.label}</option>)}
          </Select>
        </label>
        <Button variant="ghost" type="button" className="crop-confirm" disabled={busy} onClick={submit}>
          <Check size={18} weight="bold" />{busy ? t("image.crop.processing") : t("image.crop.confirm")}</Button>
      </footer>
    </div>
  </div>, document.body);
}
