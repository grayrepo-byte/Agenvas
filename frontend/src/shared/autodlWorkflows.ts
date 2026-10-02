import type { MediaCapability } from "./api/client";

// Reviewed fixed workflow metadata, paired with providers/autodl-h3-workflows.json.
export const autodlWorkflows = [
  {
    "id": "minimax_h3_b99_001",
    "label": "H3文生视频",
    "minimumSeconds": 1,
    "maximumSeconds": 15,
    "promptLimit": 10000,
    "mode": "TEXT",
    "imageFields": [],
    "audioFields": [],
    "minimumImages": 0,
    "minimumAudios": 0,
    "resolutions": [
      "736p竖",
      "736p横",
      "736p(1:1)"
    ],
    "defaultResolution": "736p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_b99_002",
    "label": "H3首尾帧生成视频",
    "minimumSeconds": 1,
    "maximumSeconds": 15,
    "promptLimit": 10000,
    "mode": "START_END",
    "imageFields": [
      "first_frame",
      "last_frame"
    ],
    "audioFields": [],
    "minimumImages": 2,
    "minimumAudios": 0,
    "resolutions": [
      "736p竖",
      "736p横",
      "736p(1:1)"
    ],
    "defaultResolution": "736p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_b99_003_12s",
    "label": "H3多图生视频12秒",
    "minimumSeconds": 1,
    "maximumSeconds": 12,
    "promptLimit": 10000,
    "mode": "GENERAL_REFERENCE",
    "imageFields": [
      "ref_image_0",
      "ref_image_1",
      "ref_image_2",
      "ref_image_3",
      "ref_image_4",
      "ref_image_5",
      "ref_image_6",
      "ref_image_7",
      "ref_image_8"
    ],
    "audioFields": [],
    "minimumImages": 1,
    "minimumAudios": 0,
    "resolutions": [
      "736p竖",
      "736p横",
      "736p(1:1)"
    ],
    "defaultResolution": "736p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_image_audio_to_video_v2",
    "label": "H3多图多音频生视频",
    "minimumSeconds": 1,
    "maximumSeconds": 10,
    "promptLimit": 10000,
    "mode": "GENERAL_REFERENCE",
    "imageFields": [
      "ref_image_0",
      "ref_image_1",
      "ref_image_2",
      "ref_image_3",
      "ref_image_4",
      "ref_image_5",
      "ref_image_6",
      "ref_image_7",
      "ref_image_8"
    ],
    "audioFields": [
      "ref_audio_0",
      "ref_audio_1",
      "ref_audio_2"
    ],
    "minimumImages": 0,
    "minimumAudios": 0,
    "resolutions": [
      "480p竖",
      "768p竖",
      "1080p竖",
      "480p横",
      "768p横",
      "1080p横"
    ],
    "defaultResolution": "768p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_image_audio_to_video_v2_15s",
    "label": "H3多图多音频生视频15秒",
    "minimumSeconds": 1,
    "maximumSeconds": 15,
    "promptLimit": 10000,
    "mode": "GENERAL_REFERENCE",
    "imageFields": [
      "ref_image_0",
      "ref_image_1",
      "ref_image_2",
      "ref_image_3",
      "ref_image_4",
      "ref_image_5",
      "ref_image_6",
      "ref_image_7",
      "ref_image_8"
    ],
    "audioFields": [
      "ref_audio_0",
      "ref_audio_1",
      "ref_audio_2"
    ],
    "minimumImages": 0,
    "minimumAudios": 0,
    "resolutions": [
      "480p竖",
      "768p竖",
      "480p横",
      "768p横"
    ],
    "defaultResolution": "768p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_lightx2v",
    "label": "H3首尾帧生成视频",
    "minimumSeconds": 1,
    "maximumSeconds": 15,
    "promptLimit": 10000,
    "mode": "START_END",
    "imageFields": [
      "first_frame",
      "last_frame"
    ],
    "audioFields": [],
    "minimumImages": 2,
    "minimumAudios": 0,
    "resolutions": [
      "480p竖",
      "768p竖",
      "480p横",
      "768p横",
      "480p(1:1)",
      "768p(1:1)"
    ],
    "defaultResolution": "768p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_lightx2v_no_pic",
    "label": "H3文生视频",
    "minimumSeconds": 1,
    "maximumSeconds": 15,
    "promptLimit": 10000,
    "mode": "TEXT",
    "imageFields": [],
    "audioFields": [],
    "minimumImages": 0,
    "minimumAudios": 0,
    "resolutions": [
      "480p竖",
      "768p竖",
      "480p横",
      "768p横",
      "480p(1:1)",
      "768p(1:1)"
    ],
    "defaultResolution": "768p",
    "supportsSeed": false
  },
  {
    "id": "minimax_h3_lightx2v_v5",
    "label": "H3多图参考生视频",
    "minimumSeconds": 1,
    "maximumSeconds": 10,
    "promptLimit": 10000,
    "mode": "GENERAL_REFERENCE",
    "imageFields": [
      "ref_image_0",
      "ref_image_1",
      "ref_image_2",
      "ref_image_3",
      "ref_image_4",
      "ref_image_5",
      "ref_image_6",
      "ref_image_7",
      "ref_image_8"
    ],
    "audioFields": [],
    "minimumImages": 1,
    "minimumAudios": 0,
    "resolutions": [
      "480p竖",
      "768p竖",
      "1080p竖",
      "480p横",
      "768p横",
      "1080p横",
      "480p(1:1)",
      "768p(1:1)",
      "1080p(1:1)"
    ],
    "defaultResolution": "768p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_lightx2v_v5_15s",
    "label": "H3多图生视频15秒",
    "minimumSeconds": 1,
    "maximumSeconds": 15,
    "promptLimit": 10000,
    "mode": "GENERAL_REFERENCE",
    "imageFields": [
      "ref_image_0",
      "ref_image_1",
      "ref_image_2",
      "ref_image_3",
      "ref_image_4",
      "ref_image_5",
      "ref_image_6",
      "ref_image_7",
      "ref_image_8"
    ],
    "audioFields": [],
    "minimumImages": 1,
    "minimumAudios": 0,
    "resolutions": [
      "480p竖",
      "768p竖",
      "480p横",
      "768p横",
      "480p(1:1)",
      "768p(1:1)"
    ],
    "defaultResolution": "768p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_z0901",
    "label": "H3文生视频（高质量创意直出）",
    "minimumSeconds": 1,
    "maximumSeconds": 15,
    "promptLimit": 10000,
    "mode": "TEXT",
    "imageFields": [],
    "audioFields": [],
    "minimumImages": 0,
    "minimumAudios": 0,
    "resolutions": [
      "480p竖(480*864)",
      "480p横(864*480)",
      "768p竖(768*1344)",
      "768p横(1344*768)",
      "1088p竖(1088*1920)",
      "1088p横(1920*1088)",
      "1440p竖(1440*2560)",
      "1440p横(2560*1440)"
    ],
    "defaultResolution": "768p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_z0902",
    "label": "H3六图生视频（多图一致性创作）",
    "minimumSeconds": 1,
    "maximumSeconds": 15,
    "promptLimit": 10000,
    "mode": "GENERAL_REFERENCE",
    "imageFields": [
      "ref_image_0",
      "ref_image_1",
      "ref_image_2",
      "ref_image_3",
      "ref_image_4",
      "ref_image_5"
    ],
    "audioFields": [],
    "minimumImages": 1,
    "minimumAudios": 0,
    "resolutions": [
      "480p竖(480*864)",
      "480p横(864*480)",
      "768p竖(768*1376)",
      "768p横(1376*768)",
      "1088p竖(1088*1920)",
      "1088p横(1920*1088)",
      "1440p竖(1440*2560)",
      "1440p横(2560*1440)"
    ],
    "defaultResolution": "768p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_z0903",
    "label": "H3六图三音频生视频（高质量音画融合）",
    "minimumSeconds": 1,
    "maximumSeconds": 15,
    "promptLimit": 10000,
    "mode": "GENERAL_REFERENCE",
    "imageFields": [
      "ref_image_0",
      "ref_image_1",
      "ref_image_2",
      "ref_image_3",
      "ref_image_4",
      "ref_image_5"
    ],
    "audioFields": [
      "ref_audio_0",
      "ref_audio_1",
      "ref_audio_2"
    ],
    "minimumImages": 1,
    "minimumAudios": 1,
    "resolutions": [
      "480p竖(480*864)",
      "480p横(864*480)",
      "768p竖(768*1376)",
      "768p横(1376*768)",
      "1088p竖(1088*1920)",
      "1088p横(1920*1088)",
      "1440p竖(1440*2560)",
      "1440p横(2560*1440)"
    ],
    "defaultResolution": "768p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_zm_u08",
    "label": "H3多图多音频生视频(高速版)",
    "minimumSeconds": 1,
    "maximumSeconds": 15,
    "promptLimit": 10000,
    "mode": "GENERAL_REFERENCE",
    "imageFields": [
      "ref_image_0",
      "ref_image_1",
      "ref_image_2",
      "ref_image_3",
      "ref_image_4",
      "ref_image_5",
      "ref_image_6",
      "ref_image_7",
      "ref_image_8"
    ],
    "audioFields": [
      "ref_audio_0",
      "ref_audio_1",
      "ref_audio_2"
    ],
    "minimumImages": 1,
    "minimumAudios": 0,
    "resolutions": [
      "480p横",
      "480p竖",
      "768p横",
      "768p竖",
      "480p(1:1)",
      "768p(1:1)"
    ],
    "defaultResolution": "768p",
    "supportsSeed": true
  },
  {
    "id": "minimax_h3_zm_u24",
    "label": "H3多图多音频生视频(升级画质)",
    "minimumSeconds": 1,
    "maximumSeconds": 15,
    "promptLimit": 10000,
    "mode": "GENERAL_REFERENCE",
    "imageFields": [
      "ref_image_0",
      "ref_image_1",
      "ref_image_2",
      "ref_image_3",
      "ref_image_4",
      "ref_image_5",
      "ref_image_6",
      "ref_image_7",
      "ref_image_8"
    ],
    "audioFields": [
      "ref_audio_0",
      "ref_audio_1",
      "ref_audio_2"
    ],
    "minimumImages": 1,
    "minimumAudios": 0,
    "resolutions": [
      "480p横",
      "480p竖",
      "768p横",
      "768p竖",
      "480p(1:1)",
      "768p(1:1)"
    ],
    "defaultResolution": "768p",
    "supportsSeed": true
  }
] as const;

