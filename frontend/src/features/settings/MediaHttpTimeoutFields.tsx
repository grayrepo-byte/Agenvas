import { useId } from "react";
import { t, useLocale } from "../../shared/i18n";
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import type { MediaCapabilityDraft } from "./mediaCapabilityDraft";
import {
  HTTP_TIMEOUT_FIELDS,
  HTTP_TIMEOUT_MAX_SECONDS,
  mediaHttpTimeoutDraft,
  mediaHttpTimeoutErrors,
  type MediaHttpTimeouts,
} from "./mediaHttpTimeouts";

const TIMEOUT_HINTS = {
  connectSeconds: "connectHint",
  writeSeconds: "writeHint",
  readSeconds: "readHint",
  callSeconds: "callHint",
} as const;

/** A displayed default is kept outside API settings until publication or an explicit field edit. */
export function MediaHttpTimeoutFields({
  values,
  defaults,
  onChange,
  creating = false,
  hideLegend = false,
}: {
  values: MediaCapabilityDraft;
  defaults?: MediaHttpTimeouts;
  onChange: (value: MediaCapabilityDraft) => void;
  creating?: boolean;
  hideLegend?: boolean;
}) {
  useLocale();
  const id = useId();
  const policy = values.httpTimeouts ?? defaults;
  const raw = values.httpTimeoutDraft ?? mediaHttpTimeoutDraft(policy);
  const required = creating || Boolean(policy || values.httpTimeoutDraft);
  const errors = required ? mediaHttpTimeoutErrors(raw) : {};
  return (
    <FieldSet className="media-config-section">
      <FieldLegend className={hideLegend ? "sr-only" : undefined}>{t("settings.httpTimeouts.title")}</FieldLegend>
      <FieldDescription>{t("settings.httpTimeouts.scopeHint")}</FieldDescription>
      {!creating && !values.httpTimeouts && !values.httpTimeoutDraft && defaults ? (
        <FieldDescription>{t("settings.httpTimeouts.legacyHint")}</FieldDescription>
      ) : null}
      {!policy && !values.httpTimeoutDraft ? (
        <FieldDescription>{t("settings.httpTimeouts.defaultsUnavailable")}</FieldDescription>
      ) : null}
      <FieldGroup className="ui-form-grid">
        {HTTP_TIMEOUT_FIELDS.map(field => (
          <Field key={field} data-invalid={Boolean(errors[field])}>
            <FieldLabel htmlFor={`${id}-${field}`}>{t(`settings.httpTimeouts.${field}`)}</FieldLabel>
            <Input
              id={`${id}-${field}`}
              type="number"
              inputMode="numeric"
              min={1}
              max={HTTP_TIMEOUT_MAX_SECONDS[field]}
              step={1}
              required={required}
              value={raw[field]}
              aria-invalid={Boolean(errors[field])}
              aria-describedby={`${id}-${field}-hint${errors[field] ? ` ${id}-${field}-error` : ""}`}
              ref={input => {
                input?.setCustomValidity(errors[field] ?? "");
              }}
              onChange={event => onChange({ ...values, httpTimeoutDraft: { ...raw, [field]: event.target.value } })}
            />
            <FieldDescription id={`${id}-${field}-hint`}>
              {t(`settings.httpTimeouts.${TIMEOUT_HINTS[field]}`)}
            </FieldDescription>
            {errors[field] ? <FieldError id={`${id}-${field}-error`}>{errors[field]}</FieldError> : null}
          </Field>
        ))}
      </FieldGroup>
    </FieldSet>
  );
}
