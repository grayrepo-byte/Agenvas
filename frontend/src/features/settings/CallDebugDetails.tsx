import { useQuery } from "@tanstack/react-query";
import { CaretRight } from "@/shared/ui/icons";
import { useState } from "react";
import { Link,Navigate } from "react-router";
import { HTTP_STATUS,ApiError,getCallDebug,type CallLog } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { LlmStreamDetails } from "./LlmStreamDetails";
import { CopyButton, FormattedCallExchange } from "./FormattedCallExchange";


/** Bodies are fetched only while the detail dialog is open and discarded from the query cache on closing. */
export function CallDebugDetails({ id, kind, generationId }: { id: string; kind?: CallLog["kind"]; generationId?: string | null }) {
  useLocale();
  const [mode, setMode] = useState<"formatted" | "raw">("formatted");
  const details = useQuery({ queryKey: ["call-debug", id], queryFn: () => getCallDebug(id), retry: false, gcTime: 0 });
  if (details.error instanceof ApiError && details.error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = details.error instanceof ApiError && details.error.status === HTTP_STATUS.FORBIDDEN;
  // The final HTTP request is already paired with the single stream response above.
  const streamRequest = details.data?.llmStream?.content ? details.data.exchanges.at(-1) : undefined;
  return <section className="call-log-debug" aria-label={t("logs.details.title")}>
    <header className="call-log-debug-header">
      <h3>{t("logs.details.title")}</h3>
      {details.data && !details.isError && (details.data.exchanges.length > 0 || details.data.llmStream?.content) ?
        <ToggleGroup type="single" variant="outline" size="sm" value={mode}
          aria-label={t("logs.details.displayMode")} onValueChange={(value) => {
            if (value === "formatted" || value === "raw") setMode(value);
          }}>
          <ToggleGroupItem value="formatted">{t("logs.details.formatted")}</ToggleGroupItem>
          <ToggleGroupItem value="raw">{t("logs.details.raw")}</ToggleGroupItem>
        </ToggleGroup> : null}
    </header>
    {details.isPending ? <LoadingState compact label={t("logs.details.loading")} /> : null}
    {details.isError ? <Notice tone="danger" title={forbidden ? t("logs.details.forbidden") : t("logs.details.loadFailed")}>
      <Button variant="outline"  type="button" disabled={details.isFetching} onClick={() => void details.refetch()}>{t("logs.details.retry")}</Button>
    </Notice> : null}
    {details.data && !details.isError ? <>
      {details.data.captured ? <p className="ui-muted call-log-privacy-hint">{t(kind === "LLM" || details.data.llmStream ? "logs.details.llmPrivacyHint" : "logs.details.privacyHint")}</p> : null}
      {details.data.llmStream ? <LlmStreamDetails log={details.data.llmStream} mode={mode}
        requestBody={details.data.exchanges.at(-1)?.requestBody ?? null} displayedGenerationId={generationId} /> : null}
      {details.data.captured ? <>
        {details.data.exchanges.length === 0 ? <p className="ui-muted">{t("logs.details.requestMissingHint")}</p> : null}
        {details.data.exchanges.length > 0 ? <section className="call-log-http" aria-label={t("logs.details.httpRequests")}>
          <h4>{t("logs.details.httpRequests")}</h4>
          {details.data.exchanges.map((exchange, index) => <details className="call-log-exchange llm-log-section" key={index} open={!details.data.llmStream?.content}>
            <summary><CaretRight size={16} aria-hidden />{t("logs.details.requestSummary", { "0": index + 1, "1": exchange.method, "2": exchange.responseStatus === null ? t("logs.details.responseMissing") : `HTTP ${exchange.responseStatus}` })}</summary>
            <div className="call-log-exchange-content">
              <p className="call-log-url">{exchange.url}</p>
              <dl className="call-log-metadata" aria-label={t("logs.calls.providerIdentifiers")}>
                {Object.keys(exchange.responseIdentifiers ?? {}).length ? Object.entries(exchange.responseIdentifiers).map(([source, value]) =>
                  <div key={source}><dt>{t("logs.calls.providerIdentifier", { "0": source })}</dt><dd>{value} <CopyButton content={value} /></dd></div>)
                  : <div><dt>{t("logs.calls.providerIdentifiers")}</dt><dd className="ui-muted">{t("logs.calls.providerIdentifiersMissing")}</dd></div>}
              </dl>
              {exchange !== streamRequest ? <FormattedCallExchange exchange={exchange} mode={mode} hideResponse={Boolean(details.data.llmStream?.content)}
                displayedGenerationId={generationId} llm={!details.data.llmStream?.content && (kind === undefined || kind === "LLM")} /> : null}
            </div>
          </details>)}
        </section> : null}
      </> : <p className="ui-muted">{t("logs.details.debugDisabledPrefix")}<Link to="/settings/general?tab=logs">{t("common.systemSettings")}</Link>{t("logs.details.enableDebugSuffix")}</p>}
    </> : null}
  </section>;
}
