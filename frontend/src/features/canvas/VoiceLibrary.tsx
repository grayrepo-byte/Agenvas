import { Check,Play,Star,X } from "@phosphor-icons/react";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useLayoutEffect,useRef,useState,type Ref } from "react";
import { assetContentUrl,listCanvasItems,listDirectMediaTasks,runMediaDraft } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Alert,AlertDescription } from "../../shared/ui/primitives/alert";
import { Empty,EmptyDescription,EmptyHeader } from "../../shared/ui/primitives/empty";
import { Input } from "../../shared/ui/primitives/input";
import { Tabs,TabsContent,TabsList,TabsTrigger } from "../../shared/ui/primitives/tabs";
import { Select } from "../../shared/ui/Select";
import { readContentText } from "./artifactContent";
import { AudioPlayer } from "./AudioPlayer";
import { prepareMediaNode,type PreparedMediaNode } from "./mediaNodeActions";
import { VOICES,VOICE_LANGUAGE_LABEL_KEYS,VOICE_SCENE_LABEL_KEYS } from "./voiceCatalog";
import "./VoiceLibrary.css";
const PREFERENCE_KEY = "agenvas.voice-preferences.v1";
const RECENT_LIMIT = 8;
const POPOVER_VIEWPORT_MARGIN = 12;
const POPOVER_MAX_VIEWPORT_RATIO = 0.7;
const PREVIEW_REFRESH_INTERVAL_MS = 2000;
const TERMINAL_PREVIEW_STATUSES = new Set(["SUCCEEDED", "FAILED", "BLOCKED", "UNKNOWN", "CANCELED"]);
function initialPreferences(): { favorites: string[]; recent: string[] } {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(PREFERENCE_KEY) ?? "null");
    if (saved && typeof saved === "object" && "favorites" in saved && "recent" in saved
        && Array.isArray(saved.favorites) && Array.isArray(saved.recent)) return {
      favorites: saved.favorites.filter((id): id is string => typeof id === "string"),
      recent: saved.recent.filter((id): id is string => typeof id === "string").slice(0, RECENT_LIMIT) };
  } catch { /* Preferences are optional; storage restrictions never block a choice. */ }
  return { favorites: [], recent: [] };
}
export function VoiceLibrary({ selected, onSelect, onClose, projectId, canvasItemId, capabilityId, mock, containerRef }: {
  selected: string; onSelect: (speaker: string) => void; onClose: () => void;
  projectId: string; canvasItemId: string; capabilityId?: string; mock: boolean; containerRef: Ref<HTMLDivElement>;
}) {
  useLocale();
  const rootRef = useRef<HTMLDivElement>(null);
  const [availableHeight, setAvailableHeight] = useState<number>();
  useLayoutEffect(() => {
    const measure = () => {
      const root = rootRef.current;
      if (!root) return;
      const headerBottom = document.querySelector(".workspace-header")?.getBoundingClientRect().bottom ?? 0;
      const usable = root.getBoundingClientRect().bottom - headerBottom - POPOVER_VIEWPORT_MARGIN;
      setAvailableHeight(Math.min(window.innerHeight * POPOVER_MAX_VIEWPORT_RATIO, usable));
    };
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, []);
  const [search, setSearch] = useState("");
  const [language, setLanguage] = useState("");
  const [scene, setScene] = useState("");
  const [tab, setTab] = useState<"all" | "recent" | "favorites">("all");
  const [preferences, setPreferences] = useState(initialPreferences);
  const [previewNode, setPreviewNode] = useState<{ itemId: string; artifactId: string } | null>(null);
  const previewProgress = useRef(new Map<string, PreparedMediaNode>());
  const queryClient = useQueryClient();
  function remember(next: typeof preferences) {
    setPreferences(next);
    try { localStorage.setItem(PREFERENCE_KEY, JSON.stringify(next)); } catch { /* Optional UI preference only. */ }
  }
  const preview = useMutation({ mutationFn: async (speaker: string) => {
    const progress = previewProgress.current.get(speaker) ?? { createKey: crypto.randomUUID(), itemId: crypto.randomUUID() };
    previewProgress.current.set(speaker, progress);
    const node = await prepareMediaNode(projectId, canvasItemId, "AUDIO", t("audio.voiceLibrary.preview"), {
      prompt: speaker.startsWith("en_") ? 'Say warmly: "Welcome. Let us create something wonderful together."'
        : '用自然亲切的语气说：“你好，欢迎来到创作画布，让我们开始创作吧。”',
      parameters: { speaker, speechRate: 0, loudnessRate: 0, pitchRate: 0 }, capabilityId: capabilityId ?? null,
      durationSeconds: null, videoInputMode: null, mediaInputs: [], mentions: [],
    }, progress);
    await runMediaDraft(projectId, node.artifactId, { canvasItemId: node.canvasItemId, expectedDraftVersion: node.expectedDraftVersion }, progress.createKey);
    setPreviewNode({ itemId: node.canvasItemId, artifactId: node.artifactId });
    await queryClient.invalidateQueries({ queryKey: ["canvas", projectId] });
  } });
  const previewTasks = useQuery({ queryKey: ["voice-preview-task", projectId, previewNode?.itemId],
    queryFn: () => listDirectMediaTasks(projectId, previewNode!.artifactId, previewNode!.itemId), enabled: Boolean(previewNode),
    refetchInterval: (query) => query.state.data?.some((task) => TERMINAL_PREVIEW_STATUSES.has(task.status))
      ? false : PREVIEW_REFRESH_INTERVAL_MS });
  const previewCanvas = useQuery({ queryKey: ["voice-preview-node", projectId, previewNode?.itemId],
    queryFn: () => listCanvasItems(projectId), enabled: Boolean(previewNode), refetchInterval: (query) => query.state.data?.items.some((item) => item.id === previewNode?.itemId && item.selectedVersion)
      || previewTasks.data?.some((task) => task.status !== "SUCCEEDED" && TERMINAL_PREVIEW_STATUSES.has(task.status)) ? false : PREVIEW_REFRESH_INTERVAL_MS });
  const previewItem = previewCanvas.data?.items.find((item) => item.id === previewNode?.itemId);
  const assetId = readContentText(previewItem?.selectedVersion?.content, "assetId");
  const failedTask = previewTasks.data?.find((task) => ["FAILED", "BLOCKED", "UNKNOWN", "CANCELED"].includes(task.status));
  const choices = VOICES.filter((voice) => (!language || voice.language === language)
    && (!scene || voice.scene === scene) && (!search || `${voice.name} ${voice.id}`.toLocaleLowerCase().includes(search.toLocaleLowerCase()))
    && (tab === "all" || (tab === "recent" ? preferences.recent : preferences.favorites).includes(voice.id)));
  return <div ref={(element) => {
    rootRef.current = element;
    if (typeof containerRef === "function") return containerRef(element);
    if (containerRef) containerRef.current = element;
  }} style={availableHeight !== undefined && availableHeight > 0 ? { maxHeight: availableHeight } : undefined}
    className="ui-popover-surface voice-library flex flex-col gap-3 overflow-hidden rounded-xl border p-4 text-popover-foreground" role="dialog" aria-label={t("media.editor.voiceLibrary")} onKeyDown={(event) => {
    if (event.key === "Escape") { event.stopPropagation(); onClose(); }
  }}>
    <div className="flex shrink-0 items-center justify-between gap-3">
      <strong className="text-sm font-semibold">{t("media.editor.voiceLibrary")}</strong>
      <Button variant="ghost" size="icon-sm" type="button" aria-label={t("audio.voiceLibrary.close")} onClick={onClose}><X /></Button>
    </div>
    <Input className="shrink-0" autoFocus type="search" aria-label={t("audio.voiceLibrary.search")} placeholder={t("audio.voiceLibrary.searchPlaceholder")} value={search} onChange={(event) => setSearch(event.target.value)} />
    <div className="grid shrink-0 grid-cols-2 gap-2">
      <Select aria-label={t("audio.voiceLibrary.language")} value={language} onChange={(event) => setLanguage(event.target.value)}>
        <option value="">{t("audio.voiceLibrary.allLanguages")}</option>
        {Array.from(new Set(VOICES.map((voice) => voice.language))).map((value) => <option key={value} value={value}>{t(VOICE_LANGUAGE_LABEL_KEYS[value])}</option>)}
      </Select>
      <Select aria-label={t("audio.voiceLibrary.scene")} value={scene} onChange={(event) => setScene(event.target.value)}>
        <option value="">{t("audio.voiceLibrary.allScenes")}</option>
        {Array.from(new Set(VOICES.map((voice) => voice.scene))).map((value) => <option key={value} value={value}>{t(VOICE_SCENE_LABEL_KEYS[value])}</option>)}
      </Select>
    </div>
    <Tabs className="min-h-0 shrink" value={tab} onValueChange={(value) => {
      if (value === "all" || value === "recent" || value === "favorites") setTab(value);
    }}>
      <TabsList className="shrink-0" aria-label={t("audio.voiceLibrary.scope")}>
        <TabsTrigger value="all">{t("common.all")}</TabsTrigger>
        <TabsTrigger value="recent">{t("audio.voiceLibrary.recent")}</TabsTrigger>
        <TabsTrigger value="favorites">{t("audio.voiceLibrary.favorites")}</TabsTrigger>
      </TabsList>
      <TabsContent value={tab} className="flex min-h-0 flex-col gap-2">
        <Button variant={!selected ? "outline" : "ghost"} size="sm" type="button" className="w-full justify-between" aria-pressed={!selected} onClick={() => onSelect("")}>
          {t("audio.voiceLibrary.automaticVoice")}{!selected ? <Check data-icon="inline-end" /> : null}
        </Button>
        <div className="voice-library-list">{choices.map((voice) => {
          const isSelected = selected === voice.id;
          const isFavorite = preferences.favorites.includes(voice.id);
          return <div className="flex min-w-0 items-center gap-1" key={voice.id}>
            <Button variant={isSelected ? "outline" : "ghost"} type="button" className="h-auto min-w-0 flex-1 justify-start py-2" aria-pressed={isSelected} onClick={() => {
              remember({ ...preferences, recent: [voice.id, ...preferences.recent.filter((id) => id !== voice.id)].slice(0, RECENT_LIMIT) }); onSelect(voice.id);
            }}>
              <span aria-hidden="true" className="flex size-8 shrink-0 items-center justify-center rounded-full border bg-muted text-xs text-muted-foreground">{voice.name.slice(0, 1)}</span>
              <span className="min-w-0 flex-1 text-left">
                <strong className="block truncate text-sm font-medium">{voice.name}</strong>
                <small className="block truncate text-xs text-muted-foreground">{t(VOICE_LANGUAGE_LABEL_KEYS[voice.language])} · {t(VOICE_SCENE_LABEL_KEYS[voice.scene])}</small>
              </span>
              {isSelected ? <Check data-icon="inline-end" /> : null}
            </Button>
            <Button variant={isFavorite ? "secondary" : "ghost"} size="icon-sm" type="button" aria-label={t("audio.voiceLibrary.favoriteNamed", { "0": voice.name })} aria-pressed={isFavorite} onClick={() => remember({ ...preferences,
              favorites: isFavorite ? preferences.favorites.filter((id) => id !== voice.id) : [...preferences.favorites, voice.id] })}>
              <Star weight={isFavorite ? "fill" : "regular"} />
            </Button>
            <Button variant="ghost" size="icon-sm" type="button" aria-label={t("audio.voiceLibrary.previewNamed", { "0": voice.name })} disabled={!capabilityId || preview.isPending || mock}
              title={mock ? t("audio.voiceLibrary.mockPreviewHint") : t("audio.voiceLibrary.previewBillingHint")}
              onClick={() => preview.mutate(voice.id)}><Play /></Button>
          </div>;
        })}
          {!choices.length ? <Empty className="col-span-full"><EmptyHeader><EmptyDescription>{t("audio.voiceLibrary.empty")}</EmptyDescription></EmptyHeader></Empty> : null}
        </div>
      </TabsContent>
    </Tabs>
    <p className="shrink-0 text-xs leading-relaxed text-muted-foreground">{t("audio.voiceLibrary.previewUsageHint")}</p>
    {preview.isPending ? <p className="shrink-0 text-sm text-muted-foreground" role="status">{t("audio.voiceLibrary.previewSubmitting")}</p> : null}
    {preview.error ? <Alert variant="destructive" className="shrink-0"><AlertDescription>{t("audio.voiceLibrary.previewSubmitFailed", { "0": preview.error.message })}</AlertDescription></Alert> : null}
    {assetId ? <AudioPlayer src={assetContentUrl(projectId, assetId)} title={t("audio.voiceLibrary.preview")} /> : previewNode ? <p className="shrink-0 text-sm text-muted-foreground" role="status">{failedTask ? t("audio.voiceLibrary.previewLabel", { "0": failedTask.status === "UNKNOWN" ? t("audio.voiceLibrary.previewUnknown") : t("audio.voiceLibrary.previewFailed") }) : t("audio.voiceLibrary.previewQueued")}</p> : null}
  </div>;
}
