import {
ArrowClockwise,
ArrowsOutSimple,
Buildings,CaretDown,
CopySimple,
Crop,Cube,
DownloadSimple,
Eraser,Image as ImageIcon,
MagicWand,
MusicNotes,
PaintBrush,
PersonSimple,Play,Scissors,SlidersHorizontal,Smiley,
Stack,
Sun,UploadSimple,
VideoCamera,
X
} from "@phosphor-icons/react";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useEffect,useRef,useState,type ReactNode } from "react";
import {
ApiError,assetContentUrl,assetThumbnailUrl,getMediaSettings,listDirectMediaTasks,
runImageOperation,type Artifact,type CanvasItem,type MediaCapability,
type RunImageOperationRequest,type Task
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { Button } from "../../shared/ui/primitives/button";
import { DropdownMenu,DropdownMenuContent,DropdownMenuGroup,DropdownMenuItem,DropdownMenuSub,DropdownMenuSubContent,DropdownMenuSubTrigger,DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Select } from "../../shared/ui/Select";
import { SaveToLibraryButton } from "../library/SaveToLibraryButton";
import { ArtifactCardFrame } from "./ArtifactCardFrame";
import { readContentText } from "./artifactContent";
import { AudioPlayer } from "./AudioPlayer";
import { BrushMarkupEditor } from "./BrushMarkupEditor";
import { CropPanel } from "./CropPanel";
import { ImagePreviewDialog } from "./ImagePreviewDialog";
import { MediaCardUpload } from "./MediaCardUpload";
import { assetMetadataQueryOptions,displayedMediaAssetId,isMediaDraftDisplayed,mediaDraftQueryOptions } from "./mediaDisplay";
import { isMediaTaskRunning,latestMediaTask,MEDIA_TASK_REFRESH_INTERVAL_MS } from "./mediaTaskState";
import { MediaVersionPicker } from "./MediaVersionPicker";
import { RelightPanel } from "./RelightPanel";
import { SmartEditDialog } from "./SmartEditDialog";
import { taskErrorMessage } from "./taskErrorMessages";

const TASK_LABELS: Partial<Record<Task["status"], string>> = {
  // Synchronous providers generate before returning, so SUBMITTING also covers generation time.
  get PENDING() { return t("等待生成"); }, get READY() { return t("排队中"); }, get RUNNING() { return t("正在生成"); }, get SUBMITTING() { return t("正在生成"); },
  get WAITING_PROVIDER() { return t("正在生成"); }, get FAILED() { return t("生成失败"); }, get CANCELED() { return t("已取消"); },
  get UNKNOWN() { return t("结果未知"); }, get BLOCKED() { return t("任务已阻断"); }, get SUCCEEDED() { return t("生成结果已保存至历史"); },
};
type ImageOperation = RunImageOperationRequest["operation"];
type ImageTool = ImageOperation | "BRUSH_MARKUP";
type ThreeViewType = NonNullable<RunImageOperationRequest["parameters"]["threeViewType"]>;
type AspectRatio = NonNullable<RunImageOperationRequest["parameters"]["aspectRatio"]>;
const LOCAL_IMAGE_OPERATIONS: readonly ImageTool[] = [
  "BRUSH_MARKUP",
  "DEPTH_MAP", "UPSCALE", "CROP", "ROTATE", "FLIP_HORIZONTAL", "FLIP_VERTICAL",
];
const THREE_VIEW_OPTIONS = [
  { value: "CHARACTER", get label() { return t("角色三视图"); }, get summary() { return t("正面、侧面、背面全身"); }, icon: PersonSimple,
    aspectRatio: "16:9" },
  { value: "FACE", get label() { return t("脸部三视图"); }, get summary() { return t("正面、四分之三、侧脸"); }, icon: Smiley,
    aspectRatio: "16:9" },
  { value: "PROP", get label() { return t("道具三视图"); }, get summary() { return t("正面、侧面、背面正投影"); }, icon: Cube,
    aspectRatio: "16:9" },
  { value: "SCENE_GRID", get label() { return t("场景宫格图"); }, get summary() { return t("远景、反向、中景、细节"); }, icon: Buildings,
    aspectRatio: "1:1" },
] as const satisfies readonly { value: ThreeViewType; label: string; summary: string;
  icon: typeof Cube; aspectRatio: "1:1" | "16:9" }[];
const EXTENSIONS = [
  { get label() { return t("三视图"); }, icon: Cube, operation: "THREE_VIEW", submenu: true },
  { get label() { return t("图层分离"); }, icon: Stack, operation: "LAYER_SPLIT", submenu: false },
  { get label() { return t("表情调整"); }, icon: Smiley, operation: "EXPRESSION_EDIT", submenu: false },
  { get label() { return t("重新打光"); }, icon: Sun, operation: "RELIGHT", submenu: false },
  { get label() { return t("高清放大"); }, icon: ArrowsOutSimple, operation: "UPSCALE", submenu: false },
  { get label() { return t("裁剪"); }, icon: Crop, operation: "CROP", submenu: false },
  { get label() { return t("顺时针旋转 90°"); }, icon: ArrowClockwise, operation: "ROTATE", submenu: false },
  { get label() { return t("水平镜像"); }, icon: ArrowClockwise, operation: "FLIP_HORIZONTAL", submenu: false },
  { get label() { return t("画笔标注"); }, icon: PaintBrush, operation: "BRUSH_MARKUP", submenu: false },
  { get label() { return t("移除背景"); }, icon: Scissors, operation: "REMOVE_BACKGROUND", submenu: false },
  { get label() { return t("AI 扩图"); }, icon: ArrowsOutSimple, operation: "OUTPAINT", submenu: false },
  { get label() { return t("局部擦除"); }, icon: Eraser, operation: "OBJECT_REMOVE", submenu: false },
  { get label() { return t("视角调整"); }, icon: Cube, operation: "VIEW_ANGLE", submenu: false },
] as const;

/** The media surface contains only the preview; editing and history live outside its bounds. */
export function MediaCanvasCard({ artifact, item, selected, toolbarVisible, locked, onEdit, onInspect,
  onDuplicate, onMakeMV, children }: {
  artifact: Artifact; item: CanvasItem; selected: boolean; toolbarVisible?: boolean; locked: boolean; onEdit: () => void;
  onInspect: () => void; onDuplicate?: () => void;
  onMakeMV?: () => void; children: ReactNode;
}) {
  useLocale();
  const [menuOpen, setMenuOpen] = useState(false);
  const [threeViewMenuOpen, setThreeViewMenuOpen] = useState(false);
  const [operationOpen, setOperationOpen] = useState<ImageTool | null>(null);
  const [threeViewType, setThreeViewType] = useState<ThreeViewType | null>(null);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
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
  const isAudio = artifact.kind === "AUDIO";
  const metadata = useQuery(assetMetadataQueryOptions(artifact.projectId, isImage || artifact.kind === "VIDEO" ? assetId : null));
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
  const toolbarActive = selected && toolbarVisible !== false;
  const { isPending: operationPending, reset: resetOperation } = operation;

  useEffect(() => {
    if (toolbarActive) return;
    // The card stays mounted when its toolbar hides; dismiss all transient tool state.
    setMenuOpen(false);
    setThreeViewMenuOpen(false);
    setOperationOpen(null);
    setThreeViewType(null);
    // Keep tracking an in-flight submission even after its panel is dismissed.
    if (!operationPending) resetOperation();
  }, [toolbarActive, operationPending, resetOperation]);

  function runOperation(name: ImageOperation, parameters: RunImageOperationRequest["parameters"] = {},
      instruction?: string, capabilityId?: string | null,
      extras: Pick<RunImageOperationRequest, "referenceVersionIds" | "maskAssetId"> = {}) {
    const sourceVersionId = item.selectedVersion?.id;
    if (!sourceVersionId || operation.isPending) return;
    operation.mutate({ canvasItemId: item.id, sourceVersionId,
      expectedCanvasItemVersion: item.version, operation: name,
      instruction: instruction || null, capabilityId: capabilityId || null, parameters, ...extras });
  }

  function chooseOperation(name: ImageTool, threeView: ThreeViewType | null = null) {
    setMenuOpen(false);
    setThreeViewMenuOpen(false);
    setThreeViewType(threeView);
    if (name === "ROTATE") runOperation(name, { quarterTurns: 1 });
    else if (name === "FLIP_HORIZONTAL" || name === "FLIP_VERTICAL") runOperation(name);
    else setOperationOpen(name);
  }

  return <ArtifactCardFrame title={item.title} kindLabel={isImage ? t("图片") : isAudio ? t("音频") : t("视频")}
    titleIcon={isImage ? <ImageIcon size={16} /> : isAudio ? <MusicNotes size={16} /> : <VideoCamera size={16} />}
    className={isAudio && assetId ? "audio-canvas-card" : undefined}
    selected={selected} locked={locked} toolbarVisible={toolbarVisible}
    toolbarRaised={menuOpen || operationOpen !== null}
    editableTitle={{ projectId: artifact.projectId, item }}
    toolbarLabel={t("媒体卡片操作")} toolbar={<>
        <SaveToLibraryButton projectId={artifact.projectId} itemId={item.id} disabled={!assetId} />
        {isImage ? <>
          <Button variant="ghost" type="button" disabled={!assetId || Boolean(busy) || operation.isPending}
            onClick={() => setOperationOpen("SMART_EDIT")}><MagicWand size={17} />{t("智能编辑")}</Button>
          <Button variant="ghost" type="button" disabled={!assetId || Boolean(busy) || operation.isPending}
            title={t("使用服务端内置的本地 Depth Anything V2 Small 模型")}
            onClick={() => runOperation("DEPTH_MAP")}><Stack size={17} />{t("深度提取")}</Button>
          <DropdownMenu open={menuOpen} onOpenChange={setMenuOpen} modal={false}>
            <DropdownMenuTrigger asChild><Button variant="ghost" type="button" ref={menuButton}>
              <MagicWand />{t("扩展")}<CaretDown /></Button></DropdownMenuTrigger>
            <DropdownMenuContent aria-labelledby={undefined} aria-label={t("图片扩展功能")} className="w-max p-2 nodrag nowheel nopan"><DropdownMenuGroup>
              {EXTENSIONS.map((entry) => entry.submenu ? <DropdownMenuSub key={entry.label} open={threeViewMenuOpen} onOpenChange={setThreeViewMenuOpen}>
                <DropdownMenuSubTrigger className="py-2" disabled={!assetId || Boolean(busy) || operation.isPending}>
                  <entry.icon /><span>{entry.label}</span>
                </DropdownMenuSubTrigger>
                <DropdownMenuSubContent aria-labelledby={undefined} aria-label={t("三视图类型")} className="w-max p-2"><DropdownMenuGroup>
                  {THREE_VIEW_OPTIONS.map((option) => <DropdownMenuItem key={option.value} className="py-2"
                    disabled={!assetId || Boolean(busy) || operation.isPending} onSelect={() => chooseOperation("THREE_VIEW", option.value)}>
                    <option.icon /><span>{option.label}</span><small>AI</small>
                  </DropdownMenuItem>)}
                </DropdownMenuGroup></DropdownMenuSubContent>
              </DropdownMenuSub> : <DropdownMenuItem key={entry.label} className="py-2"
                disabled={!assetId || Boolean(busy) || operation.isPending} onSelect={() => chooseOperation(entry.operation)}>
                <entry.icon /><span>{entry.label}</span><small>{LOCAL_IMAGE_OPERATIONS.includes(entry.operation) ? t("本地") : "AI"}</small>
              </DropdownMenuItem>)}
            </DropdownMenuGroup></DropdownMenuContent>
          </DropdownMenu>
          {operationOpen === "BRUSH_MARKUP" ? (assetId && item.selectedVersionId ? <BrushMarkupEditor
            key={item.id}
            projectId={artifact.projectId} canvasItemId={item.id}
            sourceVersionId={item.selectedVersionId} expectedVersion={item.version}
            sourceTitle={item.title} sourceUrl={assetContentUrl(artifact.projectId, assetId)}
            onClose={() => setOperationOpen(null)} /> : null)
            : operationOpen === "SMART_EDIT" && assetId ? <SmartEditDialog
            projectId={artifact.projectId} sourceVersionId={item.selectedVersion?.id ?? ""}
            sourceTitle={item.title}
            sourceUrl={assetContentUrl(artifact.projectId, assetId)}
            capabilities={cloudCapabilities} busy={operation.isPending} error={operation.error}
            onClose={() => { setOperationOpen(null); operation.reset(); }}
            onSubmit={(input) => runOperation("SMART_EDIT", {}, input.instruction,
              input.capabilityId, { referenceVersionIds: input.referenceVersionIds,
                maskAssetId: input.maskAssetId ?? null })} />
            : operationOpen === "RELIGHT" && assetId ? <RelightPanel
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
            : operationOpen ? <ImageOperationPanel key={`${operationOpen}:${threeViewType ?? ""}`}
              operation={operationOpen} initialThreeViewType={threeViewType ?? undefined}
              capabilities={cloudCapabilities} busy={operation.isPending}
              error={operation.error} onClose={() => { setOperationOpen(null); operation.reset(); }}
              onSubmit={(operationParameters, instruction, capabilityId) =>
                runOperation(operationOpen, operationParameters, instruction, capabilityId)} /> : null}
        </> : null}
        {isAudio && assetId && onMakeMV ? <Button variant="ghost" type="button" onClick={onMakeMV}><VideoCamera size={17} />{t("MV 制作")}</Button> : null}
        <Button variant="ghost" type="button" onClick={onEdit} title={t("编辑工作草稿，运行后为当前节点增加版本")}>
          <ArrowClockwise size={17} />{item.selectedVersionId ? t("重新生成") : t("编辑草稿")}</Button>
        <MediaVersionPicker projectId={artifact.projectId} item={item} />
        {onDuplicate ? <Button variant="ghost" type="button" onClick={onDuplicate} title={t("复制完整工作草稿，不复制任务和连线")}>
          <CopySimple size={17} />{t("复制")}</Button> : null}
        <Button variant="ghost" type="button" onClick={onInspect} aria-label={t("卡片详情")}><SlidersHorizontal size={17} /></Button>
        {assetId ? <a href={assetContentUrl(artifact.projectId, assetId)} download
          aria-label={isImage ? t("下载图片") : isAudio ? t("下载音频") : t("下载视频")}><DownloadSimple size={19} /></a> : null}
    </>}>
      {children}
      {(isImage || isAudio) && !assetId ? <Input ref={uploadInput} className="sr-only nodrag" type="file"
        aria-label={isAudio ? t("选择要上传的音频") : t("选择要上传的图片")} accept={isAudio ? MEDIA_FILE_ACCEPT.AUDIO : MEDIA_FILE_ACCEPT.IMAGE}
        onClick={(event) => event.stopPropagation()}
        onPointerDown={(event) => event.stopPropagation()}
        onChange={(event) => {
          const selectedFile = event.currentTarget.files?.[0];
          event.currentTarget.value = "";
          if (selectedFile) setUploadFile(selectedFile);
        }} /> : null}
      {assetId ? <><MediaPreview key={assetId} assetId={assetId} artifact={artifact}
        title={item.title} audioDescription={readContentText(content, "prompt")} demo={demo} selected={selected} />
        {metadata.error ? <p className="media-card-error media-card-size-error nodrag" role="alert">
          {metadata.data
            ? isImage ? t("图片尺寸刷新失败，请重试") : t("视频尺寸刷新失败，请重试")
            : isImage ? t("图片尺寸读取失败，暂按原卡片尺寸显示") : t("视频尺寸读取失败，暂按原卡片尺寸显示")}
          <Button variant="ghost" type="button" onClick={() => void metadata.refetch()} disabled={metadata.isFetching}>
            {metadata.isFetching ? t("正在重试…") : t("重试尺寸")}</Button></p> : null}</>
        : <div className="media-card-empty">
          {busy ? <CanvasLoadingState label={status ?? t("正在生成")} /> : <>
            {isImage ? <ImageIcon className="media-empty-icon" size={44} />
              : isAudio ? <MusicNotes className="media-empty-icon" size={44} /> : <VideoCamera className="media-empty-icon" size={44} />}
            {status ? <div className="media-card-state" role="status">{status}
              {latest?.errorCode ? <small>{taskErrorMessage(latest.errorCode) || latest.errorCode}</small> : null}
              {latest?.status === "UNKNOWN" ? <small>{t("可在编辑区重试")}</small> : null}
            </div> : null}
            {uploadFile ? <MediaCardUpload key={`${uploadFile.name}:${uploadFile.size}:${uploadFile.lastModified}`}
              artifact={artifact} item={item} initialFile={uploadFile} compact
              onDone={() => setUploadFile(null)} />
            : latest?.status === "UNKNOWN" || latest?.status === "BLOCKED" ?
              <Button variant="ghost" className="media-upload-button nodrag" type="button" onClick={onEdit}>
                <SlidersHorizontal size={15} />{t("查看任务")}</Button>
              : isImage || isAudio ? <Button variant="ghost" className="media-upload-button nodrag" type="button"
                onPointerDown={(event) => event.stopPropagation()}
                onClick={(event) => { event.stopPropagation(); uploadInput.current?.click(); }}>
              <UploadSimple size={15} />{isAudio ? t("上传音频") : t("上传图片")}</Button>
              : <Button variant="ghost" className="media-upload-button nodrag" type="button" onClick={onEdit}>
                <Play size={15} />{t("生成视频")}</Button>}
          </>}
          {draft.error ? <p className="media-card-error" role="alert">{t("草稿读取失败")}<Button variant="ghost" type="button" className="nodrag" onClick={() => void draft.refetch()}>{t("重试")}</Button></p> : null}
          {tasks.error ? <p className="media-card-error" role="alert">{t("任务状态暂不可用")}<Button variant="ghost" type="button" className="nodrag" onClick={() => void tasks.refetch()}>{t("重试状态")}</Button></p> : null}
        </div>}
  </ArtifactCardFrame>;
}

const OPERATION_TITLES: Record<ImageOperation, string> = {
  get SMART_EDIT() { return t("智能编辑"); }, get RELIGHT() { return t("打光"); }, get OUTPAINT() { return t("AI 扩图"); },
  get THREE_VIEW() { return t("三视图"); }, get LAYER_SPLIT() { return t("图层分离"); }, get EXPRESSION_EDIT() { return t("表情调整"); },
  get REMOVE_BACKGROUND() { return t("移除背景"); },
  get OBJECT_REMOVE() { return t("局部擦除"); }, get VIEW_ANGLE() { return t("视角调整"); },
  get DEPTH_MAP() { return t("深度提取"); }, get UPSCALE() { return t("高清放大"); }, get CROP() { return t("裁剪"); },
  get ROTATE() { return t("旋转"); }, get FLIP_HORIZONTAL() { return t("水平镜像"); }, get FLIP_VERTICAL() { return t("垂直镜像"); },
};

const INSTRUCTION_COPY: Partial<Record<ImageOperation, { label: string; placeholder: string;
  required?: boolean }>> = {
  SMART_EDIT: { get label() { return t("修改说明"); }, get placeholder() { return t("例如：把服装改为深蓝色，其他内容保持不变"); }, required: true },
  OUTPAINT: { get label() { return t("补充说明（可选）"); }, get placeholder() { return t("例如：延展室内背景，不新增人物"); } },
  THREE_VIEW: { get label() { return t("主体说明（可选）"); }, get placeholder() { return t("例如：以画面中央人物为主体，保留完整服装细节"); } },
  LAYER_SPLIT: { get label() { return t("分层说明（可选）"); }, get placeholder() { return t("例如：主体是画面中央穿红衣的人物"); } },
  EXPRESSION_EDIT: { get label() { return t("目标表情"); }, get placeholder() { return t("例如：自然微笑，嘴唇闭合，眼神放松"); }, required: true },
  REMOVE_BACKGROUND: { get label() { return t("主体说明（可选）"); }, get placeholder() { return t("例如：只保留人物及手中的花束"); } },
  OBJECT_REMOVE: { get label() { return t("擦除目标"); }, get placeholder() { return t("例如：移除右下角的路人并自然补全地面"); }, required: true },
  VIEW_ANGLE: { get label() { return t("补充说明（可选）"); }, get placeholder() { return t("例如：镜头距离保持不变，完整保留人物服装"); } },
};

const VIEW_ANGLE_OPTIONS = [
  ["FRONT", "正面"], ["LEFT_THREE_QUARTER", "左前 45°"],
  ["RIGHT_THREE_QUARTER", "右前 45°"], ["LEFT_PROFILE", "左侧面"],
  ["RIGHT_PROFILE", "右侧面"], ["HIGH_ANGLE", "俯视"],
  ["LOW_ANGLE", "仰视"], ["BACK", "背面"],
] as const;

function ImageOperationPanel({ operation, initialThreeViewType, capabilities, busy, error,
  onClose, onSubmit }: {
  operation: ImageOperation; capabilities: MediaCapability[]; busy: boolean; error: Error | null;
  initialThreeViewType?: ThreeViewType;
  onClose: () => void;
  onSubmit: (parameters: RunImageOperationRequest["parameters"], instruction?: string,
    capabilityId?: string) => void;
}) {
  useLocale();
  const cloud = !LOCAL_IMAGE_OPERATIONS.includes(operation);
  const [instruction, setInstruction] = useState("");
  const [capabilityId, setCapabilityId] = useState(capabilities[0]?.id ?? "");
  const [scale, setScale] = useState<2 | 4>(2);
  const initialThreeView = initialThreeViewType ?? "CHARACTER";
  const [threeViewType, setThreeViewType] = useState<ThreeViewType>(initialThreeView);
  const [ratio, setRatio] = useState<AspectRatio>(THREE_VIEW_OPTIONS.find(
    (option) => option.value === initialThreeView)?.aspectRatio ?? "16:9");
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
      ? { scale } : operation === "THREE_VIEW"
        ? { aspectRatio: ratio, threeViewType }
        : operation === "OUTPAINT" ? { aspectRatio: ratio }
        : operation === "LAYER_SPLIT" ? { layerTarget }
          : operation === "VIEW_ANGLE" ? { viewAngle }
        : {};
    onSubmit(parameters, instruction.trim(), selectedCapabilityId || undefined);
  }

  const threeViewLabel = THREE_VIEW_OPTIONS.find((option) => option.value === threeViewType)?.label;
  return <div className="media-operation-panel nodrag nowheel nopan" role="dialog"
    aria-label={operation === "THREE_VIEW" ? threeViewLabel : OPERATION_TITLES[operation]}
    onKeyDown={(event) => { if (event.key === "Escape") onClose(); }}>
    <header><div><strong>{operation === "THREE_VIEW" ? threeViewLabel : OPERATION_TITLES[operation]}</strong>
      <span>{cloud ? t("使用 OpenAI / Google 图片能力") : t("在本机处理，不上传图片")}</span></div>
      <Button variant="ghost" type="button" aria-label={t("关闭图片处理面板")} onClick={onClose}><X size={16} /></Button>
    </header>
    {operation === "THREE_VIEW" ? <fieldset className="media-three-view-types">
      <legend>{t("输出类型")}</legend>
      {THREE_VIEW_OPTIONS.map((option) => <label key={option.value}
        className={threeViewType === option.value ? "is-selected" : ""}>
        <input type="radio" name="three-view-type" value={option.value}
          checked={threeViewType === option.value} onChange={() => {
            setThreeViewType(option.value); setRatio(option.aspectRatio);
          }} />
        <option.icon size={18} /><span><strong>{option.label}</strong><small>{option.summary}</small></span>
      </label>)}
    </fieldset> : null}
    {operation === "LAYER_SPLIT" ? <label>{t("输出图层")}<Select variant="ghost" density="compact" value={layerTarget}
      onChange={(event) => setLayerTarget(event.target.value as "FOREGROUND" | "BACKGROUND")}>
      <option value="FOREGROUND">{t("主体层（透明背景）")}</option>
      <option value="BACKGROUND">{t("背景层（移除主体后补全）")}</option>
    </Select></label> : null}
    {operation === "VIEW_ANGLE" ? <label>{t("目标视角")}<Select variant="ghost" density="compact" value={viewAngle}
      onChange={(event) => setViewAngle(event.target.value as typeof viewAngle)}>
      {VIEW_ANGLE_OPTIONS.map(([value, label]) => <option key={value} value={value}>{t(label)}</option>)}
    </Select></label> : null}
    {cloud ? <label>{t("图片能力")}<Select variant="ghost" density="compact" value={selectedCapabilityId}
      onChange={(event) => setCapabilityId(event.target.value)}>
      {eligibleCapabilities.length ? eligibleCapabilities.map((capability) => <option key={capability.id}
        value={capability.id}>{capability.name}</option>)
        : <option value="">{transparentOutput
          ? t("请配置支持透明背景的 OpenAI / Google 图片能力")
          : t("请先在设置中配置 OpenAI 或 Google")}</option>}
    </Select></label> : null}
    {instructionCopy
      ? <label>{instructionCopy.label}
        <Textarea value={instruction} maxLength={4000}
          placeholder={instructionCopy.placeholder}
          onChange={(event) => setInstruction(event.target.value)} /></label> : null}
    {operation === "UPSCALE" ? <label>{t("放大倍数")}<Select variant="ghost" density="compact" value={scale}
      onChange={(event) => setScale(Number(event.target.value) as 2 | 4)}>
      <option value={2}>{t("2× 本地双三次插值")}</option><option value={4}>{t("4× 本地双三次插值")}</option>
    </Select></label> : null}
    {operation === "OUTPAINT" || operation === "THREE_VIEW" ? <label>{t("目标画幅")}<Select variant="ghost" density="compact" value={ratio}
      onChange={(event) => setRatio(event.target.value as AspectRatio)}>
      {["1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9"].map((value) =>
        <option key={value} value={value}>{value}</option>)}</Select></label> : null}
    {error ? <p role="alert">{error instanceof ApiError ? error.message : t("图片处理任务受理失败，请重试。")}</p> : null}
    <footer><Button variant="ghost" type="button" onClick={onClose}>{t("取消")}</Button>
      <Button variant="ghost" type="button" className="is-primary" disabled={!canSubmit} onClick={submit}>
        {busy ? t("正在受理…") : t("开始处理")}</Button></footer>
  </div>;
}

