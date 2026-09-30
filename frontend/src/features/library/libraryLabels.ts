import type { LibraryCategory } from "../../shared/api/client";
export const CATEGORY_LABELS: Record<LibraryCategory, string> = { CHARACTER: "角色", SCENE: "场景", PROP: "道具", OTHER: "其他" };
export const MAX_LIBRARY_NAME_LENGTH = 160;
export const KIND_LABELS = { TEXT: "文字", IMAGE: "图片", VIDEO: "视频", AUDIO: "音频" } as const;
