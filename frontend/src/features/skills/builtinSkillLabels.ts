import type { CreativeSkill, SkillVersion } from "../../shared/api/client";
import { t, type MessageKey } from "../../shared/i18n";
import metadata from "./builtinSkillMetadata.json";

type CatalogueIdentity = Pick<CreativeSkill, "id" | "builtin" | "builtinSource" | "title" | "description">;
type VersionMetadata = Pick<SkillVersion, "skillId" | "name" | "description">;
const MESSAGE_KEYS: Readonly<Record<string, { title: MessageKey; description: MessageKey }>> = {
  "short-drama-assets": {
    title: "skills.builtin.visualAssets.title",
    description: "skills.builtin.visualAssets.description",
  },
  "short-drama-develop": {
    title: "skills.builtin.storyDevelopment.title",
    description: "skills.builtin.storyDevelopment.description",
  },
  "short-drama-image-prompts": {
    title: "skills.builtin.imagePrompts.title",
    description: "skills.builtin.imagePrompts.description",
  },
  "short-drama-novel-analyze": {
    title: "skills.builtin.sourceAnalysis.title",
    description: "skills.builtin.sourceAnalysis.description",
  },
  "short-drama-review": {
    title: "skills.builtin.creativeReview.title",
    description: "skills.builtin.creativeReview.description",
  },
  "short-drama-storyboard": {
    title: "skills.builtin.storyboards.title",
    description: "skills.builtin.storyboards.description",
  },
  "short-drama-video-prompts": {
    title: "skills.builtin.videoPrompts.title",
    description: "skills.builtin.videoPrompts.description",
  },
  "short-drama-write": {
    title: "skills.builtin.episodeScripts.title",
    description: "skills.builtin.episodeScripts.description",
  },
  "h3-prompt-writing": {
    title: "skills.builtin.h3Prompts.title",
    description: "skills.builtin.h3Prompts.description",
  },
  "minimalist-product-ad-generator": {
    title: "skills.builtin.productAds.title",
    description: "skills.builtin.productAds.description",
  },
  "3d-animation-short-generator": {
    title: "skills.builtin.animatedShorts.title",
    description: "skills.builtin.animatedShorts.description",
  },
  "brand-promo-video-generator": {
    title: "skills.builtin.brandVideos.title",
    description: "skills.builtin.brandVideos.description",
  },
  "music-video-subtitle-generator": {
    title: "skills.builtin.musicVideos.title",
    description: "skills.builtin.musicVideos.description",
  },
  "co-op-game-intro-generator": {
    title: "skills.builtin.gameIntros.title",
    description: "skills.builtin.gameIntros.description",
  },
  "paper-collage-explainer-generator": {
    title: "skills.builtin.collageExplainers.title",
    description: "skills.builtin.collageExplainers.description",
  },
  "papercraft-stop-motion-explainer": {
    title: "skills.builtin.papercraftExplainers.title",
    description: "skills.builtin.papercraftExplainers.description",
  },
  "handdrawn-live-video-generator": {
    title: "skills.builtin.handdrawnVideo.title",
    description: "skills.builtin.handdrawnVideo.description",
  },
};

/** A package source and its exact original title identify display metadata, never personal copies. */
function packagedMetadata(skill: CatalogueIdentity) {
  if (!skill.builtin || !skill.builtinSource) return undefined;
  return metadata.find(
    entry =>
      entry.sourceName === skill.builtinSource!.name &&
      entry.repository === skill.builtinSource!.repository &&
      entry.title === skill.title,
  );
}

export function getSkillLabels(skill: CatalogueIdentity) {
  const original = packagedMetadata(skill);
  const keys = original && MESSAGE_KEYS[original.name];
  return {
    title: keys ? t(keys.title) : skill.title,
    description: keys && skill.description === original.description ? t(keys.description) : skill.description,
  };
}

/** Versions do not carry origin. Require their catalogue identity before localizing a preview. */
export function getSkillVersionLabels(version: VersionMetadata, skill?: CatalogueIdentity) {
  const original = skill?.id === version.skillId ? packagedMetadata(skill) : undefined;
  const keys = original?.name === version.name ? MESSAGE_KEYS[original.name] : undefined;
  return {
    title: keys ? t(keys.title) : version.name,
    description: keys && version.description === original?.description ? t(keys.description) : version.description,
  };
}

/** Only likely packaged versions need an extra origin lookup when opened outside the catalogue. */
export function needsSkillOrigin(version?: Pick<SkillVersion, "name" | "description">): boolean {
  return Boolean(
    version && metadata.some(entry => entry.name === version.name && entry.description === version.description),
  );
}
