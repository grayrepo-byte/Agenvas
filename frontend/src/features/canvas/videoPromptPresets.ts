import type { Locale, MessageKey } from "../../shared/i18n";
import firstPresets from "./video-prompt-presets-a.json";
import secondPresets from "./video-prompt-presets-b.json";
import supplementalPresets from "./video-prompt-presets-c.json";
import { VIDEO_PROMPT_SCENARIOS_BY_PRESET, type VideoPromptScenario } from "./videoPromptPresetScenarios";

export { appendVideoPrompt } from "./videoPromptAppend";
export type { VideoPromptScenario } from "./videoPromptPresetScenarios";

export const VIDEO_PROMPT_CATEGORIES = [
  { id: "framing", label: "video.presets.category.framing" },
  { id: "rotation", label: "video.presets.category.rotation" },
  { id: "dolly", label: "video.presets.category.dolly" },
  { id: "tracking", label: "video.presets.category.tracking" },
  { id: "orbit", label: "video.presets.category.orbit" },
  { id: "vertical", label: "video.presets.category.vertical" },
  { id: "lens", label: "video.presets.category.lens" },
  { id: "handheld", label: "video.presets.category.handheld" },
  { id: "aerial", label: "video.presets.category.aerial" },
  { id: "reveal", label: "video.presets.category.reveal" },
  { id: "sequence", label: "video.presets.category.sequence" },
] as const satisfies readonly { id: string; label: MessageKey }[];

export const VIDEO_PROMPT_SCENARIOS = [
  { id: "dialogue", label: "video.presets.scenario.dialogue" },
  { id: "product", label: "video.presets.scenario.product" },
  { id: "entrance", label: "video.presets.scenario.entrance" },
  { id: "chase", label: "video.presets.scenario.chase" },
  { id: "landscape", label: "video.presets.scenario.landscape" },
] as const satisfies readonly { id: VideoPromptScenario; label: MessageKey }[];

export type VideoPromptCategory = typeof VIDEO_PROMPT_CATEGORIES[number]["id"];
export type VideoPromptPreset = Readonly<{
  id: string;
  category: string;
  /** Authored use cases are independent of the renderer's geometric scene. */
  scenarios: readonly VideoPromptScenario[];
  englishName: string;
  /** Fixed creative text, independent of the UI locale and provider configuration. */
  prompt: string;
  copy: Readonly<Record<Locale, Readonly<{ name: string; conditions: string; effect: string; caution: string }>>>;
}>;

/** Bundled, authored data stays available in self-hosted and offline Mock installations. */
export const VIDEO_PROMPT_PRESETS: readonly VideoPromptPreset[] = [...firstPresets, ...secondPresets, ...supplementalPresets]
  .map((preset) => ({ ...preset, scenarios: VIDEO_PROMPT_SCENARIOS_BY_PRESET[preset.id] ?? [] }));

const searchTextByPreset = new WeakMap<VideoPromptPreset, string>();

/** Compile each immutable bundled guide only when searched, then reuse it across filters. */
function presetSearchText(preset: VideoPromptPreset): string {
  const cached = searchTextByPreset.get(preset);
  if (cached !== undefined) return cached;
  const searchable = [preset.englishName, preset.prompt,
    ...Object.values(preset.copy).flatMap((copy) => [copy.name, copy.conditions, copy.effect, copy.caution])]
    .join(" ").normalize("NFKC").toLocaleLowerCase();
  searchTextByPreset.set(preset, searchable);
  return searchable;
}

export function filterVideoPromptPresets(query: string, category: string, scenario: VideoPromptScenario | "all" = "all"): readonly VideoPromptPreset[] {
  const terms = query.normalize("NFKC").trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  return VIDEO_PROMPT_PRESETS.filter((preset) => {
    if (category !== "all" && preset.category !== category) return false;
    if (scenario !== "all" && !preset.scenarios.includes(scenario)) return false;
    if (terms.length === 0) return true;
    const searchable = presetSearchText(preset);
    return terms.every((term) => searchable.includes(term));
  });
}
