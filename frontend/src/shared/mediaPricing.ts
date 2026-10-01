import { t } from "./i18n";
import type { MediaCapability } from "./api/client";

const DECIMAL_SCALE = 6;
const MONEY_SCALE = 10n ** BigInt(DECIMAL_SCALE);

/** Decimal arithmetic keeps configured prices exact across batches and video seconds. */
export function estimatedMediaCost(capability: MediaCapability | undefined,
  imageCount: number, durationSeconds: number | null | undefined): string {
  const price = capability?.settings.pricing;
  if (!price || !/^[0-9]{1,10}(\.[0-9]{1,6})?$/.test(price.amount)) return t("费用未知");
  const quantity = price.unit === "IMAGE" ? imageCount
    : price.unit === "SECOND" ? durationSeconds : 1;
  if (quantity == null || !Number.isInteger(quantity) || quantity < 1) return t("费用未知");
  const [whole = "0", fraction = ""] = price.amount.split(".");
  const amount = (BigInt(whole) * MONEY_SCALE
    + BigInt(fraction.padEnd(DECIMAL_SCALE, "0"))) * BigInt(quantity);
  const decimal = (amount % MONEY_SCALE).toString().padStart(DECIMAL_SCALE, "0").replace(/0+$/, "");
  return t("预计 {0} {1}{2}", { "0": price.currency, "1": amount / MONEY_SCALE, "2": decimal ? `.${decimal}` : "" });
}
