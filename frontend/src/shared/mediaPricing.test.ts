import { describe, expect, it } from "vitest";
import type { MediaCapability } from "./api/client";
import { estimatedMediaCost } from "./mediaPricing";

function priced(amount: string, unit: "IMAGE" | "VIDEO" | "SECOND"): MediaCapability {
  return { settings: { pricing: { amount, currency: "CNY", unit } } } as MediaCapability;
}

describe("estimatedMediaCost", () => {
  it("calculates exact decimal batch and second estimates", () => {
    expect(estimatedMediaCost(priced("0.1", "IMAGE"), 4, null)).toBe("预计 CNY 0.4");
    expect(estimatedMediaCost(priced("0.123456", "SECOND"), 1, 15)).toBe("预计 CNY 1.85184");
    expect(estimatedMediaCost(priced("2", "VIDEO"), 1, 15)).toBe("预计 CNY 2");
  });
  it("keeps unknown prices unknown and distinguishes explicit zero", () => {
    expect(estimatedMediaCost(undefined, 1, null)).toBe("费用未知");
    expect(estimatedMediaCost(priced("1", "SECOND"), 1, null)).toBe("费用未知");
    expect(estimatedMediaCost(priced("0", "IMAGE"), 1, null)).toBe("预计 CNY 0");
    expect(estimatedMediaCost(priced("oops", "IMAGE"), 1, null)).toBe("费用未知");
  });
  it("uses resolution prices before the fallback including zero and per-video prices", () => {
    const capability = priced("2", "VIDEO");
    capability.settings.pricingByResolution = {
      "480p": { amount: "0", currency: "CNY", unit: "SECOND" },
      "768p": { amount: "0.123456", currency: "USD", unit: "SECOND" },
      "1080p": { amount: "3", currency: "CNY", unit: "VIDEO" },
    };
    expect(estimatedMediaCost(capability, 1, 8, "480p")).toBe("预计 CNY 0");
    expect(estimatedMediaCost(capability, 1, 8, "768p")).toBe("预计 USD 0.987648");
    expect(estimatedMediaCost(capability, 1, 8, "1080p")).toBe("预计 CNY 3");
    expect(estimatedMediaCost(capability, 1, 8, "1440p")).toBe("预计 CNY 2");
    delete capability.settings.pricing;
    expect(estimatedMediaCost(capability, 1, 8, "1440p")).toBe("费用未知");
  });
});
