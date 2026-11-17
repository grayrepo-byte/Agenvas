import type { PromptDefinition } from "../../shared/api/client";
import { t, type MessageKey } from "../../shared/i18n";
import seededMetadata from "./builtInPromptDefaults.json";

type BuiltInPrompt = { name: MessageKey; description: MessageKey };
const originalMetadata: Readonly<Record<string, { name: string; description: string }>> = seededMetadata;
// A customized prompt body can retain the V6 description after the guarded default upgrade.
const legacyCharacterDescription = seededMetadata["image.three-view.character"].legacyDescription;
// Purpose keys are immutable built-in consumer identities. Compare each original
// seed field exactly so administrator metadata and all prompt content stay literal.
const builtInPrompts: Readonly<Record<string, BuiltInPrompt>> = {
  "image.costume-extract": { name: "costume.extract", description: "costume.promptDescription" },
  "agent.director": {
    name: "agent.defaults.director",
    description: "prompts.builtIn.director.description",
  },
  "text.generate": {
    name: "prompts.builtIn.textGeneration.name",
    description: "prompts.builtIn.textGeneration.description",
  },
  "image.three-view.character": {
    name: "media.card.characterViews",
    description: "prompts.builtIn.characterViews.description",
  },
  "image.three-view.face": {
    name: "media.card.faceViews",
    description: "prompts.builtIn.faceViews.description",
  },
  "image.three-view.prop": {
    name: "media.card.objectViews",
    description: "prompts.builtIn.propViews.description",
  },
  "image.three-view.scene-grid": {
    name: "media.card.sceneGrid",
    description: "prompts.builtIn.sceneGrid.description",
  },
  "image.reverse-prompt": {
    name: "image.reversePrompt.title",
    description: "prompts.builtIn.reversePrompt.description",
  },
  "video.optimize-prompt": {
    name: "video.optimize.title",
    description: "prompts.builtIn.videoOptimization.description",
  },
};

/** Display metadata only; never apply these labels to stored configuration or history. */
export function getPromptLabels(prompt: Pick<PromptDefinition, "key" | "builtIn" | "name" | "description">) {
  const defaults = prompt.builtIn && Object.hasOwn(builtInPrompts, prompt.key) ? builtInPrompts[prompt.key] : undefined;
  const original = originalMetadata[prompt.key];
  return {
    name: defaults && prompt.name === original?.name ? t(defaults.name) : prompt.name,
    description:
      defaults && prompt.description === original?.description
        ? t(defaults.description)
        : defaults && prompt.key === "image.three-view.character" && prompt.description === legacyCharacterDescription
          ? t("prompts.builtIn.characterViews.legacyDescription")
          : prompt.description,
  };
}
