import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { CaretDown, CaretUp, CheckCircle, CircleNotch, DownloadSimple, FilmStrip, Info, MusicNotes, Plus, Scissors, SpeakerHigh, TextT, Trash, X } from "@/shared/ui/icons";
import { cn } from "cn";
import { useId, useRef, useState, type ReactNode } from "react";
import { assetContentUrl, assetThumbnailUrl, composeVideo, getVideoCompositionCapabilities, type CanvasItem, type VideoCompositionRequest } from "../../shared/api/client";
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
import { SubtitleEditor } from "./subtitles/SubtitleEditor";
import { useSubtitleWorkbench } from "./subtitles/useSubtitleWorkbench";
import { mapSubtitleTrack, mapSubtitleTrackToAudio } from "./subtitles/subtitleTimeline";
import { downloadSubtitleFile, encodeSubtitleFile } from "./subtitles/subtitleExchange";
import type { SubtitleFormat } from "./subtitles/subtitlesApi";
import { CompositionPlayback } from "./compositionPlayback";
import { COMPOSITION_FPS, createCompositionTimeline } from "./compositionTimeline";
import { VideoCompositionPlayer, VideoCompositionClipControls } from "./VideoCompositionPlayer";
import { VideoCompositionTimeline } from "./VideoCompositionTimeline";
import "./VideoEditDialog.css";

