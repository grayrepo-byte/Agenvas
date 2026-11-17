import { describe, expect, it } from "vitest";
import { videoFunctionsFixture } from "../test/videoFunctionsFixture";
import { compatibleImageFunction, IMAGE_OPERATIONS, videoFunctionChoices } from "./mediaFunctions";
import { imageFunctionsFixture } from "../test/imageFunctionsFixture";
import { setLocale } from "./i18n";
import type { MediaCapability } from "./api/client";

describe("costume extraction function", () => {
  it("offers only OpenAI with capacity for source and style and a 2:3 output", () => {
    const capability = imageFunctionsFixture().connections[1]!.capabilities[0]!;
    expect(IMAGE_OPERATIONS).toContain("COSTUME_EXTRACT");
    const openAi: MediaCapability = {
      ...capability,
      adapterId: "OPENAI_GPT_IMAGE_2",
      maxReferenceImages: 2,
      supportedImageAspectRatios: ["2:3"],
    };
    expect(compatibleImageFunction("COSTUME_EXTRACT", openAi)).toBe(true);
    expect(compatibleImageFunction("COSTUME_EXTRACT", { ...openAi, maxReferenceImages: 1 })).toBe(false);
    expect(compatibleImageFunction("COSTUME_EXTRACT", { ...openAi, adapterId: "GOOGLE_NANO_BANANA_2" })).toBe(false);
    expect(compatibleImageFunction("COSTUME_EXTRACT", { ...openAi, supportedImageAspectRatios: ["1:1"] })).toBe(false);
  });
});

describe("video function choices", () => {
  it("localizes an installed processor at display time and leaves its raw name and custom names intact", () => {
    const settings = videoFunctionsFixture();
    const capability = settings.connections[0]!.capabilities[0]!;
    capability.id = "00000000-0000-4000-8000-000000000203";
    capability.name = "本地视频深度提取";
    setLocale("en");
    expect(videoFunctionChoices(settings, "DEPTH_MAP")[0]!.label).not.toMatch(/\p{Script=Han}/u);
    expect(capability.name).toBe("本地视频深度提取");
    setLocale("zh");
    expect(videoFunctionChoices(settings, "DEPTH_MAP")[0]!.label).toContain("本地视频深度提取");
    setLocale("en");
    capability.name = "我的深度处理";
    expect(videoFunctionChoices(settings, "DEPTH_MAP")[0]!.label).toContain("我的深度处理");
  });
  it.each([
    ["UPSCALE", ["upscale-cap"]],
    ["DEPTH_MAP", ["depth-cap", "upscale-cap"]],
    ["EXTRACT_AUDIO", ["audio-cap"]],
  ] as const)("keeps compatible %s choices in connection order", (operation, ids) => {
    const settings = videoFunctionsFixture();
    const choices = videoFunctionChoices(settings, operation);
    expect(choices.map(({ capability }) => capability.id)).toEqual(ids);
    for (const choice of choices) {
      const connection = settings.connections.find(entry => entry.capabilities.includes(choice.capability))!;
      expect(choice.capability).toBe(connection.capabilities.find(entry => entry.id === choice.capability.id));
      expect(choice.label).toBe(
        connection.platform === "LOCAL"
          ? `本地媒体处理 · ${choice.capability.name}`
          : `RUNNINGHUB · 合成工作流 · ${choice.capability.name}`,
      );
    }
  });

  it("excludes disabled connections, disabled capabilities and conditional source inputs", () => {
    const settings = videoFunctionsFixture();
    const local = settings.connections[0]!;
    const cloud = settings.connections[1]!;
    settings.connections.push({
      ...local,
      id: "disabled-connection",
      enabled: false,
      capabilities: [{ ...local.capabilities[0]!, id: "disabled-connection-capability" }],
    });
    local.capabilities.push({ ...local.capabilities[0]!, id: "disabled-capability", enabled: false });
    cloud.capabilities[0]!.settings.runningHub!.fields[0]!.enabledWhen = { field: "scale", value: 2 };
    expect(videoFunctionChoices(settings, "DEPTH_MAP").map(({ capability }) => capability.id)).toEqual(["depth-cap"]);
    expect(videoFunctionChoices(settings, "UPSCALE")).toEqual([]);
  });
});
