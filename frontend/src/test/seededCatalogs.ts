import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { MediaStyle, PromptDefinition } from "../shared/api/client";

function migration(name: string) {
  return readFileSync(
    resolve(dirname(fileURLToPath(import.meta.url)), "../../../backend/src/main/resources/db/migration", name),
    "utf8",
  );
}
const styleSeeds = migration("V1__initial_schema.sql");
const promptSeeds = migration("V5__unified_prompt_management.sql");
const viewSeeds = migration("V6__three_view_function_prompts.sql");
const reverseSeeds = migration("V24__image_reverse_prompt.sql");
const videoSeeds = migration("V40__video_prompt_optimization.sql");

// Read the metadata from real migrations so a fixture cannot silently diverge
// from the defaults on an installed instance. Prompt bodies remain test content.
export const seededPrompts: PromptDefinition[] = [
  ...[promptSeeds, viewSeeds, reverseSeeds, videoSeeds]
    .join("\n")
    .matchAll(
      /\('(00000000-0000-4000-8000-00000000040[1-8])', '([^']+)', '(AGENT|FUNCTION)', '([^']+)',\s*'([^']*)',/g,
    ),
].map(([, id, key, kind, name, description]) => ({
  id: id!,
  key: key!,
  kind: kind === "AGENT" ? "AGENT" : "FUNCTION",
  name: name!,
  description: description!,
  content: "Existing creative instructions.",
  builtIn: true,
  version: 1,
  createdAt: "2026-10-03T00:00:00Z",
  updatedAt: "2026-10-03T00:00:00Z",
}));

// Keep both catalog generations: customized bodies can retain the V6 metadata.
const characterUpgrade = migration("V61__character_view_prompt_style_flexible.sql");
export const upgradedCharacterPrompt: PromptDefinition = {
  ...seededPrompts.find(prompt => prompt.key === "image.three-view.character")!,
  description: characterUpgrade.match(/\$description\$([\s\S]*?)\$description\$/)![1]!,
  content: characterUpgrade.match(/\$content\$([\s\S]*?)\$content\$/)![1]!,
  version: 2,
};

export const seededStyles: MediaStyle[] = [
  ...styleSeeds.matchAll(/VALUES \('(00000000-0000-4000-8000-00000000030[1-8])', '([^']+)', '([^']+)', '([^']+)',/g),
].map(([, id, name, category, promptSuffix]) => ({
  id: id!,
  name: name!,
  category: category!,
  promptSuffix: promptSuffix!,
  enabled: true,
  builtIn: true,
  version: 0,
  thumbnailUrl: `/api/v1/media-styles/${id}/thumbnail?v=0`,
}));
