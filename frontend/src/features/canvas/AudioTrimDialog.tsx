import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { ApiError, HTTP_STATUS, runAudioTrim, type RunAudioTrimRequest } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { Slider } from "../../shared/ui/primitives/slider";
import { AudioPlayer } from "./AudioPlayer";
import "./AudioTrimDialog.css";

const MIN_TRIM_MS = 100;
const MAX_AUDIO_BYTES = 50 * 1024 * 1024;
const WAV_BYTES_PER_SECOND = 48_000 * 2 * 2;
const WAV_HEADER_BUDGET = 4096;

/** Reject fractional milliseconds and empty input rather than silently rounding an edit. */
function milliseconds(value: string): number {
  if (!/^\d+(?:\.\d{1,3})?$/.test(value)) return Number.NaN;
  const [seconds, fraction = ""] = value.split(".");
  const result = Number(seconds) * 1000 + Number(fraction.padEnd(3, "0"));
  return Number.isSafeInteger(result) ? result : Number.NaN;
}

type Source = Pick<RunAudioTrimRequest, "canvasItemId" | "sourceVersionId" | "expectedCanvasItemVersion">;

/** A dialog is one frozen source session; later card selections cannot retarget its command. */
export function AudioTrimDialog({ projectId, artifactId, source, src, title, durationMs, onClose }: {
  projectId: string; artifactId: string; source: Source; src: string; title: string;
  durationMs: number; onClose: () => void;
}) {
  useLocale();
  const id = useId();
  const [session] = useState(() => ({ projectId, artifactId, source: { ...source }, src, title, durationMs }));
  const [start, setStart] = useState("0");
  const [end, setEnd] = useState(String(Math.min(15_000, session.durationMs) / 1000));
  const [format, setFormat] = useState<NonNullable<RunAudioTrimRequest["format"]>>("WAV");
  const [audio, setAudio] = useState<HTMLAudioElement | null>(null);
  const [ready, setReady] = useState(false);
  const [playbackError, setPlaybackError] = useState(false);
  const previewIntent = useRef(0);
  const previewing = useRef(false);
  const previewTimer = useRef<number | undefined>(undefined);
  const submitting = useRef(false);
  const command = useRef<{ payload: string; key: string; input: RunAudioTrimRequest } | null>(null);
  const client = useQueryClient();
  const startMs = milliseconds(start);
  const endMs = milliseconds(end);
  const invalid = !Number.isSafeInteger(session.durationMs) || session.durationMs <= 0
    || !Number.isFinite(startMs) || !Number.isFinite(endMs) || startMs < 0
    || endMs - startMs < MIN_TRIM_MS || endMs > session.durationMs;
  const wavTooLarge = !invalid && format === "WAV"
    && (endMs - startMs) / 1000 * WAV_BYTES_PER_SECOND + WAV_HEADER_BUDGET > MAX_AUDIO_BYTES;
  const mutation = useMutation({
    mutationFn: ({ input, key }: { input: RunAudioTrimRequest; key: string }) => runAudioTrim(session.projectId, session.artifactId, input, key),
    onSuccess: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ["snapshot", session.projectId] }),
        client.invalidateQueries({ queryKey: ["canvas", session.projectId] }),
        client.invalidateQueries({ queryKey: ["canvas-connections", session.projectId] }),
        client.invalidateQueries({ queryKey: ["direct-media-tasks", session.projectId] }),
        client.invalidateQueries({ queryKey: ["artifacts", session.projectId] }),
      ]);
      onClose();
    },
    onSettled: () => { submitting.current = false; },
  });
  useEffect(() => () => {
    previewIntent.current++; previewing.current = false; window.clearTimeout(previewTimer.current); audio?.pause();
  }, [audio]);
  function stopPreview() {
    previewIntent.current++; previewing.current = false; window.clearTimeout(previewTimer.current); audio?.pause();
  }
  // timeupdate can be much slower than a short clip; schedule its end from actual playback position.
  function schedulePreviewEnd(element: HTMLAudioElement) {
    window.clearTimeout(previewTimer.current);
    if (!previewing.current) return;
    const remaining = Math.max(0, endMs - element.currentTime * 1000) / (element.playbackRate || 1);
    previewTimer.current = window.setTimeout(() => { stopPreview(); element.currentTime = endMs / 1000; }, remaining);
  }
  function changeRange(setter: (value: string) => void, value: string) {
    stopPreview(); setPlaybackError(false); setter(value);
  }
  async function preview() {
    if (!audio || !ready || invalid) return;
    stopPreview(); setPlaybackError(false);
    const intent = ++previewIntent.current;
    audio.currentTime = startMs / 1000; previewing.current = true;
    try {
      await audio.play();
      if (intent !== previewIntent.current) audio.pause();
      else schedulePreviewEnd(audio);
    } catch {
      if (intent === previewIntent.current) { previewing.current = false; setPlaybackError(true); }
    }
  }
  function submit() {
    if (invalid || wavTooLarge || submitting.current || denied) return;
    stopPreview();
    const input: RunAudioTrimRequest = { ...session.source, startMs, endMs, format };
    const payload = JSON.stringify(input);
    if (command.current?.payload !== payload) command.current = { payload, input, key: crypto.randomUUID() };
    submitting.current = true;
    mutation.mutate(command.current);
  }
  const denied = mutation.error instanceof ApiError
    && (mutation.error.status === HTTP_STATUS.UNAUTHORIZED || mutation.error.status === HTTP_STATUS.FORBIDDEN);
  const conflict = mutation.error instanceof ApiError && mutation.error.status === HTTP_STATUS.CONFLICT;
  return <Dialog title={t("audio.trim.title")} description={t("audio.trim.description")}
    className="audio-trim-dialog" busy={mutation.isPending} onClose={() => { stopPreview(); onClose(); }}
    onSubmit={(event) => { event.preventDefault(); submit(); }} footer={<>
      <Button type="button" variant="ghost" disabled={mutation.isPending} onClick={() => { stopPreview(); onClose(); }}>{t("audio.trim.close")}</Button>
      <Button type="submit" disabled={invalid || wavTooLarge || mutation.isPending || denied}>
        {mutation.isPending ? t("audio.trim.submitting") : t("audio.trim.submit")}</Button>
    </>}>
    <AudioPlayer src={session.src} title={session.title} onElement={setAudio} playbackResetKey={`${start}:${end}`}
      onLoadedMetadata={(element) => setReady(Number.isFinite(element.duration) && element.duration > 0)}
      onPlaybackEvent={(event, element) => {
        if (event === "pause" && element.paused) { previewIntent.current++; previewing.current = false; window.clearTimeout(previewTimer.current); }
        else if (event === "seeking" || event === "waiting") window.clearTimeout(previewTimer.current);
        else if (!element.paused) schedulePreviewEnd(element);
      }}
      onTimeUpdate={(element) => {
        if (previewing.current && element.currentTime * 1000 >= endMs) {
          stopPreview(); element.currentTime = endMs / 1000;
        }
      }} />
    <FieldGroup>
      <div className="audio-trim-timeline">
        <div className="audio-trim-timeline-heading"><strong>{t("media.tools.trimRange")}</strong>
          <span>{!invalid ? t("media.tools.selectedDuration", { 0: ((endMs - startMs) / 1000).toFixed(3) }) : t("audio.trim.rangeInvalid")}</span></div>
        <Slider min={0} max={Math.max(MIN_TRIM_MS, session.durationMs)} step={1} minStepsBetweenThumbs={MIN_TRIM_MS}
          value={invalid ? [0, Math.min(15_000, Math.max(MIN_TRIM_MS, session.durationMs))] : [startMs, endMs]}
          disabled={mutation.isPending || session.durationMs < MIN_TRIM_MS}
          thumbLabels={[t("audio.trim.start"), t("audio.trim.end")]}
          formatValue={(value) => `${(value / 1000).toFixed(3)} s`}
          onValueChange={([nextStart, nextEnd]) => {
            if (nextStart === undefined || nextEnd === undefined) return;
            stopPreview(); setPlaybackError(false); setStart(String(nextStart / 1000)); setEnd(String(nextEnd / 1000));
          }} />
        <div className="audio-trim-time-ruler"><span>0:00</span><span>{(session.durationMs / 1000).toFixed(3)} s</span></div>
      </div>
      <FieldDescription>{t("audio.trim.referenceHint")}</FieldDescription>
      <FieldGroup className="audio-trim-range">
        <Field data-invalid={invalid} data-disabled={mutation.isPending}>
          <FieldLabel htmlFor={`${id}-start`}>{t("audio.trim.start")}</FieldLabel>
          <Input id={`${id}-start`} type="number" min={0} max={session.durationMs / 1000} step={0.001}
            value={start} disabled={mutation.isPending} aria-invalid={invalid}
            onChange={(event) => changeRange(setStart, event.target.value)} />
        </Field>
        <Field data-invalid={invalid} data-disabled={mutation.isPending}>
          <FieldLabel htmlFor={`${id}-end`}>{t("audio.trim.end")}</FieldLabel>
          <Input id={`${id}-end`} type="number" min={0} max={session.durationMs / 1000} step={0.001}
            value={end} disabled={mutation.isPending} aria-invalid={invalid}
            onChange={(event) => changeRange(setEnd, event.target.value)} />
        </Field>
      </FieldGroup>
      {invalid ? <FieldError>{t("audio.trim.rangeInvalid")}</FieldError> : null}
      <div className="audio-trim-actions">
        <Button type="button" variant="outline" disabled={!ready || mutation.isPending}
          onClick={() => changeRange(setStart, String(Math.round((audio?.currentTime ?? 0) * 1000) / 1000))}>{t("audio.trim.setStart")}</Button>
        <Button type="button" variant="outline" disabled={!ready || mutation.isPending}
          onClick={() => changeRange(setEnd, String(Math.round((audio?.currentTime ?? 0) * 1000) / 1000))}>{t("audio.trim.setEnd")}</Button>
        <Button type="button" variant="outline" disabled={!ready || invalid || mutation.isPending} onClick={() => void preview()}>{t("audio.trim.preview")}</Button>
      </div>
      {playbackError ? <Alert variant="destructive"><AlertDescription>{t("audio.player.playbackFailed")}</AlertDescription></Alert> : null}
      <Field data-disabled={mutation.isPending} data-invalid={wavTooLarge}>
        <FieldLabel id={`${id}-format`}>{t("audio.trim.format")}</FieldLabel>
        <ToggleGroup type="single" variant="outline" value={format} aria-labelledby={`${id}-format`} disabled={mutation.isPending}
          onValueChange={(value) => { if (value === "WAV" || value === "MP3") setFormat(value); }}>
          <ToggleGroupItem value="WAV">WAV</ToggleGroupItem><ToggleGroupItem value="MP3">MP3</ToggleGroupItem>
        </ToggleGroup>
        <FieldDescription>{t("audio.trim.formatHint")}</FieldDescription>
        {wavTooLarge ? <FieldError>{t("audio.trim.wavTooLarge")}</FieldError> : null}
      </Field>
    </FieldGroup>
    {mutation.error ? <Alert variant="destructive"><AlertDescription>
      {denied ? t("audio.trim.unauthorized") : conflict ? t("audio.trim.conflict") : mutation.error.message}
      {!denied && !conflict ? <p>{t("audio.trim.retryHint")}</p> : null}
    </AlertDescription></Alert> : null}
  </Dialog>;
}
