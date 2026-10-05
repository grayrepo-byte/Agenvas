import type { MediaFunctionSetting, MediaSettings, MediaCapability } from "../shared/api/client";
import { IMAGE_OPERATIONS, LOCAL_IMAGE_OPERATIONS, imageFunction } from "../shared/mediaFunctions";
import { videoFunctionCapability } from "./videoFunctionsFixture";

export function imageFunctionSettings(cloudCapabilityId = "ai-capability"): MediaFunctionSetting[] {
  return IMAGE_OPERATIONS.map((operation) => ({ operation: imageFunction(operation), version: 3,
    capabilityId: LOCAL_IMAGE_OPERATIONS.includes(operation) ? "local-image" : cloudCapabilityId }));
}

export function imageFunctionsFixture(): MediaSettings {
  return { defaults: [], connections: [{ id: "local", name: "本地处理", platform: "LOCAL", enabled: true,
    version: 0, connectionVersion: 1, origin: null, keyMask: null, connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
    capabilities: [videoFunctionCapability({ id: "local-image", name: "本地图片处理", kind: "IMAGE_GENERATION",
      adapterId: "LOCAL_IMAGE_PROCESSOR", maxReferenceImages: 1 })] },
  { id: "openai", name: "合成图片服务", platform: "OPENAI", enabled: true, version: 0, connectionVersion: 1,
    origin: null, keyMask: null, connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
    capabilities: [videoFunctionCapability({ id: "ai-capability", name: "合成图片模型", kind: "IMAGE_GENERATION",
      adapterId: "OPENAI_GPT_IMAGE_2", maxReferenceImages: 4, supportsTransparentBackground: true, supportsImageMask: true })] }] };
}

export function imageWorkflowCapability(): MediaCapability {
  return videoFunctionCapability({ id: "image-upscale", name: "合成图片超分", kind: "IMAGE_GENERATION", adapterId: "RUNNINGHUB_IMAGE", maxReferenceImages: 1,
    settings: { pricing: { amount: "0.25", currency: "USD", unit: "IMAGE" }, runningHub: {
      schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "123", usePersonalQueue: false, addMetadata: false,
      fields: [{ key: "image", label: "来源图片", type: "IMAGE", required: true, advanced: false, nodeId: "1", fieldName: "image" },
        { key: "scale", label: "模型放大倍数", type: "SELECT", required: true, advanced: false, nodeId: "2", fieldName: "scale", defaultValue: 2,
          options: [{ label: "2×", value: 2 }, { label: "4×", value: 4 }] }],
      outputs: [{ nodeId: "3", kind: "IMAGE", primary: true, maxCount: 1 }],
    } } });
}
