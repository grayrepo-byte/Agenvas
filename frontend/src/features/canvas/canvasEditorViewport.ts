export type CanvasEditorViewportInput = {
  canvas: { width: number; height: number };
  nodeSize: { width: number; height: number };
  currentZoom: number;
  minZoom: number;
  editorHeight: number;
  editorGap: number;
  topToolbarSpace: number;
  margin: number;
};

/** Reserve screen space for a node's toolbar and editor without changing its layout.
 * Keep the user's zoom when possible, then move the node only as far upward as needed.
 * Add centerOffsetY to the node's world-space center when calling React Flow's setCenter.
 */
export function calculateCanvasEditorViewport({
  canvas, nodeSize, currentZoom, minZoom, editorHeight, editorGap, topToolbarSpace, margin,
}: CanvasEditorViewportInput): { zoom: number; centerOffsetY: number } {
  const availableWidth = canvas.width - margin * 2;
  const availableHeight = canvas.height - margin * 2 - topToolbarSpace - editorGap - editorHeight;
  const zoom = availableWidth <= 0 || availableHeight <= 0 ? minZoom
    : Math.max(minZoom, Math.min(currentZoom, availableWidth / nodeSize.width,
      availableHeight / nodeSize.height));
  const canvasCenterY = canvas.height / 2;
  const halfNodeHeight = nodeSize.height * zoom / 2;
  const topLimit = margin + topToolbarSpace + halfNodeHeight;
  const bottomLimit = canvas.height - margin - editorHeight - editorGap - halfNodeHeight;
  // At minZoom the full stack may still not fit. Keep the editor's scrolling fallback
  // instead of moving the node below the canvas center to satisfy the top boundary.
  const targetY = Math.min(canvasCenterY, Math.max(topLimit, Math.min(canvasCenterY, bottomLimit)));
  return { zoom, centerOffsetY: (canvasCenterY - targetY) / zoom };
}