export const AUTODL_ADAPTER = "AUTODL_COMFY_VIDEO";
export const AUTODL_DEFAULT_WORKFLOW = "minimax_h3_z0903";
export function getAutoDlWorkflow(id: string | undefined) {
  return autodlWorkflows.find((workflow) => workflow.id === (id ?? AUTODL_DEFAULT_WORKFLOW));
}
export function autoDlRatioSupported(workflow: AutoDlWorkflow, tier: string, ratio: string) {
  if (ratio === "AUTO") return true;
  const suffix = ratio === "16:9" ? "横" : ratio === "9:16" ? "竖" : "(1:1)";
  return workflow.resolutions.some((label) => label.startsWith(tier + suffix));
}

export type AutoDlResolution = NonNullable<MediaCapability["settings"]["videoResolution"]>;
export function autoDlResolutionTiers(workflow: AutoDlWorkflow): AutoDlResolution[] {
  return [...new Set(workflow.resolutions.filter((label) => /^[1-9][0-9]{2,3}p/.test(label)).map((label) => `${label.split("p")[0]}p` as AutoDlResolution))];
}
export function publishedAutoDlResolutions(settings: MediaCapability["settings"]): AutoDlResolution[] {
  const workflow = resolveAutoDlWorkflow(settings);
  if (!workflow) return [];
  return settings.videoResolutions ?? [settings.videoResolution ?? workflow.defaultResolution as AutoDlResolution];
}

export type AutoDlWorkflow = Omit<NonNullable<MediaCapability["settings"]["workflowDefinition"]>, "schemaVersion" | "imageFields" | "audioFields" | "resolutions"> & {
  imageFields: readonly string[]; audioFields: readonly string[]; resolutions: readonly string[];
};
export function resolveAutoDlWorkflow(settings: MediaCapability["settings"]): AutoDlWorkflow | undefined {
  return settings.workflowDefinition ?? getAutoDlWorkflow(settings.workflowId);
}
