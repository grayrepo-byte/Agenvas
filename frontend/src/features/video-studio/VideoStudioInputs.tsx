import { useId, useRef, useState, type ReactNode } from "react";
import { assetContentUrl, assetThumbnailUrl } from "../../shared/api/client";
import type { VideoStudioCapability, VideoStudioReferenceAsset } from "../../shared/api/videoStudio";
import type { CreatorInputField } from "../../shared/creatorInputs";
import { formatNumber, t } from "../../shared/i18n";
import { MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import {
  ArrowsClockwise,
  CaretDown,
  FilmStrip,
  ImageSquare,
  MusicNotes,
  Plus,
  SlidersHorizontal,
  WarningCircle,
  X,
} from "../../shared/ui/icons";
import { Button } from "../../shared/ui/primitives/button";
import { RunningHubForm } from "../canvas/RunningHubForm";
import { workflowFieldHint, workflowFieldLabel } from "../canvas/workflowFieldPresentation";
import { workflowValidationErrors, type WorkflowValue } from "../canvas/workflowFields";
import {
  FRAME_LABELS,
  LIMIT_KEYS,
  MAX_REFERENCES,
  allowsFrame,
  studioFrames,
  workflowRatio,
  type FrameSlot,
  type VideoStudioSettings,
} from "./videoStudioDraft";
import "./VideoStudioInputs.css";

const KINDS = ["IMAGE", "VIDEO", "AUDIO"] as const;
const KIND_LABELS = { IMAGE: "common.image", VIDEO: "common.video", AUDIO: "common.audio" } as const;
const KIND_ICONS = { IMAGE: ImageSquare, VIDEO: FilmStrip, AUDIO: MusicNotes };
type MediaKind = VideoStudioReferenceAsset["kind"];
const STUDIO_ACCEPT = { ...MEDIA_FILE_ACCEPT, IMAGE: "image/png,image/jpeg,image/webp" };
// Named workflows accept MP3 and WAV from the studio's decoded audio archive.
const WORKFLOW_ACCEPT = { ...STUDIO_ACCEPT, AUDIO: "audio/mpeg,audio/wav" };

function mediaSummary(reference: VideoStudioReferenceAsset) {
  if (reference.durationMs != null)
    return t("agent.approval.seconds", { 0: formatNumber(reference.durationMs / 1000) });
  if (reference.width && reference.height) return `${reference.width} × ${reference.height}`;
  return t("library.referencePicker.added");
}

function mediaField(field: CreatorInputField): field is CreatorInputField & { type: MediaKind } {
  return field.type === "IMAGE" || field.type === "VIDEO" || field.type === "AUDIO";
}

type Props = {
  settings: VideoStudioSettings;
  model: VideoStudioCapability | undefined;
  fields: CreatorInputField[];
  values: Record<string, WorkflowValue>;
  labels: string[];
  disabled: boolean;
  restoring: boolean;
  onUploadReference: (kind: MediaKind, file: File, fieldKey?: string) => Promise<void>;
  onUploadFrame: (slot: FrameSlot, file: File) => void;
  onRemoveFrame: (slot: FrameSlot) => void;
  onRemoveReference: (index: number, trigger: string) => void;
  onChange: (key: string, value: WorkflowValue | undefined) => void;
};

/** Retry the owner's original image once if the lightweight thumbnail cannot be read. */
function AssetThumbnail({ projectId, assetId, label }: { projectId: string; assetId: string; label: string }) {
  const [source, setSource] = useState<"thumbnail" | "original" | "failed">("thumbnail");
  const [loaded, setLoaded] = useState(false);
  return source === "failed" ? (
    <span
      className="video-input-image-failed"
      role={label ? "img" : undefined}
      aria-label={label ? `${label} · ${t("media.card.previewUnavailable")}` : undefined}
      title={t("media.card.previewUnavailable")}
    >
      <WarningCircle aria-hidden />
    </span>
  ) : (
    <img
      src={source === "thumbnail" ? assetThumbnailUrl(projectId, assetId) : assetContentUrl(projectId, assetId)}
      alt={label}
      loading="lazy"
      decoding="async"
      data-loaded={loaded}
      aria-busy={!loaded}
      onLoad={() => setLoaded(true)}
      onError={() => setSource(source === "thumbnail" ? "original" : "failed")}
    />
  );
}

function UploadControl({
  triggerId,
  kind,
  label,
  hint,
  inputLabel,
  accept,
  disabled,
  onUpload,
  versionId,
  thumbnail,
}: {
  triggerId: string;
  kind: MediaKind;
  label: string;
  hint?: string;
  inputLabel: string;
  accept: string;
  disabled: boolean;
  onUpload: (file: File) => void | Promise<void>;
  versionId?: string;
  thumbnail?: ReactNode;
}) {
  const input = useRef<HTMLInputElement>(null);
  const filled = versionId !== undefined;
  const [failed, setFailed] = useState<{ file: File; message: string; versionId?: string }>();
  const [busy, setBusy] = useState(false);
  const Icon = KIND_ICONS[kind];

  async function upload(file: File) {
    setFailed(undefined);
    setBusy(true);
    try {
      await onUpload(file);
    } catch (error) {
      setFailed({
        file,
        versionId,
        message: error instanceof Error ? error.message : t("media.runningHub.uploadFailed"),
      });
    } finally {
      setBusy(false);
    }
  }

  const action = t(filled ? "media.upload.replaceTitle" : "media.runningHub.uploadNamed", { 0: label });
  return (
    <div className="video-input-upload">
      <input
        ref={input}
        type="file"
        hidden
        aria-label={inputLabel}
        accept={accept}
        disabled={disabled || busy}
        onChange={event => {
          const file = event.currentTarget.files?.[0];
          event.currentTarget.value = "";
          if (file) void upload(file);
        }}
      />
      <Button
        id={triggerId}
        type="button"
        size="content"
        variant="outline"
        className="video-input-tile"
        data-filled={filled}
        aria-label={filled ? action : undefined}
        aria-disabled={disabled || busy}
        tabIndex={disabled || busy ? -1 : undefined}
        aria-describedby={hint ? `${triggerId}-hint` : undefined}
        title={action}
        onClick={() => {
          if (!disabled && !busy) input.current?.click();
        }}
      >
        <span className="video-input-symbol" aria-hidden={!thumbnail || undefined}>
          {thumbnail ?? <Icon />}
        </span>
        <span className="video-input-caption">
          <strong>
            <span className="sr-only">{action}</span>
            <span aria-hidden>{label}</span>
          </strong>
          {hint ? (
            <small id={`${triggerId}-hint`} aria-hidden>
              {hint}
            </small>
          ) : null}
        </span>
        {filled ? <ArrowsClockwise data-icon="inline-end" aria-hidden /> : <Plus data-icon="inline-end" aria-hidden />}
      </Button>
      {busy ? <small role="status">{t("media.runningHub.uploading")}</small> : null}
      {failed && failed.versionId === versionId ? (
        <div className="video-input-error" role="alert">
          {failed.message}
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={disabled}
            onClick={() => {
              document.getElementById(triggerId)?.focus({ preventScroll: true });
              void upload(failed.file);
            }}
          >
            {t("media.retryUpload")}
          </Button>
        </div>
      ) : null}
    </div>
  );
}

function RemoveButton({
  label,
  disabled,
  onRemove,
  uploadTriggerId,
}: {
  uploadTriggerId: string;
  label: string;
  disabled: boolean;
  onRemove: (id: string) => void;
}) {
  const id = useId();
  const remove = t("videoStudio.removeFrame", { 0: label });
  return (
    <Button
      id={id}
      type="button"
      size="icon-sm"
      variant="ghost"
      disabled={disabled}
      aria-label={remove}
      title={remove}
      onClick={() => {
        // The remove button disappears on success. Keep keyboard navigation on this input.
        document.getElementById(uploadTriggerId)?.focus({ preventScroll: true });
        onRemove(id);
      }}
    >
      <X aria-hidden />
    </Button>
  );
}

function SceneParameters({
  fields,
  model,
  values,
  disabled,
  onChange,
}: Pick<Props, "fields" | "model" | "values" | "disabled" | "onChange">) {
  const primary: CreatorInputField[] = [];
  const advanced: CreatorInputField[] = [];
  const mediaControllers = new Set(model?.fields?.filter(mediaField).map(field => field.enabledWhen?.field));
  for (const field of fields) {
    if (mediaField(field)) continue;
    // Source-mapped duration is already in the model controls. For custom parameters, use
    // published identifiers or unambiguous ratio choices, never infer semantics from translated labels.
    const outputSetting =
      /^(aspect_?ratio|video_?resolution|resolution|duration(_?seconds)?|seconds)$/i.test(field.key) ||
      workflowRatio(field, field.options?.[0]?.value) !== null;
    const bucket = !field.advanced && (outputSetting || mediaControllers.has(field.key)) ? primary : advanced;
    bucket.push({ ...field, advanced: false });
  }

  function render(fields: CreatorInputField[]) {
    return (
      <RunningHubForm
        definition={{ fields }}
        values={values}
        prompt=""
        durationSeconds={null}
        choices={[]}
        disabled={disabled}
        onChange={onChange}
      />
    );
  }

  const advancedValues = Object.fromEntries(
    advanced.filter(field => values[field.key] !== undefined).map(field => [field.key, values[field.key]!]),
  );
  const invalid = workflowValidationErrors({ fields: advanced }, advancedValues, "", null, []).length > 0;
  return (
    <>
      {primary.length ? <div className="video-input-parameters">{render(primary)}</div> : null}
      {advanced.length ? (
        <AdvancedParameters invalid={invalid} count={advanced.length}>
          {render(advanced)}
        </AdvancedParameters>
      ) : null}
    </>
  );
}

function AdvancedParameters({ invalid, count, children }: { invalid: boolean; count: number; children: ReactNode }) {
  const panel = useId();
  const [expanded, setExpanded] = useState(invalid);
  const [previousInvalid, setPreviousInvalid] = useState(invalid);
  if (invalid !== previousInvalid) {
    setPreviousInvalid(invalid);
    if (invalid) setExpanded(true);
  }
  return (
    <div className="video-input-advanced">
      <Button
        type="button"
        variant="ghost"
        size="content"
        aria-expanded={expanded}
        aria-controls={panel}
        onClick={() => setExpanded(!expanded)}
      >
        <SlidersHorizontal data-icon="inline-start" aria-hidden />
        {t("media.runningHub.advancedParameters")}
        <span className="video-input-count">{count}</span>
        <CaretDown data-icon="inline-end" aria-hidden />
      </Button>
      <div
        id={panel}
        className="video-input-parameters"
        hidden={!expanded}
        onInvalidCapture={event => {
          // Keep native number constraints (including step) actionable inside a closed panel.
          if (expanded || !(event.target instanceof HTMLInputElement)) return;
          const input = event.target;
          event.preventDefault();
          setExpanded(true);
          requestAnimationFrame(() => {
            if (!input.isConnected) return;
            input.focus();
            input.reportValidity();
          });
        }}
      >
        {children}
      </div>
    </div>
  );
}

export function VideoStudioInputs({
  settings,
  model,
  fields,
  values,
  labels,
  disabled,
  restoring,
  onUploadReference,
  onUploadFrame,
  onRemoveFrame,
  onRemoveReference,
  onChange,
}: Props) {
  const inputsId = useId();
  const uploadId = (key: string) => `${inputsId}-upload-${key}`;
  const uploads: ReactNode[] = [];
  const uploadDisabled = disabled || restoring;
  for (const [slot, frame] of studioFrames(settings)) {
    const supported = !!model && allowsFrame(model, settings.mode, slot);
    if (!supported && !frame) continue;
    const label = t(FRAME_LABELS[slot]);
    uploads.push(
      <div className="video-input-frame" key={slot}>
        <UploadControl
          kind="IMAGE"
          triggerId={uploadId(slot)}
          label={label}
          inputLabel={label}
          accept={STUDIO_ACCEPT.IMAGE}
          hint={
            frame
              ? `${frame.width} × ${frame.height}`
              : t(
                  slot === "startFrame" || model?.requiresEndFrame
                    ? "videoStudio.requiredFrame"
                    : "videoStudio.optionalFrame",
                )
          }
          versionId={frame?.versionId}
          thumbnail={
            frame ? (
              <AssetThumbnail key={frame.assetId} projectId={frame.projectId} assetId={frame.assetId} label={label} />
            ) : undefined
          }
          disabled={!supported || uploadDisabled}
          onUpload={file => onUploadFrame(slot, file)}
        />
        {frame ? (
          <RemoveButton
            label={label}
            uploadTriggerId={uploadId(slot)}
            disabled={disabled}
            onRemove={() => onRemoveFrame(slot)}
          />
        ) : null}
      </div>,
    );
  }

  const media = fields.filter(mediaField);
  const names = new Map(media.map(field => [field.key, field]));
  const countFor = (kind: MediaKind) => settings.references.filter(ref => ref.kind === kind).length;
  function atLimit(kind: MediaKind) {
    return (
      !model ||
      settings.references.length >= MAX_REFERENCES ||
      countFor(kind) >= model.referenceLimits[LIMIT_KEYS[kind]]
    );
  }
  if (settings.mode === "GENERAL_REFERENCE" && model) {
    for (const field of media) {
      const selected = settings.references.find(ref => ref.fieldKey === field.key);
      const label = workflowFieldLabel(field);
      uploads.push(
        <UploadControl
          key={field.key}
          label={label}
          kind={field.type}
          triggerId={uploadId(field.key)}
          versionId={selected?.versionId}
          thumbnail={
            selected?.kind === "IMAGE" ? (
              <AssetThumbnail
                key={selected.assetId}
                projectId={selected.projectId}
                assetId={selected.assetId}
                label=""
              />
            ) : undefined
          }
          inputLabel={t("media.runningHub.uploadNamed", { 0: label })}
          hint={[
            field.group,
            selected ? mediaSummary(selected) : t(field.required ? "skills.required" : "modelConnection.optional"),
          ]
            .filter(Boolean)
            .join(" · ")}
          accept={WORKFLOW_ACCEPT[field.type]}
          disabled={uploadDisabled || (!selected && atLimit(field.type))}
          onUpload={file => onUploadReference(field.type, file, field.key)}
        />,
      );
    }
    if (!model.fields) {
      for (const kind of KINDS) {
        const limit = model.referenceLimits[LIMIT_KEYS[kind]];
        if (!limit) continue;
        const count = countFor(kind);
        const label = t(KIND_LABELS[kind]);
        uploads.push(
          <UploadControl
            key={kind}
            label={label}
            kind={kind}
            triggerId={uploadId(kind)}
            hint={`${count} / ${limit}`}
            inputLabel={`${t("media.runningHub.uploadNamed", { 0: label })} (${count}/${limit})`}
            accept={STUDIO_ACCEPT[kind]}
            disabled={uploadDisabled || atLimit(kind)}
            onUpload={file => onUploadReference(kind, file)}
          />,
        );
      }
    }
  }

  return (
    <>
      {uploads.length ? <div className="video-input-slots">{uploads}</div> : null}
      {settings.references.length ? (
        <ul className="video-input-previews" aria-label={t("common.mixedReference")}>
          {settings.references.map((ref, index) => {
            const label = labels[index]!;
            const field = ref.fieldKey ? names.get(ref.fieldKey) : undefined;
            const source = assetContentUrl(ref.projectId, ref.assetId);
            return (
              <li key={`${ref.fieldKey ?? ref.kind}:${ref.versionId}`} className="video-input-preview">
                {ref.kind === "IMAGE" ? (
                  <AssetThumbnail key={ref.assetId} projectId={ref.projectId} assetId={ref.assetId} label={label} />
                ) : ref.kind === "VIDEO" ? (
                  <video
                    src={source}
                    poster={assetThumbnailUrl(ref.projectId, ref.assetId)}
                    controls
                    preload="none"
                    aria-label={label}
                  />
                ) : (
                  <audio src={source} controls preload="none" aria-label={label} />
                )}
                <div className="video-input-preview-bar">
                  <span title={field ? workflowFieldHint(field) : label}>{label}</span>
                  <RemoveButton
                    label={label}
                    uploadTriggerId={uploadId(ref.fieldKey ?? ref.kind)}
                    disabled={disabled}
                    onRemove={trigger => onRemoveReference(index, trigger)}
                  />
                </div>
              </li>
            );
          })}
        </ul>
      ) : null}
      <SceneParameters fields={fields} model={model} values={values} disabled={uploadDisabled} onChange={onChange} />
    </>
  );
}
