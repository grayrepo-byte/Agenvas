import { ArrowRight, ArrowsOutSimple } from "@/shared/ui/icons";
import { useId, useState, type ReactNode } from "react";
import type { RunImageOperationRequest } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { Range } from "../../shared/ui/Range";
import { MediaToolImage, MediaToolWorkspace } from "./MediaToolWorkspace";
import { imageResizeDimensions, MAX_RESIZE_PIXELS, type ImageResizeMode } from "./imageResize";

/** A local immutable result is created only after the user confirms the pixel dimensions. */
export function ImageResizePanel({ sourceWidth, sourceHeight, loading, metadataError, onRetryMetadata,
  busy, error, extraControls, submitDisabled, sourceUrl, sourceTitle, onClose, onSubmit }: {
  sourceUrl?: string; sourceTitle?: string;
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
  const presets = mode === "PERCENTAGE" ? [25, 50, 100, 200] : [512, 1024, 2048, 4096];
  return <MediaToolWorkspace title={t("image.resize.title")} description={t("media.tools.description.resize")}
    busy={busy} onClose={onClose}
    preview={<MediaToolImage src={sourceUrl ?? ""} title={sourceTitle ?? t("media.tools.original")} caption={t("image.resize.hint")}>
      {ready && dimensions ? <div className="media-tool-dimensions"><span>{sourceWidth} × {sourceHeight}</span><ArrowRight />
        <strong>{dimensions.width} × {dimensions.height} px</strong></div> : null}
    </MediaToolImage>}
    onSubmit={(event) => {
      event.preventDefault();
      if (canSubmit) onSubmit(mode === "PERCENTAGE" ? { resizeMode: mode, percentage: value } : { resizeMode: mode, longestEdge: value });
    }} footer={<><Button variant="ghost" type="button" disabled={busy} onClick={onClose}>{t("common.cancel")}</Button>
      <Button type="submit" disabled={!canSubmit}><ArrowsOutSimple data-icon="inline-start" />{t(busy ? "image.crop.processing" : "image.resize.submit")}</Button></>}>
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
      <Field><FieldLabel>{t("media.tools.quickSize")}</FieldLabel>
        <ToggleGroup type="single" spacing={2} value={String(value)} disabled={busy} aria-label={t("media.tools.quickSize")}
          className="media-tool-choice-grid media-tool-compact-choices" data-columns="4"
          onValueChange={(next) => { if (next) { if (mode === "PERCENTAGE") setPercentage(next); else setLongestEdge(next); } }}>
          {presets.map((preset) => <ToggleGroupItem key={preset} value={String(preset)}>{preset}{mode === "PERCENTAGE" ? "%" : ""}</ToggleGroupItem>)}
        </ToggleGroup>
      </Field>
      <Field data-disabled={busy} data-invalid={ready && !dimensions}>
        <FieldLabel htmlFor={inputId}>{t(mode === "PERCENTAGE" ? "image.resize.percentage" : "image.resize.longestEdge")}</FieldLabel>
        <Input id={inputId} type="number" required value={rawValue} disabled={busy}
          min={mode === "PERCENTAGE" ? 0.01 : 1} max={mode === "PERCENTAGE" ? 1000 : 40000}
          step={mode === "PERCENTAGE" ? "any" : 1} aria-invalid={ready && !dimensions}
          onChange={(event) => mode === "PERCENTAGE" ? setPercentage(event.target.value) : setLongestEdge(event.target.value)} />
        {mode === "PERCENTAGE" ? <Range min={0.01} max={1000} step={0.01} value={Number.isFinite(value) ? value : 0.01}
          disabled={busy} aria-label={t("image.resize.percentage")} onChange={(event) => setPercentage(event.target.value)} /> : null}
      </Field>
    </FieldGroup>
    {ready && !dimensions ? <p role="alert">{t("image.resize.invalidValue")}</p> : null}
    {overLimit ? <p role="alert">{t("image.resize.pixelLimit")}</p> : null}
    {ready && dimensions ? <p role="status">{t("image.resize.targetSize", { 0: dimensions.width, 1: dimensions.height })}</p> : null}
    {extraControls}
    {error ? <p role="alert">{error.message}</p> : null}
  </MediaToolWorkspace>;
}
