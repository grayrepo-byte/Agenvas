import {
ArrowClockwise,
ArrowsOutSimple,
Buildings,CaretDown,
CopySimple,
Crop,Cube,
HighDefinition,
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
} from "@/shared/ui/icons";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useEffect,useRef,useState,type ReactNode } from "react";
import {
ApiError,assetContentUrl,assetThumbnailUrl,getMediaSettings,getMediaFunctions,listDirectMediaTasks,
runImageOperation,runVideoOperation,type Artifact,type CanvasItem,type MediaCapability,
type RunImageOperationRequest,type RunVideoOperationRequest,type VideoOperation,type Task
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
import { ImageResizePanel } from "./ImageResizePanel";
import { CropPanel } from "./CropPanel";
import { MediaPreviewDialog } from "./MediaPreviewDialog";
import { MediaCardUpload } from "./MediaCardUpload";
import { assetMetadataQueryOptions,displayedMediaAssetId,isMediaDraftDisplayed,mediaDraftQueryOptions,mediaVersionsQueryOptions } from "./mediaDisplay";
import { isMediaTaskRunning,latestMediaTask,MEDIA_TASK_REFRESH_INTERVAL_MS } from "./mediaTaskState";
import { MediaVersionPicker } from "./MediaVersionPicker";
import { RelightPanel } from "./RelightPanel";
import { SmartEditDialog } from "./SmartEditDialog";
import { taskErrorMessage } from "./taskErrorMessages";
import { VideoOperationPanel } from "./VideoOperationPanel";
import { VideoPreview } from "./VideoPreview";
import { ImageFunctionConfiguration } from "./ImageFunctionConfiguration";
import { MEDIA_FUNCTIONS_QUERY_KEY, imageFunction, mediaFunctionChoices } from "../../shared/mediaFunctions";

const TASK_LABELS: Partial<Record<Task["status"], string>> = {
  // Synchronous providers generate before returning, so SUBMITTING also covers generation time.
  get READY() { return t("tasks.status.queued"); }, get RUNNING() { return t("media.card.generating"); }, get SUBMITTING() { return t("media.card.generating"); },
  get WAITING_PROVIDER() { return t("media.card.generating"); }, get FAILED() { return t("media.card.generationFailed"); }, get CANCELED() { return t("common.canceled"); },
  get UNKNOWN() { return t("tasks.status.unknown"); }, get BLOCKED() { return t("media.card.blocked"); }, get SUCCEEDED() { return t("media.card.resultArchived"); },
};
type ImageOperation = RunImageOperationRequest["operation"];
type ImageTool = ImageOperation | "BRUSH_MARKUP";
type ThreeViewType = NonNullable<RunImageOperationRequest["parameters"]["threeViewType"]>;
type AspectRatio = NonNullable<RunImageOperationRequest["parameters"]["aspectRatio"]>;
const THREE_VIEW_OPTIONS = [
  { value: "CHARACTER", get label() { return t("media.card.characterViews"); }, get summary() { return t("media.card.characterViewsHint"); }, icon: PersonSimple,
    aspectRatio: "16:9" },
  { value: "FACE", get label() { return t("media.card.faceViews"); }, get summary() { return t("media.card.faceViewsHint"); }, icon: Smiley,
    aspectRatio: "16:9" },
  { value: "PROP", get label() { return t("media.card.objectViews"); }, get summary() { return t("media.card.objectViewsHint"); }, icon: Cube,
    aspectRatio: "16:9" },
  { value: "SCENE_GRID", get label() { return t("media.card.sceneGrid"); }, get summary() { return t("media.card.sceneViewsHint"); }, icon: Buildings,
    aspectRatio: "1:1" },
] as const satisfies readonly { value: ThreeViewType; label: string; summary: string;
  icon: typeof Cube; aspectRatio: "1:1" | "16:9" }[];
const EXTENSIONS = [
  { get label() { return t("media.card.threeView"); }, icon: Cube, operation: "THREE_VIEW", submenu: true },
  { get label() { return t("media.card.splitLayers"); }, icon: Stack, operation: "LAYER_SPLIT", submenu: false },
  { get label() { return t("media.card.changeExpression"); }, icon: Smiley, operation: "EXPRESSION_EDIT", submenu: false },
  { get label() { return t("media.card.relight"); }, icon: Sun, operation: "RELIGHT", submenu: false },
  { get label() { return t("media.card.upscaleTitle"); }, icon: ArrowsOutSimple, operation: "UPSCALE", submenu: false },
  { get label() { return t("media.card.crop"); }, icon: Crop, operation: "CROP", submenu: false },
  { get label() { return t("media.card.rotateClockwise"); }, icon: ArrowClockwise, operation: "ROTATE", submenu: false },
  { get label() { return t("media.card.flipHorizontal"); }, icon: ArrowClockwise, operation: "FLIP_HORIZONTAL", submenu: false },
  { get label() { return t("image.markupEditor.title"); }, icon: PaintBrush, operation: "BRUSH_MARKUP", submenu: false },
  { get label() { return t("media.card.removeBackground"); }, icon: Scissors, operation: "REMOVE_BACKGROUND", submenu: false },
  { get label() { return t("media.card.outpaint"); }, icon: ArrowsOutSimple, operation: "OUTPAINT", submenu: false },
  { get label() { return t("media.card.removeObject"); }, icon: Eraser, operation: "OBJECT_REMOVE", submenu: false },
  { get label() { return t("media.card.changeAngle"); }, icon: Cube, operation: "VIEW_ANGLE", submenu: false },
] as const;

/** The media surface contains only the preview; editing and history live outside its bounds. */
export function MediaCanvasCard({ artifact, item, selected, toolbarVisible, locked, onEdit,
  onDuplicate, onMakeMV, children }: {
  artifact: Artifact; item: CanvasItem; selected: boolean; toolbarVisible?: boolean; locked: boolean; onEdit: () => void;
  onDuplicate?: () => void;
  onMakeMV?: () => void; children: ReactNode;
}) {
  useLocale();
  const [menuOpen, setMenuOpen] = useState(false);
  const [threeViewMenuOpen, setThreeViewMenuOpen] = useState(false);
  const [operationOpen, setOperationOpen] = useState<ImageTool | null>(null);
  const [videoOperationOpen, setVideoOperationOpen] = useState<VideoOperation | null>(null);
  const videoCommand = useRef<{ payload: string; key: string; input: RunVideoOperationRequest } | null>(null);
  const [threeViewType, setThreeViewType] = useState<ThreeViewType | null>(null);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const menuButton = useRef<HTMLButtonElement>(null);
  const uploadInput = useRef<HTMLInputElement>(null);
  const queryClient = useQueryClient();
  const draft = useQuery(mediaDraftQueryOptions(artifact.projectId, item.id));
  const showDraft = isMediaDraftDisplayed(item, draft.data);
  const history = useQuery(mediaVersionsQueryOptions(artifact.projectId, item.id));
  const hasResults = Boolean(history.data?.items.length);
  const tasks = useQuery({ queryKey: ["direct-media-tasks", artifact.projectId, item.id],
    queryFn: () => listDirectMediaTasks(artifact.projectId, artifact.id, item.id), enabled: showDraft,
    refetchInterval: (query) => query.state.data?.some(isMediaTaskRunning)
      ? MEDIA_TASK_REFRESH_INTERVAL_MS : false });
  const latest = latestMediaTask(tasks.data);
  const showSavedResults = hasResults || latest?.status === "SUCCEEDED" || item.selectedVersionId !== null;
  const busy = showDraft && latest && isMediaTaskRunning(latest);
  const status = showDraft && latest ? latest.status === "SUCCEEDED" && item.selectedVersionId
    ? t("media.card.draftDisplayed") : TASK_LABELS[latest.status] : undefined;
  const assetId = displayedMediaAssetId(item, draft.data);
  const content = item.selectedVersion?.content;
  const parameters = content && typeof content === "object" && "parameters" in content ? content.parameters : null;
  const demo = Boolean(parameters && typeof parameters === "object" && "mock" in parameters && parameters.mock === true);
  const isImage = artifact.kind === "IMAGE";
  const isAudio = artifact.kind === "AUDIO";
  const isVideo = artifact.kind === "VIDEO";
  const metadata = useQuery(assetMetadataQueryOptions(artifact.projectId, isImage || artifact.kind === "VIDEO" ? assetId : null));
  const settings = useQuery({ queryKey: ["settings", "media"], queryFn: getMediaSettings,
    enabled: isImage && Boolean(assetId) });
  const functions = useQuery({ queryKey: MEDIA_FUNCTIONS_QUERY_KEY, queryFn: getMediaFunctions,
    enabled: isImage && Boolean(assetId), retry: false });
  const imageCommand = useRef<{ payload: string; key: string; input: RunImageOperationRequest } | null>(null);
  const operation = useMutation({
    mutationFn: (input: RunImageOperationRequest) => {
      const payload = JSON.stringify({ ...input, expectedCanvasItemVersion: undefined });
      if (imageCommand.current?.payload !== payload) imageCommand.current = { payload, key: crypto.randomUUID(), input };
      return runImageOperation(artifact.projectId, artifact.id, imageCommand.current.input, imageCommand.current.key);
    },
    onSuccess: async () => {
      imageCommand.current = null;
      setOperationOpen(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["direct-media-tasks", artifact.projectId] }),
      ]);
    },
  });
  const toolbarActive = selected && toolbarVisible !== false;
  const videoOperation = useMutation({
    mutationFn: (input: RunVideoOperationRequest) => {
      // A layout save after an uncertain response must replay the original command.
      const payload = JSON.stringify({ ...input, expectedCanvasItemVersion: undefined });
      if (videoCommand.current?.payload !== payload) videoCommand.current = { payload, key: crypto.randomUUID(), input };
      return runVideoOperation(artifact.projectId, artifact.id, videoCommand.current.input, videoCommand.current.key);
    },
    onSuccess: async () => {
      videoCommand.current = null;
      setVideoOperationOpen(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["direct-media-tasks", artifact.projectId] }),
      ]);
    },
  });
  const { isPending: operationPending, reset: resetOperation } = operation;
  const { isPending: videoPending, reset: resetVideoOperation } = videoOperation;

  useEffect(() => {
    if (toolbarActive) return;
    // The card stays mounted when its toolbar hides; dismiss all transient tool state.
    setMenuOpen(false);
    setThreeViewMenuOpen(false);
    setOperationOpen(null);
    setVideoOperationOpen(null);
    setThreeViewType(null);
    // Keep tracking an in-flight submission even after its panel is dismissed.
    if (!operationPending) resetOperation();
    if (!videoPending) resetVideoOperation();
  }, [toolbarActive, operationPending, resetOperation, videoPending, resetVideoOperation]);

  function runOperation(name: ImageOperation, parameters: RunImageOperationRequest["parameters"] = {}) {
    const sourceVersionId = item.selectedVersion?.id;
    if (!sourceVersionId || operation.isPending) return;
    const setting = functions.data?.find((entry) => entry.operation === imageFunction(name));
    const configured = settings.data && setting?.capabilityId ? mediaFunctionChoices(settings.data, imageFunction(name))
      .find(({ capability }) => capability.id === setting.capabilityId) : undefined;
    if (!setting || !configured || configured.capability.adapterId !== "LOCAL_IMAGE_PROCESSOR") {
      setOperationOpen(name); return;
    }
    operation.mutate({ canvasItemId: item.id, sourceVersionId, expectedCanvasItemVersion: item.version,
      operation: name, instruction: null, parameters, expectedFunctionVersion: setting.version,
      expectedCapabilityVersion: configured.capability.capabilityVersion });
  }

  function chooseOperation(name: ImageTool, threeView: ThreeViewType | null = null) {
    setMenuOpen(false);
    setThreeViewMenuOpen(false);
    setThreeViewType(threeView);
    if (name === "ROTATE") runOperation(name, { quarterTurns: 1 });
    else if (name === "FLIP_HORIZONTAL" || name === "FLIP_VERTICAL") runOperation(name);
    else setOperationOpen(name);
  }

  function imageToolMethod(name: ImageTool) {
    if (name === "BRUSH_MARKUP") return t("media.card.local");
    const setting = functions.data?.find((entry) => entry.operation === imageFunction(name));
    const configured = settings.data && setting?.capabilityId
      ? mediaFunctionChoices(settings.data, imageFunction(name)).find(({ capability }) => capability.id === setting.capabilityId)
      : undefined;
    if (!configured) return t("models.unconfigured");
    return configured.capability.adapterId === "LOCAL_IMAGE_PROCESSOR" ? t("media.card.local") : "AI";
  }

  return <ArtifactCardFrame title={item.title} kindLabel={isImage ? t("common.image") : isAudio ? t("common.audio") : t("common.video")}
    titleIcon={isImage ? <ImageIcon size={16} /> : isAudio ? <MusicNotes size={16} /> : <VideoCamera size={16} />}
    className={isAudio && assetId ? "audio-canvas-card" : undefined}
    selected={selected} locked={locked} toolbarVisible={toolbarVisible}
    toolbarRaised={menuOpen || operationOpen !== null || videoOperationOpen !== null}
    editableTitle={{ projectId: artifact.projectId, item }}
    toolbarLabel={t("media.card.toolbarLabel")} toolbar={<>
        <SaveToLibraryButton projectId={artifact.projectId} itemId={item.id} disabled={!assetId} />
        {isImage ? <>
          <Button variant="ghost" type="button" disabled={!assetId || Boolean(busy) || operation.isPending}
            onClick={() => setOperationOpen("SMART_EDIT")}><MagicWand size={17} />{t("media.card.smartEdit")}</Button>
          <Button variant="ghost" type="button" disabled={!assetId || Boolean(busy) || operation.isPending}
            onClick={() => runOperation("DEPTH_MAP")}><Stack size={17} />{t("media.card.extractDepth")}</Button>
          <Button variant="ghost" type="button" disabled={!assetId || Boolean(busy) || operation.isPending}
            onClick={() => setOperationOpen("RESIZE")}><ArrowsOutSimple data-icon="inline-start" />{t("image.resize.title")}</Button>
          <DropdownMenu open={menuOpen} onOpenChange={setMenuOpen} modal={false}>
            <DropdownMenuTrigger asChild><Button variant="ghost" type="button" ref={menuButton}>
              <MagicWand />{t("media.card.extensions")}<CaretDown /></Button></DropdownMenuTrigger>
            <DropdownMenuContent aria-labelledby={undefined} aria-label={t("media.card.imageExtensions")} className="w-max p-2 nodrag nowheel nopan"><DropdownMenuGroup>
              {EXTENSIONS.map((entry) => entry.submenu ? <DropdownMenuSub key={entry.label} open={threeViewMenuOpen} onOpenChange={setThreeViewMenuOpen}>
                <DropdownMenuSubTrigger className="py-2" disabled={!assetId || Boolean(busy) || operation.isPending}>
                  <entry.icon /><span>{entry.label}</span>
                </DropdownMenuSubTrigger>
                <DropdownMenuSubContent aria-labelledby={undefined} aria-label={t("media.card.threeViewType")} className="w-max p-2"><DropdownMenuGroup>
                  {THREE_VIEW_OPTIONS.map((option) => <DropdownMenuItem key={option.value} className="py-2"
                    disabled={!assetId || Boolean(busy) || operation.isPending} onSelect={() => chooseOperation("THREE_VIEW", option.value)}>
                    <option.icon /><span>{option.label}</span><small>AI</small>
                  </DropdownMenuItem>)}
                </DropdownMenuGroup></DropdownMenuSubContent>
              </DropdownMenuSub> : <DropdownMenuItem key={entry.label} className="py-2"
                disabled={!assetId || Boolean(busy) || operation.isPending} onSelect={() => chooseOperation(entry.operation)}>
                <entry.icon /><span>{entry.label}</span><small>{imageToolMethod(entry.operation)}</small>
              </DropdownMenuItem>)}
            </DropdownMenuGroup></DropdownMenuContent>
          </DropdownMenu>
          {operationOpen === "BRUSH_MARKUP" ? (assetId && item.selectedVersionId ? <BrushMarkupEditor
            key={item.id}
            projectId={artifact.projectId} canvasItemId={item.id}
            sourceVersionId={item.selectedVersionId} expectedVersion={item.version}
            sourceTitle={item.title} sourceUrl={assetContentUrl(artifact.projectId, assetId)}
            onClose={() => setOperationOpen(null)} /> : null)
            : operationOpen && assetId && item.selectedVersionId ? <ImageFunctionConfiguration operation={operationOpen}
              sourceVersionId={item.selectedVersionId} busy={operation.isPending}
              onClose={() => { setOperationOpen(null); operation.reset(); }}
              onSubmit={(input) => operation.mutate({ ...input, canvasItemId: item.id,
                sourceVersionId: item.selectedVersionId ?? "", expectedCanvasItemVersion: item.version, operation: operationOpen })}>
              {({ capability, controls, parameterControls, submitDisabled, submit }) => operationOpen === "SMART_EDIT" ? <SmartEditDialog
                projectId={artifact.projectId} sourceVersionId={item.selectedVersionId ?? ""} sourceTitle={item.title}
                sourceUrl={assetContentUrl(artifact.projectId, assetId)} capabilities={[capability]} configuredMethod
                extraControls={parameterControls} submitDisabled={submitDisabled} busy={operation.isPending} error={operation.error}
                onClose={() => { setOperationOpen(null); operation.reset(); }}
                onSubmit={(input) => submit({}, input.instruction, { referenceVersionIds: input.referenceVersionIds, maskAssetId: input.maskAssetId ?? null })} />
                : operationOpen === "RELIGHT" ? <RelightPanel sourceUrl={assetContentUrl(artifact.projectId, assetId)}
                  capabilities={[capability]} configuredMethod extraControls={controls} submitDisabled={submitDisabled}
                  busy={operation.isPending} error={operation.error} onClose={() => { setOperationOpen(null); operation.reset(); }}
                  onSubmit={(parameters, instruction) => submit(parameters, instruction)} />
                : operationOpen === "RESIZE" ? <ImageResizePanel key={item.selectedVersionId}
                  sourceWidth={metadata.data?.width} sourceHeight={metadata.data?.height}
                  loading={metadata.isPending} metadataError={metadata.error} onRetryMetadata={() => void metadata.refetch()}
                  busy={operation.isPending} error={operation.error} extraControls={controls} submitDisabled={submitDisabled}
                  onClose={() => { setOperationOpen(null); operation.reset(); }} onSubmit={(parameters) => submit(parameters)} />
                : operationOpen === "CROP" ? <CropPanel sourceUrl={assetContentUrl(artifact.projectId, assetId)}
                  sourceWidth={metadata.data?.width} sourceHeight={metadata.data?.height} busy={operation.isPending}
                  error={operation.error} onClose={() => { setOperationOpen(null); operation.reset(); }} onSubmit={(parameters) => submit(parameters)} />
                : <ImageOperationPanel operation={operationOpen} initialThreeViewType={threeViewType ?? undefined}
                  capabilities={[capability]} extraControls={controls} submitDisabled={submitDisabled}
                  busy={operation.isPending} error={operation.error} onClose={() => { setOperationOpen(null); operation.reset(); }}
                  onSubmit={(parameters, instruction) => submit(parameters, instruction)} />}
            </ImageFunctionConfiguration> : null}
        </> : null}
        {isVideo ? <>
          <span className="media-toolbar-divider" aria-hidden="true" />
          <Button variant="ghost" type="button" disabled={!assetId || !item.selectedVersionId || Boolean(busy) || videoPending}
            onClick={() => { videoOperation.reset(); setVideoOperationOpen("UPSCALE"); }}><HighDefinition size={17} />{t("media.video.upscale")}</Button>
          <span className="media-toolbar-divider" aria-hidden="true" />
          <Button variant="ghost" type="button" disabled={!assetId || !item.selectedVersionId || Boolean(busy) || videoPending}
            onClick={() => { videoOperation.reset(); setVideoOperationOpen("DEPTH_MAP"); }}><Stack size={17} />{t("media.card.extractDepth")}</Button>
          <span className="media-toolbar-divider" aria-hidden="true" />
          <DropdownMenu open={menuOpen} onOpenChange={setMenuOpen} modal={false}>
            <DropdownMenuTrigger asChild><Button variant="ghost" type="button"><MagicWand size={17} />{t("media.video.edit")}<CaretDown size={14} /></Button></DropdownMenuTrigger>
            <DropdownMenuContent aria-label={t("media.video.editTools")} className="w-max p-2 nodrag nowheel nopan"><DropdownMenuGroup>
              <DropdownMenuItem className="py-2" disabled={!assetId || !item.selectedVersionId || Boolean(busy) || videoPending}
                onSelect={() => { setMenuOpen(false); videoOperation.reset(); setVideoOperationOpen("EXTRACT_AUDIO"); }}>
                <MusicNotes size={17} /><span>{t("media.video.extractAudio")}</span>
              </DropdownMenuItem>
            </DropdownMenuGroup></DropdownMenuContent>
          </DropdownMenu>
          {videoOperationOpen && item.selectedVersionId ? <VideoOperationPanel operation={videoOperationOpen}
            sourceVersionId={item.selectedVersionId} sourceTitle={item.title} busy={videoPending} error={videoOperation.error}
            onClose={() => { setVideoOperationOpen(null); videoOperation.reset(); }}
            onSubmit={(input) => videoOperation.mutate({ ...input, operation: videoOperationOpen,
              canvasItemId: item.id, sourceVersionId: item.selectedVersionId ?? "", expectedCanvasItemVersion: item.version })} /> : null}
        </> : null}
        {isAudio && assetId && onMakeMV ? <Button variant="ghost" type="button" onClick={onMakeMV}><VideoCamera size={17} />{t("media.card.musicVideo")}</Button> : null}
        <Button variant="ghost" type="button" onClick={onEdit} title={t("media.card.regenerateHint")}>
          <ArrowClockwise size={17} />{item.selectedVersionId ? t("media.card.regenerate") : t("media.card.editDraft")}</Button>
        <MediaVersionPicker projectId={artifact.projectId} item={item} />
        {onDuplicate ? <Button variant="ghost" type="button" onClick={onDuplicate} title={t("media.card.copyHint")}>
          <CopySimple size={17} />{t("common.copy")}</Button> : null}
        {assetId ? <a href={assetContentUrl(artifact.projectId, assetId)} download
          aria-label={isImage ? t("media.card.downloadImage") : isAudio ? t("media.card.downloadAudio") : t("media.card.downloadVideo")}><DownloadSimple size={19} /></a> : null}
    </>}>
      {children}
      {(isImage || isAudio) && !assetId ? <Input ref={uploadInput} className="sr-only nodrag" type="file"
        aria-label={isAudio ? t("media.card.chooseAudioUpload") : t("media.card.chooseImageUpload")} accept={isAudio ? MEDIA_FILE_ACCEPT.AUDIO : MEDIA_FILE_ACCEPT.IMAGE}
        onClick={(event) => event.stopPropagation()}
        onPointerDown={(event) => event.stopPropagation()}
        onChange={(event) => {
          const selectedFile = event.currentTarget.files?.[0];
          event.currentTarget.value = "";
          if (selectedFile) setUploadFile(selectedFile);
        }} /> : null}
      {assetId ? <><MediaPreview key={assetId} assetId={assetId} artifact={artifact}
        title={item.title} audioDescription={readContentText(content, "prompt")} demo={demo} selected={selected}
        width={metadata.data?.width ?? undefined} height={metadata.data?.height ?? undefined}
        contentType={metadata.data?.contentType} />
        {metadata.error ? <p className="media-card-error media-card-size-error nodrag" role="alert">
          {metadata.data
            ? isImage ? t("media.card.imageDimensionsRefreshFailed") : t("media.card.videoDimensionsRefreshFailed")
            : isImage ? t("media.card.imageDimensionsLoadFailed") : t("media.card.videoDimensionsLoadFailed")}
          <Button variant="ghost" type="button" onClick={() => void metadata.refetch()} disabled={metadata.isFetching}>
            {metadata.isFetching ? t("common.retrying") : t("media.card.retryDimensions")}</Button></p> : null}</>
        : <div className="media-card-empty">
          {busy ? <CanvasLoadingState label={status ?? t("media.card.generating")} /> : <>
            {isImage ? <ImageIcon className="media-empty-icon" size={44} />
              : isAudio ? <MusicNotes className="media-empty-icon" size={44} /> : <VideoCamera className="media-empty-icon" size={44} />}
            {status ? <div className="media-card-state" role="status">{status}
              {latest?.status === "SUCCEEDED" ? <small>{t("media.card.savedResultHint")}</small> : null}
              {latest?.errorCode ? <small>{taskErrorMessage(latest.errorCode) || latest.errorCode}</small> : null}
              {latest?.status === "UNKNOWN" && !latest.runId ? <small>{t("media.card.retryEditorHint")}</small> : null}
            </div> : null}
            {uploadFile ? <MediaCardUpload key={`${uploadFile.name}:${uploadFile.size}:${uploadFile.lastModified}`}
              artifact={artifact} item={item} initialFile={uploadFile} compact
              onDone={() => setUploadFile(null)} />
            : latest?.status === "UNKNOWN" || latest?.status === "BLOCKED" ?
              <Button variant="ghost" className="media-upload-button nodrag" type="button" onClick={onEdit}>
                <SlidersHorizontal size={15} />{t("media.card.viewTask")}</Button>
              : showSavedResults ? null : isImage || isAudio ? <Button variant="ghost" className="media-upload-button nodrag" type="button"
                onPointerDown={(event) => event.stopPropagation()}
                onClick={(event) => { event.stopPropagation(); uploadInput.current?.click(); }}>
              <UploadSimple size={15} />{isAudio ? t("media.card.uploadAudio") : t("media.uploadImage")}</Button>
              : <Button variant="ghost" className="media-upload-button nodrag" type="button" onClick={onEdit}>
                <Play size={15} />{t("media.generateVideo")}</Button>}
          </>}
          {draft.error ? <p className="media-card-error" role="alert">{t("media.card.draftLoadFailed")}<Button variant="ghost" type="button" className="nodrag" onClick={() => void draft.refetch()}>{t("common.retry")}</Button></p> : null}
          {tasks.error ? <p className="media-card-error" role="alert">{t("media.card.statusUnavailable")}<Button variant="ghost" type="button" className="nodrag" onClick={() => void tasks.refetch()}>{t("media.card.retryStatus")}</Button></p> : null}
        </div>}
  </ArtifactCardFrame>;
}

