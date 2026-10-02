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
    <FieldLegend>{t("按分辨率估算价格")}</FieldLegend>
    {tiers.map((tier) => <FieldGroup key={tier} className="ui-form-grid">
      <Field><FieldLabel className="ui-field block">{t("{0} 单位价格", { "0": tier })}
        <Input type="number" min={0} max="9999999999.999999" step={PRICE_STEP}
          value={values.pricingByResolution?.[tier]?.amount ?? ""} placeholder={t("留空使用统一价格")}
          onChange={(event) => change(tier, { amount: event.target.value })} />
      </FieldLabel></Field>
      <Field><FieldLabel className="ui-field block">{t("{0} 币种", { "0": tier })}
        <Select value={values.pricingByResolution?.[tier]?.currency ?? values.pricing?.currency ?? "CNY"}
          onChange={(event) => change(tier, { currency: event.target.value as Price["currency"] })}>
          <option value="CNY">{t("CNY · 人民币")}</option><option value="USD">{t("USD · 美元")}</option>
        </Select>
      </FieldLabel></Field>
      <Field><FieldLabel className="ui-field block">{t("{0} 计价单位", { "0": tier })}
        <Select value={values.pricingByResolution?.[tier]?.unit ?? values.pricing?.unit ?? "SECOND"}
          onChange={(event) => change(tier, { unit: event.target.value as Price["unit"] })}>
          <option value="SECOND">{t("每秒视频")}</option><option value="VIDEO">{t("每个视频")}</option>
        </Select>
      </FieldLabel></Field>
    </FieldGroup>)}
    <p className="ui-muted">{t("分辨率价格优先；留空使用统一价格，两者都未设置时费用未知。")}</p>
  </FieldSet>;
}
