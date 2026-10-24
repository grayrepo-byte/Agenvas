import { Input } from "../../shared/ui/primitives/input";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { ArrowRight, MusicNotes, VideoCamera } from "@/shared/ui/icons";
import { useQuery } from "@tanstack/react-query";
import { useId, useState, type ReactNode } from "react";
import { getMediaFunctions, getMediaSettings, type RunVideoOperationRequest, type VideoOperation } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { MEDIA_FUNCTIONS_QUERY_KEY, videoOperationLabel, videoFunctionChoices, videoFunction } from "../../shared/mediaFunctions";
import { estimatedMediaCost } from "../../shared/mediaPricing";
import { Button } from "../../shared/ui/primitives/button";
import { RunningHubForm } from "./RunningHubForm";
import { workflowValidationErrors, type WorkflowValue } from "./workflowFields";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { MediaToolVideo, MediaToolWorkspace } from "./MediaToolWorkspace";
import "./VideoOperationPanel.css";

type SubmissionInput = Pick<RunVideoOperationRequest, "expectedFunctionVersion" | "expectedCapabilityVersion" | "prompt" | "parameters">;

/** Reads the configured method and pins the visible video in the workflow's source slot. */
export function VideoOperationPanel({ operation, sourceVersionId, sourceTitle, sourceUrl, posterUrl, busy, error, onClose, onSubmit }: {
  operation: VideoOperation; sourceVersionId: string; sourceTitle: string; sourceUrl?: string; posterUrl?: string; busy: boolean; error: Error | null;
  onClose: () => void; onSubmit: (input: SubmissionInput) => void;
}) {
  useLocale();
  const functions = useQuery({ queryKey: MEDIA_FUNCTIONS_QUERY_KEY, queryFn: getMediaFunctions, retry: false });
  const settings = useQuery({ queryKey: ["settings", "media"], queryFn: getMediaSettings, retry: false });
  const setting = functions.data?.find((entry) => entry.operation === videoFunction(operation));
  const configured = settings.data && setting?.capabilityId
    ? videoFunctionChoices(settings.data, operation).find(({ capability }) => capability.id === setting.capabilityId) : undefined;
  const preview = <>
    <div className="media-tool-stage-heading"><span>{t("media.tools.original")}</span><span>{sourceTitle}</span></div>
    {sourceUrl ? <MediaToolVideo key={sourceUrl} src={sourceUrl} poster={posterUrl} title={sourceTitle} /> : <div className="media-tool-image-area"><VideoCamera size={40} /></div>}
    <p className="media-tool-preview-caption">{t("media.video.source", { "0": sourceTitle })}</p>
  </>;
  if (configured && setting) return <ConfiguredOperation key={`${configured.capability.id}:${configured.capability.capabilityVersion}:${setting.version}`}
    operation={operation} sourceVersionId={sourceVersionId} sourceTitle={sourceTitle} settingVersion={setting.version}
    configured={configured} busy={busy} error={error} onSubmit={onSubmit} preview={preview} onClose={onClose} />;
  return <MediaToolWorkspace title={videoOperationLabel(operation)} description={t(operation === "UPSCALE" ? "media.tools.video.upscale" : operation === "DEPTH_MAP" ? "media.tools.video.depthMap" : "media.tools.video.extractAudio")}
    preview={preview} busy={busy} onClose={onClose} onSubmit={(event) => event.preventDefault()}
    footer={<Button type="button" variant="ghost" onClick={onClose} disabled={busy}>{t("common.close")}</Button>}>
    {functions.isPending || settings.isPending ? <p role="status">{t("app.pageLoading")}</p>
      : functions.error || settings.error ? <p role="alert">{(functions.error ?? settings.error)?.message}
        <Button variant="ghost" type="button" onClick={() => { void functions.refetch(); void settings.refetch(); }}>{t("common.retry")}</Button></p>
      : <p>{setting?.capabilityId ? t("media.functions.unavailable") : t("media.video.configureFirst")}</p>}
  </MediaToolWorkspace>;
}

