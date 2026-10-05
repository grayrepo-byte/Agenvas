import { t } from "../../shared/i18n";
import type { LibraryCategory } from "../../shared/api/client";
export const CATEGORY_LABELS: Record<LibraryCategory, string> = { get CHARACTER() { return t("library.categories.character"); }, get SCENE() { return t("library.categories.scene"); }, get PROP() { return t("library.categories.prop"); }, get OTHER() { return t("library.categories.other"); } };
export const MAX_LIBRARY_NAME_LENGTH = 160;
export const KIND_LABELS = { get TEXT() { return t("common.text"); }, get IMAGE() { return t("common.image"); }, get VIDEO() { return t("common.video"); }, get AUDIO() { return t("common.audio"); } } as const;

/** Asset-page browsing excludes text; reference pickers retain their own supported kinds. */
export const MEDIA_ASSET_KINDS = ["IMAGE", "VIDEO", "AUDIO"] as const;
