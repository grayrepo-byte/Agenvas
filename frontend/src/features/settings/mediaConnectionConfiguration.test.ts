import { describe, expect, it } from "vitest";
import { mediaConnectionAddresses, mediaConnectionOrigin } from "./mediaConnectionConfiguration";

describe("media connection configuration", () => {
  it.each(["MINIMAX", "COMFYUI", "RUNNINGHUB", "OPENAI", "GOOGLE", "WAN"] as const)("submits the complete editable origin for %s", (platform) => {
    expect(mediaConnectionAddresses[platform]?.fixed).toBeUndefined();
    expect(mediaConnectionOrigin(platform, " https://gateway.example.test/proxy/v1/ "))
      .toBe("https://gateway.example.test/proxy/v1/");
    expect(mediaConnectionOrigin(platform, " \t ")).toBeNull();
  });

  it.each(["ARK", "VOLCENGINE", "AUTODL", "LOCAL", "MOCK"] as const)("excludes editable origins for %s", (platform) => {
    expect(mediaConnectionOrigin(platform, "https://old-gateway.example.test")).toBeNull();
  });

  it("preserves the selected Kling region and retains Singapore for an unset historical origin", () => {
    expect(mediaConnectionOrigin("KLING", " https://api-beijing.klingai.com ")).toBe("https://api-beijing.klingai.com");
    expect(mediaConnectionOrigin("KLING", "https://api-singapore.klingai.com")).toBe("https://api-singapore.klingai.com");
    expect(mediaConnectionOrigin("KLING", " ")).toBe("https://api-singapore.klingai.com");
    expect(mediaConnectionAddresses.WAN).toMatchObject({ required: true, requiresApiKey: true });
  });

  it("requires a ComfyUI address without an API key while keeping credentials on the remote platforms", () => {
    expect(mediaConnectionAddresses.COMFYUI).toMatchObject({ required: true, requiresApiKey: false });
    for (const platform of ["MINIMAX", "RUNNINGHUB", "OPENAI", "GOOGLE", "ARK", "VOLCENGINE", "AUTODL", "KLING", "WAN"] as const) {
      expect(mediaConnectionAddresses[platform]?.requiresApiKey).toBe(true);
    }
    expect(mediaConnectionAddresses.LOCAL).toBeUndefined();
    expect(mediaConnectionAddresses.MOCK).toBeUndefined();
  });
});
