import type { CallDebug } from "../../shared/api/client";
import { formatNumber, t, useLocale } from "../../shared/i18n";
import { CallLogText } from "./FormattedCallExchange";

type StreamLog = NonNullable<CallDebug["llmStream"]>;

/** A single public model response with timing and actual usage metadata. */
export function LlmStreamDetails({ log }: { log: StreamLog }) {
  useLocale();
  const { metrics, content } = log;
  const time = (value: number | null) => value === null ? t("common.notRecorded") : `${formatNumber(value)} ms`;
  const count = (value: number | null) => value === null ? t("common.notRecorded") : formatNumber(value);
  const status = metrics.status === "COMPLETED" ? t("logs.stream.completed") : metrics.status === "CANCELED" ? t("common.canceled") : t("common.failed");
  return <div className="llm-log">
    <h4>{t("logs.stream.title")}</h4>
    <dl className="llm-log-facts">
      <Fact title={t("logs.stream.firstChunk")} value={time(metrics.firstChunkMs)} />
      <Fact title={t("logs.stream.firstText")} value={time(metrics.firstTextMs)} />
      <Fact title={t("logs.stream.duration")} value={time(metrics.durationMs)} />
      <Fact title={t("logs.stream.chunks")} value={count(metrics.chunkCount)} />
      <Fact title={t("logs.calls.result")} value={status} />
      <Fact title={t("common.model")} value={metrics.model ?? t("common.notRecorded")} />
      <Fact title={t("logs.exchange.generationId")} value={metrics.responseId ?? t("common.notRecorded")} />
      <Fact title={t("logs.exchange.finishReason")} value={metrics.finishReasons.join(", ") || t("common.notRecorded")} />
      <Fact title={t("logs.stream.error")} value={metrics.errorCode ?? t("common.notRecorded")} />
    </dl>
    <dl className="llm-log-usage">
      <Fact title="Prompt tokens" value={count(metrics.promptTokens)} />
      <Fact title="Completion tokens" value={count(metrics.completionTokens)} />
      <Fact title="Total tokens" value={count(metrics.totalTokens)} />
    </dl>
    <p className="ui-muted">{t("logs.stream.timingHint")}</p>
    {content ? <>
      <p className="ui-muted">{t("logs.stream.contentHint")}</p>
      {metrics.status !== "COMPLETED" || content.truncated ? <p className="ui-muted">{t("logs.stream.partial")}</p> : null}
      <CallLogText title={t("logs.stream.response")} content={content.response} json />
    </> : <p className="ui-muted">{t("logs.stream.noContent")}</p>}
  </div>;
}
function Fact({ title, value }: { title: string; value: string }) {
  return <div><dt>{title}</dt><dd>{value}</dd></div>;
}
