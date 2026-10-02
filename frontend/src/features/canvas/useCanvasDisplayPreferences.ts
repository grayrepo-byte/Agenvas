import { t, type MessageKey } from "../../shared/i18n";
import { useEffect, useState } from "react";

export type CanvasDisplayPreferences = {
  alwaysShowConnections: boolean;
  connectionFlowEnabled: boolean;
};

export const DEFAULT_CANVAS_DISPLAY_PREFERENCES: Readonly<CanvasDisplayPreferences> = Object.freeze({
  alwaysShowConnections: true,
  connectionFlowEnabled: true,
});
const STORAGE_PREFIX = "agenvas:canvas-display";
const STORAGE_VERSION = 1;
const READ_ERROR = "canvas.displayPreferences.readFailed";
const SAVE_ERROR = "canvas.displayPreferences.saveFailed";

export function canvasDisplayStorageKey(userId: string, projectId: string) {
  return `${STORAGE_PREFIX}:${encodeURIComponent(userId)}:${encodeURIComponent(projectId)}`;
}

type PreferenceState = {
  storageKey: string | null;
  preferences: CanvasDisplayPreferences;
  persistenceError: MessageKey | null;
};

function readPreferences(storageKey: string | null): PreferenceState {
  const defaults = { storageKey, preferences: DEFAULT_CANVAS_DISPLAY_PREFERENCES, persistenceError: null };
  if (!storageKey) return defaults;
  try {
    const raw = localStorage.getItem(storageKey);
    if (!raw) return defaults;
    const saved: unknown = JSON.parse(raw);
    if (typeof saved !== "object" || saved === null || !("version" in saved)
        || saved.version !== STORAGE_VERSION) return defaults;
    return { ...defaults, preferences: {
      alwaysShowConnections: "alwaysShowConnections" in saved && typeof saved.alwaysShowConnections === "boolean"
        ? saved.alwaysShowConnections : DEFAULT_CANVAS_DISPLAY_PREFERENCES.alwaysShowConnections,
      connectionFlowEnabled: "connectionFlowEnabled" in saved && typeof saved.connectionFlowEnabled === "boolean"
        ? saved.connectionFlowEnabled : DEFAULT_CANVAS_DISPLAY_PREFERENCES.connectionFlowEnabled,
    } };
  } catch {
    return { ...defaults, persistenceError: READ_ERROR };
  }
}

/** Local presentation preferences only; the server remains authoritative for every relation. */
export function useCanvasDisplayPreferences(userId: string | undefined, projectId: string) {
  const storageKey = userId ? canvasDisplayStorageKey(userId, projectId) : null;
  const [state, setState] = useState(() => readPreferences(storageKey));
  // Resolve a newly authenticated user/project before rendering its preferences, avoiding a stale
  // preference frame or a write of the previous scope's defaults during hydration.
  if (state.storageKey !== storageKey) setState(readPreferences(storageKey));

  useEffect(() => {
    if (!storageKey) return;
    const synchronize = (event: StorageEvent) => {
      if (event.key === storageKey || event.key === null) setState(readPreferences(storageKey));
    };
    window.addEventListener("storage", synchronize);
    return () => window.removeEventListener("storage", synchronize);
  }, [storageKey]);

  function save(preferences: CanvasDisplayPreferences) {
    if (!storageKey) return;
    let persistenceError: MessageKey | null = null;
    try {
      localStorage.setItem(storageKey, JSON.stringify({ version: STORAGE_VERSION, ...preferences }));
    } catch {
      persistenceError = SAVE_ERROR;
    }
    setState({ storageKey, preferences, persistenceError });
  }

  return {
    preferences: state.preferences,
    persistenceError: state.persistenceError ? t(state.persistenceError) : null,
    setPreference: (key: keyof CanvasDisplayPreferences, enabled: boolean) =>
      save({ ...state.preferences, [key]: enabled }),
    retrySave: () => save(state.preferences),
    ready: storageKey !== null,
  };
}
