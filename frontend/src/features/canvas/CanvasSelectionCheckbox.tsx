import { Check } from "@phosphor-icons/react";
import { t, useLocale } from "../../shared/i18n";
import "./CanvasSelectionCheckbox.css";

/** Isolate the toggle from node selection, dragging and viewport gestures. */
export function CanvasSelectionCheckbox({ title, selected, onToggle, inline = false }: {
  title: string; selected: boolean; onToggle: () => void; inline?: boolean;
}) {
  useLocale();
  return <label className={`canvas-selection-checkbox nodrag nopan${inline ? " canvas-selection-checkbox--inline" : ""}`}
    onPointerDown={(event) => event.stopPropagation()}
    onClick={(event) => event.stopPropagation()}
    onDoubleClick={(event) => event.stopPropagation()}>
    <input type="checkbox" checked={selected} onChange={onToggle}
      aria-label={t("canvas.selection.selectCard", { "0": title })} />
    <Check weight="bold" aria-hidden="true" />
  </label>;
}
