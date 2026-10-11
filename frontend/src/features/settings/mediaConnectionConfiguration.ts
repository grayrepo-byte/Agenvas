import type { MediaConnection } from "../../shared/api/client";
import type { MessageKey } from "../../shared/i18n";

// Shared address guidance also determines whether an origin is editable and can be submitted.
export const mediaConnectionAddresses: Partial<Record<MediaConnection["platform"], {
  label: MessageKey; hint: MessageKey; placeholder?: string; fixed?: string; required?: boolean; requiresApiKey: boolean;
}>> = {
  MINIMAX: { label: "settings.mediaSettings.minimaxAddressLabel", hint: "settings.mediaSettings.minimaxAddressHint", requiresApiKey: true },
  COMFYUI: { label: "settings.mediaSettings.comfyUrl", hint: "settings.mediaSettings.comfyAddressHint", placeholder: "http://127.0.0.1:8188", required: true, requiresApiKey: false },
  RUNNINGHUB: { label: "settings.mediaSettings.runningHubApi", hint: "settings.mediaSettings.runningHubAddressHint", placeholder: "https://www.runninghub.ai", requiresApiKey: true },
  OPENAI: { label: "settings.mediaSettings.baseUrl", hint: "settings.mediaSettings.openAiAddressHint", placeholder: "https://api.openai.com/v1", requiresApiKey: true },
  GOOGLE: { label: "settings.mediaSettings.baseUrl", hint: "settings.mediaSettings.googleAddressHint", placeholder: "https://generativelanguage.googleapis.com", requiresApiKey: true },
  ARK: { label: "settings.mediaSettings.fixedApiUrl", hint: "settings.mediaSettings.arkAddressHint", fixed: "https://ark.cn-beijing.volces.com/api/v3", requiresApiKey: true },
  VOLCENGINE: { label: "settings.mediaSettings.fixedApiUrl", hint: "settings.mediaSettings.volcengineAddressHint", fixed: "https://openspeech.bytedance.com/api/v3/tts/create", requiresApiKey: true },
  AUTODL: { label: "settings.mediaSettings.fixedApiUrl", hint: "settings.mediaSettings.autoDlAddressHint", fixed: "https://autodl.art/api/v1/comfyui/comfyui_workflow", requiresApiKey: true },
};

/** Fixed protocol addresses are guidance only; editable origins retain their complete user-provided path. */
export function mediaConnectionOrigin(platform: MediaConnection["platform"], origin: string): string | null {
  const address = mediaConnectionAddresses[platform];
  return address && !address.fixed ? origin.trim() || null : null;
}