type EditShot = { id: string; videoVersionId: string; startMs: number; endMs: number | null; speed: number; fadeMs: number; lines: { audioVersionId: string; startMs?: number }[] };
const CAPTIONS_PER_PAGE = 50;
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
  const capabilities = useQuery({ queryKey: ["video-composition-capabilities", projectId],
    queryFn: ({ signal }) => getVideoCompositionCapabilities(projectId, signal), retry: false, staleTime: 0,
    refetchOnWindowFocus: false });
  const limits = capabilities.data;
  const [sources] = useState(() => items.filter((item) => item.selectedVersionId && item.selectedVersion));
  const allVideos = sources.filter((item) => item.artifact?.kind === "VIDEO");
  const videos = [...new Map(allVideos.map((item) => [item.selectedVersionId, item])).values()];
  const audios = [...new Map(sources.filter((item) => item.artifact?.kind === "AUDIO").map((item) => [item.selectedVersionId, item])).values()];
  const createShot = (item: CanvasItem): EditShot => ({ id: crypto.randomUUID(), videoVersionId: item.selectedVersionId!, startMs: 0, endMs: null, speed: 1, fadeMs: 0, lines: [] });
  const [shots, setShots] = useState<EditShot[]>(() => {
    const selected = initialIds.flatMap((id) => allVideos.find((item) => item.id === id) ?? []);
    return (selected.length ? selected : videos.slice(0, 1)).slice(0, 120).map(createShot);
  });
  const [activeShotId, setActiveShotId] = useState<string | null>(() => shots[0]?.id ?? null);
  const [inspector, setInspector] = useState("clip");
  const inspectorElement = useRef<HTMLElement>(null);
  const [playback] = useState(() => new CompositionPlayback());
  const [trimming, setTrimming] = useState(false);
  const [title, setTitle] = useState(t("video.edit.defaultTitle"));
  const [originalAudioMode, setOriginalAudioMode] = useState<"KEEP" | "MUTE" | "BACKGROUND">("KEEP");
  const [resolution, setResolution] = useState<"720P" | "1080P" | "2160P">("720P");
  const [aspectRatio, setAspectRatio] = useState<"16:9" | "9:16" | "1:1">("16:9");
  const [music, setMusic] = useState("");
  const [musicVolume, setMusicVolume] = useState(0.2);
  const [musicStartMs, setMusicStartMs] = useState(0);
  const [captions, setCaptions] = useState<Caption[]>([]);
  const [excludedSubtitleSources, setExcludedSubtitleSources] = useState<ReadonlySet<string>>(() => new Set());
  const [accepting, setAccepting] = useState(false);
  const [closing, setClosing] = useState(false);
  const [subtitleExportError, setSubtitleExportError] = useState<string | null>(null);
  const [captionPage, setCaptionPage] = useState(0);
  const captionPages = Math.max(1, Math.ceil(captions.length / CAPTIONS_PER_PAGE));
  const currentCaptionPage = Math.min(captionPage, captionPages - 1);
  const client = useQueryClient();
  const command = useRef<{ payload: string; key: string } | null>(null);
  const mediaSources = [...videos, ...audios];
  const requiredSources = new Set([...shots.map((shot) => shot.videoVersionId), ...shots.flatMap((shot) => shot.lines.map((line) => line.audioVersionId)), music]);
  const subtitleWorkbench = useSubtitleWorkbench(projectId, mediaSources.filter((item) => requiredSources.has(item.selectedVersionId!))
    .map((item) => ({ versionId: item.selectedVersionId!, title: item.title, kind: item.artifact?.kind === "AUDIO" ? "AUDIO" as const : "VIDEO" as const })));
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
  const sourceAssets = new Map(mediaSources.filter((item) => requiredSources.has(item.selectedVersionId!))
    .flatMap((item) => { const asset = details.get(item.selectedVersionId)?.data; return asset ? [[asset.id, asset] as const] : []; }));
  const invalidSources = limits && ([...sourceAssets.values()].reduce((sum, asset) => sum + asset.byteSize, 0) > limits.maxSourceBytes
    || [...sourceAssets.values()].some((asset) => !asset.durationMs || asset.durationMs > limits.maxSourceDurationMs));
  const invalidCaption = (caption: Caption) => !caption.text.trim() || /\n[ \t]*\n/.test(caption.text) || [...caption.text].some((character) => character !== '\n' && /\p{Cc}/u.test(character)) || caption.text.length > 500 || !Number.isInteger(caption.startMs)
    || !Number.isInteger(caption.endMs) || caption.startMs < 0 || caption.endMs <= caption.startMs || caption.endMs > totalMs;
  const invalidMusic = !!music && (!Number.isFinite(musicVolume) || musicVolume < 0 || musicVolume > 1 || !Number.isInteger(musicStartMs) || musicStartMs < 0 || musicStartMs > 599999
    || musicStartMs >= (details.get(music)?.data?.durationMs ?? 0));
  const invalidCaptions = captions.some(invalidCaption);
  const invalidBase = !limits || capabilities.isFetching || !!capabilities.error || !title.trim() || !shots.length
    || shots.length > limits.maxShots || totalMs > limits.maxDurationMs || invalidShot || invalidSources
    || shots.reduce((sum, shot) => sum + shot.lines.length, 0) > limits.maxLines || captions.length > limits.maxSubtitles
    || !limits.resolutions.includes(resolution) || invalidCaptions || invalidMusic;
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
  }), limits?.maxFrames ?? 0);
  const musicItem = audios.find((item) => item.selectedVersionId === music);
  const musicAssetId = readContentText(musicItem?.selectedVersion?.content, "assetId");
  const previewMusic = musicAssetId && !invalidMusic ? { src: assetContentUrl(projectId, musicAssetId), sourceStartMs: musicStartMs,
    durationMs: details.get(music)?.data?.durationMs ?? 0, volume: musicVolume } : undefined;
  const previewShots = shots.map((shot) => ({ videoVersionId: shot.videoVersionId, sourceStartMs: shot.startMs,
    durationMs: duration(shot) - shot.startMs, speed: shot.speed, fadeMs: shot.fadeMs, lines: shot.lines }));
  const voicePreview = useCompositionVoices(projectId, previewShots, audios, timeline.valid && !metadataPending && !metadataError && !!limits && !capabilities.error
    && shots.length <= limits.maxShots && voiceCount <= limits.maxLines && !invalidSources);
  const sourceCaptions: Caption[] = [];
  let sourceCaptionError: string | null = null;
  const subtitleTracks = new Map(subtitleWorkbench.tracks.filter((track) => !excludedSubtitleSources.has(track.sourceVersionId))
    .map((track) => [track.sourceVersionId, track]));
  const voiceSubtitlePending = shots.some((shot) => shot.lines.some((line) => subtitleTracks.get(line.audioVersionId)?.cues.length)) && voicePreview.blocked;
  try {
    for (const clip of timeline.clips) {
      const sourceVersionId = shots.find((shot) => shot.id === clip.id)!.videoVersionId;
      const track = subtitleTracks.get(sourceVersionId);
      if (track) sourceCaptions.push(...mapSubtitleTrack(track, { ...clip, id: `source:${clip.id}`, sourceVersionId }));
      if (sourceCaptions.length + captions.length > (limits?.maxSubtitles ?? 0)) throw new Error(t("subtitle.invalid"));
    }
    for (const [shotIndex, shot] of (voicePreview.plan?.shots ?? []).entries()) {
      for (const [lineIndex, line] of shot.lines.entries()) {
        const track = subtitleTracks.get(line.audioVersionId);
        if (track) sourceCaptions.push(...mapSubtitleTrackToAudio(track, { id: `voice:${shots[shotIndex]?.id}:${lineIndex}`,
          sourceVersionId: line.audioVersionId, sourceStartMs: line.trimStartMs, sourceEndMs: line.trimEndMs, speed: line.speed,
          startMs: shot.timelineStartMs + line.startMs, endMs: shot.timelineStartMs + line.endMs }));
        if (sourceCaptions.length + captions.length > (limits?.maxSubtitles ?? 0)) throw new Error(t("subtitle.invalid"));
      }
    }
    const musicTrack = subtitleTracks.get(music);
    if (musicTrack && !invalidMusic) {
      const endMs = Math.min(totalMs, musicTrack.sourceDurationMs - musicStartMs);
      if (endMs > 0) sourceCaptions.push(...mapSubtitleTrackToAudio(musicTrack, { id: "music", sourceVersionId: music,
        sourceStartMs: musicStartMs, sourceEndMs: musicStartMs + endMs, speed: 1, startMs: 0, endMs }));
    }
  } catch { sourceCaptionError = t("subtitle.invalid"); }
  const allCaptions = [...captions, ...sourceCaptions].sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs);
  const subtitlePending = subtitleWorkbench.pendingSourceVersions.some((id) => !excludedSubtitleSources.has(id));
  const invalid = invalidBase || !!sourceCaptionError || subtitleWorkbench.blocking || voiceSubtitlePending || subtitlePending
    || allCaptions.length > (limits?.maxSubtitles ?? 0) || allCaptions.some(invalidCaption);
  const busy = submit.isPending || accepting || closing || subtitleWorkbench.saving || subtitleWorkbench.operating;
  async function requestClose() {
    if (busy) return;
    setClosing(true);
    try { if (await subtitleWorkbench.flush()) onClose(); else openInspector("captions"); }
    finally { setClosing(false); }
  }
  async function guardSourceChange(change: () => void) {
    if (await subtitleWorkbench.flush()) change(); else openInspector("captions");
  }
  async function changeSource(id: string, videoVersionId: string) {
    if (await subtitleWorkbench.flush()) patch(id, { videoVersionId, startMs: 0, endMs: null });
    else openInspector("captions");
  }
  async function acceptComposition() {
    if (busy || invalid || metadataPending || metadataError) return;
    setAccepting(true);
    try {
      if (!await subtitleWorkbench.flush()) { openInspector("captions"); return; }
      submit.mutate({ title: title.trim(), originalAudioMode, output: { resolution, aspectRatio }, shots: previewShots,
        ...(music ? { music: { audioVersionId: music, volume: musicVolume, sourceStartMs: musicStartMs } } : {}),
        subtitles: allCaptions.map(({ text, startMs, endMs }) => ({ text, startMs, endMs })) });
    } finally { setAccepting(false); }
  }
  async function exportTimeline(format: SubtitleFormat) {
    if (busy || invalid || !await subtitleWorkbench.flush()) { openInspector("captions"); return; }
    setSubtitleExportError(null);
    try { downloadSubtitleFile(encodeSubtitleFile(allCaptions, totalMs, format), format); }
    catch { setSubtitleExportError(t("subtitle.invalid")); }
  }
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
  const statusText = subtitleWorkbench.saving ? t("subtitle.saving") : subtitlePending ? t("subtitle.recognizing") : submit.isPending ? t("video.edit.submitting") : capabilities.isPending || capabilities.isFetching ? t("video.edit.readingCapabilities") : capabilities.error ? t("video.edit.capabilitiesFailed") : metadataPending ? t("video.edit.readingSources") : metadataError ? t("video.edit.sourceErrorHint")
    : limits && safeTotal > limits.maxDurationMs ? t("video.edit.tooLong", { "0": timeLabel(limits.maxDurationMs) }) : invalidSources ? t("video.edit.sourcesTooLarge") : !shots.length ? t("video.edit.emptyHint")
    : invalidCaptions || sourceCaptionError || subtitleWorkbench.blocking ? t("video.edit.subtitleInvalid") : invalidMusic ? t("video.edit.musicInvalid") : invalid ? t("video.edit.checkSettings") : submit.error ? t("video.edit.failed") : t("video.edit.ready");
  const sourceError = (versionId: string) => details.get(versionId)?.error ? <Alert variant="destructive"><Info />
    <AlertDescription>{t("video.edit.metadataFailed")}<Button type="button" size="sm" variant="outline" disabled={busy}
      onClick={() => void details.get(versionId)?.refetch()}>{t("common.retryRead")}</Button></AlertDescription></Alert> : null;

  return <Dialog open onOpenChange={(open) => { if (!open && !busy) void requestClose(); }}>
    <DialogContent className="video-edit-dialog nodrag nowheel nopan" showCloseButton={false}
      onEscapeKeyDown={(event) => { if (trimming || busy) event.preventDefault(); }}
      onKeyDown={(event) => { if (event.key !== "Tab") event.stopPropagation(); }}>
      <TooltipProvider delayDuration={300}>
        <header className="video-edit-header">
          <div className="video-edit-brand"><span className="video-edit-brand-icon"><Scissors aria-hidden="true" /></span>
            <div><DialogTitle>{t("video.edit.title")}</DialogTitle></div>
          </div>
          <div className="video-edit-header-actions"><Badge variant="outline">{t("video.edit.localProcessing")}</Badge>
            <IconAction label={t("common.close")} size="icon-sm" disabled={busy} onClick={() => void requestClose()}><X /></IconAction>
          </div>
          <DialogDescription className="sr-only">{t("video.edit.description")}</DialogDescription>
        </header>

        <div className="video-edit-body">
        <div className="video-edit-workspace">
          <aside className="video-edit-sources" aria-label={t("video.edit.sequence")}>
            <div className="video-edit-panel-heading"><h3><FilmStrip aria-hidden="true" />{t("video.edit.sequence")}</h3><Badge variant="secondary">{shots.length} / {limits?.maxShots ?? "—"}</Badge></div>
            <ol className="video-edit-clip-list">
              {shots.map((shot, index) => {
                const source = videos.find((item) => item.selectedVersionId === shot.videoVersionId);
                const failed = details.get(shot.videoVersionId)?.isError;
                return <li key={shot.id} className={cn("video-edit-clip", shot.id === activeShot?.id && "is-active")}
                  aria-label={`${t("video.edit.clip")} ${index + 1}`}>
                  <Button type="button" variant="ghost" className="video-edit-clip-select" aria-pressed={shot.id === activeShot?.id}
                    disabled={busy} onClick={() => showShot(shot.id)} aria-label={t("video.edit.selectClip", { "0": index + 1, "1": source?.title ?? "" })}>
                    <span className="video-edit-clip-thumb"><ClipThumbnail key={thumbnail(shot)} src={thumbnail(shot)} /><span>{String(index + 1).padStart(2, "0")}</span></span>
                    <span className="video-edit-clip-copy"><strong>{source?.title ?? t("video.edit.source")}</strong>
                      <small>{timeLabel(outputMs(shot))} <span>· {Number.isFinite(shot.speed) ? shot.speed : "—"}×</span></small>
                      {failed || shotInvalid(shot) ? <span className="video-edit-clip-warning">{failed ? t("video.edit.metadataFailed") : t("video.edit.needsAttention")}</span> : null}
                    </span>
                  </Button>
                  <div className="video-edit-clip-actions">
                    <span>{t("video.edit.clip")} {String(index + 1).padStart(2, "0")}</span>
                    <IconAction label={t("video.edit.moveUp")} disabled={busy || index === 0} onClick={() => move(index, -1)}><CaretUp /></IconAction>
                    <IconAction label={t("video.edit.moveDown")} disabled={busy || index === shots.length - 1} onClick={() => move(index, 1)}><CaretDown /></IconAction>
                    <IconAction label={t("video.edit.remove")} disabled={busy} onClick={() => void guardSourceChange(() => removeShot(shot.id))}><Trash /></IconAction>
                  </div>
                </li>;
              })}
            </ol>
            <Button type="button" variant="outline" className="video-edit-add-clip" disabled={busy || !limits || shots.length >= limits.maxShots || !videos.length} onClick={addShot}>
              <Plus data-icon="inline-start" />{t("video.edit.addClip")}</Button>
            <p className="video-edit-source-note"><Info aria-hidden="true" />{t("video.edit.pinnedHint")}</p>
          </aside>

          <section className="video-edit-monitor" aria-label={t("video.edit.player")}>
            <div className="video-edit-panel-heading"><h3>{t("video.edit.player")}</h3><span>{t("video.edit.fullPreview")}</span></div>
            {shots.length ? <VideoCompositionPlayer controller={playback} model={{ timeline, originalAudioMode, music: previewMusic, voices: voicePreview.voices, blocked: metadataPending || metadataError || invalidMusic || voicePreview.blocked || subtitleWorkbench.blocking || !!sourceCaptionError || subtitlePending }}
              captions={allCaptions} fontFamily={voicePreview.fontFamily} resolution={resolution} aspectRatio={aspectRatio} disabled={busy} />
              : <Empty><EmptyHeader><EmptyMedia variant="icon"><FilmStrip /></EmptyMedia><EmptyTitle>{t("video.edit.emptyTitle")}</EmptyTitle>
                <EmptyDescription>{t("video.edit.emptyHint")}</EmptyDescription></EmptyHeader></Empty>}
            {voicePreview.pending ? <p className="video-edit-preview-note" role="status">{t("video.edit.preparingPreview")}</p> : null}
            {voicePreview.error ? <Alert variant="destructive"><AlertDescription>{voicePreview.error.message}<Button type="button" variant="outline" size="sm" disabled={busy || voicePreview.pending} onClick={() => void voicePreview.retry()}>{t("common.retryRead")}</Button></AlertDescription></Alert> : null}
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
                      <Select id={`${prefix}-source`} value={activeShot.videoVersionId} disabled={busy}
                        onChange={(event) => void changeSource(activeShot.id, event.target.value)}>
                        {videos.map((item) => <option key={item.id} value={item.selectedVersionId!}>{item.title}</option>)}
                      </Select></Field>
                    {sourceError(activeShot.videoVersionId)}
                    <VideoCompositionClipControls controller={playback} timeline={timeline} clipId={activeShot.id} startMs={activeShot.startMs} endMs={duration(activeShot)}
                      sourceDurationMs={details.get(activeShot.videoVersionId)?.data?.durationMs ?? 0} disabled={busy}
                      playbackBlocked={metadataPending || metadataError || invalidMusic || voicePreview.blocked}
                      onChange={(range) => patch(activeShot.id, range)} />
                    <FieldGroup className="video-edit-grid">
                      <Field data-invalid={invalidSpeed(activeShot)}><FieldLabel htmlFor={`${prefix}-speed`}>{t("video.edit.speed")}</FieldLabel>
                        <Input id={`${prefix}-speed`} type="number" min={0.5} max={2} step={0.05} value={Number.isFinite(activeShot.speed) ? activeShot.speed : ""}
                          disabled={busy} aria-invalid={invalidSpeed(activeShot)} onChange={(event) => patch(activeShot.id, { speed: event.target.valueAsNumber })} /></Field>
                      <Field data-invalid={invalidFade(activeShot)}><FieldLabel htmlFor={`${prefix}-fade`}>{t("video.edit.fade")}</FieldLabel>
                        <Input id={`${prefix}-fade`} type="number" min={0} max={500} step={10} value={Number.isFinite(activeShot.fadeMs) ? activeShot.fadeMs : ""}
                          disabled={busy} aria-invalid={invalidFade(activeShot)} onChange={(event) => patch(activeShot.id, { fadeMs: event.target.valueAsNumber })} /></Field>
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
                    <Select id={`${prefix}-sound`} value={originalAudioMode} disabled={busy} onChange={(event) => setOriginalAudioMode(event.target.value as typeof originalAudioMode)}>
                      <option value="KEEP">{t("video.edit.keep")}</option><option value="MUTE">{t("video.edit.mute")}</option><option value="BACKGROUND">{t("video.edit.background")}</option>
                    </Select></Field>
                  <Field><FieldLabel htmlFor={`${prefix}-music`}>{t("video.edit.music")}</FieldLabel><Select id={`${prefix}-music`} value={music} disabled={busy} onChange={(event) => { const value = event.target.value; void guardSourceChange(() => setMusic(value)); }}>
                    <option value="">{t("video.edit.none")}</option>{audios.map((item) => <option key={item.id} value={item.selectedVersionId!}>{item.title}</option>)}
                  </Select></Field>
                  {music ? <>
                    <Field data-invalid={invalidMusic}><FieldLabel htmlFor={`${prefix}-volume`}>{t("video.edit.volume")}</FieldLabel>
                      <Input id={`${prefix}-volume`} type="number" min={0} max={1} step={0.05} value={Number.isFinite(musicVolume) ? musicVolume : ""} disabled={busy} aria-invalid={invalidMusic} onChange={(event) => setMusicVolume(event.target.valueAsNumber)} /></Field>
                    <Field data-invalid={invalidMusic}><FieldLabel htmlFor={`${prefix}-music-start`}>{t("video.edit.musicStart")}</FieldLabel>
                      <Input id={`${prefix}-music-start`} type="number" min={0} step={0.001} value={Number.isFinite(musicStartMs) ? musicStartMs / 1000 : ""} disabled={busy} aria-invalid={invalidMusic} onChange={(event) => setMusicStartMs(Math.round(event.target.valueAsNumber * 1000))} /></Field>
                    {sourceError(music)}{invalidMusic && details.get(music)?.data ? <FieldError>{t("video.edit.musicInvalid")}</FieldError> : null}
                  </> : null}
                  <div className="video-edit-section-heading"><h3>{activeShot ? t("video.edit.clipVoice", { "0": activeIndex + 1 }) : t("video.edit.voiceCount")}</h3><Badge variant="secondary">{activeShot?.lines.length ?? 0}</Badge></div>
                  <p className="video-edit-panel-hint">{t("video.edit.voiceHint")}</p>
                  {activeShot?.lines.map((line, index) => <div className="video-edit-voice-row" key={index}>
                    <Select aria-label={t("video.edit.voice")} value={line.audioVersionId} disabled={busy} onChange={(event) => { const value = event.target.value; void guardSourceChange(() => patch(activeShot.id, { lines: activeShot.lines.map((item, position) => position === index ? { audioVersionId: value } : item) })); }}>
                      {audios.map((item) => <option key={item.id} value={item.selectedVersionId!}>{item.title}</option>)}
                    </Select><IconAction label={t("video.edit.remove")} disabled={busy} onClick={() => void guardSourceChange(() => patch(activeShot.id, { lines: activeShot.lines.filter((_, position) => position !== index) }))}><Trash /></IconAction>
                  </div>)}
                  <Button type="button" variant="ghost" size="sm" disabled={busy || !activeShot || !audios.length || !limits || voiceCount >= limits.maxLines} onClick={() => {
                    const audio = audios[0]; if (activeShot && audio?.selectedVersionId) patch(activeShot.id, { lines: [...activeShot.lines, { audioVersionId: audio.selectedVersionId }] });
                  }}><Plus data-icon="inline-start" />{t("video.edit.addVoice")}</Button>
                  {!audios.length ? <p className="video-edit-panel-hint">{t("video.edit.noAudio")}</p> : null}
                </FieldGroup>
              </TabsContent>
              <TabsContent value="captions" className="video-edit-inspector-content">
                <SubtitleEditor workbench={subtitleWorkbench} disabled={busy}
                  included={!excludedSubtitleSources.has(subtitleWorkbench.selectedSource?.versionId ?? "")}
                  onIncludedChange={(included) => { const id = subtitleWorkbench.selectedSource?.versionId; if (id) setExcludedSubtitleSources((current) => {
                    const next = new Set(current); if (included) next.delete(id); else next.add(id); return next;
                  }); }} />
                <div className="subtitle-actions"><Button type="button" size="sm" variant="ghost" disabled={busy || invalid} onClick={() => void exportTimeline("SRT")}>{t("subtitle.exportTimelineSrt")}</Button>
                  <Button type="button" size="sm" variant="ghost" disabled={busy || invalid} onClick={() => void exportTimeline("VTT")}>{t("subtitle.exportTimelineVtt")}</Button></div>
                <div className="video-edit-section-heading"><h3>{t("video.edit.timedCaptions")}</h3><Badge variant="secondary">{allCaptions.length} / {limits?.maxSubtitles ?? "—"}</Badge></div>
                {!captions.length ? <Empty className="video-edit-empty-captions"><EmptyHeader><EmptyMedia variant="icon"><TextT /></EmptyMedia>
                  <EmptyTitle>{t("video.edit.noCaptions")}</EmptyTitle><EmptyDescription>{t("video.edit.captionHint")}</EmptyDescription></EmptyHeader></Empty> : null}
                <FieldGroup>
                  {captions.slice(currentCaptionPage * CAPTIONS_PER_PAGE, (currentCaptionPage + 1) * CAPTIONS_PER_PAGE).map((caption, pageIndex) => {
                    const index = currentCaptionPage * CAPTIONS_PER_PAGE + pageIndex;
                    return <section className="video-edit-caption" key={caption.id} aria-label={`${t("video.edit.captionsTab")} ${index + 1}`}>
                    <div className="video-edit-section-heading"><span>{String(index + 1).padStart(2, "0")}</span><IconAction label={t("video.edit.remove")} disabled={busy} onClick={() => setCaptions((current) => current.filter((item) => item.id !== caption.id))}><Trash /></IconAction></div>
                    <Field data-invalid={invalidCaption(caption)}><FieldLabel htmlFor={`${prefix}-${caption.id}`}>{t("video.edit.subtitle")}</FieldLabel>
                      <Textarea id={`${prefix}-${caption.id}`} value={caption.text} maxLength={500} disabled={busy} aria-invalid={invalidCaption(caption)} onChange={(event) => setCaptions((current) => current.map((item) => item.id === caption.id ? { ...item, text: event.target.value } : item))} /></Field>
                    <FieldGroup className="video-edit-grid">{(["startMs", "endMs"] as const).map((field) => <Field key={field} data-invalid={invalidCaption(caption)}>
                      <FieldLabel htmlFor={`${prefix}-${caption.id}-${field}`}>{t(field === "startMs" ? "video.edit.captionStart" : "video.edit.captionEnd")}</FieldLabel>
                      <Input id={`${prefix}-${caption.id}-${field}`} type="number" min={0} step={0.001} value={Number.isFinite(caption[field]) ? caption[field] / 1000 : ""} disabled={busy} aria-invalid={invalidCaption(caption)}
                        onChange={(event) => setCaptions((current) => current.map((item) => item.id === caption.id ? { ...item, [field]: Math.round(event.target.valueAsNumber * 1000) } : item))} />
                    </Field>)}</FieldGroup>
                    {invalidCaption(caption) ? <FieldError>{t("video.edit.subtitleInvalid")}</FieldError> : null}
                  </section>; })}
                  {captionPages > 1 ? <div className="video-edit-section-heading"><Button type="button" variant="ghost" size="sm" disabled={busy || currentCaptionPage === 0} onClick={() => setCaptionPage(currentCaptionPage - 1)}>{t("video.edit.previousCaptions")}</Button>
                    <span>{t("video.edit.captionPage", { "0": currentCaptionPage + 1, "1": captionPages })}</span>
                    <Button type="button" variant="ghost" size="sm" disabled={busy || currentCaptionPage + 1 >= captionPages} onClick={() => setCaptionPage(currentCaptionPage + 1)}>{t("video.edit.nextCaptions")}</Button></div> : null}
                  <Button type="button" variant="ghost" size="sm" disabled={busy || !limits || allCaptions.length >= limits.maxSubtitles} onClick={() => { setCaptionPage(Math.floor(captions.length / CAPTIONS_PER_PAGE)); setCaptions((current) => [...current, { id: crypto.randomUUID(), text: "", startMs: 0, endMs: Math.max(100, safeTotal) }]); }}>
                    <Plus data-icon="inline-start" />{t("video.edit.addSubtitle")}</Button>
                </FieldGroup>
              </TabsContent>
              <TabsContent value="output" className="video-edit-inspector-content">
                <div className="video-edit-section-heading"><h3>{t("video.edit.outputSettings")}</h3><Badge variant="outline">MP4</Badge></div>
                <FieldGroup>
                  <Field data-invalid={!title.trim()}><FieldLabel htmlFor={`${prefix}-title`}>{t("video.edit.resultTitle")}</FieldLabel><Input id={`${prefix}-title`} value={title} maxLength={160} disabled={busy} aria-invalid={!title.trim()} onChange={(event) => setTitle(event.target.value)} /></Field>
                  <Field><FieldLabel htmlFor={`${prefix}-resolution`}>{t("video.edit.resolution")}</FieldLabel><Select id={`${prefix}-resolution`} value={resolution} disabled={busy} onChange={(event) => setResolution(event.target.value as typeof resolution)}>
                    {(limits?.resolutions ?? ["720P"]).map((value) => <option key={value} value={value}>{value.toLowerCase()}</option>)}</Select></Field>
                  <Field><FieldLabel htmlFor={`${prefix}-aspect`}>{t("video.edit.aspect")}</FieldLabel><Select id={`${prefix}-aspect`} value={aspectRatio} disabled={busy} onChange={(event) => setAspectRatio(event.target.value as typeof aspectRatio)}>
                    {["16:9", "9:16", "1:1"].map((value) => <option key={value}>{value}</option>)}</Select></Field>
                </FieldGroup>
                <dl className="video-edit-output-summary"><div><dt>{t("video.edit.frameRate")}</dt><dd>30 fps</dd></div>
                  <div><dt>{t("video.edit.clipCount")}</dt><dd>{shots.length} / {limits?.maxShots ?? "—"}</dd></div><div><dt>{t("video.edit.voiceCount")}</dt><dd>{voiceCount} / {limits?.maxLines ?? "—"}</dd></div>
                  <div><dt>{t("video.edit.totalDuration")}</dt><dd>{timeLabel(totalMs)}</dd></div></dl>
                <p className="video-edit-panel-hint">{t("video.edit.exportHint")}</p>
                {limits ? <p className="video-edit-panel-hint">{t("video.edit.limitsHint", { "0": timeLabel(limits.maxDurationMs),
                  "1": limits.maxShots, "2": limits.maxLines, "3": limits.maxSubtitles,
                  "4": Math.floor(limits.maxSourceBytes / 1048576), "5": Math.floor(limits.maxOutputBytes / 1048576) })}</p> : null}
                {resolution === "2160P" ? <p className="video-edit-panel-hint">{t("video.edit.fourKHint")}</p> : null}
              </TabsContent>
            </Tabs>
          </aside>
        </div>

        <VideoCompositionTimeline timeline={timeline} controller={playback} onTrimmingChange={setTrimming} activeId={activeShot?.id} onSelect={showShot} onTrim={(id, range) => patch(id, range)} onReorder={reorder}
          captions={allCaptions} music={previewMusic} musicTitle={musicItem?.title} voiceCount={voiceCount} onAudio={() => openInspector("audio")} onCaptions={() => openInspector("captions")} disabled={busy} />

        </div>
        {capabilities.error ? <Alert variant="destructive"><Info /><AlertDescription>{t("video.edit.capabilitiesFailed")}
          <Button type="button" size="sm" variant="outline" disabled={capabilities.isFetching} onClick={() => void capabilities.refetch()}>{t("common.retryRead")}</Button>
        </AlertDescription></Alert> : null}
        {subtitleWorkbench.error || sourceCaptionError || subtitleExportError ? <Alert variant="destructive"><AlertDescription>{subtitleWorkbench.error ?? sourceCaptionError ?? subtitleExportError}</AlertDescription></Alert> : null}
        <DialogFooter className="video-edit-footer">
          <div className="video-edit-status" role="status" aria-live="polite">
            {metadataPending || submit.isPending ? <CircleNotch className="video-edit-spinner" aria-hidden="true" /> : invalid || metadataError || submit.error ? <Info aria-hidden="true" /> : <CheckCircle aria-hidden="true" />}
            <div><strong>{timeLabel(totalMs)}<span> / {limits ? timeLabel(limits.maxDurationMs) : "—"}</span></strong><p>{statusText}</p></div>
          </div>
          <div className="video-edit-footer-actions"><Button type="button" variant="ghost" disabled={busy} onClick={() => openInspector("output")}>{t("video.edit.outputSettings")}</Button>
            <Button type="button" disabled={busy || metadataPending || invalid || metadataError} onClick={() => void acceptComposition()}>{submit.isPending ? <CircleNotch data-icon="inline-start" className="video-edit-spinner" /> : <DownloadSimple data-icon="inline-start" />}{submit.isPending ? t("video.edit.submitting") : t("video.edit.submit")}</Button></div>
          {submit.error ? <Alert variant="destructive" className="video-edit-submit-error"><AlertDescription>{t("video.edit.failed")}{submit.error.message !== t("video.edit.failed") ? ` · ${submit.error.message}` : null}</AlertDescription></Alert> : null}
        </DialogFooter>
      </TooltipProvider>
    </DialogContent>
  </Dialog>;
}
