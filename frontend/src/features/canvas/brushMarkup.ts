import { t } from "../../shared/i18n";
export type MarkupPoint = { x: number; y: number };
export type MarkupTool = "BRUSH" | "ERASER" | "RECTANGLE" | "ARROW" | "TEXT";
export type MarkupStroke = {
  tool: MarkupTool; color: string; width: number; points: MarkupPoint[]; text?: string;
};
const ARROW_HEAD_WIDTH_MULTIPLIER = 4;
const ARROW_HEAD_ANGLE = Math.PI / 6;
const TEXT_SIZE_MULTIPLIER = 6;

/** Coordinates and widths scale with the image, so preview and original-size export agree. */
export function renderMarkup(canvas: HTMLCanvasElement, strokes: readonly MarkupStroke[]) {
  const context = canvas.getContext("2d");
  if (!context) throw new Error(t("浏览器无法打开图片编辑画布。"));
  context.clearRect(0, 0, canvas.width, canvas.height);
  for (const stroke of strokes) {
    const start = stroke.points[0];
    if (!start) continue;
    const end = stroke.points.at(-1) ?? start;
    const x = start.x * canvas.width; const y = start.y * canvas.height;
    const endX = end.x * canvas.width; const endY = end.y * canvas.height;
    context.save();
    context.globalCompositeOperation = stroke.tool === "ERASER" ? "destination-out" : "source-over";
    context.strokeStyle = stroke.color; context.fillStyle = stroke.color;
    context.lineWidth = stroke.width * canvas.width;
    context.lineCap = "round"; context.lineJoin = "round";
    if (stroke.tool === "TEXT") {
      context.font = `${context.lineWidth * TEXT_SIZE_MULTIPLIER}px sans-serif`;
      context.textBaseline = "top";
      context.fillText(stroke.text ?? "", x, y);
    } else if (stroke.tool === "RECTANGLE") {
      context.strokeRect(x, y, endX - x, endY - y);
    } else {
      context.beginPath(); context.moveTo(x, y);
      for (const point of stroke.points.slice(1)) {
        context.lineTo(point.x * canvas.width, point.y * canvas.height);
      }
      context.stroke();
      if (stroke.points.length === 1) {
        context.beginPath(); context.arc(x, y, context.lineWidth / 2, 0, Math.PI * 2); context.fill();
      }
      if (stroke.tool === "ARROW" && (endX !== x || endY !== y)) {
        const angle = Math.atan2(endY - y, endX - x);
        const length = context.lineWidth * ARROW_HEAD_WIDTH_MULTIPLIER;
        context.beginPath(); context.moveTo(endX, endY);
        context.lineTo(endX - length * Math.cos(angle - ARROW_HEAD_ANGLE),
          endY - length * Math.sin(angle - ARROW_HEAD_ANGLE));
        context.lineTo(endX - length * Math.cos(angle + ARROW_HEAD_ANGLE),
          endY - length * Math.sin(angle + ARROW_HEAD_ANGLE));
        context.closePath(); context.fill();
      }
    }
    context.restore();
  }
}

/** Erasing touches only the annotation layer; the original image remains intact underneath. */
export async function exportMarkup(image: HTMLImageElement, strokes: readonly MarkupStroke[]) {
  const overlay = document.createElement("canvas");
  overlay.width = image.naturalWidth; overlay.height = image.naturalHeight;
  renderMarkup(overlay, strokes);
  const output = document.createElement("canvas");
  output.width = overlay.width; output.height = overlay.height;
  const context = output.getContext("2d");
  if (!context) throw new Error(t("浏览器无法保存图片。"));
  context.drawImage(image, 0, 0); context.drawImage(overlay, 0, 0);
  const blob = await new Promise<Blob>((resolve, reject) => output.toBlob((result) =>
    result ? resolve(result) : reject(new Error(t("图片导出失败，请重试。"))), "image/png"));
  return new File([blob], t("画笔标注.png"), { type: "image/png" });
}
