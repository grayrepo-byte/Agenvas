import type { MediaCapability } from "../../shared/api/client";
import { t } from "../../shared/i18n";
import { Select } from "../../shared/ui/Select";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { openAiImagePricePreset } from "./openAiImagePricingPresets";

type Settings = MediaCapability["settings"];
const RATE_FIELDS = [
  ["textInputPerMillion", "settings.capabilities.tokenTextInput", true],
  ["imageInputPerMillion", "settings.capabilities.tokenImageInput", true],
  ["imageOutputPerMillion", "settings.capabilities.tokenImageOutput", true],
  ["cachedTextInputPerMillion", "settings.capabilities.tokenCachedTextInput", false],
  ["cachedImageInputPerMillion", "settings.capabilities.tokenCachedImageInput", false],
] as const;

export function ImageTokenPricingFields({
  values,
  onChange,
  connectionOrigin,
}: {
  values: Settings;
  onChange: (value: Settings) => void;
  connectionOrigin?: string | null;
}) {
  const rates = values.tokenPricing;
  const preset = openAiImagePricePreset(values.model, connectionOrigin);
  if (!rates) return null;
  return (
    <>
      <Field>
        <FieldLabel className="ui-field block">
          {t("settings.capabilities.currency")}
          <Select
            value={rates.currency}
            onChange={event =>
              onChange({ ...values, tokenPricing: { ...rates, currency: event.target.value as "CNY" | "USD" } })
            }
          >
            <option value="CNY">{t("media.pricing.cny")}</option>
            <option value="USD">{t("media.pricing.usd")}</option>
          </Select>
        </FieldLabel>
      </Field>
      {RATE_FIELDS.map(([field, label, required]) => (
        <Field key={field}>
          <FieldLabel className="ui-field block">
            {t(label)}
            <Input
              type="number"
              min={0}
              max="9999999999.999999"
              step="0.000001"
              required={required}
              value={rates[field] ?? ""}
              onChange={event => onChange({ ...values, tokenPricing: { ...rates, [field]: event.target.value } })}
            />
          </FieldLabel>
        </Field>
      ))}
      <p className="ui-muted">{t("settings.capabilities.tokenPricingHint")}</p>
      {preset ? (
        <p className="ui-muted">
          {t("settings.capabilities.officialTokenPreset", { "0": preset.model, "1": preset.verifiedAt })}{" "}
          <a href={preset.source} target="_blank" rel="noreferrer">
            {t("settings.capabilities.officialTokenSource")}
          </a>
        </p>
      ) : (
        <p className="ui-muted">{t("settings.capabilities.noOfficialTokenPreset")}</p>
      )}
    </>
  );
}
