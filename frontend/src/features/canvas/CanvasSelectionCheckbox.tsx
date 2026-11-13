import { cn } from "cn";
import { t, useLocale } from "../../shared/i18n";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import "./CanvasSelectionCheckbox.css";

/** Isolate the toggle from node selection, dragging and viewport gestures. */
export function CanvasSelectionCheckbox({
  title,
  selected,
  onToggle,
  inline = false,
}: {
  title: string;
  selected: boolean;
  onToggle: () => void;
  inline?: boolean;
}) {
  useLocale();
  return (
    <Checkbox
      className={cn("canvas-selection-checkbox nodrag nopan", inline && "canvas-selection-checkbox--inline")}
      checked={selected}
      onCheckedChange={onToggle}
      aria-label={t("canvas.selection.selectCard", { "0": title })}
      onPointerDown={event => event.stopPropagation()}
      onClick={event => event.stopPropagation()}
      onDoubleClick={event => event.stopPropagation()}
    />
  );
}
