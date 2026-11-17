import { useMutation } from "@tanstack/react-query";
import { useCallback, useEffect, useRef } from "react";
import { Navigate } from "react-router";
import { Question } from "@/shared/ui/icons";
import {
  ApiError,
  discoverLocalService,
  HTTP_STATUS,
  type LocalServiceDiscoveryResult,
  type LocalServiceKind,
} from "../../shared/api/client";
import { t, useLocale, type MessageKey } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, StatusBadge } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription } from "../../shared/ui/primitives/field";
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "../../shared/ui/primitives/popover";

const SERVICES = { OLLAMA: { name: "Ollama", port: 11434 }, COMFYUI: { name: "ComfyUI", port: 8188 } } as const;
const STATUS_MESSAGES = {
  FOUND: "localServices.found",
  NOT_FOUND: "localServices.notFound",
  UNRESOLVED: "localServices.unresolved",
  TIMEOUT: "localServices.timeout",
  UNEXPECTED_RESPONSE: "localServices.unexpectedResponse",
  AUTH_REQUIRED: "localServices.authRequired",
  UNSUPPORTED_RUNTIME: "localServices.unsupportedRuntime",
} as const satisfies Record<LocalServiceDiscoveryResult["status"], MessageKey>;
const RUNTIME_MESSAGES = {
  CONTAINER: "localServices.container",
  NATIVE: "localServices.native",
  UNKNOWN: "localServices.unknown",
} as const satisfies Record<LocalServiceDiscoveryResult["runtime"], MessageKey>;

function failureMessage(error: Error): MessageKey {
  if (error instanceof ApiError) {
    if (error.status === HTTP_STATUS.FORBIDDEN) return "localServices.forbidden";
    if (error.status === HTTP_STATUS.TOO_MANY_REQUESTS) return "localServices.busy";
    if (error.status === HTTP_STATUS.SERVICE_UNAVAILABLE) return "localServices.unavailable";
  }
  return "localServices.failed";
}

/** Discovery never saves configuration. Only an explicit selection changes the parent form's draft. */
export function LocalServiceDiscovery({
  service,
  disabled = false,
  onSelect,
}: {
  service: LocalServiceKind;
  disabled?: boolean;
  onSelect: (endpoint: string) => void;
}) {
  useLocale();
  const sequence = useRef(0);
  const active = useRef<AbortController | null>(null);
  const { mutate, reset, data, error, isPending } = useMutation({
    mutationFn: async (request: { service: LocalServiceKind; signal: AbortSignal; sequence: number }) => {
      const result = await discoverLocalService(request.service, request.signal);
      // A transport may finish after abort. Neither cancelled nor superseded results can fill a draft.
      if (request.signal.aborted || request.sequence !== sequence.current)
        throw new DOMException("Cancelled", "AbortError");
      if (result.service !== request.service) throw new Error("Unexpected discovery service");
      return result;
    },
    retry: false,
  });
  const cancel = useCallback(() => {
    sequence.current++;
    active.current?.abort();
    active.current = null;
    reset();
  }, [reset]);
  useEffect(() => {
    reset();
    return () => {
      sequence.current++;
      active.current?.abort();
      active.current = null;
    };
  }, [service, reset]);
  useEffect(() => {
    if (disabled) cancel();
  }, [disabled, cancel]);

  function discover() {
    if (disabled || isPending) return;
    active.current?.abort();
    active.current = new AbortController();
    mutate({ service, signal: active.current.signal, sequence: ++sequence.current });
  }
  if (error instanceof ApiError && error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const details = SERVICES[service];
  const result = data?.service === service && !disabled ? data : undefined;
  const endpoint = result?.status === "FOUND" ? result.endpoint : null;
  return (
    <Field className="col-span-full gap-2" data-disabled={disabled} aria-busy={isPending}>
      <FieldDescription>{t("localServices.scope", { "0": details.name, "1": details.port })}</FieldDescription>
      <div className="ui-form-actions">
        <Button type="button" variant="outline" disabled={disabled || isPending} onClick={discover}>
          {t("localServices.discover", { "0": details.name })}
        </Button>
        <Popover>
          <PopoverTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              disabled={disabled}
              aria-label={t("localServices.help")}
            >
              <Question aria-hidden />
            </Button>
          </PopoverTrigger>
          <PopoverContent align="start" className="max-w-[calc(100vw-2rem)]" aria-label={t("localServices.help")}>
            <PopoverHeader>
              <PopoverTitle>{t("localServices.help")}</PopoverTitle>
              <PopoverDescription>{t("localServices.requestHint")}</PopoverDescription>
              <PopoverDescription>{t("localServices.hostHint")}</PopoverDescription>
              <PopoverDescription>{t("localServices.manualHint")}</PopoverDescription>
              <PopoverDescription>{t("localServices.listeningHint")}</PopoverDescription>
            </PopoverHeader>
          </PopoverContent>
        </Popover>
        {isPending ? (
          <Button type="button" variant="ghost" onClick={cancel}>
            {t("localServices.cancel")}
          </Button>
        ) : null}
      </div>
      {isPending ? <LoadingState compact label={t("localServices.checking")} /> : null}
      {error ? <Notice tone="danger">{t(failureMessage(error))}</Notice> : null}
      {result ? (
        <>
          {result.status === "FOUND" ? (
            <div className="ui-form-actions">
              <StatusBadge tone="success">{t(STATUS_MESSAGES.FOUND)}</StatusBadge>
              <span className="ui-muted">{t(RUNTIME_MESSAGES[result.runtime])}</span>
            </div>
          ) : (
            <>
              <FieldDescription>{t(RUNTIME_MESSAGES[result.runtime])}</FieldDescription>
              {result.status === "NOT_FOUND" ? (
                <EmptyState title={t(STATUS_MESSAGES.NOT_FOUND)} />
              ) : (
                <Notice tone="warning">{t(STATUS_MESSAGES[result.status])}</Notice>
              )}
            </>
          )}
          {endpoint ? (
            <div className="ui-form-actions">
              <p className="ui-muted min-w-0 break-all">{endpoint}</p>
              <Button
                type="button"
                variant="outline"
                disabled={disabled || isPending}
                onClick={() => onSelect(endpoint)}
              >
                {t("localServices.fill")}
              </Button>
            </div>
          ) : null}
          {result.status === "FOUND" ? (
            <FieldDescription>
              {t("localServices.notVerified")}
              {endpoint ? <> {t("localServices.draftOnly")}</> : null}
            </FieldDescription>
          ) : null}
        </>
      ) : null}
    </Field>
  );
}
