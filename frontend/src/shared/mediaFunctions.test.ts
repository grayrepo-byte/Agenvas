import { describe, expect, it } from "vitest";
import { videoFunctionsFixture } from "../test/videoFunctionsFixture";
import { videoFunctionChoices } from "./mediaFunctions";

describe("video function choices", () => {
  it.each([
    ["UPSCALE", ["upscale-cap"]],
    ["DEPTH_MAP", ["depth-cap", "upscale-cap"]],
    ["EXTRACT_AUDIO", ["audio-cap"]],
  ] as const)("keeps compatible %s choices in connection order", (operation, ids) => {
    const settings = videoFunctionsFixture();
    const choices = videoFunctionChoices(settings, operation);
    expect(choices.map(({ capability }) => capability.id)).toEqual(ids);
    for (const choice of choices) {
      const connection = settings.connections.find((entry) => entry.capabilities.includes(choice.capability))!;
      expect(choice.capability).toBe(connection.capabilities.find((entry) => entry.id === choice.capability.id));
      expect(choice.label).toBe(connection.platform === "LOCAL"
        ? `本地媒体处理 · ${choice.capability.name}` : `RUNNINGHUB · 合成工作流 · ${choice.capability.name}`);
    }
  });

  it("excludes disabled connections, disabled capabilities and conditional source inputs", () => {
    const settings = videoFunctionsFixture();
    const local = settings.connections[0]!;
    const cloud = settings.connections[1]!;
    settings.connections.push({ ...local, id: "disabled-connection", enabled: false,
      capabilities: [{ ...local.capabilities[0]!, id: "disabled-connection-capability" }] });
    local.capabilities.push({ ...local.capabilities[0]!, id: "disabled-capability", enabled: false });
    cloud.capabilities[0]!.settings.runningHub!.fields[0]!.enabledWhen = { field: "scale", value: 2 };
    expect(videoFunctionChoices(settings, "DEPTH_MAP").map(({ capability }) => capability.id)).toEqual(["depth-cap"]);
    expect(videoFunctionChoices(settings, "UPSCALE")).toEqual([]);
  });
});