const OPERATION_TITLES: Record<ImageOperation, string> = {
  get SMART_EDIT() { return t("media.card.smartEdit"); }, get RELIGHT() { return t("media.card.lighting"); }, get OUTPAINT() { return t("media.card.outpaint"); },
  get THREE_VIEW() { return t("media.card.threeView"); }, get LAYER_SPLIT() { return t("media.card.splitLayers"); }, get EXPRESSION_EDIT() { return t("media.card.changeExpression"); },
  get REMOVE_BACKGROUND() { return t("media.card.removeBackground"); },
  get OBJECT_REMOVE() { return t("media.card.removeObject"); }, get VIEW_ANGLE() { return t("media.card.changeAngle"); },
  get DEPTH_MAP() { return t("media.card.extractDepth"); }, get UPSCALE() { return t("media.card.upscaleTitle"); }, get CROP() { return t("media.card.crop"); },
  get RESIZE() { return t("image.resize.title"); },
  get ROTATE() { return t("media.card.rotate"); }, get FLIP_HORIZONTAL() { return t("media.card.flipHorizontal"); }, get FLIP_VERTICAL() { return t("media.card.flipVertical"); },
};

const INSTRUCTION_COPY: Partial<Record<ImageOperation, { label: string; placeholder: string;
  required?: boolean }>> = {
  SMART_EDIT: { get label() { return t("media.card.editInstruction"); }, get placeholder() { return t("media.card.editInstructionPlaceholder"); }, required: true },
  OUTPAINT: { get label() { return t("media.card.additionalInstruction"); }, get placeholder() { return t("media.card.outpaintInstructionPlaceholder"); } },
  THREE_VIEW: { get label() { return t("media.card.subjectInstruction"); }, get placeholder() { return t("media.card.threeViewInstructionPlaceholder"); } },
  LAYER_SPLIT: { get label() { return t("media.card.layerInstruction"); }, get placeholder() { return t("media.card.layerInstructionPlaceholder"); } },
  EXPRESSION_EDIT: { get label() { return t("media.card.expression"); }, get placeholder() { return t("media.card.expressionInstructionPlaceholder"); }, required: true },
  REMOVE_BACKGROUND: { get label() { return t("media.card.subjectInstruction"); }, get placeholder() { return t("media.card.backgroundInstructionPlaceholder"); } },
  OBJECT_REMOVE: { get label() { return t("media.card.removeInstruction"); }, get placeholder() { return t("media.card.removeInstructionPlaceholder"); }, required: true },
  VIEW_ANGLE: { get label() { return t("media.card.additionalInstruction"); }, get placeholder() { return t("media.card.angleInstructionPlaceholder"); } },
};

