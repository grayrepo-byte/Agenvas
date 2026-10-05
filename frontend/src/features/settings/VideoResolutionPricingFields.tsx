import type { MediaCapability } from "../../shared/api/client";
import { autoDlResolutionTiers, resolveAutoDlWorkflow } from "../../shared/autodlWorkflows";
import { t, useLocale } from "../../shared/i18n";
import { Field, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";

type Settings = MediaCapability["settings"];
type Price = NonNullable<Settings["pricing"]>;
const PRICE_STEP = "0.000001";

export function VideoResolutionPricingFields({ values, onChange }: {
  values: Settings; onChange: (value: Settings) => void;
}) {
  useLocale();
  const workflow = resolveAutoDlWorkflow(values);
  const tiers = values.videoResolutions ?? (values.videoResolution ? [values.videoResolution] : workflow ? autoDlResolutionTiers(workflow) : []);
  function change(tier: NonNullable<Settings["videoResolution"]>, patch: Partial<Price>) {
    const current = values.pricingByResolution?.[tier];
    onChange({ ...values, pricingByResolution: { ...values.pricingByResolution,
      [tier]: { amount: "", currency: values.pricing?.currency ?? "CNY", unit: values.pricing?.unit ?? "SECOND", ...current, ...patch },
    } });
  }
  return <FieldSet>
    <FieldLegend>{t("settings.resolutionPricing.title")}</FieldLegend>
    {tiers.map((tier) => <FieldGroup key={tier} className="ui-form-grid">
      <Field><FieldLabel className="ui-field block">{t("settings.resolutionPricing.priceLabel", { "0": tier })}
        <Input type="number" min={0} max="9999999999.999999" step={PRICE_STEP}
          value={values.pricingByResolution?.[tier]?.amount ?? ""} placeholder={t("settings.resolutionPricing.fallbackPlaceholder")}
          onChange={(event) => change(tier, { amount: event.target.value })} />
      </FieldLabel></Field>
      <Field><FieldLabel className="ui-field block">{t("settings.resolutionPricing.currencyLabel", { "0": tier })}
        <Select value={values.pricingByResolution?.[tier]?.currency ?? values.pricing?.currency ?? "CNY"}
          onChange={(event) => change(tier, { currency: event.target.value as Price["currency"] })}>
          <option value="CNY">{t("media.pricing.cny")}</option><option value="USD">{t("media.pricing.usd")}</option>
        </Select>
      </FieldLabel></Field>
      <Field><FieldLabel className="ui-field block">{t("settings.resolutionPricing.unitLabel", { "0": tier })}
        <Select value={values.pricingByResolution?.[tier]?.unit ?? values.pricing?.unit ?? "SECOND"}
          onChange={(event) => change(tier, { unit: event.target.value as Price["unit"] })}>
          <option value="SECOND">{t("media.pricing.perVideoSecond")}</option><option value="VIDEO">{t("media.pricing.perVideo")}</option>
        </Select>
      </FieldLabel></Field>
    </FieldGroup>)}
    <p className="ui-muted">{t("settings.resolutionPricing.pricingFallbackHint")}</p>
  </FieldSet>;
}
