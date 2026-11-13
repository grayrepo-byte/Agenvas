import { ArrowUpRight, CheckCircle, Plus, PlugsConnected, ShieldCheck } from "@/shared/ui/icons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, Navigate } from "react-router";
import {
  activateLlmConnection,
  ApiError,
  diagnoseLlmConnection,
  getCurrentUser,
  getSystemDiagnostics,
  HTTP_STATUS,
  listLlmConnections,
  type LlmConnection,
  type LlmConnections,
} from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { LlmConnectionEditor } from "./LlmConnectionEditor";
import "./SettingsPages.css";
import "./LlmSettingsPage.css";

const CONNECTIONS_KEY = ["settings", "llm", "connections"] as const;

/** Named connections are editable heads; selecting one changes only future requests. */
export function LlmSettingsPage() {
  useLocale();
  const client = useQueryClient();
  const user = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const connections = useQuery({
    queryKey: CONNECTIONS_KEY,
    queryFn: listLlmConnections,
    enabled: user.isSuccess,
    retry: false,
  });
  const mode = useQuery({
    queryKey: ["settings", "diagnostics"],
    queryFn: getSystemDiagnostics,
    enabled: user.isSuccess,
    retry: false,
  });
  const [editor, setEditor] = useState<{ id: string; connection: LlmConnection | null } | null>(null);
  const [diagnostic, setDiagnostic] = useState<LlmConnection | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [diagnosticReading, setDiagnosticReading] = useState(false);
  const [diagnosticRefreshError, setDiagnosticRefreshError] = useState("");
  const [feedback, setFeedback] = useState("");
  const catalog = connections.data;
  const active = catalog?.items.find(item => item.id === catalog.activeConnectionId);
  const diagnosticLatest = catalog?.items.find(item => item.id === diagnostic?.id);
  const diagnosticChanged = !!diagnostic && diagnosticLatest?.version !== diagnostic.version;

  async function acceptCatalog(value: LlmConnections) {
    await client.cancelQueries({ queryKey: CONNECTIONS_KEY });
    client.setQueryData(CONNECTIONS_KEY, value);
    void client.invalidateQueries({ queryKey: ["settings", "llm"], exact: true });
    void client.invalidateQueries({ queryKey: ["settings", "diagnostics"] });
  }
  async function acceptConnection(value: LlmConnection) {
    await client.cancelQueries({ queryKey: CONNECTIONS_KEY });
    client.setQueryData<LlmConnections>(CONNECTIONS_KEY, current =>
      current
        ? {
            ...current,
            items: current.items.some(item => item.id === value.id)
              ? current.items.map(item => (item.id === value.id && item.version <= value.version ? value : item))
              : [...current.items, value],
            activeVersion:
              current.activeConnectionId === value.id
                ? Math.max(current.activeVersion, value.version)
                : current.activeVersion,
          }
        : undefined,
    );
    void client.invalidateQueries({ queryKey: ["settings", "llm"] });
    void client.invalidateQueries({ queryKey: ["settings", "diagnostics"] });
  }
  const activate = useMutation({
    mutationFn: (intent: { id: string; expectedVersion: number; expectedActiveVersion: number }) =>
      activateLlmConnection(intent.id, {
        expectedVersion: intent.expectedVersion,
        expectedActiveVersion: intent.expectedActiveVersion,
      }),
    onMutate: () => client.cancelQueries({ queryKey: CONNECTIONS_KEY }),
    onSuccess: async value => {
      await acceptCatalog(value);
      setFeedback(t("settings.llm.connections.switched"));
    },
    retry: false,
  });
  const diagnose = useMutation({
    mutationFn: (target: LlmConnection) =>
      diagnoseLlmConnection(target.id, { expectedVersion: target.version, acknowledgeCost: true }),
    onMutate: () => client.cancelQueries({ queryKey: CONNECTIONS_KEY }),
    onSuccess: async value => {
      await acceptConnection(value);
      setFeedback(t("settings.llm.protocolVerified"));
      setDiagnostic(null);
    },
    onSettled: () => setAcknowledged(false),
    retry: false,
  });
  const diagnosticBlocked =
    diagnosticChanged || (diagnose.error instanceof ApiError && diagnose.error.status === HTTP_STATUS.CONFLICT);
  const busy = activate.isPending || diagnose.isPending || diagnosticReading;
  function openDiagnostic(target: LlmConnection) {
    diagnose.reset();
    setAcknowledged(false);
    setDiagnosticRefreshError("");
    setDiagnostic(target);
  }
  async function refreshDiagnostic() {
    if (!diagnostic || busy) return;
    setDiagnosticReading(true);
    setAcknowledged(false);
    setDiagnosticRefreshError("");
    try {
      // A server conflict can precede any cache update; explicitly read the target again.
      await client.cancelQueries({ queryKey: CONNECTIONS_KEY });
      const latest = await client.fetchQuery({ queryKey: CONNECTIONS_KEY, queryFn: listLlmConnections, staleTime: 0 });
      const target = latest.items.find(item => item.id === diagnostic.id);
      if (!target) {
        setDiagnosticRefreshError(t("settings.llm.connections.missing"));
        return;
      }
      setDiagnostic(target);
      diagnose.reset();
    } catch (cause) {
      setDiagnosticRefreshError(cause instanceof Error ? cause.message : t("settings.llm.loadFailed"));
    } finally {
      setDiagnosticReading(false);
    }
  }

  if (
    [user.error, connections.error, mode.error, activate.error, diagnose.error].some(
      error => error instanceof ApiError && error.status === HTTP_STATUS.UNAUTHORIZED,
    )
  )
    return <Navigate to="/login" replace />;

  return (
    <PageShell
      title={t("settings.providerTitle")}
      actions={
        <Button
          type="button"
          disabled={busy || !catalog}
          onClick={() => {
            setFeedback("");
            setEditor({ id: crypto.randomUUID(), connection: null });
          }}
        >
          <Plus data-icon="inline-start" />
          {t("settings.llm.connections.add")}
        </Button>
      }
    >
      <div className="provider-workspace">
        {user.isPending || (user.isSuccess && connections.isPending) ? (
          <LoadingState label={t("settings.llm.loading")} />
        ) : null}
        {user.isError || connections.isError ? (
          <Notice tone="danger" title={t("settings.llm.loadFailed")}>
            <p>{t("settings.llm.settingsUnavailable")}</p>
            <Button
              variant="outline"
              type="button"
              disabled={user.isFetching || connections.isFetching}
              onClick={() => {
                if (user.isError) void user.refetch();
                else void connections.refetch();
              }}
            >
              {t("settings.llm.refreshSettings")}
            </Button>
          </Notice>
        ) : null}
        {feedback ? <Notice tone="success">{feedback}</Notice> : null}
        {mode.isError ? (
          <Notice tone="warning">
            <p>{t("settings.llm.connections.modeUnavailable")}</p>
            <Button
              variant="ghost"
              type="button"
              disabled={mode.isFetching}
              onClick={() => {
                void mode.refetch();
              }}
            >
              {t("common.retryRead")}
            </Button>
          </Notice>
        ) : null}
        {activate.error ? (
          <Notice tone="danger">
            <p>{activate.error.message}</p>
            <div className="ui-form-actions">
              <Button
                type="button"
                variant="outline"
                disabled={busy}
                onClick={() => {
                  if (activate.variables) activate.mutate(activate.variables);
                }}
              >
                {t("common.retry")}
              </Button>
              <Button
                type="button"
                variant="ghost"
                disabled={connections.isFetching}
                onClick={() => {
                  void connections.refetch().then(result => {
                    if (!result.error) activate.reset();
                  });
                }}
              >
                {t("settings.shared.refreshConfig")}
              </Button>
            </div>
          </Notice>
        ) : null}
        {catalog ? (
          <Panel className="provider-catalog">
            <div className="provider-current">
              <PlugsConnected aria-hidden />
              <div>
                <span className="ui-muted">{t("settings.llm.connections.current")}</span>
                <strong>{active?.name ?? t("settings.llm.connections.noActive")}</strong>
              </div>
              {active ? (
                <span className="provider-current-model" title={active.modelId}>
                  {active.modelId}
                </span>
              ) : null}
              {mode.data?.llmMode === "MOCK" ? <StatusBadge>{t("models.mockMode")}</StatusBadge> : null}
            </div>
            {catalog.items.length ? (
              <div className="provider-table-scroll">
                <table className="provider-table">
                  <caption className="sr-only">{t("settings.llm.connections.saved")}</caption>
                  <thead>
                    <tr>
                      <th>{t("common.name")}</th>
                      <th>{t("settings.llm.endpoint")}</th>
                      <th>{t("settings.llm.modelId")}</th>
                      <th>{t("settings.llm.protocol")}</th>
                      <th className="provider-actions-heading">{t("settings.llm.connections.actions")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {catalog.items.map(connection => (
                      <tr key={connection.id} data-active={connection.id === catalog.activeConnectionId}>
                        <td>
                          <div className="provider-name">
                            <strong>{connection.name}</strong>
                            {connection.id === catalog.activeConnectionId ? (
                              <StatusBadge tone="success">{t("settings.llm.connections.active")}</StatusBadge>
                            ) : null}
                          </div>
                        </td>
                        <td>
                          <span className="provider-endpoint" title={connection.endpoint}>
                            {connection.endpoint}
                          </span>
                        </td>
                        <td>
                          <span className="provider-model" title={connection.modelId}>
                            {connection.modelId}
                          </span>
                        </td>
                        <td>
                          <span className="provider-verification" data-verified={connection.toolCallingVerified}>
                            {connection.toolCallingVerified ? <CheckCircle aria-hidden /> : <ShieldCheck aria-hidden />}
                            {connection.toolCallingVerified
                              ? t("settings.llm.verified")
                              : t("models.pendingValidation")}
                          </span>
                        </td>
                        <td>
                          <div className="provider-row-actions">
                            {connection.id !== catalog.activeConnectionId ? (
                              <Button
                                variant="outline"
                                size="sm"
                                type="button"
                                disabled={busy}
                                onClick={() => {
                                  setFeedback("");
                                  activate.mutate({
                                    id: connection.id,
                                    expectedVersion: connection.version,
                                    expectedActiveVersion: catalog.activeVersion,
                                  });
                                }}
                              >
                                {t("settings.llm.connections.use")}
                              </Button>
                            ) : null}
                            <Button
                              variant="ghost"
                              size="sm"
                              type="button"
                              disabled={busy}
                              onClick={() => {
                                setFeedback("");
                                setEditor({ id: connection.id, connection });
                              }}
                            >
                              {t("settings.llm.connections.editAction")}
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              type="button"
                              disabled={busy}
                              onClick={() => openDiagnostic(connection)}
                            >
                              {t("settings.llm.connections.test")}
                            </Button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <EmptyState
                icon={<PlugsConnected />}
                title={t("settings.llm.connections.empty")}
                action={
                  <Button type="button" onClick={() => setEditor({ id: crypto.randomUUID(), connection: null })}>
                    {t("settings.llm.connections.add")}
                  </Button>
                }
              />
            )}
            {catalog.items.length && !active ? (
              <p className="provider-no-active">{t("settings.llm.connections.chooseActive")}</p>
            ) : null}
          </Panel>
        ) : null}
        <footer className="provider-related">
          <span>{t("settings.llm.connections.mediaHint")}</span>
          <div>
            <Button asChild variant="ghost" size="sm">
              <Link to="/settings/media">
                {t("settings.llm.manageMedia")}
                <ArrowUpRight data-icon="inline-end" />
              </Link>
            </Button>
            <Button asChild variant="ghost" size="sm">
              <Link to="/settings/general?tab=diagnostics">
                {t("settings.llm.viewDiagnostics")}
                <ArrowUpRight data-icon="inline-end" />
              </Link>
            </Button>
          </div>
        </footer>
      </div>
      {editor ? (
        <LlmConnectionEditor
          key={editor.id}
          id={editor.id}
          connection={editor.connection}
          onClose={() => setEditor(null)}
          onCatalog={acceptCatalog}
          onSaved={async value => {
            await acceptConnection(value);
            setEditor(null);
            setFeedback(t("settings.llm.connections.savedMessage"));
          }}
        />
      ) : null}
      {diagnostic ? (
        <Dialog
          compact
          title={t("settings.llm.protocolDiagnostics")}
          description={`${diagnostic.name} · ${diagnostic.modelId} · v${diagnostic.version}`}
          busy={diagnose.isPending || diagnosticReading}
          onClose={() => {
            setDiagnostic(null);
            setAcknowledged(false);
          }}
          onSubmit={event => {
            event.preventDefault();
            if (acknowledged && !diagnosticBlocked && !busy) diagnose.mutate(diagnostic);
          }}
          footer={
            <>
              <Button
                variant="outline"
                type="button"
                disabled={diagnose.isPending || diagnosticReading}
                onClick={() => {
                  setDiagnostic(null);
                  setAcknowledged(false);
                }}
              >
                {t("common.cancel")}
              </Button>
              <Button type="submit" disabled={!acknowledged || diagnosticBlocked || busy}>
                {diagnose.isPending ? t("settings.llm.diagnosing") : t("settings.llm.runDiagnostic")}
              </Button>
            </>
          }
        >
          <div className="ui-stack">
            <p>{t("settings.llm.diagnosticScopeHint")}</p>
            <p className="ui-muted">{t("settings.llm.savedConfigHint")}</p>
            {diagnosticBlocked ? (
              <Notice tone="warning">
                <p>{t("settings.llm.connections.diagnosticChanged")}</p>
                <Button
                  variant="outline"
                  type="button"
                  disabled={busy}
                  onClick={() => {
                    void refreshDiagnostic();
                  }}
                >
                  {t("settings.shared.refreshConfig")}
                </Button>
              </Notice>
            ) : null}
            {diagnosticRefreshError ? <Notice tone="danger">{diagnosticRefreshError}</Notice> : null}
            <label className="settings-consent">
              <Checkbox
                checked={acknowledged && !diagnosticBlocked}
                disabled={busy || diagnosticBlocked}
                onCheckedChange={value => setAcknowledged(value === true)}
              />
              <span>{t("settings.llm.confirmDiagnosticCost")}</span>
            </label>
            <p className="ui-muted">{t("settings.llm.costConfirmationHint")}</p>
            {diagnose.error ? <Notice tone="danger">{diagnose.error.message}</Notice> : null}
            {diagnose.isPending ? <LoadingState compact label={t("settings.llm.protocolChecking")} /> : null}
          </div>
        </Dialog>
      ) : null}
    </PageShell>
  );
}
