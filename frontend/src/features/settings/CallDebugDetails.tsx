import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link,Navigate } from "react-router";
import { HTTP_STATUS,ApiError,getCallDebug,type CallLog } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { LlmStreamDetails } from "./LlmStreamDetails";
import { CopyButton, FormattedCallExchange } from "./FormattedCallExchange";


/** Bodies are fetched only while the detail dialog is open and discarded from the query cache on closing. */
export function CallDebugDetails({ id, kind, generationId }: { id: string; kind?: CallLog["kind"]; generationId?: string | null }) {
  useLocale();
  const [mode, setMode] = useState<"formatted" | "raw">("formatted");
  const details = useQuery({ queryKey: ["call-debug", id], queryFn: () => getCallDebug(id), retry: false, gcTime: 0 });
  if (details.error instanceof ApiError && details.error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = details.error instanceof ApiError && details.error.status === HTTP_STATUS.FORBIDDEN;
  return <section className="call-log-debug" aria-label={t("logs.details.title")}>
    <h3>{t("logs.details.title")}</h3>
    {details.isPending ? <LoadingState compact label={t("logs.details.loading")} /> : null}
    {details.isError ? <Notice tone="danger" title={forbidden ? t("logs.details.forbidden") : t("logs.details.loadFailed")}>
      <Button variant="outline"  type="button" disabled={details.isFetching} onClick={() => void details.refetch()}>{t("logs.details.retry")}</Button>
    </Notice> : null}
    {details.data && !details.isError ? <>
      {details.data.exchanges.length || details.data.llmStream?.content ? <div className="call-log-view-toggle" role="group" aria-label={t("logs.details.displayMode")}>
        <Button variant="outline"  type="button" aria-pressed={mode === "formatted"} onClick={() => setMode("formatted")}>{t("logs.details.formatted")}</Button>
        <Button variant="outline"  type="button" aria-pressed={mode === "raw"} onClick={() => setMode("raw")}>{t("logs.details.raw")}</Button>
      </div> : null}
      {details.data.llmStream ? <LlmStreamDetails log={details.data.llmStream} mode={mode}
        requestBody={details.data.exchanges.at(-1)?.requestBody ?? null} displayedGenerationId={generationId} /> : null}
      <Button variant="ghost" type="button" disabled={details.isFetching} onClick={() => void details.refetch()}>{t("logs.stream.reload")}</Button>
      {details.data.captured ? <>
      <p className="ui-muted">{t(kind === "LLM" || details.data.llmStream ? "logs.details.llmPrivacyHint" : "logs.details.privacyHint")}</p>
      {details.data.exchanges.length === 0 ? <p className="ui-muted">{t("logs.details.requestMissingHint")}</p> : null}
      {details.data.exchanges.map((exchange, index) => <div className="call-log-exchange" key={index}>
        <h4>{t("logs.details.requestSummary", { "0": index + 1, "1": exchange.method, "2": exchange.responseStatus === null ? t("logs.details.responseMissing") : `HTTP ${exchange.responseStatus}` })}</h4>
        <dl className="call-log-metadata" aria-label={t("logs.calls.providerIdentifiers")}>
          {Object.keys(exchange.responseIdentifiers ?? {}).length ? Object.entries(exchange.responseIdentifiers).map(([source, value]) =>
            <div key={source}><dt>{t("logs.calls.providerIdentifier", { "0": source })}</dt><dd>{value} <CopyButton content={value} /></dd></div>)
            : <div><dt>{t("logs.calls.providerIdentifiers")}</dt><dd className="ui-muted">{t("logs.calls.providerIdentifiersMissing")}</dd></div>}
        </dl>
        <p className="call-log-url">{exchange.url}</p>
        <FormattedCallExchange exchange={exchange} mode={mode} hideResponse={Boolean(details.data.llmStream?.content)}
          displayedGenerationId={generationId} llm={!details.data.llmStream?.content && (kind === undefined || kind === "LLM")} />
      </div>)}
    </> : <p className="ui-muted">{t("logs.details.debugDisabledPrefix")}<Link to="/settings/general?tab=logs">{t("common.systemSettings")}</Link>{t("logs.details.enableDebugSuffix")}</p>}
    </> : null}
  </section>;
}
