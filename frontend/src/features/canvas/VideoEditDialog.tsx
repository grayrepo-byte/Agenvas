import { useMutation, useQueries, useQueryClient } from "@tanstack/react-query";
import { CaretDown, CaretUp, CheckCircle, CircleNotch, DownloadSimple, FilmStrip, Info, MusicNotes, Plus, Scissors, SpeakerHigh, TextT, Trash, X } from "@/shared/ui/icons";
import { cn } from "cn";
import { useId, useRef, useState, type ReactNode } from "react";
import { assetContentUrl, assetThumbnailUrl, composeVideo, type CanvasItem, type VideoCompositionRequest } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle } from "../../shared/ui/primitives/dialog";
import { Field, FieldError, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "../../shared/ui/primitives/empty";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../shared/ui/primitives/tabs";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "../../shared/ui/primitives/tooltip";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { assetMetadataQueryOptions } from "./mediaDisplay";
import { readContentText } from "./artifactContent";
import { videoTimeLabel } from "./VideoReviewControls";
import { useCompositionVoices } from "./useCompositionVoices";
import { CompositionPlayback } from "./compositionPlayback";
import { COMPOSITION_FPS, createCompositionTimeline } from "./compositionTimeline";
import { VideoCompositionPlayer, VideoCompositionClipControls } from "./VideoCompositionPlayer";
import { VideoCompositionTimeline } from "./VideoCompositionTimeline";
import "./VideoEditDialog.css";

type EditShot = { id: string; videoVersionId: string; startMs: number; endMs: number | null; speed: number; fadeMs: number; lines: { audioVersionId: string; startMs?: number }[] };
type Caption = { id: string; text: string; startMs: number; endMs: number };

function ClipThumbnail({ src }: { src?: string }) {
  const [failed, setFailed] = useState(false);
  return src && !failed ? <img src={src} alt="" loading="lazy" onError={() => setFailed(true)} /> : <FilmStrip aria-hidden="true" />;
}

function IconAction({ label, children, ...props }: React.ComponentProps<typeof Button> & { label: string; children: ReactNode }) {
  return <Tooltip><TooltipTrigger asChild><Button type="button" variant="ghost" size="icon-xs" aria-label={label} {...props}>{children}</Button></TooltipTrigger>
    <TooltipContent>{label}</TooltipContent></Tooltip>;
}

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
  const [activeShotId, setActiveShotId] = useState<string | null>(() => shots[0]?.id ?? null);
  const [inspector, setInspector] = useState("clip");
  const inspectorElement = useRef<HTMLElement>(null);
  const [playback] = useState(() => new CompositionPlayback());
  const [trimming, setTrimming] = useState(false);
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
  const metadataError = metadata.some((query, index) => requiredSources.has(mediaSources[index]?.selectedVersionId ?? "") && query.isError);
  const duration = (shot: EditShot) => shot.endMs ?? details.get(shot.videoVersionId)?.data?.durationMs ?? 0;
  const frames = (shot: EditShot) => Math.floor((duration(shot) - shot.startMs) * COMPOSITION_FPS / (1000 * shot.speed));
  const outputMs = (shot: EditShot) => Math.floor(frames(shot) * 1000 / COMPOSITION_FPS);
  const totalMs = Math.floor(shots.reduce((sum, shot) => sum + frames(shot), 0) * 1000 / COMPOSITION_FPS);
  const invalidSpeed = (shot: EditShot) => !Number.isFinite(shot.speed) || shot.speed < 0.5 || shot.speed > 2;
  const invalidFade = (shot: EditShot) => !Number.isInteger(shot.fadeMs) || shot.fadeMs < 0 || shot.fadeMs > 500 || shot.fadeMs * 2 > outputMs(shot);
  const shotInvalid = (shot: EditShot) => invalidSpeed(shot)
    || !Number.isInteger(shot.startMs) || !Number.isInteger(duration(shot)) || shot.startMs < 0 || duration(shot) - shot.startMs < 100
    || duration(shot) > (details.get(shot.videoVersionId)?.data?.durationMs ?? 0)
    || invalidFade(shot);
  const invalidShot = shots.some(shotInvalid);
  const invalidCaption = (caption: Caption) => !caption.text.trim() || [...caption.text].some((character) => character !== '\n' && /\p{Cc}/u.test(character)) || caption.text.length > 500 || !Number.isInteger(caption.startMs)
    || !Number.isInteger(caption.endMs) || caption.startMs < 0 || caption.endMs <= caption.startMs || caption.endMs > totalMs;
  const invalidMusic = !!music && (!Number.isFinite(musicVolume) || musicVolume < 0 || musicVolume > 1 || !Number.isInteger(musicStartMs) || musicStartMs < 0 || musicStartMs > 599999
    || musicStartMs >= (details.get(music)?.data?.durationMs ?? 0));
  const invalidCaptions = captions.some(invalidCaption);
  const invalid = !title.trim() || !shots.length || shots.length > 12 || totalMs > 60_000 || invalidShot
    || shots.reduce((sum, shot) => sum + shot.lines.length, 0) > 30
    || invalidCaptions || invalidMusic;
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
  const activeShot = shots.find((shot) => shot.id === activeShotId) ?? shots[0];
  const activeIndex = activeShot ? shots.indexOf(activeShot) : -1;
  const voiceCount = shots.reduce((sum, shot) => sum + shot.lines.length, 0);
  const safeTotal = Number.isFinite(totalMs) && totalMs > 0 ? totalMs : 0;
  const timeline = createCompositionTimeline(shots.map((shot) => {
    const source = videos.find((item) => item.selectedVersionId === shot.videoVersionId);
    const assetId = readContentText(source?.selectedVersion?.content, "assetId");
    return { id: shot.id, title: source?.title ?? "", src: assetId ? assetContentUrl(projectId, assetId) : "",
      poster: assetId ? assetThumbnailUrl(projectId, assetId) : undefined, sourceDurationMs: details.get(shot.videoVersionId)?.data?.durationMs ?? 0,
      sourceStartMs: shot.startMs, sourceEndMs: duration(shot), speed: shot.speed, fadeMs: shot.fadeMs };
  }));
  const musicItem = audios.find((item) => item.selectedVersionId === music);
  const musicAssetId = readContentText(musicItem?.selectedVersion?.content, "assetId");
  const previewMusic = musicAssetId && !invalidMusic ? { src: assetContentUrl(projectId, musicAssetId), sourceStartMs: musicStartMs,
    durationMs: details.get(music)?.data?.durationMs ?? 0, volume: musicVolume } : undefined;
  const previewShots = shots.map((shot) => ({ videoVersionId: shot.videoVersionId, sourceStartMs: shot.startMs,
    durationMs: duration(shot) - shot.startMs, speed: shot.speed, fadeMs: shot.fadeMs, lines: shot.lines }));
  const voicePreview = useCompositionVoices(projectId, previewShots, audios, timeline.valid && !metadataPending && !metadataError);
  const thumbnail = (shot: EditShot) => {
    const source = videos.find((item) => item.selectedVersionId === shot.videoVersionId);
    const assetId = readContentText(source?.selectedVersion?.content, "assetId");
    return assetId ? assetThumbnailUrl(projectId, assetId) : undefined;
  };
  const timeLabel = (ms: number) => Number.isFinite(ms) && ms >= 0 ? videoTimeLabel(ms / 1000) : "—";
  const showShot = (id: string) => { setActiveShotId(id); setInspector("clip"); playback.seekToClip(id); };
  const openInspector = (tab: string) => {
    setInspector(tab);
    // The inspector stacks below the preview on narrow screens; footer shortcuts must reveal it.
    inspectorElement.current?.scrollIntoView?.({ block: "nearest" });
  };
  const addShot = () => {
    const source = videos.find((item) => !shots.some((shot) => shot.videoVersionId === item.selectedVersionId)) ?? videos[0];
    if (!source) return;
    const next = createShot(source);
    setShots((current) => [...current, next]); showShot(next.id);
  };
  const removeShot = (id: string) => {
    const index = shots.findIndex((shot) => shot.id === id);
    setShots((current) => current.filter((shot) => shot.id !== id));
    if (activeShot?.id === id) setActiveShotId(shots[index + 1]?.id ?? shots[index - 1]?.id ?? null);
  };
  const reorder = (sourceId: string, targetId: string) => {
    if (sourceId === targetId) return;
    setShots((current) => {
      const from = current.findIndex((shot) => shot.id === sourceId), to = current.findIndex((shot) => shot.id === targetId);
      if (from < 0 || to < 0) return current;
      const next = [...current]; const [source] = next.splice(from, 1);
      if (source) next.splice(to, 0, source);
      return next;
    });
  };
  const statusText = submit.isPending ? t("video.edit.submitting") : metadataPending ? t("video.edit.readingSources") : metadataError ? t("video.edit.sourceErrorHint")
    : safeTotal > 60000 ? t("video.edit.tooLong") : !shots.length ? t("video.edit.emptyHint")
    : invalidCaptions ? t("video.edit.subtitleInvalid") : invalidMusic ? t("video.edit.musicInvalid") : invalid ? t("video.edit.checkSettings") : submit.error ? t("video.edit.failed") : t("video.edit.ready");
  const sourceError = (versionId: string) => details.get(versionId)?.error ? <Alert variant="destructive"><Info />
    <AlertDescription>{t("video.edit.metadataFailed")}<Button type="button" size="sm" variant="outline" disabled={submit.isPending}
      onClick={() => void details.get(versionId)?.refetch()}>{t("common.retryRead")}</Button></AlertDescription></Alert> : null;

  return <Dialog open onOpenChange={(open) => { if (!open && !submit.isPending) onClose(); }}>
    <DialogContent className="video-edit-dialog nodrag nowheel nopan" showCloseButton={false}
      onEscapeKeyDown={(event) => { if (trimming) event.preventDefault(); }}
      onOpenAutoFocus={(event) => { event.preventDefault(); }}
      onKeyDown={(event) => { if (event.key !== "Tab") event.stopPropagation(); }}>
      <TooltipProvider delayDuration={300}>
        <header className="video-edit-header">
          <div className="video-edit-brand"><span className="video-edit-brand-icon"><Scissors aria-hidden="true" /></span>
            <div><DialogTitle>{t("video.edit.title")}</DialogTitle><p>{t("video.edit.workspaceHint")}</p></div>
          </div>
          <div className="video-edit-header-actions"><Badge variant="outline">{t("video.edit.localProcessing")}</Badge>
            <IconAction label={t("common.close")} size="icon-sm" disabled={submit.isPending} onClick={onClose}><X /></IconAction>
          </div>
          <DialogDescription className="sr-only">{t("video.edit.description")}</DialogDescription>
        </header>

        <div className="video-edit-workspace">
          <aside className="video-edit-sources" aria-label={t("video.edit.sequence")}>
            <div className="video-edit-panel-heading"><h3><FilmStrip aria-hidden="true" />{t("video.edit.sequence")}</h3><Badge variant="secondary">{shots.length} / 12</Badge></div>
            <p className="video-edit-panel-hint">{t("video.edit.sequenceHint")}</p>
            <ol className="video-edit-clip-list">
              {shots.map((shot, index) => {
                const source = videos.find((item) => item.selectedVersionId === shot.videoVersionId);
                const failed = details.get(shot.videoVersionId)?.isError;
                return <li key={shot.id} className={cn("video-edit-clip", shot.id === activeShot?.id && "is-active")}
                  aria-label={`${t("video.edit.clip")} ${index + 1}`}>
                  <Button type="button" variant="ghost" className="video-edit-clip-select" aria-pressed={shot.id === activeShot?.id}
                    disabled={submit.isPending} onClick={() => showShot(shot.id)} aria-label={t("video.edit.selectClip", { "0": index + 1, "1": source?.title ?? "" })}>
                    <span className="video-edit-clip-thumb"><ClipThumbnail key={thumbnail(shot)} src={thumbnail(shot)} /><span>{String(index + 1).padStart(2, "0")}</span></span>
                    <span className="video-edit-clip-copy"><strong>{source?.title ?? t("video.edit.source")}</strong>
                      <small>{timeLabel(outputMs(shot))} <span>· {Number.isFinite(shot.speed) ? shot.speed : "—"}×</span></small>
                      {failed || shotInvalid(shot) ? <span className="video-edit-clip-warning">{failed ? t("video.edit.metadataFailed") : t("video.edit.needsAttention")}</span> : null}
                    </span>
                  </Button>
                  <div className="video-edit-clip-actions">
                    <span>{t("video.edit.clip")} {String(index + 1).padStart(2, "0")}</span>
                    <IconAction label={t("video.edit.moveUp")} disabled={submit.isPending || index === 0} onClick={() => move(index, -1)}><CaretUp /></IconAction>
                    <IconAction label={t("video.edit.moveDown")} disabled={submit.isPending || index === shots.length - 1} onClick={() => move(index, 1)}><CaretDown /></IconAction>
                    <IconAction label={t("video.edit.remove")} disabled={submit.isPending} onClick={() => removeShot(shot.id)}><Trash /></IconAction>
                  </div>
                </li>;
              })}
            </ol>
            <Button type="button" variant="outline" className="video-edit-add-clip" disabled={submit.isPending || shots.length >= 12 || !videos.length} onClick={addShot}>
              <Plus data-icon="inline-start" />{t("video.edit.addClip")}</Button>
            <p className="video-edit-source-note"><Info aria-hidden="true" />{t("video.edit.pinnedHint")}</p>
          </aside>

          <section className="video-edit-monitor" aria-label={t("video.edit.player")}>
            <div className="video-edit-panel-heading"><h3>{t("video.edit.player")}</h3><span>{t("video.edit.fullPreview")}</span></div>
            {shots.length ? <VideoCompositionPlayer controller={playback} model={{ timeline, originalAudioMode, music: previewMusic, voices: voicePreview.voices, blocked: metadataPending || metadataError || invalidMusic || voicePreview.blocked }}
              captions={captions} fontFamily={voicePreview.fontFamily} resolution={resolution} aspectRatio={aspectRatio} disabled={submit.isPending} />
              : <Empty><EmptyHeader><EmptyMedia variant="icon"><FilmStrip /></EmptyMedia><EmptyTitle>{t("video.edit.emptyTitle")}</EmptyTitle>
                <EmptyDescription>{t("video.edit.emptyHint")}</EmptyDescription></EmptyHeader></Empty>}
            {voicePreview.pending ? <p className="video-edit-preview-note" role="status">{t("video.edit.preparingVoices")}</p> : null}
            {voicePreview.error ? <Alert variant="destructive"><AlertDescription>{voicePreview.error.message}<Button type="button" variant="outline" size="sm" disabled={submit.isPending || voicePreview.pending} onClick={() => void voicePreview.retry()}>{t("common.retryRead")}</Button></AlertDescription></Alert> : null}
            <p className="video-edit-preview-note"><Info aria-hidden="true" />{t("video.edit.livePreviewHint")}</p>
          </section>

          <aside ref={inspectorElement} className="video-edit-inspector" aria-label={t("video.edit.inspector")}>
            <Tabs value={inspector} onValueChange={setInspector} className="video-edit-inspector-tabs">
              <TabsList variant="line" className="video-edit-inspector-tab-list" aria-label={t("video.edit.inspector")}>
                <TabsTrigger value="clip"><Scissors />{t("video.edit.clip")}</TabsTrigger>
                <TabsTrigger value="audio"><MusicNotes />{t("video.edit.audioTab")}</TabsTrigger>
                <TabsTrigger value="captions"><TextT />{t("video.edit.captionsTab")}</TabsTrigger>
                <TabsTrigger value="output"><DownloadSimple />{t("video.edit.outputTab")}</TabsTrigger>
              </TabsList>
              <TabsContent value="clip" className="video-edit-inspector-content">
                {activeShot ? <section aria-label={`${t("video.edit.clip")} ${activeIndex + 1}`}>
                  <div className="video-edit-section-heading"><h3>{t("video.edit.clipSettings")}</h3><Badge variant="secondary">{String(activeIndex + 1).padStart(2, "0")}</Badge></div>
                  <FieldGroup>
                    <Field><FieldLabel htmlFor={`${prefix}-source`}>{t("video.edit.source")}</FieldLabel>
                      <Select id={`${prefix}-source`} value={activeShot.videoVersionId} disabled={submit.isPending}
                        onChange={(event) => patch(activeShot.id, { videoVersionId: event.target.value, startMs: 0, endMs: null })}>
                        {videos.map((item) => <option key={item.id} value={item.selectedVersionId!}>{item.title}</option>)}
                      </Select></Field>
                    {sourceError(activeShot.videoVersionId)}
                    <VideoCompositionClipControls controller={playback} timeline={timeline} clipId={activeShot.id} startMs={activeShot.startMs} endMs={duration(activeShot)}
                      sourceDurationMs={details.get(activeShot.videoVersionId)?.data?.durationMs ?? 0} disabled={submit.isPending}
                      onChange={(range) => patch(activeShot.id, range)} />
                    <FieldGroup className="video-edit-grid">
                      <Field data-invalid={invalidSpeed(activeShot)}><FieldLabel htmlFor={`${prefix}-speed`}>{t("video.edit.speed")}</FieldLabel>
                        <Input id={`${prefix}-speed`} type="number" min={0.5} max={2} step={0.05} value={Number.isFinite(activeShot.speed) ? activeShot.speed : ""}
                          disabled={submit.isPending} aria-invalid={invalidSpeed(activeShot)} onChange={(event) => patch(activeShot.id, { speed: event.target.valueAsNumber })} /></Field>
                      <Field data-invalid={invalidFade(activeShot)}><FieldLabel htmlFor={`${prefix}-fade`}>{t("video.edit.fade")}</FieldLabel>
                        <Input id={`${prefix}-fade`} type="number" min={0} max={500} step={10} value={Number.isFinite(activeShot.fadeMs) ? activeShot.fadeMs : ""}
                          disabled={submit.isPending} aria-invalid={invalidFade(activeShot)} onChange={(event) => patch(activeShot.id, { fadeMs: event.target.valueAsNumber })} /></Field>
                    </FieldGroup>
                    {invalidSpeed(activeShot) || invalidFade(activeShot) ? <FieldError>{t("video.edit.clipInvalid")}</FieldError> : null}
                  </FieldGroup>
                  <dl className="video-edit-clip-summary"><div><dt>{t("video.edit.sourceRange")}</dt><dd>{timeLabel(activeShot.startMs)} – {timeLabel(duration(activeShot))}</dd></div>
                    <div><dt>{t("video.edit.clipOutput")}</dt><dd>{timeLabel(outputMs(activeShot))}</dd></div></dl>
                </section> : <Empty><EmptyHeader><EmptyDescription>{t("video.edit.emptyHint")}</EmptyDescription></EmptyHeader></Empty>}
              </TabsContent>
              <TabsContent value="audio" className="video-edit-inspector-content">
                <FieldGroup>
                  <div className="video-edit-section-heading"><h3><SpeakerHigh />{t("video.edit.soundMix")}</h3></div>
                  <Field><FieldLabel htmlFor={`${prefix}-sound`}>{t("video.edit.originalAudio")}</FieldLabel>
                    <Select id={`${prefix}-sound`} value={originalAudioMode} disabled={submit.isPending} onChange={(event) => setOriginalAudioMode(event.target.value as typeof originalAudioMode)}>
                      <option value="KEEP">{t("video.edit.keep")}</option><option value="MUTE">{t("video.edit.mute")}</option><option value="BACKGROUND">{t("video.edit.background")}</option>
                    </Select></Field>
                  <Field><FieldLabel htmlFor={`${prefix}-music`}>{t("video.edit.music")}</FieldLabel><Select id={`${prefix}-music`} value={music} disabled={submit.isPending} onChange={(event) => setMusic(event.target.value)}>
                    <option value="">{t("video.edit.none")}</option>{audios.map((item) => <option key={item.id} value={item.selectedVersionId!}>{item.title}</option>)}
                  </Select></Field>
                  {music ? <>
                    <Field data-invalid={invalidMusic}><FieldLabel htmlFor={`${prefix}-volume`}>{t("video.edit.volume")}</FieldLabel>
                      <Input id={`${prefix}-volume`} type="number" min={0} max={1} step={0.05} value={Number.isFinite(musicVolume) ? musicVolume : ""} disabled={submit.isPending} aria-invalid={invalidMusic} onChange={(event) => setMusicVolume(event.target.valueAsNumber)} /></Field>
                    <Field data-invalid={invalidMusic}><FieldLabel htmlFor={`${prefix}-music-start`}>{t("video.edit.musicStart")}</FieldLabel>
                      <Input id={`${prefix}-music-start`} type="number" min={0} step={0.001} value={Number.isFinite(musicStartMs) ? musicStartMs / 1000 : ""} disabled={submit.isPending} aria-invalid={invalidMusic} onChange={(event) => setMusicStartMs(Math.round(event.target.valueAsNumber * 1000))} /></Field>
                    {sourceError(music)}{invalidMusic && details.get(music)?.data ? <FieldError>{t("video.edit.musicInvalid")}</FieldError> : null}
                  </> : null}
                  <div className="video-edit-section-heading"><h3>{activeShot ? t("video.edit.clipVoice", { "0": activeIndex + 1 }) : t("video.edit.voiceCount")}</h3><Badge variant="secondary">{activeShot?.lines.length ?? 0}</Badge></div>
                  <p className="video-edit-panel-hint">{t("video.edit.voiceHint")}</p>
                  {activeShot?.lines.map((line, index) => <div className="video-edit-voice-row" key={index}>
                    <Select aria-label={t("video.edit.voice")} value={line.audioVersionId} disabled={submit.isPending} onChange={(event) => patch(activeShot.id, { lines: activeShot.lines.map((item, position) => position === index ? { audioVersionId: event.target.value } : item) })}>
                      {audios.map((item) => <option key={item.id} value={item.selectedVersionId!}>{item.title}</option>)}
                    </Select><IconAction label={t("video.edit.remove")} disabled={submit.isPending} onClick={() => patch(activeShot.id, { lines: activeShot.lines.filter((_, position) => position !== index) })}><Trash /></IconAction>
                  </div>)}
                  <Button type="button" variant="outline" size="sm" disabled={submit.isPending || !activeShot || !audios.length || voiceCount >= 30} onClick={() => {
                    const audio = audios[0]; if (activeShot && audio?.selectedVersionId) patch(activeShot.id, { lines: [...activeShot.lines, { audioVersionId: audio.selectedVersionId }] });
                  }}><Plus data-icon="inline-start" />{t("video.edit.addVoice")}</Button>
                  {!audios.length ? <p className="video-edit-panel-hint">{t("video.edit.noAudio")}</p> : null}
                </FieldGroup>
              </TabsContent>
              <TabsContent value="captions" className="video-edit-inspector-content">
                <div className="video-edit-section-heading"><h3>{t("video.edit.timedCaptions")}</h3><Badge variant="secondary">{captions.length} / 100</Badge></div>
                {!captions.length ? <Empty className="video-edit-empty-captions"><EmptyHeader><EmptyMedia variant="icon"><TextT /></EmptyMedia>
                  <EmptyTitle>{t("video.edit.noCaptions")}</EmptyTitle><EmptyDescription>{t("video.edit.captionHint")}</EmptyDescription></EmptyHeader></Empty> : null}
                <FieldGroup>
                  {captions.map((caption, index) => <section className="video-edit-caption" key={caption.id} aria-label={`${t("video.edit.captionsTab")} ${index + 1}`}>
                    <div className="video-edit-section-heading"><span>{String(index + 1).padStart(2, "0")}</span><IconAction label={t("video.edit.remove")} disabled={submit.isPending} onClick={() => setCaptions((current) => current.filter((item) => item.id !== caption.id))}><Trash /></IconAction></div>
                    <Field data-invalid={invalidCaption(caption)}><FieldLabel htmlFor={`${prefix}-${caption.id}`}>{t("video.edit.subtitle")}</FieldLabel>
                      <Textarea id={`${prefix}-${caption.id}`} value={caption.text} maxLength={500} disabled={submit.isPending} aria-invalid={invalidCaption(caption)} onChange={(event) => setCaptions((current) => current.map((item) => item.id === caption.id ? { ...item, text: event.target.value } : item))} /></Field>
                    <FieldGroup className="video-edit-grid">{(["startMs", "endMs"] as const).map((field) => <Field key={field} data-invalid={invalidCaption(caption)}>
                      <FieldLabel htmlFor={`${prefix}-${caption.id}-${field}`}>{t(field === "startMs" ? "video.edit.captionStart" : "video.edit.captionEnd")}</FieldLabel>
                      <Input id={`${prefix}-${caption.id}-${field}`} type="number" min={0} step={0.001} value={Number.isFinite(caption[field]) ? caption[field] / 1000 : ""} disabled={submit.isPending} aria-invalid={invalidCaption(caption)}
                        onChange={(event) => setCaptions((current) => current.map((item) => item.id === caption.id ? { ...item, [field]: Math.round(event.target.valueAsNumber * 1000) } : item))} />
                    </Field>)}</FieldGroup>
                    {invalidCaption(caption) ? <FieldError>{t("video.edit.subtitleInvalid")}</FieldError> : null}
                  </section>)}
                  <Button type="button" variant="outline" size="sm" disabled={submit.isPending || captions.length >= 100} onClick={() => setCaptions((current) => [...current, { id: crypto.randomUUID(), text: "", startMs: 0, endMs: Math.max(100, safeTotal) }])}>
                    <Plus data-icon="inline-start" />{t("video.edit.addSubtitle")}</Button>
                </FieldGroup>
              </TabsContent>
              <TabsContent value="output" className="video-edit-inspector-content">
                <div className="video-edit-section-heading"><h3>{t("video.edit.outputSettings")}</h3><Badge variant="outline">MP4</Badge></div>
                <FieldGroup>
                  <Field data-invalid={!title.trim()}><FieldLabel htmlFor={`${prefix}-title`}>{t("video.edit.resultTitle")}</FieldLabel><Input id={`${prefix}-title`} value={title} maxLength={160} disabled={submit.isPending} aria-invalid={!title.trim()} onChange={(event) => setTitle(event.target.value)} /></Field>
                  <Field><FieldLabel htmlFor={`${prefix}-resolution`}>{t("video.edit.resolution")}</FieldLabel><Select id={`${prefix}-resolution`} value={resolution} disabled={submit.isPending} onChange={(event) => setResolution(event.target.value as typeof resolution)}>
                    <option value="720P">720p</option><option value="1080P">1080p</option></Select></Field>
                  <Field><FieldLabel htmlFor={`${prefix}-aspect`}>{t("video.edit.aspect")}</FieldLabel><Select id={`${prefix}-aspect`} value={aspectRatio} disabled={submit.isPending} onChange={(event) => setAspectRatio(event.target.value as typeof aspectRatio)}>
                    {["16:9", "9:16", "1:1"].map((value) => <option key={value}>{value}</option>)}</Select></Field>
                </FieldGroup>
                <dl className="video-edit-output-summary"><div><dt>{t("video.edit.frameRate")}</dt><dd>30 fps</dd></div>
                  <div><dt>{t("video.edit.clipCount")}</dt><dd>{shots.length} / 12</dd></div><div><dt>{t("video.edit.voiceCount")}</dt><dd>{voiceCount} / 30</dd></div>
                  <div><dt>{t("video.edit.totalDuration")}</dt><dd>{timeLabel(totalMs)}</dd></div></dl>
                <p className="video-edit-panel-hint">{t("video.edit.exportHint")}</p>
              </TabsContent>
            </Tabs>
          </aside>
        </div>

        <VideoCompositionTimeline timeline={timeline} controller={playback} onTrimmingChange={setTrimming} activeId={activeShot?.id} onSelect={showShot} onTrim={(id, range) => patch(id, range)} onReorder={reorder}
          captions={captions} music={previewMusic} musicTitle={musicItem?.title} voiceCount={voiceCount} onAudio={() => openInspector("audio")} onCaptions={() => openInspector("captions")} disabled={submit.isPending} />

        <DialogFooter className="video-edit-footer">
          <div className="video-edit-status" role="status" aria-live="polite">
            {metadataPending || submit.isPending ? <CircleNotch className="video-edit-spinner" aria-hidden="true" /> : invalid || metadataError || submit.error ? <Info aria-hidden="true" /> : <CheckCircle aria-hidden="true" />}
            <div><strong>{timeLabel(totalMs)}<span> / 01:00</span></strong><p>{statusText}</p></div>
          </div>
          <div className="video-edit-footer-actions"><Button type="button" variant="outline" disabled={submit.isPending} onClick={() => openInspector("output")}>{t("video.edit.outputSettings")}</Button>
            <Button type="button" disabled={submit.isPending || metadataPending || invalid || metadataError} onClick={() => submit.mutate({ title: title.trim(), originalAudioMode, output: { resolution, aspectRatio },
              shots: previewShots,
              ...(music ? { music: { audioVersionId: music, volume: musicVolume, sourceStartMs: musicStartMs } } : {}), subtitles: captions.map(({ text, startMs, endMs }) => ({ text, startMs, endMs })),
            })}>{submit.isPending ? <CircleNotch data-icon="inline-start" className="video-edit-spinner" /> : <DownloadSimple data-icon="inline-start" />}{submit.isPending ? t("video.edit.submitting") : t("video.edit.submit")}</Button></div>
          {submit.error ? <Alert variant="destructive" className="video-edit-submit-error"><AlertDescription>{t("video.edit.failed")}{submit.error.message !== t("video.edit.failed") ? ` · ${submit.error.message}` : null}</AlertDescription></Alert> : null}
        </DialogFooter>
      </TooltipProvider>
    </DialogContent>
  </Dialog>;
}
