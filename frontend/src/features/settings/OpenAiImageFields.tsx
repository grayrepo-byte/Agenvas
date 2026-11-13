import { t } from "../../shared/i18n";
import { Select } from "../../shared/ui/Select";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import type { MediaCapabilityDraft } from "./mediaCapabilityDraft";
import { changeOpenAiImageFormat, isOpenAiImage26 } from "./openAiImageOptions";

export function OpenAiImageFields({
  values,
  onChange,
}: {
  values: MediaCapabilityDraft;
  onChange: (value: MediaCapabilityDraft) => void;
}) {
  return (
    <>
      <Field>
        <FieldLabel className="ui-field block">
          {t("settings.openAi.moderation")}
          <Select
            value={values.moderation ?? "auto"}
            onChange={event => onChange({ ...values, moderation: event.target.value as "auto" | "low" })}
          >
            <option value="auto">{t("settings.openAi.moderationAuto")}</option>
            <option value="low">{t("settings.openAi.moderationLow")}</option>
          </Select>
        </FieldLabel>
      </Field>
      <p className="ui-muted">{t("settings.openAi.moderationHint")}</p>
      {isOpenAiImage26(values.model) ? (
        <>
          <Field>
            <FieldLabel className="ui-field block">
              {t("settings.openAi.outputFormat")}
              <Select
                value={values.outputFormat ?? "png"}
                onChange={event => onChange(changeOpenAiImageFormat(values, event.target.value as "png" | "gif"))}
              >
                <option value="png">PNG</option>
                <option value="gif">GIF</option>
              </Select>
            </FieldLabel>
          </Field>
          <Field>
            <FieldLabel className="ui-field block">
              {t("settings.openAi.complexity")}
              <Select
                value={values.complexity ?? "auto"}
                onChange={event =>
                  onChange({
                    ...values,
                    complexity:
                      event.target.value === "auto" ? undefined : (event.target.value as "low" | "medium" | "high"),
                  })
                }
              >
                <option value="auto">{t("common.automatic")}</option>
                <option value="low">low</option>
                <option value="medium">medium</option>
                <option value="high">high</option>
              </Select>
            </FieldLabel>
          </Field>
          {values.outputFormat === "gif" ? (
            <>
              <Field>
                <FieldLabel className="ui-field block">
                  {t("settings.openAi.frameCount")}
                  <Input
                    type="number"
                    required
                    min={2}
                    max={16}
                    step={1}
                    value={values.frameCount ?? 9}
                    onChange={event => onChange({ ...values, frameCount: Number(event.target.value) })}
                  />
                </FieldLabel>
              </Field>
              <Field>
                <FieldLabel className="ui-field block">
                  {t("settings.openAi.frameDuration")}
                  <Input
                    type="number"
                    required
                    min={20}
                    max={2000}
                    step={10}
                    value={values.frameDurationMs ?? 200}
                    onChange={event => onChange({ ...values, frameDurationMs: Number(event.target.value) })}
                  />
                </FieldLabel>
              </Field>
              <p className="ui-muted">{t("settings.openAi.gifHint")}</p>
            </>
          ) : null}
        </>
      ) : null}
    </>
  );
}
