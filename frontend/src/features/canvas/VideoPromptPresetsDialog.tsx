import { ArrowRight, FilmStrip, MagnifyingGlass } from "@/shared/ui/icons";
import { useCallback, useId, useMemo, useRef, useState } from "react";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { appendVideoPrompt, filterVideoPromptPresets, VIDEO_PROMPT_CATEGORIES, VIDEO_PROMPT_PRESETS, VIDEO_PROMPT_SCENARIOS, type VideoPromptPreset, type VideoPromptScenario } from "./videoPromptPresets";
import { VideoPromptPresetList } from "./VideoPromptPresetList";
import { VideoCameraMotionPreview } from "./VideoCameraMotionPreview";
import "./VideoPromptPresetsDialog.css";

export function VideoPromptPresetsDialog({ prompt, maxLength, onApply, onClose }: {
  prompt: string;
  maxLength: number;
  onApply: (presetPrompt: string) => boolean;
  onClose: () => void;
}) {
  const locale = useLocale();
  const searchId = useId();
  const scenarioId = useId();
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("all");
  const [scenario, setScenario] = useState<VideoPromptScenario | "all">("all");
  const [applyRejected, setApplyRejected] = useState(false);
  const [resultsResetCount, setResultsResetCount] = useState(0);
  const [selectedId, setSelectedId] = useState(VIDEO_PROMPT_PRESETS.find((preset) => /over.the.shoulder/i.test(preset.englishName))?.id ?? "");
  const detailsRef = useRef<HTMLElement>(null);
  // Category counts reflect the current scene/search, without the active category hiding alternatives.
  const matchingPresets = useMemo(() => filterVideoPromptPresets(query, "all", scenario), [query, scenario]);
  const results = useMemo(() => category === "all" ? matchingPresets : matchingPresets.filter((preset) => preset.category === category), [matchingPresets, category]);
  const categoryCounts = useMemo(() => {
    const counts = new Map<string, number>();
    for (const preset of matchingPresets) counts.set(preset.category, (counts.get(preset.category) ?? 0) + 1);
    return counts;
  }, [matchingPresets]);
  const selectPreset = useCallback((preset: VideoPromptPreset) => {
    setSelectedId(preset.id);
    setApplyRejected(false);
    if (window.matchMedia?.("(max-width: 700px)").matches) detailsRef.current?.scrollIntoView?.({ block: "start", behavior: "instant" });
  }, []);
  const filtered = query.trim() !== "" || category !== "all" || scenario !== "all";
  const selected = results.find((preset) => preset.id === selectedId) ?? results[0];
  const copy = selected?.copy[locale];
  const nextLength = selected ? appendVideoPrompt(prompt, selected.prompt).length : 0;
  const tooLong = nextLength > maxLength;
  // Clicking an already active filter still returns to the start of its results.
  function resetResults() { setApplyRejected(false); setResultsResetCount((count) => count + 1); }
  function clearFilters() { setQuery(""); setCategory("all"); setScenario("all"); resetResults(); }

  return <Dialog title={t("video.presets.title")} description={t("video.presets.description")} className="video-presets-dialog"
    onClose={onClose} onSubmit={(event) => { event.preventDefault(); }} footer={<>
      <div className="video-presets-footer-note"><span role={applyRejected ? "alert" : undefined}>{applyRejected ? t("video.presets.addFailed") : selected ? t("video.presets.length", { count: nextLength, max: maxLength }) : t("video.presets.noSelection")}</span>
        <small>{t("video.presets.appendHint")}</small></div>
      <Button type="button" variant="outline" onClick={onClose}>{t("common.cancel")}</Button>
      <Button type="button" disabled={!selected || tooLong} onClick={() => { if (selected && !tooLong) { if (onApply(selected.prompt)) onClose(); else setApplyRejected(true); } }}>
        <ArrowRight data-icon="inline-start" />{t("video.presets.apply")}
      </Button>
    </>}>
    <div className="video-presets-toolbar">
      <Field className="video-presets-search"><FieldLabel htmlFor={searchId} className="sr-only">{t("video.presets.search")}</FieldLabel>
        <MagnifyingGlass aria-hidden="true" /><Input id={searchId} type="search" value={query} placeholder={t("video.presets.searchPlaceholder")}
          onChange={(event) => { setQuery(event.target.value); resetResults(); }} /></Field>
      <Badge variant="secondary">{t("video.presets.total", { count: VIDEO_PROMPT_PRESETS.length })}</Badge>
    </div>
    <div className="video-presets-scenarios">
      <span id={scenarioId}>{t("video.presets.scenarios")}</span>
      <ToggleGroup type="single" variant="outline" size="sm" spacing={1} className="video-presets-scenario-options" value={scenario}
        aria-labelledby={scenarioId} onValueChange={(value) => {
          setScenario(VIDEO_PROMPT_SCENARIOS.find((item) => item.id === value)?.id ?? "all"); resetResults();
        }}>
        <ToggleGroupItem value="all">{t("video.presets.scenario.all")}</ToggleGroupItem>
        {VIDEO_PROMPT_SCENARIOS.map((item) => <ToggleGroupItem key={item.id} value={item.id}>{t(item.label)}</ToggleGroupItem>)}
      </ToggleGroup>
    </div>
    <div className="video-presets-workspace">
      <nav className="video-presets-categories" aria-label={t("video.presets.categories")}>
        <Button type="button" variant={category === "all" ? "secondary" : "ghost"} aria-pressed={category === "all"} onClick={() => { setCategory("all"); resetResults(); }}>
          <FilmStrip data-icon="inline-start" /><span>{t("video.presets.all")}</span><small>{matchingPresets.length}</small>
        </Button>
        {VIDEO_PROMPT_CATEGORIES.map((item, index) => <Button key={item.id} type="button" variant={category === item.id ? "secondary" : "ghost"}
          aria-pressed={category === item.id} onClick={() => { setCategory(item.id); resetResults(); }}>
          <span className="video-presets-category-number" aria-hidden="true">{String(index + 1).padStart(2, "0")}</span><span>{t(item.label)}</span>
          <small>{categoryCounts.get(item.id) ?? 0}</small>
        </Button>)}
      </nav>
      <div className="video-presets-catalog">
        <div className="video-presets-result-count"><p role="status">{t("video.presets.resultCount", { count: results.length })}</p>
          {filtered && results.length > 0 ? <Button type="button" variant="ghost" size="sm" onClick={clearFilters}>{t("video.presets.reset")}</Button> : null}
        </div>
        <VideoPromptPresetList presets={results} selectedId={selected?.id} onSelect={selectPreset}
          resetKey={JSON.stringify([query, category, scenario, resultsResetCount])} onReset={clearFilters} />
      </div>
      {selected && copy ? <section className="video-presets-detail" ref={detailsRef} aria-label={t("video.presets.details")} key={selected.id}>
        <div className="video-presets-detail-heading"><Badge variant="outline">{t(VIDEO_PROMPT_CATEGORIES.find((item) => item.id === selected.category)?.label ?? "video.presets.all")}</Badge>
          <span>{t("video.presets.shotNumber", { count: String(VIDEO_PROMPT_PRESETS.indexOf(selected) + 1).padStart(3, "0") })}</span></div>
        <h3>{copy.name}</h3><p className="video-presets-english-name" lang="en">{selected.englishName}</p>
        <VideoCameraMotionPreview key={selected.id} preset={selected} />
        <dl className="video-presets-notes"><div><dt>{t("video.presets.conditions")}</dt><dd>{copy.conditions}</dd></div>
          <div><dt>{t("video.presets.effect")}</dt><dd>{copy.effect}</dd></div><div><dt>{t("video.presets.caution")}</dt><dd>{copy.caution}</dd></div></dl>
        <div className="video-presets-prompt-heading"><h4>{t("video.presets.prompt")}</h4><Badge variant="secondary">EN</Badge></div>
        <p className="video-presets-prompt" lang="en">{selected.prompt}</p>
        <p className="video-presets-model-note">{t("video.presets.modelHint")}</p>
        {tooLong ? <Alert variant="destructive"><AlertDescription>{t("video.presets.tooLong", { count: nextLength, max: maxLength })}</AlertDescription></Alert> : null}
      </section> : null}
    </div>
  </Dialog>;
}
