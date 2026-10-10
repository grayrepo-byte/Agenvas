import { Check,Play,Star,Stop,X } from "@/shared/ui/icons";
import { useEffect,useLayoutEffect,useRef,useState,type Ref } from "react";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Alert,AlertDescription } from "../../shared/ui/primitives/alert";
import { Empty,EmptyDescription,EmptyHeader } from "../../shared/ui/primitives/empty";
import { Input } from "../../shared/ui/primitives/input";
import { Tabs,TabsContent,TabsList,TabsTrigger } from "../../shared/ui/primitives/tabs";
import { Select } from "../../shared/ui/Select";
import { VOICES,VOICE_LANGUAGE_LABEL_KEYS,VOICE_SCENE_LABEL_KEYS } from "./voiceCatalog";
import "./VoiceLibrary.css";
const PREFERENCE_KEY = "agenvas.voice-preferences.v1";
const RECENT_LIMIT = 8;
const POPOVER_VIEWPORT_MARGIN = 12;
const POPOVER_MAX_VIEWPORT_RATIO = 0.7;
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
export function VoiceLibrary({ selected, onSelect, onClose, containerRef }: {
  selected: string; onSelect: (speaker: string) => void; onClose: () => void;
  containerRef: Ref<HTMLDivElement>;
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
  const samples = useRef(new Map<string, HTMLAudioElement>());
  const activeSample = useRef<HTMLAudioElement | null>(null);
  const playAttempt = useRef(0);
  const [preview, setPreview] = useState<{ voiceId: string; name: string; status: "loading" | "playing" | "error" } | null>(null);
  useEffect(() => () => {
    playAttempt.current += 1;
    activeSample.current?.pause();
    if (activeSample.current) activeSample.current.currentTime = 0;
    activeSample.current = null;
  }, []);
  function remember(next: typeof preferences) {
    setPreferences(next);
    try { localStorage.setItem(PREFERENCE_KEY, JSON.stringify(next)); } catch { /* Optional UI preference only. */ }
  }
  function stopPreview() {
    playAttempt.current += 1;
    activeSample.current?.pause();
    if (activeSample.current) activeSample.current.currentTime = 0;
    activeSample.current = null;
    setPreview(null);
  }
  function close() { stopPreview(); onClose(); }
  function previewVoice(voice: typeof VOICES[number]) {
    const audio = samples.current.get(voice.id);
    if (!audio || !voice.previewUrl) return;
    if (activeSample.current === audio) { stopPreview(); return; }
    stopPreview();
    activeSample.current = audio;
    const attempt = ++playAttempt.current;
    setPreview({ voiceId: voice.id, name: voice.name, status: "loading" });
    // Start in the click handler to preserve the browser's user activation. Older
    // promises cannot overwrite a newer voice, a stopped preview, or a closed library.
    void audio.play().then(() => {
      if (playAttempt.current === attempt && activeSample.current === audio)
        setPreview({ voiceId: voice.id, name: voice.name, status: "playing" });
    }).catch(() => {
      if (playAttempt.current === attempt && activeSample.current === audio) {
        activeSample.current = null;
        audio.pause();
        setPreview({ voiceId: voice.id, name: voice.name, status: "error" });
      }
    });
  }
  const choices = VOICES.filter((voice) => (!language || voice.language === language)
    && (!scene || voice.scene === scene) && (!search || `${voice.name} ${voice.id}`.toLocaleLowerCase().includes(search.toLocaleLowerCase()))
    && (tab === "all" || (tab === "recent" ? preferences.recent : preferences.favorites).includes(voice.id)));
  return <div ref={(element) => {
    rootRef.current = element;
    if (typeof containerRef === "function") return containerRef(element);
    if (containerRef) containerRef.current = element;
  }} style={availableHeight !== undefined && availableHeight > 0 ? { maxHeight: availableHeight } : undefined}
    className="ui-popover-surface voice-library flex flex-col gap-3 overflow-hidden rounded-xl border p-4 text-popover-foreground" role="dialog" aria-label={t("media.editor.voiceLibrary")} onKeyDown={(event) => {
    if (event.key === "Escape") { event.stopPropagation(); close(); }
  }}>
    <div className="flex shrink-0 items-center justify-between gap-3">
      <strong className="text-sm font-semibold">{t("media.editor.voiceLibrary")}</strong>
      <Button variant="ghost" size="icon-sm" type="button" aria-label={t("audio.voiceLibrary.close")} onClick={close}><X /></Button>
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
            <Button variant={preview?.voiceId === voice.id && preview.status !== "error" ? "secondary" : "ghost"} size="icon-sm" type="button"
              aria-label={t(preview?.voiceId === voice.id && preview.status !== "error" ? "audio.voiceLibrary.stopPreviewNamed" : "audio.voiceLibrary.previewNamed", { "0": voice.name })}
              aria-busy={preview?.voiceId === voice.id && preview.status === "loading"}
              disabled={!voice.previewUrl} title={t(voice.previewUrl ? "audio.voiceLibrary.previewHint" : "audio.voiceLibrary.previewUnavailable")}
              onClick={() => previewVoice(voice)}>{preview?.voiceId === voice.id && preview.status !== "error" ? <Stop /> : <Play />}</Button>
          </div>;
        })}
          {!choices.length ? <Empty className="col-span-full"><EmptyHeader><EmptyDescription>{t("audio.voiceLibrary.empty")}</EmptyDescription></EmptyHeader></Empty> : null}
        </div>
      </TabsContent>
    </Tabs>
    <p className="shrink-0 text-xs leading-relaxed text-muted-foreground">{t("audio.voiceLibrary.previewUsageHint")}</p>
    {preview?.status === "error" ? <Alert variant="destructive" className="shrink-0"><AlertDescription>{t("audio.voiceLibrary.previewFailed", { "0": preview.name })}</AlertDescription></Alert>
      : preview ? <p className="shrink-0 text-sm text-muted-foreground" role="status">{t(preview.status === "loading" ? "audio.voiceLibrary.previewLoading" : "audio.voiceLibrary.previewPlaying", { "0": preview.name })}</p> : null}
    {VOICES.map((voice) => voice.previewUrl ? <audio hidden key={voice.id} data-voice-id={voice.id} src={voice.previewUrl} preload="none" ref={(audio) => {
      if (audio) samples.current.set(voice.id, audio); else samples.current.delete(voice.id);
    }} onWaiting={(event) => {
      if (activeSample.current === event.currentTarget) setPreview({ voiceId: voice.id, name: voice.name, status: "loading" });
    }} onPlaying={(event) => {
      if (activeSample.current === event.currentTarget) setPreview({ voiceId: voice.id, name: voice.name, status: "playing" });
    }} onEnded={(event) => {
      if (activeSample.current === event.currentTarget) stopPreview();
    }} onError={(event) => {
      if (activeSample.current === event.currentTarget) {
        stopPreview();
        setPreview({ voiceId: voice.id, name: voice.name, status: "error" });
      }
    }} /> : null)}
  </div>;
}
