import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createClientId, createCommandKey } from "./clientId";

beforeEach(() => vi.stubGlobal("crypto", { getRandomValues: crypto.getRandomValues.bind(crypto) }));
afterEach(() => vi.unstubAllGlobals());

it("creates distinct entity IDs and command keys without a secure context", () => {
  const ids = new Set(Array.from({ length: 1000 }, createClientId));
  const keys = new Set(Array.from({ length: 1000 }, createCommandKey));
  expect(ids.size).toBe(1000);
  expect(keys.size).toBe(1000);
  for (const id of ids) expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  for (const key of keys) expect(key).toMatch(/^[0-9a-f]{32}$/);
});

it.each([
  [0x00, "00000000-0000-4000-8000-000000000000"],
  [0xff, "ffffffff-ffff-4fff-bfff-ffffffffffff"],
])("preserves UUID version and variant with random byte value %i", (value, expected) => {
  vi.stubGlobal("crypto", { getRandomValues: (bytes: Uint8Array) => bytes.fill(value) });
  expect(createClientId()).toBe(expected);
});