const VIEW_ANGLE_OPTIONS = [
  ["FRONT", "media.card.front"], ["LEFT_THREE_QUARTER", "media.card.leftThreeQuarter"],
  ["RIGHT_THREE_QUARTER", "media.card.rightThreeQuarter"], ["LEFT_PROFILE", "media.card.leftProfile"],
  ["RIGHT_PROFILE", "media.card.rightProfile"], ["HIGH_ANGLE", "media.card.highAngle"],
  ["LOW_ANGLE", "media.card.lowAngle"], ["BACK", "media.card.back"],
] as const;

function ImageOperationPanel({ operation, initialThreeViewType, capabilities, busy, error, extraControls, submitDisabled,
  onClose, onSubmit }: {
  operation: ImageOperation; capabilities: MediaCapability[]; busy: boolean; error: Error | null;
  initialThreeViewType?: ThreeViewType; extraControls: ReactNode; submitDisabled: boolean;
  onClose: () => void;
  onSubmit: (parameters: RunImageOperationRequest["parameters"], instruction?: string,
    capabilityId?: string) => void;
}) {
  useLocale();
  const cloud = capabilities[0]?.adapterId !== "LOCAL_IMAGE_PROCESSOR";
  const [instruction, setInstruction] = useState("");
  const capabilityId = capabilities[0]?.id ?? "";
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
  const canSubmit = !busy && !submitDisabled && (!cloud || Boolean(selectedCapabilityId))
    && (!instructionCopy?.required || instruction.trim().length > 0);

  function submit() {
    if (!canSubmit) return;
    const parameters: RunImageOperationRequest["parameters"] = operation === "UPSCALE"
      ? capabilities[0]?.settings.runningHub ? {} : { scale } : operation === "THREE_VIEW"
        ? { aspectRatio: ratio, threeViewType }
        : operation === "OUTPAINT" ? { aspectRatio: ratio }
        : operation === "LAYER_SPLIT" ? { layerTarget }
          : operation === "VIEW_ANGLE" ? { viewAngle }
            : operation === "ROTATE" ? { quarterTurns: 1 } : {};
    onSubmit(parameters, instruction.trim(), selectedCapabilityId || undefined);
  }

  const threeViewLabel = THREE_VIEW_OPTIONS.find((option) => option.value === threeViewType)?.label;
  return <div className="media-operation-panel nodrag nowheel nopan" role="dialog"
    aria-label={operation === "THREE_VIEW" ? threeViewLabel : OPERATION_TITLES[operation]}
    onKeyDown={(event) => { if (event.key === "Escape") onClose(); }}>
    <header><div><strong>{operation === "THREE_VIEW" ? threeViewLabel : OPERATION_TITLES[operation]}</strong>
      <span>{cloud ? t("media.card.cloudProcessingHint") : t("media.card.localProcessingHint")}</span></div>
      <Button variant="ghost" type="button" aria-label={t("media.card.closeOperation")} onClick={onClose}><X size={16} /></Button>
    </header>
    {operation === "THREE_VIEW" ? <fieldset className="media-three-view-types">
      <legend>{t("media.card.outputType")}</legend>
      {THREE_VIEW_OPTIONS.map((option) => <label key={option.value}
        className={threeViewType === option.value ? "is-selected" : ""}>
        <input type="radio" name="three-view-type" value={option.value}
          checked={threeViewType === option.value} onChange={() => {
            setThreeViewType(option.value); setRatio(option.aspectRatio);
          }} />
        <option.icon size={18} /><span><strong>{option.label}</strong><small>{option.summary}</small></span>
      </label>)}
    </fieldset> : null}
    {operation === "LAYER_SPLIT" ? <label>{t("media.card.outputLayer")}<Select value={layerTarget}
      onChange={(event) => setLayerTarget(event.target.value as "FOREGROUND" | "BACKGROUND")}>
      <option value="FOREGROUND">{t("media.card.foregroundLayer")}</option>
      <option value="BACKGROUND">{t("media.card.backgroundLayer")}</option>
    </Select></label> : null}
    {operation === "VIEW_ANGLE" ? <label>{t("media.card.viewAngle")}<Select value={viewAngle}
      onChange={(event) => setViewAngle(event.target.value as typeof viewAngle)}>
      {VIEW_ANGLE_OPTIONS.map(([value, label]) => <option key={value} value={value}>{t(label)}</option>)}
    </Select></label> : null}
    {instructionCopy
      ? <label>{instructionCopy.label}
        <Textarea value={instruction} maxLength={4000}
          placeholder={instructionCopy.placeholder}
          onChange={(event) => setInstruction(event.target.value)} /></label> : null}
    {operation === "UPSCALE" && !capabilities[0]?.settings.runningHub ? <label>{t("media.card.upscaleFactor")}<Select value={scale}
      onChange={(event) => setScale(Number(event.target.value) as 2 | 4)}>
      <option value={2}>{t("media.card.upscaleDouble")}</option><option value={4}>{t("media.card.upscaleQuadruple")}</option>
    </Select></label> : null}
    {operation === "OUTPAINT" || operation === "THREE_VIEW" ? <label>{t("media.card.aspectRatio")}<Select value={ratio}
      onChange={(event) => setRatio(event.target.value as AspectRatio)}>
      {["1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9"].filter((value) =>
        !capabilities[0]?.supportedImageAspectRatios?.length || capabilities[0].supportedImageAspectRatios.some((ratio) => ratio === value)).map((value) =>
        <option key={value} value={value}>{value}</option>)}</Select></label> : null}
    {extraControls}
    {error ? <p role="alert">{error instanceof ApiError ? error.message : t("media.card.operationFailed")}</p> : null}
    <footer><Button variant="ghost" type="button" onClick={onClose}>{t("common.cancel")}</Button>
      <Button variant="ghost" type="button" className="is-primary" disabled={!canSubmit} onClick={submit}>
        {busy ? t("media.card.accepting") : t("media.card.startOperation")}</Button></footer>
  </div>;
}

