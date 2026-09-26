import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ArrowsOutSimple, ArrowClockwise, CaretDown, Crop, Cube, DownloadSimple,
  Eraser, Image as ImageIcon, Stack, MagicWand, PaintBrush, Play, Scissors,
  SlidersHorizontal, Smiley, Sun, UploadSimple, VideoCamera, X } from "@phosphor-icons/react";
import { assetContentUrl, assetThumbnailUrl, listDirectMediaTasks,
  type Artifact, type Task } from "../../shared/api/client";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { ArtifactCardFrame } from "./ArtifactCardFrame";
import { isMediaTaskRunning, latestMediaTask, MEDIA_TASK_REFRESH_INTERVAL_MS } from "./mediaTaskState";
import { assetMetadataQueryOptions, displayedMediaAssetId, isMediaDraftDisplayed, mediaDraftQueryOptions } from "./mediaDisplay";

const TASK_LABELS: Partial<Record<Task["status"], string>> = {
  PENDING: "等待生成", READY: "排队中", RUNNING: "正在生成", SUBMITTING: "正在提交",
  WAITING_PROVIDER: "正在生成", FAILED: "生成失败", CANCELED: "已取消",
  UNKNOWN: "结果未知", BLOCKED: "任务已阻断", SUCCEEDED: "生成结果已保存至历史",
};
const EXTENSIONS = [
  { label: "三视图", icon: Cube }, { label: "图层分离", icon: Stack },
  { label: "表情调整", icon: Smiley }, { label: "重新打光", icon: Sun },
  { label: "高清放大", icon: ArrowsOutSimple }, { label: "裁剪", icon: Crop },
  { label: "旋转与翻转", icon: ArrowClockwise }, { label: "画笔标注", icon: PaintBrush },
  { label: "移除背景", icon: Scissors }, { label: "扩图", icon: ArrowsOutSimple },
  { label: "局部擦除", icon: Eraser }, { label: "视角调整", icon: Cube },
] as const;

