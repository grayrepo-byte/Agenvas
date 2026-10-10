import { X } from "@/shared/ui/icons";
import { useId, useState, type ReactNode } from "react";
import type { RunImageOperationRequest } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { imageResizeDimensions, MAX_RESIZE_PIXELS, type ImageResizeMode } from "./imageResize";

/** A local immutable result is created only after the user confirms the pixel dimensions. */
export function ImageResizePanel({ sourceWidth, sourceHeight, loading, metadataError, onRetryMetadata,
  busy, error, extraControls, submitDisabled, onClose, onSubmit }: {
  sourceWidth?: number | null; sourceHeight?: number | null; loading: boolean; metadataError: Error | null;
  onRetryMetadata: () => void; busy: boolean; error: Error | null; extraControls: ReactNode; submitDisabled: boolean;
  onClose: () => void; onSubmit: (parameters: RunImageOperationRequest["parameters"]) => void;
}) {
  useLocale();
  const inputId = useId();
  const [mode, setMode] = useState<ImageResizeMode>("PERCENTAGE");
  const [percentage, setPercentage] = useState("50");
  const [longestEdge, setLongestEdge] = useState("1024");
  const rawValue = mode === "PERCENTAGE" ? percentage : longestEdge;
  const value = rawValue.trim() ? Number(rawValue) : NaN;
  const dimensions = sourceWidth && sourceHeight ? imageResizeDimensions(sourceWidth, sourceHeight, mode, value) : null;
  const overLimit = dimensions && dimensions.width * dimensions.height > MAX_RESIZE_PIXELS;
  const ready = !loading && !metadataError && Boolean(sourceWidth && sourceHeight);
  const canSubmit = ready && dimensions && !overLimit && !busy && !submitDisabled;
  return <form className="media-operation-panel nodrag nowheel nopan" role="dialog" aria-label={t("image.resize.title")}
    onSubmit={(event) => {
      event.preventDefault();
      if (canSubmit) onSubmit(mode === "PERCENTAGE" ? { resizeMode: mode, percentage: value } : { resizeMode: mode, longestEdge: value });
    }}>
    <header><strong>{t("image.resize.title")}</strong><Button variant="ghost" type="button" disabled={busy}
      aria-label={t("common.close")} onClick={onClose}><X data-icon="inline-start" /></Button></header>
    {loading ? <p role="status">{t("app.pageLoading")}</p> : metadataError || !ready
      ? <p role="alert">{t("image.resize.metadataFailed")}<Button variant="ghost" type="button" disabled={busy} onClick={onRetryMetadata}>{t("common.retry")}</Button></p>
      : <p>{t("image.resize.sourceSize", { 0: sourceWidth ?? 0, 1: sourceHeight ?? 0 })}</p>}
    <FieldGroup>
      <Field data-disabled={busy}>
        <FieldLabel>{t("image.resize.mode")}</FieldLabel>
        <ToggleGroup type="single" variant="outline" value={mode} disabled={busy} aria-label={t("image.resize.mode")}
          onValueChange={(next) => { if (next === "PERCENTAGE" || next === "LONGEST_EDGE") setMode(next); }}>
          <ToggleGroupItem value="PERCENTAGE">{t("image.resize.percentageMode")}</ToggleGroupItem>
          <ToggleGroupItem value="LONGEST_EDGE">{t("image.resize.longestEdgeMode")}</ToggleGroupItem>
        </ToggleGroup>
      </Field>
      <Field data-disabled={busy} data-invalid={ready && !dimensions}>
        <FieldLabel htmlFor={inputId}>{t(mode === "PERCENTAGE" ? "image.resize.percentage" : "image.resize.longestEdge")}</FieldLabel>
        <Input id={inputId} type="number" required value={rawValue} disabled={busy}
          min={mode === "PERCENTAGE" ? 0.01 : 1} max={mode === "PERCENTAGE" ? 1000 : 40000}
          step={mode === "PERCENTAGE" ? "any" : 1} aria-invalid={ready && !dimensions}
          onChange={(event) => mode === "PERCENTAGE" ? setPercentage(event.target.value) : setLongestEdge(event.target.value)} />
      </Field>
    </FieldGroup>
    {ready && !dimensions ? <p role="alert">{t("image.resize.invalidValue")}</p> : null}
    {overLimit ? <p role="alert">{t("image.resize.pixelLimit")}</p> : null}
    {ready && dimensions ? <p role="status">{t("image.resize.targetSize", { 0: dimensions.width, 1: dimensions.height })}</p> : null}
    <p>{t("image.resize.hint")}</p>
    {extraControls}
    {error ? <p role="alert">{error.message}</p> : null}
    <footer><Button variant="ghost" type="button" disabled={busy} onClick={onClose}>{t("common.cancel")}</Button>
      <Button type="submit" disabled={!canSubmit}>{t(busy ? "image.crop.processing" : "image.resize.submit")}</Button></footer>
  </form>;
}
