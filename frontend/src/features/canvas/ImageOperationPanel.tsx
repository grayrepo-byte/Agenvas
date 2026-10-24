import { ArrowRight, ArrowsOutSimple, Camera, CheckCircle, Image as ImageIcon, PersonSimple, Rectangle, Smiley, Square } from "@/shared/ui/icons";
import { SmileyAngry } from "@phosphor-icons/react/dist/csr/SmileyAngry";
import { SmileyMeh } from "@phosphor-icons/react/dist/csr/SmileyMeh";
import { SmileyNervous } from "@phosphor-icons/react/dist/csr/SmileyNervous";
import { SmileySad } from "@phosphor-icons/react/dist/csr/SmileySad";
import { SmileyWink } from "@phosphor-icons/react/dist/csr/SmileyWink";
import { useId, useState, type ReactNode } from "react";
import { ApiError, type ImageOperation, type MediaCapability, type RunImageOperationRequest } from "../../shared/api/client";
import { t, useLocale, type MessageKey } from "../../shared/i18n";
import { imageOperationLabel } from "../../shared/mediaFunctions";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { MediaToolImage, MediaToolWorkspace } from "./MediaToolWorkspace";
import { THREE_VIEW_OPTIONS, type ThreeViewType } from "./imageOperationOptions";

type Parameters = RunImageOperationRequest["parameters"];
type AspectRatio = NonNullable<Parameters["aspectRatio"]>;
const RATIOS = ["1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9"] as const;
const ANGLES = [
  ["FRONT", "media.card.front"], ["LEFT_THREE_QUARTER", "media.card.leftThreeQuarter"],
  ["RIGHT_THREE_QUARTER", "media.card.rightThreeQuarter"], ["LEFT_PROFILE", "media.card.leftProfile"],
  ["RIGHT_PROFILE", "media.card.rightProfile"], ["HIGH_ANGLE", "media.card.highAngle"],
  ["LOW_ANGLE", "media.card.lowAngle"], ["BACK", "media.card.back"],
] as const;
const EXPRESSIONS = [
  { key: "smile", icon: Smiley }, { key: "joy", icon: SmileyWink },
  { key: "calm", icon: SmileyMeh }, { key: "surprise", icon: SmileyNervous },
  { key: "sad", icon: SmileySad }, { key: "angry", icon: SmileyAngry },
] as const;
const INSTRUCTIONS: Partial<Record<ImageOperation, readonly [MessageKey, MessageKey, boolean?]>> = {
  OUTPAINT: ["media.card.additionalInstruction", "media.card.outpaintInstructionPlaceholder"],
  THREE_VIEW: ["media.card.subjectInstruction", "media.card.threeViewInstructionPlaceholder"],
  LAYER_SPLIT: ["media.card.layerInstruction", "media.card.layerInstructionPlaceholder"],
  EXPRESSION_EDIT: ["media.card.expression", "media.card.expressionInstructionPlaceholder", true],
  REMOVE_BACKGROUND: ["media.card.subjectInstruction", "media.card.backgroundInstructionPlaceholder"],
  OBJECT_REMOVE: ["media.card.removeInstruction", "media.card.removeInstructionPlaceholder", true],
  VIEW_ANGLE: ["media.card.additionalInstruction", "media.card.angleInstructionPlaceholder"],
};

