import { describe, expect, it } from "vitest";
import { setLocale, SUPPORTED_LOCALES } from "../../shared/i18n";
import { referenceFailureMessage } from "./referenceFailure";

describe("reference download diagnostics", () => {
  it("explains blocked DNS and deployment configuration in every interface language", () => {
    for (const locale of SUPPORTED_LOCALES) {
      setLocale(locale);
      const message = referenceFailureMessage("REFERENCE_CATALOG_DNS_BLOCKED");
      expect(message).not.toContain("REFERENCE_CATALOG_");
      expect(message).toContain("DNS");
      expect(message.length).toBeGreaterThan(50);
    }
  });
  it("distinguishes proxy authentication from reachability and preserves unknown failures", () => {
    setLocale("en");
    expect(referenceFailureMessage("REFERENCE_CATALOG_PROXY_AUTH_REQUIRED")).toContain("authentication");
    expect(referenceFailureMessage("REFERENCE_CATALOG_PROXY_UNREACHABLE")).toContain("container");
    expect(referenceFailureMessage("REFERENCE_CATALOG_REMOTE_RATE_LIMITED")).toContain("later");
    expect(referenceFailureMessage("UNKNOWN_FUTURE_CODE")).toBe("UNKNOWN_FUTURE_CODE");
    expect(referenceFailureMessage(new Error("Permission changed"))).toBe("Permission changed");
  });
});
