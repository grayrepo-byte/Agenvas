import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ArrowsOutSimple, ArrowClockwise, CaretDown, Crop, Cube, DownloadSimple,
  CopySimple, Eraser, Image as ImageIcon, Stack, MagicWand, PaintBrush, Play, Scissors,
  SlidersHorizontal, Smiley, Sun, UploadSimple, VideoCamera, X } from "@phosphor-icons/react";
import { ApiError, assetContentUrl, assetThumbnailUrl, getMediaSettings, listDirectMediaTasks,
  runImageOperation, type Artifact, type CanvasItem, type MediaCapability,
  type RunImageOperationRequest, type Task } from "../../shared/api/client";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { ArtifactCardFrame } from "./ArtifactCardFrame";
import { CropPanel } from "./CropPanel";
import { RelightPanel } from "./RelightPanel";
import { isMediaTaskRunning, latestMediaTask, MEDIA_TASK_REFRESH_INTERVAL_MS } from "./mediaTaskState";
import { assetMetadataQueryOptions, displayedMediaAssetId, isMediaDraftDisplayed, mediaDraftQueryOptions } from "./mediaDisplay";
import { taskErrorMessage } from "./taskErrorMessages";

const TASK_LABELS: Partial<Record<Task["status"], string>> = {
  PENDING: "等待生成", READY: "排队中", RUNNING: "正在生成", SUBMITTING: "正在提交",
  WAITING_PROVIDER: "正在生成", FAILED: "生成失败", CANCELED: "已取消",
  UNKNOWN: "结果未知", BLOCKED: "任务已阻断", SUCCEEDED: "生成结果已保存至历史",
};
type ImageOperation = RunImageOperationRequest["operation"];
const LOCAL_IMAGE_OPERATIONS: readonly ImageOperation[] = [
  "DEPTH_MAP", "UPSCALE", "CROP", "ROTATE", "FLIP_HORIZONTAL", "FLIP_VERTICAL",
];
const EXTENSIONS = [
  { label: "三视图", icon: Cube, operation: "THREE_VIEW" },
  { label: "图层分离", icon: Stack, operation: "LAYER_SPLIT" },
  { label: "表情调整", icon: Smiley, operation: "EXPRESSION_EDIT" },
  { label: "重新打光", icon: Sun, operation: "RELIGHT" },
  { label: "高清放大", icon: ArrowsOutSimple, operation: "UPSCALE" },
  { label: "裁剪", icon: Crop, operation: "CROP" },
  { label: "顺时针旋转 90°", icon: ArrowClockwise, operation: "ROTATE" },
  { label: "水平镜像", icon: ArrowClockwise, operation: "FLIP_HORIZONTAL" },
  { label: "画笔标注", icon: PaintBrush, operation: "BRUSH_MARKUP" },
  { label: "移除背景", icon: Scissors, operation: "REMOVE_BACKGROUND" },
  { label: "AI 扩图", icon: ArrowsOutSimple, operation: "OUTPAINT" },
  { label: "局部擦除", icon: Eraser, operation: "OBJECT_REMOVE" },
  { label: "视角调整", icon: Cube, operation: "VIEW_ANGLE" },
] as const;