/**
 * Image cards load the archived original so a resized node stays sharp; the archived 480px
 * preview is kept for later list-style surfaces and is not used here. Videos load only their
 * cover frame until the pointer enters the preview.
 */
function MediaPreview({ artifact, assetId, title, audioDescription, demo, selected, width, height, contentType }: {
  artifact: Artifact; assetId: string; title: string; audioDescription: string; demo: boolean; selected: boolean;
  width?: number; height?: number; contentType?: string;
}) {
  useLocale();
  const [failed, setFailed] = useState(false);
  const [expanded, setExpanded] = useState(false);

  if (artifact.kind === "AUDIO") return <AudioPlayer key={assetId} src={assetContentUrl(artifact.projectId, assetId)} title={title}
    description={audioDescription || title} selected={selected} demo={demo} />;
  if (artifact.kind === "VIDEO") return <VideoPreview key={assetId} title={title} demo={demo}
    src={assetContentUrl(artifact.projectId, assetId)} posterSrc={assetThumbnailUrl(artifact.projectId, assetId)}
    width={width} height={height} contentType={contentType} />;
  return <div className="media-card-preview">
    {!failed ? <img alt={t("media.card.mediaLabel", { "0": title, "1": t("media.card.preview") })}
        decoding="async" draggable={false} loading="lazy" onError={() => setFailed(true)}
        src={assetContentUrl(artifact.projectId, assetId)} />
        : <div className="media-card-empty"><ImageIcon size={36} /><span>{t("media.card.previewUnavailable")}</span>
          <Button variant="ghost" className="media-upload-button nodrag" type="button" onClick={() => setFailed(false)}>{t("media.card.retryPreview")}</Button></div>}
    {demo ? <span className="media-demo-badge">{t("media.card.mockAsset")}</span> : null}
    <Button variant="ghost" className="media-expand-button nodrag nowheel nopan" type="button" aria-label={t("media.card.upscale")} title={t("media.card.upscale")}
        onPointerDown={(event) => event.stopPropagation()} onMouseDown={(event) => event.stopPropagation()}
        onClick={(event) => { event.stopPropagation(); setExpanded(true); }}><ArrowsOutSimple size={19} /></Button>
    {expanded ? <MediaPreviewDialog kind="image" title={title} width={width} height={height}
      sourceUrl={assetContentUrl(artifact.projectId, assetId)} onClose={() => setExpanded(false)} /> : null}
  </div>;
}