function ConfiguredOperation({ operation, sourceVersionId, sourceTitle, settingVersion, configured, busy, error, onSubmit, preview, onClose }: {
  operation: VideoOperation; sourceVersionId: string; sourceTitle: string; settingVersion: number;
  preview: ReactNode; onClose: () => void;
  configured: ReturnType<typeof videoFunctionChoices>[number]; busy: boolean; error: Error | null;
  onSubmit: (input: SubmissionInput) => void;
}) {
  const [values, setValues] = useState<Record<string, WorkflowValue>>({});
  const [format, setFormat] = useState<"WAV" | "MP3">("WAV");
  const [trackIndex, setTrackIndex] = useState(0);
  const id = useId();
  const invalidTrack = !Number.isInteger(trackIndex) || trackIndex < 0 || trackIndex > 31;
  const definition = configured.capability.settings.runningHub;
  const videoField = definition?.fields.find((field) => field.type === "VIDEO");
  const fixedValues = videoField ? { ...values, [videoField.key]: sourceVersionId } : values;
  const choices = [{ id: sourceVersionId, label: sourceTitle, kind: "VIDEO", available: true }];
  const formDefinition = definition ? { ...definition, fields: definition.fields.filter((field) => field.type !== "VIDEO")
    .map((field) => ({ ...field, source: "PARAMETER" as const })) } : undefined;
  const durationField = definition?.fields.find((field) => field.source === "DURATION_SECONDS");
  const durationValue = durationField ? values[durationField.key] ?? durationField.defaultValue : undefined;
  const promptField = definition?.fields.find((field) => field.source === "PROMPT");
  const prompt = promptField ? values[promptField.key] ?? promptField.defaultValue : "";
  const errors = definition ? workflowValidationErrors(definition, fixedValues, typeof prompt === "string" ? prompt : "",
    typeof durationValue === "number" ? durationValue : null, choices) : [];
  const canSubmit = !busy && errors.length === 0 && !invalidTrack;
  return <MediaToolWorkspace title={videoOperationLabel(operation)} description={t(operation === "UPSCALE" ? "media.tools.video.upscale" : operation === "DEPTH_MAP" ? "media.tools.video.depthMap" : "media.tools.video.extractAudio")}
    preview={preview} busy={busy} onClose={onClose} onSubmit={(event) => {
      event.preventDefault();
      if (!canSubmit) return;
      onSubmit({ expectedFunctionVersion: settingVersion, expectedCapabilityVersion: configured.capability.capabilityVersion,
        prompt: typeof prompt === "string" ? prompt : "", parameters: definition ? { dynamicValues: fixedValues }
          : operation === "EXTRACT_AUDIO" ? { format, trackIndex } : {} });
    }} footer={<><p className="video-operation-cost">{definition ? estimatedMediaCost(configured.capability, 1, typeof durationValue === "number" ? durationValue : null) : t("media.video.localCost")}</p>
      <Button type="submit" disabled={!canSubmit}>{busy ? t("media.video.submitting") : t("media.video.start")}<ArrowRight data-icon="inline-end" /></Button></>}>
    <p className="video-operation-method" title={configured.label}>{configured.capability.name}</p>
    {!definition ? <p>{operation === "DEPTH_MAP" ? t("media.video.depthHint") : t("media.video.audioHint")}</p> : null}
    {!definition && operation === "EXTRACT_AUDIO" ? <FieldGroup>
      <Field><FieldLabel id={`${id}-format`}>{t("video.edit.audioFormat")}</FieldLabel>
        <ToggleGroup type="single" spacing={2} value={format} disabled={busy} className="media-tool-choice-grid" aria-labelledby={`${id}-format`}
          onValueChange={(value) => { if (value === "MP3" || value === "WAV") setFormat(value); }}>
          <ToggleGroupItem value="WAV"><MusicNotes /><span>WAV</span><small>{t("media.tools.wavHint")}</small></ToggleGroupItem>
          <ToggleGroupItem value="MP3"><MusicNotes /><span>MP3</span><small>{t("media.tools.mp3Hint")}</small></ToggleGroupItem>
        </ToggleGroup><FieldDescription>{t("video.edit.audioFormatHint")}</FieldDescription></Field>
      <Field data-invalid={invalidTrack}><FieldLabel htmlFor={`${id}-track`}>{t("video.edit.audioTrack")}</FieldLabel>
        <Input id={`${id}-track`} type="number" min={0} max={31} step={1} value={Number.isFinite(trackIndex) ? trackIndex : ""} disabled={busy} aria-invalid={invalidTrack}
          onChange={(event) => setTrackIndex(event.target.valueAsNumber)} /></Field>
      {invalidTrack ? <FieldError>{t("video.edit.audioTrackInvalid")}</FieldError> : null}
      <div className="video-tool-outputs"><FieldLabel>{t("media.tools.outputs")}</FieldLabel>
        <span><MusicNotes />{t("media.tools.audioOutput")}</span><span><VideoCamera />{t("media.tools.silentVideoOutput")}</span>
      </div>
    </FieldGroup> : null}
    {formDefinition ? <div className="video-operation-fields"><RunningHubForm definition={formDefinition} values={values} prompt="" durationSeconds={null}
      choices={choices} disabled={busy} onChange={(key, value) => setValues((current) => {
        const next = { ...current }; if (value === undefined) delete next[key]; else next[key] = value; return next;
      })} /></div> : null}
    <p className="video-operation-result-hint">{t("media.video.resultHint")}</p>
    {error ? <p role="alert">{error.message}</p> : null}
    {errors.length ? <p role="status">{errors[0]}</p> : null}
  </MediaToolWorkspace>;
}
