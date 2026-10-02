import { Check,Play,Star,X } from "@phosphor-icons/react";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useLayoutEffect,useRef,useState,type Ref } from "react";
import { assetContentUrl,listCanvasItems,listDirectMediaTasks,runMediaDraft } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import { readContentText } from "./artifactContent";
import { AudioPlayer } from "./AudioPlayer";
import { prepareMediaNode,type PreparedMediaNode } from "./mediaNodeActions";
import { VOICES } from "./voiceCatalog";
import "./VoiceLibrary.css";
const PREFERENCE_KEY = "agenvas.voice-preferences.v1";
const RECENT_LIMIT = 8;
const POPOVER_VIEWPORT_MARGIN = 12;
const POPOVER_MAX_VIEWPORT_RATIO = 0.7;
const VOICE_LIST_MAX_HEIGHT = 380;
const VOICE_LIBRARY_CHROME_HEIGHT = 240;
const VOICE_LIST_MIN_HEIGHT = 100;
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
    const node = await prepareMediaNode(projectId, canvasItemId, "AUDIO", t("音色试听"), {
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
    className="ui-popover-surface voice-library" role="dialog" aria-label={t("音色库")} onKeyDown={(event) => {
    if (event.key === "Escape") { event.stopPropagation(); onClose(); }
  }}>
    <div className="voice-library-heading"><strong>{t("音色库")}</strong><Button variant="ghost" type="button" aria-label={t("关闭音色库")} onClick={onClose}><X size={17} /></Button></div>
    <Input autoFocus type="search" aria-label={t("搜索音色")} placeholder={t("搜索音色名称")} value={search} onChange={(event) => setSearch(event.target.value)} />
    <div className="voice-library-filters"><Select variant="ghost" aria-label={t("音色语言")} value={language} onChange={(event) => setLanguage(event.target.value)}>
      <option value="">{t("全部语言")}</option>{Array.from(new Set(VOICES.map((voice) => voice.language))).map((value) => <option key={value} value={value}>{t(value)}</option>)}</Select>
      <Select variant="ghost" aria-label={t("音色场景")} value={scene} onChange={(event) => setScene(event.target.value)}><option value="">{t("全部场景")}</option>
        {Array.from(new Set(VOICES.map((voice) => voice.scene))).map((value) => <option key={value} value={value}>{t(value)}</option>)}</Select></div>
    <div className="voice-library-tabs" role="group" aria-label={t("音色范围")}>
      {([{ key: "all", label: t("全部") }, { key: "recent", label: t("最近使用") }, { key: "favorites", label: t("我的收藏") }] as const).map((item) =>
        <Button variant="ghost" key={item.key} type="button" aria-pressed={tab === item.key} onClick={() => setTab(item.key)}>{item.label}</Button>)}
    </div>
    <Button variant="ghost" type="button" className="voice-library-automatic" aria-pressed={!selected} onClick={() => onSelect("")}>{t("由提示词决定音色")}{!selected ? <Check size={15} /> : null}</Button>
    <div className="voice-library-list" style={availableHeight !== undefined && availableHeight > 0
      ? { maxHeight: Math.max(VOICE_LIST_MIN_HEIGHT, Math.min(VOICE_LIST_MAX_HEIGHT, availableHeight - VOICE_LIBRARY_CHROME_HEIGHT)) } : undefined}>{choices.map((voice) => <div className="voice-library-row" key={voice.id}>
      <Button variant="ghost" type="button" className="voice-library-choice" aria-pressed={selected === voice.id} onClick={() => {
        remember({ ...preferences, recent: [voice.id, ...preferences.recent.filter((id) => id !== voice.id)].slice(0, RECENT_LIMIT) }); onSelect(voice.id);
      }}><span className="voice-library-avatar">{voice.name.slice(0, 1)}</span><span><strong>{voice.name}</strong><small>{t(voice.language)} · {t(voice.scene)}</small></span>{selected === voice.id ? <Check size={15} /> : null}</Button>
      <Button variant="ghost" type="button" aria-label={t("收藏 {0}", { "0": voice.name })} aria-pressed={preferences.favorites.includes(voice.id)} onClick={() => remember({ ...preferences,
        favorites: preferences.favorites.includes(voice.id) ? preferences.favorites.filter((id) => id !== voice.id) : [...preferences.favorites, voice.id] })}><Star size={17} weight={preferences.favorites.includes(voice.id) ? "fill" : "regular"} /></Button>
      <Button variant="ghost" type="button" aria-label={t("生成试听 {0}", { "0": voice.name })} disabled={!capabilityId || preview.isPending || mock}
        title={mock ? t("Mock 只生成提示音，不提供音色试听") : t("新增试听音频节点并运行，按所选模型计费")}
        onClick={() => preview.mutate(voice.id)}><Play size={16} /></Button>
    </div>)}{!choices.length ? <p>{t("没有匹配的音色。")}</p> : null}</div>
    <p className="voice-library-help">{t("试听会新增音频节点并运行，使用所选模型，费用计入项目用量。")}</p>
    {preview.isPending ? <p role="status">{t("正在提交试听…")}</p> : null}
    {preview.error ? <p role="alert">{t("试听提交失败：{0}", { "0": preview.error.message })}</p> : null}
    {assetId ? <AudioPlayer src={assetContentUrl(projectId, assetId)} title={t("音色试听")} /> : previewNode ? <p role="status">{failedTask ? t("试听{0}", { "0": failedTask.status === "UNKNOWN" ? t("结果未知，请在试听节点显式重试") : t("未完成，请在试听节点查看任务") }) : t("试听已排队，可在试听节点取消。")}</p> : null}
  </div>;
}