/** Operation choices map directly to the existing command; previews only explain the chosen intent. */
export function ImageOperationPanel({ operation, initialThreeViewType, capabilities, busy, error, extraControls, submitDisabled,
  sourceUrl, sourceTitle, sourceWidth, sourceHeight, onClose, onSubmit }: {
  operation: ImageOperation; capabilities: MediaCapability[]; busy: boolean; error: Error | null;
  sourceUrl: string; sourceTitle: string; sourceWidth?: number | null; sourceHeight?: number | null;
  initialThreeViewType?: ThreeViewType; extraControls: ReactNode; submitDisabled: boolean;
  onClose: () => void; onSubmit: (parameters: Parameters, instruction?: string) => void;
}) {
  useLocale();
  const id = useId();
  const capability = capabilities[0];
  const cloud = capability?.adapterId !== "LOCAL_IMAGE_PROCESSOR";
  const [instruction, setInstruction] = useState("");
  const [scale, setScale] = useState<2 | 4>(2);
  const initialType = initialThreeViewType ?? "CHARACTER";
  const [threeViewType, setThreeViewType] = useState<ThreeViewType>(initialType);
  const suggestedRatio = THREE_VIEW_OPTIONS.find((option) => option.value === initialType)?.aspectRatio ?? "16:9";
  const supportedRatios = RATIOS.filter((value) => !capability?.supportedImageAspectRatios?.length
    || capability.supportedImageAspectRatios.includes(value));
  const [ratio, setRatio] = useState<AspectRatio>(supportedRatios.find((value) => value === suggestedRatio) ?? supportedRatios[0] ?? suggestedRatio);
  const [layerTarget, setLayerTarget] = useState<"FOREGROUND" | "BACKGROUND">("FOREGROUND");
  const [viewAngle, setViewAngle] = useState<typeof ANGLES[number][0]>("FRONT");
  const transparent = operation === "REMOVE_BACKGROUND" || operation === "LAYER_SPLIT" && layerTarget === "FOREGROUND";
  const eligible = capabilities.find((entry) => !transparent || entry.supportsTransparentBackground);
  const instructionCopy = INSTRUCTIONS[operation];
  const needsRatio = operation === "OUTPAINT" || operation === "THREE_VIEW";
  const canSubmit = !busy && !submitDisabled && (!cloud || Boolean(eligible))
    && (!instructionCopy?.[2] || Boolean(instruction.trim())) && (!needsRatio || supportedRatios.includes(ratio as typeof RATIOS[number]));
  const threeView = THREE_VIEW_OPTIONS.find((option) => option.value === threeViewType);
  const title = operation === "THREE_VIEW" ? threeView?.label ?? imageOperationLabel(operation) : imageOperationLabel(operation);
  const descriptions: Partial<Record<ImageOperation, MessageKey>> = {
    OUTPAINT: "media.tools.description.outpaint", THREE_VIEW: "media.tools.description.threeView",
    LAYER_SPLIT: "media.tools.description.layerSplit", EXPRESSION_EDIT: "media.tools.description.expressionEdit",
    REMOVE_BACKGROUND: "media.tools.description.removeBackground", OBJECT_REMOVE: "media.tools.description.objectRemove",
    VIEW_ANGLE: "media.tools.description.viewAngle", UPSCALE: "media.tools.description.upscale",
    DEPTH_MAP: "media.tools.description.depthMap", ROTATE: "media.tools.description.rotate",
    FLIP_HORIZONTAL: "media.tools.description.flipHorizontal", FLIP_VERTICAL: "media.tools.description.flipVertical",
  };
  const description = t(descriptions[operation] ?? "media.tools.aiPreviewHint");
  const selection = operation === "VIEW_ANGLE" ? t(ANGLES.find(([value]) => value === viewAngle)?.[1] ?? "media.card.front")
    : operation === "THREE_VIEW" ? threeView?.summary
    : operation === "LAYER_SPLIT" ? t(layerTarget === "FOREGROUND" ? "media.tools.foregroundHint" : "media.tools.backgroundHint")
    : operation === "REMOVE_BACKGROUND" ? t("media.tools.transparentHint") : null;

  function submit() {
    if (!canSubmit) return;
    const parameters: Parameters = operation === "UPSCALE" ? capability?.settings.runningHub ? {} : { scale }
      : operation === "THREE_VIEW" ? { aspectRatio: ratio, threeViewType }
      : operation === "OUTPAINT" ? { aspectRatio: ratio }
      : operation === "LAYER_SPLIT" ? { layerTarget }
      : operation === "VIEW_ANGLE" ? { viewAngle } : operation === "ROTATE" ? { quarterTurns: 1 } : {};
    onSubmit(parameters, instruction.trim());
  }

  return <MediaToolWorkspace title={title} description={description} busy={busy} onClose={onClose}
    onSubmit={(event) => { event.preventDefault(); submit(); }}
    preview={<MediaToolImage src={sourceUrl} title={sourceTitle}
      aspectRatio={operation === "OUTPAINT" ? ratio : undefined}
      caption={operation === "OUTPAINT" ? t("media.tools.outpaintPreviewHint") : !cloud ? t("media.card.localProcessingHint") : undefined}>
      {selection ? <div className="media-tool-selection-note"><CheckCircle />{selection}</div> : null}
      {operation === "UPSCALE" && !capability?.settings.runningHub && sourceWidth && sourceHeight ? <div className="media-tool-dimensions">
        <span>{sourceWidth} × {sourceHeight}</span><ArrowRight /><strong>{sourceWidth * scale} × {sourceHeight * scale} px</strong>
      </div> : null}
    </MediaToolImage>}
    footer={<><Button variant="ghost" type="button" disabled={busy} onClick={onClose}>{t("common.cancel")}</Button>
      <Button type="submit" disabled={!canSubmit}><ArrowsOutSimple data-icon="inline-start" />{t(busy ? "media.card.accepting" : "media.card.startOperation")}</Button></>}>
    <FieldGroup>
      {operation === "THREE_VIEW" ? <Field><FieldLabel>{t("media.card.outputType")}</FieldLabel>
        <ToggleGroup type="single" spacing={2} value={threeViewType} disabled={busy} className="media-tool-choice-grid" aria-label={t("media.card.outputType")}
          onValueChange={(value) => { const option = THREE_VIEW_OPTIONS.find((entry) => entry.value === value);
            if (option) { setThreeViewType(option.value); if (supportedRatios.includes(option.aspectRatio)) setRatio(option.aspectRatio); } }}>
          {THREE_VIEW_OPTIONS.map((option) => <ToggleGroupItem key={option.value} value={option.value}><option.icon /><span>{option.label}</span></ToggleGroupItem>)}
        </ToggleGroup>
        <FieldDescription>{threeView?.summary}</FieldDescription>
      </Field> : null}
      {operation === "LAYER_SPLIT" ? <Field><FieldLabel>{t("media.card.outputLayer")}</FieldLabel>
        <ToggleGroup type="single" spacing={2} value={layerTarget} disabled={busy} className="media-tool-choice-grid" aria-label={t("media.card.outputLayer")}
          onValueChange={(value) => { if (value === "FOREGROUND" || value === "BACKGROUND") setLayerTarget(value); }}>
          <ToggleGroupItem value="FOREGROUND"><PersonSimple /><span>{t("media.card.foregroundLayer")}</span><small>PNG</small></ToggleGroupItem>
          <ToggleGroupItem value="BACKGROUND"><ImageIcon /><span>{t("media.card.backgroundLayer")}</span><small>{t("media.tools.backgroundFill")}</small></ToggleGroupItem>
        </ToggleGroup>
      </Field> : null}
      {operation === "VIEW_ANGLE" ? <Field><FieldLabel>{t("media.card.viewAngle")}</FieldLabel>
        <ToggleGroup type="single" spacing={2} value={viewAngle} disabled={busy} className="media-tool-choice-grid" aria-label={t("media.card.viewAngle")}
          onValueChange={(value) => { const found = ANGLES.find(([angle]) => angle === value); if (found) setViewAngle(found[0]); }}>
          {ANGLES.map(([value, label]) => <ToggleGroupItem key={value} value={value}><Camera /><span>{t(label)}</span></ToggleGroupItem>)}
        </ToggleGroup>
      </Field> : null}
      {operation === "EXPRESSION_EDIT" ? <Field><FieldLabel>{t("media.tools.expressionPresets")}</FieldLabel>
        <ToggleGroup type="single" spacing={2} value={EXPRESSIONS.find(({ key }) => instruction === t(`media.tools.expression.${key}.prompt`))?.key ?? ""}
          disabled={busy} className="media-tool-choice-grid" data-columns="3" aria-label={t("media.tools.expressionPresets")}
          onValueChange={(value) => { const preset = EXPRESSIONS.find(({ key }) => key === value); if (preset) setInstruction(t(`media.tools.expression.${preset.key}.prompt`)); }}>
          {EXPRESSIONS.map((preset) => <ToggleGroupItem key={preset.key} value={preset.key}><preset.icon /><span>{t(`media.tools.expression.${preset.key}.label`)}</span></ToggleGroupItem>)}
        </ToggleGroup>
      </Field> : null}
      {operation === "UPSCALE" && !capability?.settings.runningHub ? <Field><FieldLabel>{t("media.card.upscaleFactor")}</FieldLabel>
        <ToggleGroup type="single" spacing={2} value={String(scale)} disabled={busy} className="media-tool-choice-grid" aria-label={t("media.card.upscaleFactor")}
          onValueChange={(value) => { if (value === "2" || value === "4") setScale(Number(value) as 2 | 4); }}>
          {[2, 4].map((value) => <ToggleGroupItem key={value} value={String(value)}><strong>{value}×</strong><span>{t(value === 2 ? "media.card.upscaleDouble" : "media.card.upscaleQuadruple")}</span></ToggleGroupItem>)}
        </ToggleGroup><FieldDescription>{t("media.tools.interpolationHint")}</FieldDescription>
      </Field> : null}
      {needsRatio ? <Field><FieldLabel>{t("media.card.aspectRatio")}</FieldLabel>
        <ToggleGroup type="single" spacing={2} value={ratio} disabled={busy} className="media-tool-choice-grid media-tool-ratios" data-columns="4" aria-label={t("media.card.aspectRatio")}
          onValueChange={(value) => { const next = supportedRatios.find((entry) => entry === value); if (next) setRatio(next); }}>
          {supportedRatios.map((value) => { const [width = 1, height = 1] = value.split(":").map(Number); const Icon = width === height ? Square : Rectangle;
            return <ToggleGroupItem key={value} value={value}><Icon style={{ transform: width < height ? "rotate(90deg)" : undefined }} /><span>{value}</span></ToggleGroupItem>; })}
        </ToggleGroup>
        {!supportedRatios.length ? <FieldDescription>{t("media.tools.noRatios")}</FieldDescription> : null}
      </Field> : null}
      {instructionCopy ? <Field><FieldLabel htmlFor={id}>{t(instructionCopy[0])}</FieldLabel>
        <Textarea id={id} value={instruction} maxLength={4000} disabled={busy} placeholder={t(instructionCopy[1])}
          onChange={(event) => setInstruction(event.target.value)} /></Field> : null}
    </FieldGroup>
    {transparent && !eligible ? <Alert variant="destructive"><AlertDescription>{t("media.tools.transparentUnavailable")}</AlertDescription></Alert> : null}
    {extraControls}
    {error ? <Alert variant="destructive"><AlertDescription>{error instanceof ApiError ? error.message : t("media.card.operationFailed")}</AlertDescription></Alert> : null}
  </MediaToolWorkspace>;
}
