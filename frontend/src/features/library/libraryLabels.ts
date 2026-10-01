import { t } from "../../shared/i18n";
import type { LibraryCategory } from "../../shared/api/client";
export const CATEGORY_LABELS: Record<LibraryCategory, string> = { get CHARACTER() { return t("角色"); }, get SCENE() { return t("场景"); }, get PROP() { return t("道具"); }, get OTHER() { return t("其他"); } };
export const MAX_LIBRARY_NAME_LENGTH = 160;
export const KIND_LABELS = { get TEXT() { return t("文字"); }, get IMAGE() { return t("图片"); }, get VIDEO() { return t("视频"); }, get AUDIO() { return t("音频"); } } as const;