/**
 * Image cards load the archived original so a resized node stays sharp; the archived 480px
 * preview is kept for later list-style surfaces and is not used here. Videos keep loading only
 * the cover frame until the user explicitly plays the original.
 */
function MediaPreview({ artifact, assetId, title, audioDescription, demo, selected }: {
  artifact: Artifact; assetId: string; title: string; audioDescription: string; demo: boolean; selected: boolean;
}) {
  useLocale();
  const [failed, setFailed] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [playbackFailed, setPlaybackFailed] = useState(false);
  const [buffering, setBuffering] = useState(false);
  const [playbackAttempt, setPlaybackAttempt] = useState(0);
  const [expanded, setExpanded] = useState(false);
  const video = artifact.kind === "VIDEO";

  function startPlayback() {
    setPlaybackFailed(false);
    setBuffering(true);
    setPlaybackAttempt((attempt) => attempt + 1);
    setPlaying(true);
  }

  if (artifact.kind === "AUDIO") return <AudioPlayer key={assetId} src={assetContentUrl(artifact.projectId, assetId)} title={title}
    description={audioDescription || title} selected={selected} demo={demo} />;
  return <div className="media-card-preview">
    {video && playing && !playbackFailed ? <video key={playbackAttempt} className="nodrag nowheel nopan" aria-label={t("{0} 的视频", { "0": title })}
      controls autoPlay playsInline preload="metadata" src={assetContentUrl(artifact.projectId, assetId)}
      onCanPlay={() => setBuffering(false)} onPlaying={() => setBuffering(false)} onWaiting={() => setBuffering(true)}
      onError={() => { setPlaybackFailed(true); setBuffering(false); }} />
      : !failed ? <img alt={t("{0} 的{1}", { "0": title, "1": video ? t("视频封面") : t("预览") })}
        decoding="async" draggable={false} loading="lazy" onError={() => setFailed(true)}
        src={video ? assetThumbnailUrl(artifact.projectId, assetId)
          : assetContentUrl(artifact.projectId, assetId)} />
        : <div className="media-card-empty">{video ? <VideoCamera size={36} /> : <ImageIcon size={36} />}<span>{video ? t("视频封面暂不可用") : t("预览暂不可用")}</span>
          <Button variant="ghost" className="media-upload-button nodrag" type="button" onClick={() => setFailed(false)}>{t("重试预览")}</Button></div>}
    {video && playing && buffering ? <div className="media-playback-loading">
      <CanvasLoadingState compact label={t("正在加载视频")} />
    </div> : null}
    {video && playbackFailed ? <div className="media-playback-error nodrag nowheel nopan" role="alert">
      <VideoCamera size={28} /><p>{t("视频播放失败")}</p><span>{t("请重试播放，或打开原视频文件。")}</span>
      <Button variant="ghost" type="button" className="media-upload-button" onClick={startPlayback}><ArrowClockwise size={15} />{t("重试播放")}</Button>
    </div> : null}
    {video && playing ? <Button variant="ghost" type="button" className="media-stop-preview nodrag" aria-label={t("关闭视频预览")}
      title={t("关闭视频预览")} onClick={() => { setPlaying(false); setBuffering(false); setPlaybackFailed(false); }}><X size={17} /></Button> : null}
    {demo ? <span className="media-demo-badge">{video ? t("演示视频") : t("演示素材")}</span> : null}
    {video ? <a className="media-expand-button nodrag" href={assetContentUrl(artifact.projectId, assetId)}
      aria-label={t("打开视频文件")} title={t("打开视频文件")}
      rel="noopener noreferrer" target="_blank"><ArrowsOutSimple size={19} /></a>
      : <Button variant="ghost" className="media-expand-button nodrag" type="button" aria-label={t("放大图片")} title={t("放大图片")}
        onClick={(event) => { event.stopPropagation(); setExpanded(true); }}><ArrowsOutSimple size={19} /></Button>}
    {!video && expanded ? <ImagePreviewDialog title={title}
      sourceUrl={assetContentUrl(artifact.projectId, assetId)} onClose={() => setExpanded(false)} /> : null}
    {video && !playing ? <Button variant="ghost" className="media-play-button nodrag" type="button"
      aria-label={t("播放视频")} onClick={startPlayback}><Play size={28} weight="fill" /><span className="sr-only">{t("播放视频")}</span></Button> : null}
  </div>;
}
