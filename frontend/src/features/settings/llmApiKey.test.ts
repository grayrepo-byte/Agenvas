import { describe, expect, it } from "vitest";
import { validLlmApiKey } from "./llmApiKey";

describe("LLM credential validation before save or discovery", () => {
  it.each(["s".repeat(8), "s".repeat(4096), "!~01AZaz"])("accepts printable ASCII at either size boundary", (key) => {
    expect(validLlmApiKey(key)).toBe(true);
  });
  it.each(["", "s".repeat(7), "s".repeat(4097), "synthetic key", " synthetic-key", "synthetic-key ",
    "synthetic-key\n", "synthetic\tkey", "synthetic-密钥"])("rejects invalid credentials without trimming or sanitizing them", (key) => {
    expect(validLlmApiKey(key)).toBe(false);
  });
});