/** The media surface contains only the preview; editing and history live outside its bounds. */
export function MediaCanvasCard({ artifact, selected, locked, onEdit, onInspect, onUpload, children }: {
  artifact: Artifact; selected: boolean; locked: boolean; onEdit: () => void;
  onInspect: () => void; onUpload: () => void; children: ReactNode;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const menuButton = useRef<HTMLButtonElement>(null);
  const draft = useQuery(mediaDraftQueryOptions(artifact));
  const showDraft = isMediaDraftDisplayed(artifact, draft.data);
  const tasks = useQuery({ queryKey: ["direct-media-tasks", artifact.projectId, artifact.id],
    queryFn: () => listDirectMediaTasks(artifact.projectId, artifact.id), enabled: showDraft,
    refetchInterval: (query) => query.state.data?.some(isMediaTaskRunning)
      ? MEDIA_TASK_REFRESH_INTERVAL_MS : false });
  const latest = latestMediaTask(tasks.data);
  const busy = showDraft && latest && isMediaTaskRunning(latest);
  const status = showDraft && latest ? TASK_LABELS[latest.status] : undefined;
  const assetId = displayedMediaAssetId(artifact, draft.data);
  const content = artifact.currentVersion?.content;
  const parameters = content && typeof content === "object" && "parameters" in content ? content.parameters : null;
  const demo = Boolean(parameters && typeof parameters === "object" && "mock" in parameters && parameters.mock === true);
  const isImage = artifact.kind === "IMAGE";
  const metadata = useQuery(assetMetadataQueryOptions(artifact.projectId, isImage ? assetId : null));

  useEffect(() => {
    if (!menuOpen) return;
    function close(event: PointerEvent) {
      if (!menuRef.current?.contains(event.target as globalThis.Node)) setMenuOpen(false);
    }
    document.addEventListener("pointerdown", close);
    return () => document.removeEventListener("pointerdown", close);
  }, [menuOpen]);

  return <ArtifactCardFrame title={artifact.title} kindLabel={isImage ? "图片" : "视频"}
    selected={selected} locked={locked} toolbarLabel="媒体卡片操作" toolbar={<>
        {isImage ? <>
          <button type="button" disabled title="智能编辑尚未接入"><MagicWand size={17} />智能编辑</button>
          <button type="button" disabled title="深度提取尚未接入"><Stack size={17} />深度提取</button>
          <div className="media-extension-anchor" ref={menuRef} onKeyDown={(event) => {
            if (event.key === "Escape" && menuOpen) {
              event.stopPropagation(); setMenuOpen(false); menuButton.current?.focus();
            }
          }}>
            <button type="button" ref={menuButton} aria-expanded={menuOpen} aria-haspopup="true"
              className={menuOpen ? "is-open" : ""} onClick={() => setMenuOpen(!menuOpen)}>
              <MagicWand size={17} />扩展<CaretDown size={12} /></button>
            {menuOpen ? <div className="media-extension-menu" aria-label="图片扩展功能">
              {EXTENSIONS.map(({ label, icon: Icon }) => <button key={label} type="button" disabled>
                <Icon size={17} /><span>{label}</span><small>未接入</small>
              </button>)}
            </div> : null}
          </div>
        </> : null}
        <button type="button" onClick={onEdit} title="编辑工作草稿，点击运行生成新版本">
          <ArrowClockwise size={17} />{assetId ? "重新生成" : "编辑草稿"}</button>
        <button type="button" onClick={onInspect} aria-label="卡片详情"><SlidersHorizontal size={17} /></button>
        {assetId ? <a href={assetContentUrl(artifact.projectId, assetId)} download
          aria-label={isImage ? "下载图片" : "下载视频"}><DownloadSimple size={19} /></a> : null}
    </>}>
      {children}
      {assetId ? <><MediaPreview key={assetId} assetId={assetId} artifact={artifact} demo={demo} />
        {metadata.error ? <p className="media-card-error media-card-size-error nodrag" role="alert">
          {metadata.data ? "图片尺寸刷新失败，请重试" : "图片尺寸读取失败，暂按原卡片尺寸显示"}
          <button type="button" onClick={() => void metadata.refetch()} disabled={metadata.isFetching}>
            {metadata.isFetching ? "正在重试…" : "重试尺寸"}</button></p> : null}</>
        : <div className="media-card-empty">
          {busy ? <CanvasLoadingState label={status ?? "正在生成"} /> : <>
            {isImage ? <ImageIcon className="media-empty-icon" size={44} />
              : <VideoCamera className="media-empty-icon" size={44} />}
            {status ? <div className="media-card-state" role="status">{status}
              {latest?.errorCode ? <small>{latest.errorCode}</small> : null}
              {latest?.status === "UNKNOWN" ? <small>可在编辑区重试</small> : null}
            </div> : null}
            {latest?.status === "UNKNOWN" || latest?.status === "BLOCKED" ?
              <button className="media-upload-button nodrag" type="button" onClick={onEdit}>
                <SlidersHorizontal size={15} />查看任务</button>
              : isImage ? <button className="media-upload-button nodrag" type="button" onClick={onUpload}>
              <UploadSimple size={15} />上传图片</button>
              : <button className="media-upload-button nodrag" type="button" onClick={onEdit}>
                <Play size={15} />生成视频</button>}
          </>}
          {draft.error ? <p className="media-card-error" role="alert">草稿读取失败
            <button type="button" className="nodrag" onClick={() => void draft.refetch()}>重试</button></p> : null}
          {tasks.error ? <p className="media-card-error" role="alert">任务状态暂不可用
            <button type="button" className="nodrag" onClick={() => void tasks.refetch()}>重试状态</button></p> : null}
        </div>}
  </ArtifactCardFrame>;
}

/** Only thumbnails are fetched until the user explicitly opens or plays the original. */
function MediaPreview({ artifact, assetId, demo }: { artifact: Artifact; assetId: string; demo: boolean }) {
  const [failed, setFailed] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [playbackFailed, setPlaybackFailed] = useState(false);
  const [buffering, setBuffering] = useState(false);
  const [playbackAttempt, setPlaybackAttempt] = useState(0);
  const video = artifact.kind === "VIDEO";

  function startPlayback() {
    setPlaybackFailed(false);
    setBuffering(true);
    setPlaybackAttempt((attempt) => attempt + 1);
    setPlaying(true);
  }

  return <div className="media-card-preview">
    {video && playing && !playbackFailed ? <video key={playbackAttempt} className="nodrag nowheel nopan" aria-label={`${artifact.title} 的视频`}
      controls autoPlay playsInline preload="metadata" src={assetContentUrl(artifact.projectId, assetId)}
      onCanPlay={() => setBuffering(false)} onPlaying={() => setBuffering(false)} onWaiting={() => setBuffering(true)}
      onError={() => { setPlaybackFailed(true); setBuffering(false); }} />
      : !failed ? <img alt={`${artifact.title} 的${video ? "视频封面" : "预览"}`}
        decoding="async" draggable={false} loading="lazy" onError={() => setFailed(true)}
        src={assetThumbnailUrl(artifact.projectId, assetId)} />
        : <div className="media-card-empty">{video ? <VideoCamera size={36} /> : <ImageIcon size={36} />}<span>{video ? "视频封面暂不可用" : "预览暂不可用"}</span>
          <button className="media-upload-button nodrag" type="button" onClick={() => setFailed(false)}>重试预览</button></div>}
    {video && playing && buffering ? <div className="media-playback-loading">
      <CanvasLoadingState compact label="正在加载视频" />
    </div> : null}
    {video && playbackFailed ? <div className="media-playback-error nodrag nowheel nopan" role="alert">
      <VideoCamera size={28} /><p>视频播放失败</p><span>请重试播放，或打开原视频文件。</span>
      <button type="button" className="media-upload-button" onClick={startPlayback}><ArrowClockwise size={15} />重试播放</button>
    </div> : null}
    {video && playing ? <button type="button" className="media-stop-preview nodrag" aria-label="关闭视频预览"
      title="关闭视频预览" onClick={() => { setPlaying(false); setBuffering(false); setPlaybackFailed(false); }}><X size={17} /></button> : null}
    {demo ? <span className="media-demo-badge">{video ? "演示视频" : "演示素材"}</span> : null}
    <a className="media-expand-button nodrag" href={assetContentUrl(artifact.projectId, assetId)}
      aria-label={video ? "打开视频文件" : "打开原图"} title={video ? "打开视频文件" : "打开原图"}
      rel="noopener noreferrer" target="_blank"><ArrowsOutSimple size={19} /><span className="sr-only">{video ? "打开视频文件" : "打开原图"}</span></a>
    {video && !playing ? <button className="media-play-button nodrag" type="button"
      aria-label="播放视频" onClick={startPlayback}><Play size={28} weight="fill" /><span className="sr-only">播放视频</span></button> : null}
  </div>;
}