/** The media surface contains only the preview; editing and history live outside its bounds. */
export function MediaCanvasCard({ artifact, item, selected, locked, onEdit, onInspect, onUpload,
  onDuplicate, children }: {
  artifact: Artifact; item: CanvasItem; selected: boolean; locked: boolean; onEdit: () => void;
  onInspect: () => void; onUpload: (file: File) => void; onDuplicate?: () => void; children: ReactNode;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [operationOpen, setOperationOpen] = useState<ImageOperation | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const menuButton = useRef<HTMLButtonElement>(null);
  const uploadInput = useRef<HTMLInputElement>(null);
  const queryClient = useQueryClient();
  const draft = useQuery(mediaDraftQueryOptions(artifact.projectId, item.id));
  const showDraft = isMediaDraftDisplayed(item, draft.data);
  const tasks = useQuery({ queryKey: ["direct-media-tasks", artifact.projectId, item.id],
    queryFn: () => listDirectMediaTasks(artifact.projectId, artifact.id, item.id), enabled: showDraft,
    refetchInterval: (query) => query.state.data?.some(isMediaTaskRunning)
      ? MEDIA_TASK_REFRESH_INTERVAL_MS : false });
  const latest = latestMediaTask(tasks.data);
  const busy = showDraft && latest && isMediaTaskRunning(latest);
  const status = showDraft && latest ? TASK_LABELS[latest.status] : undefined;
  const assetId = displayedMediaAssetId(item, draft.data);
  const content = item.selectedVersion?.content;
  const parameters = content && typeof content === "object" && "parameters" in content ? content.parameters : null;
  const demo = Boolean(parameters && typeof parameters === "object" && "mock" in parameters && parameters.mock === true);
  const isImage = artifact.kind === "IMAGE";
  const metadata = useQuery(assetMetadataQueryOptions(artifact.projectId, isImage ? assetId : null));
  const settings = useQuery({ queryKey: ["media-settings"], queryFn: getMediaSettings,
    enabled: isImage && Boolean(assetId) });
  const cloudCapabilities = (settings.data?.connections ?? [])
    .filter((connection) => connection.enabled
      && (connection.platform === "OPENAI" || connection.platform === "GOOGLE"))
    .flatMap((connection) => connection.capabilities)
    .filter((capability) => capability.enabled && capability.kind === "IMAGE_GENERATION"
      && capability.maxReferenceImages > 0);
  const operation = useMutation({
    mutationFn: (input: RunImageOperationRequest) => runImageOperation(
      artifact.projectId, artifact.id, input, crypto.randomUUID()),
    onSuccess: async () => {
      setOperationOpen(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["direct-media-tasks", artifact.projectId] }),
      ]);
    },
  });

  function runOperation(name: ImageOperation, parameters: RunImageOperationRequest["parameters"] = {},
      instruction?: string, capabilityId?: string | null) {
    const sourceVersionId = item.selectedVersion?.id;
    if (!sourceVersionId || operation.isPending) return;
    operation.mutate({ canvasItemId: item.id, sourceVersionId,
      expectedCanvasItemVersion: item.version, operation: name,
      instruction: instruction || null, capabilityId: capabilityId || null, parameters });
  }

  function chooseOperation(name: ImageOperation) {
    setMenuOpen(false);
    if (name === "ROTATE") runOperation(name, { quarterTurns: 1 });
    else if (name === "FLIP_HORIZONTAL" || name === "FLIP_VERTICAL") runOperation(name);
    else setOperationOpen(name);
  }

  useEffect(() => {
    if (!menuOpen) return;
    function close(event: PointerEvent) {
      if (!menuRef.current?.contains(event.target as globalThis.Node)) setMenuOpen(false);
    }
    document.addEventListener("pointerdown", close);
    return () => document.removeEventListener("pointerdown", close);
  }, [menuOpen]);

  return <ArtifactCardFrame title={item.title} kindLabel={isImage ? "图片" : "视频"}
    selected={selected} locked={locked}
    toolbarRaised={menuOpen || operationOpen !== null}
    editableTitle={{ projectId: artifact.projectId, item }}
    toolbarLabel="媒体卡片操作" toolbar={<>
        {isImage ? <>
          <button type="button" disabled={!assetId || Boolean(busy) || operation.isPending}
            onClick={() => setOperationOpen("SMART_EDIT")}><MagicWand size={17} />智能编辑</button>
          <button type="button" disabled={!assetId || Boolean(busy) || operation.isPending}
            title="使用服务端配置的本地 Depth Anything V2 模型"
            onClick={() => runOperation("DEPTH_MAP")}><Stack size={17} />深度提取</button>
          <div className="media-extension-anchor" ref={menuRef} onKeyDown={(event) => {
            if (event.key === "Escape" && menuOpen) {
              event.stopPropagation(); setMenuOpen(false); menuButton.current?.focus();
            }
          }}>
            <button type="button" ref={menuButton} aria-expanded={menuOpen} aria-haspopup="true"
              className={menuOpen ? "is-open" : ""} onClick={() => setMenuOpen(!menuOpen)}>
              <MagicWand size={17} />扩展<CaretDown size={12} /></button>
            {menuOpen ? <div className="media-extension-menu" aria-label="图片扩展功能">
              {EXTENSIONS.map((entry) => <button key={entry.label} type="button"
                disabled={!assetId || Boolean(busy) || operation.isPending}
                onClick={() => chooseOperation(entry.operation)}>
                <entry.icon size={17} /><span>{entry.label}</span>
                <small>{LOCAL_IMAGE_OPERATIONS.includes(entry.operation) ? "本地" : "AI"}</small>
              </button>)}
            </div> : null}
          </div>
          {operationOpen === "RELIGHT" && assetId ? <RelightPanel
            sourceUrl={assetContentUrl(artifact.projectId, assetId)}
            capabilities={cloudCapabilities} busy={operation.isPending}
            error={operation.error} onClose={() => { setOperationOpen(null); operation.reset(); }}
            onSubmit={(operationParameters, instruction, capabilityId) =>
              runOperation("RELIGHT", operationParameters, instruction, capabilityId)} />
            : operationOpen === "CROP" && assetId ? <CropPanel
              sourceUrl={assetContentUrl(artifact.projectId, assetId)}
              sourceWidth={metadata.data?.width} sourceHeight={metadata.data?.height}
              busy={operation.isPending} error={operation.error}
              onClose={() => { setOperationOpen(null); operation.reset(); }}
              onSubmit={(operationParameters) => runOperation("CROP", operationParameters)} />
            : operationOpen ? <ImageOperationPanel key={operationOpen} operation={operationOpen}
              capabilities={cloudCapabilities} busy={operation.isPending}
              error={operation.error} onClose={() => { setOperationOpen(null); operation.reset(); }}
              onSubmit={(operationParameters, instruction, capabilityId) =>
                runOperation(operationOpen, operationParameters, instruction, capabilityId)} /> : null}
        </> : null}
        <button type="button" onClick={onEdit} title="编辑工作草稿，点击运行派生新节点">
          <ArrowClockwise size={17} />{assetId ? "重新生成" : "编辑草稿"}</button>
        {onDuplicate ? <button type="button" onClick={onDuplicate} title="复制完整工作草稿，不复制任务和连线">
          <CopySimple size={17} />复制</button> : null}
        <button type="button" onClick={onInspect} aria-label="卡片详情"><SlidersHorizontal size={17} /></button>
        {assetId ? <a href={assetContentUrl(artifact.projectId, assetId)} download
          aria-label={isImage ? "下载图片" : "下载视频"}><DownloadSimple size={19} /></a> : null}
    </>}>
      {children}
      {isImage && !assetId ? <input ref={uploadInput} className="sr-only nodrag" type="file"
        aria-label="选择要上传的图片" accept="image/png,image/jpeg,image/webp"
        onChange={(event) => {
          const selectedFile = event.currentTarget.files?.[0];
          event.currentTarget.value = "";
          if (selectedFile) onUpload(selectedFile);
        }} /> : null}
      {assetId ? <><MediaPreview key={assetId} assetId={assetId} artifact={artifact}
        title={item.title} demo={demo} />
        {metadata.error ? <p className="media-card-error media-card-size-error nodrag" role="alert">
          {metadata.data ? "图片尺寸刷新失败，请重试" : "图片尺寸读取失败，暂按原卡片尺寸显示"}
          <button type="button" onClick={() => void metadata.refetch()} disabled={metadata.isFetching}>
            {metadata.isFetching ? "正在重试…" : "重试尺寸"}</button></p> : null}</>
        : <div className="media-card-empty">
          {busy ? <CanvasLoadingState label={status ?? "正在生成"} /> : <>
            {isImage ? <ImageIcon className="media-empty-icon" size={44} />
              : <VideoCamera className="media-empty-icon" size={44} />}
            {status ? <div className="media-card-state" role="status">{status}
              <TaskReason errorCode={latest?.errorCode} />
              {latest?.status === "UNKNOWN" ? <small>可在编辑区重试</small> : null}
            </div> : null}
            {latest?.status === "UNKNOWN" || latest?.status === "BLOCKED" ?
              <button className="media-upload-button nodrag" type="button" onClick={onEdit}>
                <SlidersHorizontal size={15} />查看任务</button>
              : isImage ? <button className="media-upload-button nodrag" type="button"
                onClick={() => uploadInput.current?.click()}>
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

const OPERATION_TITLES: Record<ImageOperation, string> = {
  SMART_EDIT: "智能编辑", RELIGHT: "打光", OUTPAINT: "AI 扩图",
  THREE_VIEW: "三视图", LAYER_SPLIT: "图层分离", EXPRESSION_EDIT: "表情调整",
  BRUSH_MARKUP: "画笔标注", REMOVE_BACKGROUND: "移除背景",
  OBJECT_REMOVE: "局部擦除", VIEW_ANGLE: "视角调整",
  DEPTH_MAP: "深度提取", UPSCALE: "高清放大", CROP: "裁剪",
  ROTATE: "旋转", FLIP_HORIZONTAL: "水平镜像", FLIP_VERTICAL: "垂直镜像",
};

const INSTRUCTION_COPY: Partial<Record<ImageOperation, { label: string; placeholder: string;
  required?: boolean }>> = {
  SMART_EDIT: { label: "修改说明", placeholder: "例如：把服装改为深蓝色，其他内容保持不变", required: true },
  OUTPAINT: { label: "补充说明（可选）", placeholder: "例如：延展室内背景，不新增人物" },
  THREE_VIEW: { label: "主体说明（可选）", placeholder: "例如：以画面中央人物为主体，保留完整服装细节" },
  LAYER_SPLIT: { label: "分层说明（可选）", placeholder: "例如：主体是画面中央穿红衣的人物" },
  EXPRESSION_EDIT: { label: "目标表情", placeholder: "例如：自然微笑，嘴唇闭合，眼神放松", required: true },
  BRUSH_MARKUP: { label: "标注说明", placeholder: "例如：用红色手绘箭头指向胸针，并圈出袖口", required: true },
  REMOVE_BACKGROUND: { label: "主体说明（可选）", placeholder: "例如：只保留人物及手中的花束" },
  OBJECT_REMOVE: { label: "擦除目标", placeholder: "例如：移除右下角的路人并自然补全地面", required: true },
  VIEW_ANGLE: { label: "补充说明（可选）", placeholder: "例如：镜头距离保持不变，完整保留人物服装" },
};

const VIEW_ANGLE_OPTIONS = [
  ["FRONT", "正面"], ["LEFT_THREE_QUARTER", "左前 45°"],
  ["RIGHT_THREE_QUARTER", "右前 45°"], ["LEFT_PROFILE", "左侧面"],
  ["RIGHT_PROFILE", "右侧面"], ["HIGH_ANGLE", "俯视"],
  ["LOW_ANGLE", "仰视"], ["BACK", "背面"],
] as const;

function ImageOperationPanel({ operation, capabilities, busy, error, onClose, onSubmit }: {
  operation: ImageOperation; capabilities: MediaCapability[]; busy: boolean; error: Error | null;
  onClose: () => void;
  onSubmit: (parameters: RunImageOperationRequest["parameters"], instruction?: string,
    capabilityId?: string) => void;
}) {
  const cloud = !LOCAL_IMAGE_OPERATIONS.includes(operation);
  const [instruction, setInstruction] = useState("");
  const [capabilityId, setCapabilityId] = useState(capabilities[0]?.id ?? "");
  const [scale, setScale] = useState<2 | 4>(2);
  const [ratio, setRatio] = useState("16:9");
  const [layerTarget, setLayerTarget] = useState<"FOREGROUND" | "BACKGROUND">("FOREGROUND");
  const [viewAngle, setViewAngle] = useState<typeof VIEW_ANGLE_OPTIONS[number][0]>("FRONT");
  const transparentOutput = operation === "REMOVE_BACKGROUND"
    || operation === "LAYER_SPLIT" && layerTarget === "FOREGROUND";
  const eligibleCapabilities = capabilities.filter((capability) =>
    !transparentOutput || capability.supportsTransparentBackground);
  const selectedCapabilityId = eligibleCapabilities.some((capability) => capability.id === capabilityId)
    ? capabilityId : eligibleCapabilities[0]?.id ?? "";
  const instructionCopy = INSTRUCTION_COPY[operation];
  const canSubmit = !busy && (!cloud || Boolean(selectedCapabilityId))
    && (!instructionCopy?.required || instruction.trim().length > 0);

  function submit() {
    if (!canSubmit) return;
    const parameters: RunImageOperationRequest["parameters"] = operation === "UPSCALE"
      ? { scale } : operation === "OUTPAINT" || operation === "THREE_VIEW"
        ? { aspectRatio: ratio as "16:9" }
        : operation === "LAYER_SPLIT" ? { layerTarget }
          : operation === "VIEW_ANGLE" ? { viewAngle }
        : {};
    onSubmit(parameters, instruction.trim(), selectedCapabilityId || undefined);
  }

  return <div className="media-operation-panel nodrag nowheel nopan" role="dialog"
    aria-label={OPERATION_TITLES[operation]}
    onKeyDown={(event) => { if (event.key === "Escape") onClose(); }}>
    <header><div><strong>{OPERATION_TITLES[operation]}</strong>
      <span>{cloud ? "使用 OpenAI / Google 图片能力" : "在本机处理，不上传图片"}</span></div>
      <button type="button" aria-label="关闭图片处理面板" onClick={onClose}><X size={16} /></button>
    </header>
    {operation === "LAYER_SPLIT" ? <label>输出图层<select value={layerTarget}
      onChange={(event) => setLayerTarget(event.target.value as "FOREGROUND" | "BACKGROUND")}>
      <option value="FOREGROUND">主体层（透明背景）</option>
      <option value="BACKGROUND">背景层（移除主体后补全）</option>
    </select></label> : null}
    {operation === "VIEW_ANGLE" ? <label>目标视角<select value={viewAngle}
      onChange={(event) => setViewAngle(event.target.value as typeof viewAngle)}>
      {VIEW_ANGLE_OPTIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
    </select></label> : null}
    {cloud ? <label>图片能力<select value={selectedCapabilityId}
      onChange={(event) => setCapabilityId(event.target.value)}>
      {eligibleCapabilities.length ? eligibleCapabilities.map((capability) => <option key={capability.id}
        value={capability.id}>{capability.name}</option>)
        : <option value="">{transparentOutput
          ? "请配置支持透明背景的 OpenAI / Google 图片能力"
          : "请先在设置中配置 OpenAI 或 Google"}</option>}
    </select></label> : null}
    {instructionCopy
      ? <label>{instructionCopy.label}
        <textarea value={instruction} maxLength={4000}
          placeholder={instructionCopy.placeholder}
          onChange={(event) => setInstruction(event.target.value)} /></label> : null}
    {operation === "UPSCALE" ? <label>放大倍数<select value={scale}
      onChange={(event) => setScale(Number(event.target.value) as 2 | 4)}>
      <option value={2}>2× 本地双三次插值</option><option value={4}>4× 本地双三次插值</option>
    </select></label> : null}
    {operation === "OUTPAINT" || operation === "THREE_VIEW" ? <label>目标画幅<select value={ratio}
      onChange={(event) => setRatio(event.target.value)}>
      {["1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9"].map((value) =>
        <option key={value} value={value}>{value}</option>)}</select></label> : null}
    {error ? <p role="alert">{error instanceof ApiError ? error.message : "图片处理任务受理失败，请重试。"}</p> : null}
    <footer><button type="button" onClick={onClose}>取消</button>
      <button type="button" className="is-primary" disabled={!canSubmit} onClick={submit}>
        {busy ? "正在受理…" : "开始处理"}</button></footer>
  </div>;
}

/** 优先展示可读原因；未登记的码原样回退，便于用户拿它去检索而不是被隐藏。 */
function TaskReason({ errorCode }: { errorCode: Task["errorCode"] }) {
  const reason = taskErrorMessage(errorCode);
  if (reason) return <small>{reason}</small>;
  return errorCode ? <small>{errorCode}</small> : null;
}

/**
 * Image cards load the archived original so a resized node stays sharp; the archived 480px
 * preview is kept for later list-style surfaces and is not used here. Videos keep loading only
 * the cover frame until the user explicitly plays the original.
 */
function MediaPreview({ artifact, assetId, title, demo }: {
  artifact: Artifact; assetId: string; title: string; demo: boolean;
}) {
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
    {video && playing && !playbackFailed ? <video key={playbackAttempt} className="nodrag nowheel nopan" aria-label={`${title} 的视频`}
      controls autoPlay playsInline preload="metadata" src={assetContentUrl(artifact.projectId, assetId)}
      onCanPlay={() => setBuffering(false)} onPlaying={() => setBuffering(false)} onWaiting={() => setBuffering(true)}
      onError={() => { setPlaybackFailed(true); setBuffering(false); }} />
      : !failed ? <img alt={`${title} 的${video ? "视频封面" : "预览"}`}
        decoding="async" draggable={false} loading="lazy" onError={() => setFailed(true)}
        src={video ? assetThumbnailUrl(artifact.projectId, assetId)
          : assetContentUrl(artifact.projectId, assetId)} />
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
