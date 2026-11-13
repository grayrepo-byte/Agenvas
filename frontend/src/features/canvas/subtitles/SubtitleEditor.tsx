import { useRef, useState } from "react";
import { t, useLocale } from "../../../shared/i18n";
import { Button } from "../../../shared/ui/primitives/button";
import { Textarea } from "../../../shared/ui/primitives/textarea";
import { Checkbox } from "../../../shared/ui/primitives/checkbox";
import { Select } from "../../../shared/ui/Select";
import { CreatorNumberInput } from "../../../shared/ui/CreatorNumberInput";
import { SUBTITLE_MAX_CUES } from "./subtitleExchange";
import type { useSubtitleWorkbench } from "./useSubtitleWorkbench";
import "./SubtitleEditor.css";

const PAGE_SIZE = 50;
export function SubtitleEditor({
  workbench: work,
  disabled: locked = false,
  included,
  onIncludedChange,
}: {
  workbench: ReturnType<typeof useSubtitleWorkbench>;
  disabled?: boolean;
  included?: boolean;
  onIncludedChange?: (included: boolean) => void;
}) {
  const locale = useLocale();
  const languageNames = new Intl.DisplayNames([locale], { type: "language" });
  const fileInput = useRef<HTMLInputElement>(null);
  const [page, setPage] = useState(0);
  const pages = Math.max(1, Math.ceil(work.cues.length / PAGE_SIZE)),
    current = Math.min(page, pages - 1);
  const disabled = locked || work.saving || work.operating;
  const unavailable = work.capabilities?.availability !== "AVAILABLE";
  const output = work.task?.output;
  return (
    <section className="subtitle-editor" aria-label={t("subtitle.heading")}>
      <p className="video-edit-panel-hint">{t("subtitle.draftNote")}</p>
      {work.sources.length === 0 ? (
        <p>{t("subtitle.noSource")}</p>
      ) : (
        <label>
          {t("subtitle.source")}
          <Select
            value={work.selectedSource?.versionId ?? ""}
            disabled={disabled}
            onChange={event => {
              void work.selectSource(event.target.value);
              setPage(0);
            }}
          >
            {work.sources.map(source => (
              <option key={source.versionId} value={source.versionId}>
                {source.title}
              </option>
            ))}
          </Select>
        </label>
      )}
      {work.loading && <p role="status">{t("subtitle.loading")}</p>}
      {work.error && (
        <div role="alert">
          {work.error}
          <Button type="button" size="sm" variant="outline" onClick={work.reload}>
            {t("subtitle.retry")}
          </Button>
        </div>
      )}
      {work.conflict && (
        <Button type="button" size="sm" variant="outline" onClick={() => void work.retryMine()} disabled={disabled}>
          {t("subtitle.retryMine")}
        </Button>
      )}
      {!work.selected && !work.loading && work.selectedSource && (
        <Button type="button" onClick={() => void work.create()} disabled={disabled}>
          {t("subtitle.create")}
        </Button>
      )}
      {work.selected && (
        <>
          {onIncludedChange && (
            <label className="subtitle-include">
              <Checkbox
                checked={included}
                disabled={disabled}
                onCheckedChange={checked => onIncludedChange(checked === true)}
              />
              {t("subtitle.include")}
            </label>
          )}
          {unavailable && (
            <p role="status">
              {t(
                work.capabilities?.availability === "NOT_CONFIGURED"
                  ? "subtitle.notConfigured"
                  : "subtitle.unavailable",
              )}{" "}
              <a href="/settings/general?tab=general#local-asr">{t("localAsr.openSettings")}</a>
            </p>
          )}
          <div className="subtitle-actions">
            <label>
              {t("subtitle.language")}
              <Select
                value={work.language}
                disabled={disabled || work.activeTask}
                onChange={event => work.setLanguage(event.target.value)}
              >
                <option value="auto">{t("subtitle.autoLanguage")}</option>
                {["zh", "en", "ja", "ru"].map(language => (
                  <option key={language} value={language}>
                    {languageNames.of(language)}
                  </option>
                ))}
              </Select>
            </label>
            <Button
              type="button"
              size="sm"
              onClick={() => void work.recognize()}
              disabled={disabled || unavailable || work.activeTask || work.taskPending}
            >
              {t("subtitle.recognize")}
            </Button>
            {work.activeTask && (
              <Button type="button" size="sm" variant="outline" onClick={() => void work.cancel()} disabled={disabled}>
                {t("subtitle.cancel")}
              </Button>
            )}
          </div>
          {work.activeTask && <p role="status">{t("subtitle.recognizing")}</p>}
          {work.task && ["FAILED", "CANCELED", "UNKNOWN", "BLOCKED"].includes(work.task.status) && (
            <p role="status">
              {t("subtitle.recognitionFailed")} {work.task.errorCode}
            </p>
          )}
          {output?.applied === false && (
            <p role="status">
              {t("subtitle.pendingResult")}
              <Button type="button" size="sm" variant="outline" onClick={work.loadResult} disabled={disabled}>
                {t("subtitle.loadResult")}
              </Button>
            </p>
          )}
          <div className="subtitle-actions">
            <Button type="button" size="sm" onClick={() => void work.flush()} disabled={disabled || !work.dirty}>
              {t(work.saving ? "subtitle.saving" : "subtitle.save")}
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => fileInput.current?.click()}
              disabled={disabled}
            >
              {t("subtitle.import")}
            </Button>
            <input
              ref={fileInput}
              className="subtitle-file-input"
              type="file"
              accept=".srt,.vtt,text/vtt,application/x-subrip"
              aria-label={t("subtitle.import")}
              onChange={event => {
                const file = event.target.files?.[0];
                event.target.value = "";
                if (file) void work.importFile(file);
              }}
            />
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => void work.exportFile("SRT")}
              disabled={disabled}
            >
              {t("subtitle.exportSrt")}
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => void work.exportFile("VTT")}
              disabled={disabled}
            >
              {t("subtitle.exportVtt")}
            </Button>
          </div>
          {!work.dirty && !work.saving && <small role="status">{t("subtitle.saved")}</small>}
          {work.cues.length === 0 && <p>{t("subtitle.empty")}</p>}
          {work.cues.slice(current * PAGE_SIZE, (current + 1) * PAGE_SIZE).map((cue, offset) => {
            const index = current * PAGE_SIZE + offset;
            const edit = (change: Partial<typeof cue>) =>
              work.updateCues(work.cues.map((value, i) => (i === index ? { ...value, ...change } : value)));
            return (
              <div className="subtitle-row" key={`${work.selected!.id}:${index}`}>
                <label>
                  {t("subtitle.startTime")}
                  <CreatorNumberInput
                    aria-label={t("subtitle.start", { index: index + 1 })}
                    min={0}
                    step={1}
                    value={Number.isFinite(cue.startMs) ? cue.startMs : undefined}
                    onValueChange={value => edit({ startMs: value ?? NaN })}
                    disabled={disabled}
                  />
                </label>
                <label>
                  {t("subtitle.endTime")}
                  <CreatorNumberInput
                    aria-label={t("subtitle.end", { index: index + 1 })}
                    min={1}
                    step={1}
                    value={Number.isFinite(cue.endMs) ? cue.endMs : undefined}
                    onValueChange={value => edit({ endMs: value ?? NaN })}
                    disabled={disabled}
                  />
                </label>
                <Textarea
                  aria-label={t("subtitle.ctext", { index: index + 1 })}
                  value={cue.text}
                  maxLength={500}
                  onChange={event => edit({ text: event.target.value })}
                  disabled={disabled}
                />
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  aria-label={t("subtitle.remove", { index: index + 1 })}
                  onClick={() => work.updateCues(work.cues.filter((_, i) => i !== index))}
                  disabled={disabled}
                >
                  ×
                </Button>
              </div>
            );
          })}
          {pages > 1 && (
            <div className="subtitle-actions">
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={current === 0}
                onClick={() => setPage(current - 1)}
              >
                {t("subtitle.previous")}
              </Button>
              <span>{t("subtitle.page", { page: current + 1, pages })}</span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={current + 1 >= pages}
                onClick={() => setPage(current + 1)}
              >
                {t("subtitle.next")}
              </Button>
            </div>
          )}
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={() => {
              const start = Math.min(work.cues.at(-1)?.endMs ?? 0, work.selected!.sourceDurationMs - 1);
              work.updateCues([
                ...work.cues,
                { text: "", startMs: start, endMs: Math.min(work.selected!.sourceDurationMs, start + 1000) },
              ]);
              setPage(Math.floor(work.cues.length / PAGE_SIZE));
            }}
            disabled={disabled || work.cues.length >= SUBTITLE_MAX_CUES}
          >
            {t("subtitle.add")}
          </Button>
        </>
      )}
    </section>
  );
}
