import { Buildings, Cube, PersonSimple, Smiley } from "@/shared/ui/icons";
import type { RunImageOperationRequest } from "../../shared/api/client";
import { t } from "../../shared/i18n";

export type ThreeViewType = NonNullable<RunImageOperationRequest["parameters"]["threeViewType"]>;
export const THREE_VIEW_OPTIONS = [
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
