import { t, useLocale } from "../i18n";
import { useEffect, useState } from "react";
import "./LoadingState.css";

/**
 * Adapted from Beautiful UI's MIT-licensed Loading State (Drive variant).
 * https://github.com/slev12397/beautiful-ui/blob/main/components/primitives/LoadingState.tsx
 * Copyright (c) 2026 Shane Levine. See beautiful-ui-LICENSE.txt.
 * The animation indicates activity; only the caller's persisted state names the work.
 */
const GRID_COLUMNS = 3;
const GRID_CELLS = GRID_COLUMNS * GRID_COLUMNS;
const GRID_CENTER_ROW = 1;
const WAVE_STAGGER_MS = 90;
const ELAPSED_UPDATE_MS = 1_000;
const MILLISECONDS_PER_SECOND = 1_000;
const SECONDS_PER_MINUTE = 60;
const CHEVRON_DELAYS = Array.from({ length: GRID_CELLS }, (_, index) => {
  const row = Math.floor(index / GRID_COLUMNS);
  const column = index % GRID_COLUMNS;
  return (column + Math.abs(row - GRID_CENTER_ROW)) * WAVE_STAGGER_MS;
});

type LoadingStateProps = {
  label: string;
  /** Server-provided task start time. Omit when the actual start is unknown. */
  startedAt?: string;
  compact?: boolean;
};

function elapsedLabel(elapsedMs: number) {
  const seconds = Math.floor(elapsedMs / MILLISECONDS_PER_SECOND);
  if (seconds < SECONDS_PER_MINUTE) return `${seconds}s`;
  return `${Math.floor(seconds / SECONDS_PER_MINUTE)}m ${seconds % SECONDS_PER_MINUTE}s`;
}

export function LoadingState({ label, startedAt, compact = false }: LoadingStateProps) {
  useLocale();
  const parsedStart = startedAt === undefined ? Number.NaN : Date.parse(startedAt);
  const startTime = Number.isFinite(parsedStart) ? parsedStart : undefined;
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (startTime === undefined) return;
    // Read the clock on every tick so a backgrounded tab does not accumulate drift.
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), ELAPSED_UPDATE_MS);
    return () => window.clearInterval(timer);
  }, [startTime]);

  const elapsed = startTime === undefined ? undefined : elapsedLabel(Math.max(0, now - startTime));

  return <div
    className={`canvas-loading-state${compact ? " canvas-loading-state--compact" : ""}`}
    role="status"
    aria-label={label}
  >
    <span className="canvas-loading-state__grid" aria-hidden="true">
      {CHEVRON_DELAYS.map((delay, index) => <span
        key={index}
        className="canvas-loading-state__pixel"
        style={{ animationDelay: `${delay}ms` }}
      />)}
    </span>
    <span className="canvas-loading-state__label">{label}</span>
    {elapsed !== undefined && <span
      className="canvas-loading-state__elapsed"
      aria-hidden="true"
      title={t("自任务开始至今的时间，不代表完成进度")}
    >{elapsed}</span>}
  </div>;
}
