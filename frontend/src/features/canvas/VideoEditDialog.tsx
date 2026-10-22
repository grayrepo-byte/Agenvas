import { useMutation, useQueries, useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState } from "react";
import { assetContentUrl, assetThumbnailUrl, composeVideo, type CanvasItem, type VideoCompositionRequest } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "../../shared/ui/primitives/dialog";
import { Field, FieldError, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { assetMetadataQueryOptions } from "./mediaDisplay";
import { readContentText } from "./artifactContent";
import { VideoRangeEditor } from "./VideoRangeEditor";
import "./VideoEditDialog.css";

type EditShot = { id: string; videoVersionId: string; startMs: number; endMs: number | null; speed: number; fadeMs: number; lines: { audioVersionId: string; startMs?: number }[] };
type Caption = { id: string; text: string; startMs: number; endMs: number };

/** Editing keeps exact versions pinned locally; accepting creates a separate immutable result Task. */
export function VideoEditDialog({ projectId, items, initialIds, onClose }: {
  projectId: string; items: readonly CanvasItem[]; initialIds: readonly string[]; onClose: () => void;
}) {
  useLocale();
  const prefix = useId();
  const [sources] = useState(() => items.filter((item) => item.selectedVersionId && item.selectedVersion));
  const allVideos = sources.filter((item) => item.artifact.kind === "VIDEO");
  const videos = [...new Map(allVideos.map((item) => [item.selectedVersionId, item])).values()];
  const audios = [...new Map(sources.filter((item) => item.artifact.kind === "AUDIO").map((item) => [item.selectedVersionId, item])).values()];
  const createShot = (item: CanvasItem): EditShot => ({ id: crypto.randomUUID(), videoVersionId: item.selectedVersionId!, startMs: 0, endMs: null, speed: 1, fadeMs: 0, lines: [] });
  const [shots, setShots] = useState<EditShot[]>(() => {
    const selected = initialIds.flatMap((id) => allVideos.find((item) => item.id === id) ?? []);
    return (selected.length ? selected : videos.slice(0, 1)).slice(0, 12).map(createShot);
  });
  const [title, setTitle] = useState(t("video.edit.defaultTitle"));
  const [originalAudioMode, setOriginalAudioMode] = useState<"KEEP" | "MUTE" | "BACKGROUND">("KEEP");
  const [resolution, setResolution] = useState<"720P" | "1080P">("720P");
  const [aspectRatio, setAspectRatio] = useState<"16:9" | "9:16" | "1:1">("16:9");
  const [music, setMusic] = useState("");
  const [musicVolume, setMusicVolume] = useState(0.2);
  const [musicStartMs, setMusicStartMs] = useState(0);
  const [captions, setCaptions] = useState<Caption[]>([]);
  const client = useQueryClient();
  const command = useRef<{ payload: string; key: string } | null>(null);
  const mediaSources = [...videos, ...audios.filter((item) => item.selectedVersionId === music)];
  const requiredSources = new Set([...shots.map((shot) => shot.videoVersionId), music]);
  const metadata = useQueries({ queries: mediaSources.map((item) => ({
    ...assetMetadataQueryOptions(projectId, readContentText(item.selectedVersion?.content, "assetId")),
    enabled: requiredSources.has(item.selectedVersionId!),
  })) });
  const details = new Map(mediaSources.map((item, index) => [item.selectedVersionId, metadata[index]]));
  const metadataPending = mediaSources.some((item, index) => requiredSources.has(item.selectedVersionId!) && metadata[index]?.isPending);
  const duration = (shot: EditShot) => shot.endMs ?? details.get(shot.videoVersionId)?.data?.durationMs ?? 0;
  const frames = (shot: EditShot) => Math.floor((duration(shot) - shot.startMs) * 30 / (1000 * shot.speed));
  const outputMs = (shot: EditShot) => Math.floor(frames(shot) * 1000 / 30);
  const totalMs = Math.floor(shots.reduce((sum, shot) => sum + frames(shot), 0) * 1000 / 30);
  const invalidSpeed = (shot: EditShot) => !Number.isFinite(shot.speed) || shot.speed < 0.5 || shot.speed > 2;
  const invalidFade = (shot: EditShot) => !Number.isInteger(shot.fadeMs) || shot.fadeMs < 0 || shot.fadeMs > 500 || shot.fadeMs * 2 > outputMs(shot);
  const invalidShot = shots.some((shot) => invalidSpeed(shot)
    || !Number.isInteger(shot.startMs) || !Number.isInteger(duration(shot)) || shot.startMs < 0 || duration(shot) - shot.startMs < 100
    || duration(shot) > (details.get(shot.videoVersionId)?.data?.durationMs ?? 0)
    || invalidFade(shot));
  const invalidCaption = (caption: Caption) => !caption.text.trim() || caption.text.length > 500 || !Number.isInteger(caption.startMs)
    || !Number.isInteger(caption.endMs) || caption.startMs < 0 || caption.endMs <= caption.startMs || caption.endMs > totalMs;
  const invalidMusic = !!music && (!Number.isFinite(musicVolume) || musicVolume < 0 || musicVolume > 1 || !Number.isInteger(musicStartMs) || musicStartMs < 0 || musicStartMs > 599999
    || musicStartMs >= (details.get(music)?.data?.durationMs ?? 0));
  const invalid = !title.trim() || !shots.length || shots.length > 12 || totalMs > 60_000 || invalidShot
    || shots.reduce((sum, shot) => sum + shot.lines.length, 0) > 30
    || captions.some(invalidCaption) || invalidMusic;
  const submit = useMutation({
    mutationFn: (input: VideoCompositionRequest) => {
      const payload = JSON.stringify(input);
      if (command.current?.payload !== payload) command.current = { payload, key: crypto.randomUUID() };
      return composeVideo(projectId, input, command.current.key);
    },
    onSuccess: async () => {
      await Promise.all([client.invalidateQueries({ queryKey: ["canvas", projectId] }), client.invalidateQueries({ queryKey: ["snapshot", projectId] })]);
      onClose();
    },
  });
  function patch(id: string, value: Partial<EditShot>) { setShots((current) => current.map((shot) => shot.id === id ? { ...shot, ...value } : shot)); }
  function move(index: number, delta: number) { setShots((current) => { const next = [...current]; const first = next[index], second = next[index + delta]; if (!first || !second) return current; next[index] = second; next[index + delta] = first; return next; }); }
  return <Dialog open onOpenChange={(open) => { if (!open && !submit.isPending) onClose(); }}><DialogContent className="video-edit-dialog nodrag nowheel nopan" onKeyDown={(event) => { if (event.key !== "Tab") event.stopPropagation(); }}>
    <DialogHeader><DialogTitle>{t("video.edit.title")}</DialogTitle><DialogDescription>{t("video.edit.description")}</DialogDescription></DialogHeader>
    <div className="video-edit-body"><FieldGroup>
      <Field><FieldLabel htmlFor={`${prefix}-title`}>{t("video.edit.resultTitle")}</FieldLabel><Input id={`${prefix}-title`} value={title} maxLength={160} disabled={submit.isPending} onChange={(event) => setTitle(event.target.value)} /></Field>
      {shots.map((shot, index) => {
        const source = videos.find((item) => item.selectedVersionId === shot.videoVersionId);
        const assetId = readContentText(source?.selectedVersion?.content, "assetId");
        return <section key={shot.id} className="video-edit-shot" aria-label={`${t("video.edit.clip")} ${index + 1}`}>
          <div className="video-edit-row"><strong>{t("video.edit.clip")} {index + 1}</strong>
            <Button type="button" variant="outline" disabled={submit.isPending || index === 0} onClick={() => move(index, -1)}>{t("video.edit.moveUp")}</Button>
            <Button type="button" variant="outline" disabled={submit.isPending || index === shots.length - 1} onClick={() => move(index, 1)}>{t("video.edit.moveDown")}</Button>
            <Button type="button" variant="ghost" disabled={submit.isPending} onClick={() => setShots((current) => current.filter((item) => item.id !== shot.id))}>{t("video.edit.remove")}</Button></div>
          <Field><FieldLabel htmlFor={`${prefix}-${shot.id}-source`}>{t("video.edit.source")}</FieldLabel>
            <Select id={`${prefix}-${shot.id}-source`} value={shot.videoVersionId} disabled={submit.isPending} onChange={(event) => patch(shot.id, { videoVersionId: event.target.value, startMs: 0, endMs: null })}>
              {videos.map((item) => <option key={item.id} value={item.selectedVersionId!}>{item.title}</option>)}
            </Select></Field>
          {assetId ? <VideoRangeEditor key={assetId} src={assetContentUrl(projectId, assetId)} posterSrc={assetThumbnailUrl(projectId, assetId)} title={source?.title}
            startMs={shot.startMs} endMs={duration(shot)} disabled={submit.isPending} onChange={({ startMs, endMs }) => patch(shot.id, { startMs, endMs })} /> : null}
          {details.get(shot.videoVersionId)?.error ? <p role="alert">{t("video.edit.metadataFailed")} <Button type="button" variant="ghost" onClick={() => void details.get(shot.videoVersionId)?.refetch()}>{t("common.retryRead")}</Button></p> : null}
          <FieldGroup className="video-edit-grid"><Field data-invalid={invalidSpeed(shot)}><FieldLabel htmlFor={`${prefix}-${shot.id}-speed`}>{t("video.edit.speed")}</FieldLabel>
            <Input id={`${prefix}-${shot.id}-speed`} type="number" min={0.5} max={2} step={0.05} value={Number.isFinite(shot.speed) ? shot.speed : ""} disabled={submit.isPending} aria-invalid={invalidSpeed(shot)} onChange={(event) => patch(shot.id, { speed: event.target.valueAsNumber })} /></Field>
            <Field data-invalid={invalidFade(shot)}><FieldLabel htmlFor={`${prefix}-${shot.id}-fade`}>{t("video.edit.fade")}</FieldLabel><Input id={`${prefix}-${shot.id}-fade`} type="number" min={0} max={500} step={10} value={Number.isFinite(shot.fadeMs) ? shot.fadeMs : ""} disabled={submit.isPending} aria-invalid={invalidFade(shot)} onChange={(event) => patch(shot.id, { fadeMs: event.target.valueAsNumber })} /></Field></FieldGroup>
          {invalidSpeed(shot) || invalidFade(shot) ? <FieldError>{t("video.edit.clipInvalid")}</FieldError> : null}
          {shot.lines.map((line, lineIndex) => <div className="video-edit-row" key={lineIndex}><Select aria-label={t("video.edit.voice")} value={line.audioVersionId} disabled={submit.isPending} onChange={(event) => patch(shot.id, { lines: shot.lines.map((item, position) => position === lineIndex ? { audioVersionId: event.target.value } : item) })}>
            {audios.map((item) => <option key={item.id} value={item.selectedVersionId!}>{item.title}</option>)}</Select>
            <Button type="button" variant="ghost" disabled={submit.isPending} onClick={() => patch(shot.id, { lines: shot.lines.filter((_, position) => position !== lineIndex) })}>{t("video.edit.remove")}</Button></div>)}
          <Button type="button" variant="outline" disabled={submit.isPending || !audios.length || shots.reduce((sum, item) => sum + item.lines.length, 0) >= 30} onClick={() => { const audio = audios[0]; if (audio?.selectedVersionId) patch(shot.id, { lines: [...shot.lines, { audioVersionId: audio.selectedVersionId }] }); }}>{t("video.edit.addVoice")}</Button>
        </section>;
      })}
      <Button type="button" variant="outline" disabled={submit.isPending || shots.length >= 12 || !videos.length} onClick={() => { const first = videos[0]; if (first) setShots((current) => [...current, createShot(first)]); }}>{t("video.edit.addClip")}</Button>
      <FieldGroup className="video-edit-grid"><Field><FieldLabel htmlFor={`${prefix}-sound`}>{t("video.edit.originalAudio")}</FieldLabel><Select id={`${prefix}-sound`} value={originalAudioMode} disabled={submit.isPending} onChange={(event) => setOriginalAudioMode(event.target.value as typeof originalAudioMode)}>
        <option value="KEEP">{t("video.edit.keep")}</option><option value="MUTE">{t("video.edit.mute")}</option><option value="BACKGROUND">{t("video.edit.background")}</option></Select></Field>
        <Field><FieldLabel htmlFor={`${prefix}-resolution`}>{t("video.edit.resolution")}</FieldLabel><Select id={`${prefix}-resolution`} value={resolution} disabled={submit.isPending} onChange={(event) => setResolution(event.target.value as typeof resolution)}><option value="720P">720p</option><option value="1080P">1080p</option></Select></Field>
        <Field><FieldLabel htmlFor={`${prefix}-aspect`}>{t("video.edit.aspect")}</FieldLabel><Select id={`${prefix}-aspect`} value={aspectRatio} disabled={submit.isPending} onChange={(event) => setAspectRatio(event.target.value as typeof aspectRatio)}>{["16:9", "9:16", "1:1"].map((value) => <option key={value}>{value}</option>)}</Select></Field></FieldGroup>
      <Field><FieldLabel htmlFor={`${prefix}-music`}>{t("video.edit.music")}</FieldLabel><Select id={`${prefix}-music`} value={music} disabled={submit.isPending} onChange={(event) => setMusic(event.target.value)}><option value="">{t("video.edit.none")}</option>{audios.map((item) => <option key={item.id} value={item.selectedVersionId!}>{item.title}</option>)}</Select></Field>
      {music ? <FieldGroup className="video-edit-grid"><Field><FieldLabel htmlFor={`${prefix}-volume`}>{t("video.edit.volume")}</FieldLabel><Input id={`${prefix}-volume`} type="number" min={0} max={1} step={0.05} value={Number.isFinite(musicVolume) ? musicVolume : ""} disabled={submit.isPending} onChange={(event) => setMusicVolume(event.target.valueAsNumber)} /></Field>
        <Field><FieldLabel htmlFor={`${prefix}-music-start`}>{t("video.edit.musicStart")}</FieldLabel><Input id={`${prefix}-music-start`} type="number" min={0} step={0.001} value={Number.isFinite(musicStartMs) ? musicStartMs / 1000 : ""} disabled={submit.isPending} onChange={(event) => setMusicStartMs(Math.round(event.target.valueAsNumber * 1000))} /></Field>
        {details.get(music)?.error ? <p role="alert">{t("video.edit.metadataFailed")} <Button type="button" variant="ghost" onClick={() => void details.get(music)?.refetch()}>{t("common.retryRead")}</Button></p> : null}
        {invalidMusic && details.get(music)?.data ? <FieldError>{t("video.edit.musicInvalid")}</FieldError> : null}
      </FieldGroup> : null}
      {captions.map((caption) => <div className="video-edit-caption" key={caption.id}><Field><FieldLabel htmlFor={`${prefix}-${caption.id}`}>{t("video.edit.subtitle")}</FieldLabel><Textarea id={`${prefix}-${caption.id}`} value={caption.text} maxLength={500} disabled={submit.isPending} onChange={(event) => setCaptions((current) => current.map((item) => item.id === caption.id ? { ...item, text: event.target.value } : item))} /></Field>
        <FieldGroup className="video-edit-grid">{(["startMs", "endMs"] as const).map((field) => <Field key={field}><FieldLabel htmlFor={`${prefix}-${caption.id}-${field}`}>{t(field === "startMs" ? "video.edit.captionStart" : "video.edit.captionEnd")}</FieldLabel><Input id={`${prefix}-${caption.id}-${field}`} type="number" min={0} step={0.001} value={Number.isFinite(caption[field]) ? caption[field] / 1000 : ""} disabled={submit.isPending} onChange={(event) => setCaptions((current) => current.map((item) => item.id === caption.id ? { ...item, [field]: Math.round(event.target.valueAsNumber * 1000) } : item))} /></Field>)}</FieldGroup>
        {invalidCaption(caption) ? <FieldError>{t("video.edit.subtitleInvalid")}</FieldError> : null}
        <Button type="button" variant="ghost" disabled={submit.isPending} onClick={() => setCaptions((current) => current.filter((item) => item.id !== caption.id))}>{t("video.edit.remove")}</Button></div>)}
      <Button type="button" variant="outline" disabled={submit.isPending || captions.length >= 100} onClick={() => setCaptions((current) => [...current, { id: crypto.randomUUID(), text: "", startMs: 0, endMs: Math.max(100, Math.floor(totalMs)) }])}>{t("video.edit.addSubtitle")}</Button>
    </FieldGroup></div>
    <p role="status">{metadataPending ? t("app.pageLoading") : t("video.edit.duration", { "0": Number.isFinite(totalMs) ? (totalMs / 1000).toFixed(3) : "—" })}</p>
    {submit.error ? <p role="alert">{submit.error.message}</p> : null}
    <DialogFooter><Button type="button" variant="outline" disabled={submit.isPending} onClick={onClose}>{t("common.close")}</Button>
      <Button type="button" disabled={submit.isPending || metadataPending || invalid} onClick={() => submit.mutate({ title: title.trim(), originalAudioMode, output: { resolution, aspectRatio },
        shots: shots.map((shot) => ({ videoVersionId: shot.videoVersionId, sourceStartMs: shot.startMs, durationMs: duration(shot) - shot.startMs, speed: shot.speed, fadeMs: shot.fadeMs, lines: shot.lines })),
        ...(music ? { music: { audioVersionId: music, volume: musicVolume, sourceStartMs: musicStartMs } } : {}), subtitles: captions.map(({ text, startMs, endMs }) => ({ text, startMs, endMs })),
      })}>{submit.isPending ? t("video.edit.submitting") : t("video.edit.submit")}</Button></DialogFooter>
  </DialogContent></Dialog>;
}
