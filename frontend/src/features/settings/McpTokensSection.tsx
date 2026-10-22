import { useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useId, useState } from "react";
import { Navigate } from "react-router";
import { ApiError, HTTP_STATUS, MCP_TOKEN_SCOPES, createMcpToken, listMcpTokens, revokeMcpToken, type McpTokenCreatedResponse, type McpTokenScope, type McpTokenSummary } from "../../shared/api/client";
import { formatDate, t, useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Field, FieldContent, FieldDescription, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";

const DEFAULT_EXPIRY_DAYS = 30;
const MAX_EXPIRY_DAYS = 90;
const MIN_EXPIRY_DAYS = 1;
const MAX_NAME_LENGTH = 100;
const SCOPES = Object.values(MCP_TOKEN_SCOPES);

function scopeLabel(scope: McpTokenScope): string {
  switch (scope) {
    case MCP_TOKEN_SCOPES.READ: return t("mcp.tokens.read");
    case MCP_TOKEN_SCOPES.CONTENT_WRITE: return t("mcp.tokens.contentWrite");
    case MCP_TOKEN_SCOPES.MEDIA_GENERATE: return t("mcp.tokens.mediaGenerate");
  }
}

function tokenStatus(token: McpTokenSummary) {
  if (token.revokedAt) return { label: t("mcp.tokens.revoked"), tone: "neutral" as const, active: false };
  if (Date.parse(token.expiresAt) <= Date.now()) return { label: t("mcp.tokens.expired"), tone: "warning" as const, active: false };
  return { label: t("mcp.tokens.active"), tone: "success" as const, active: true };
}

/** Creation deliberately bypasses the mutation cache: the secret exists only in this component. */
export function McpTokensSection({ enabled, userId }: { enabled: boolean; userId: string | undefined }) {
  useLocale();
  const id = useId();
  const client = useQueryClient();
  const tokensKey = ["account", userId, "mcp-tokens"] as const;
  const tokens = useQuery({ queryKey: tokensKey, queryFn: listMcpTokens, enabled: enabled && Boolean(userId), retry: false });
  const [name, setName] = useState("");
  const [scopes, setScopes] = useState<McpTokenScope[]>([MCP_TOKEN_SCOPES.READ]);
  const [expiry, setExpiry] = useState(String(DEFAULT_EXPIRY_DAYS));
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<McpTokenCreatedResponse | null>(null);
  const [revoking, setRevoking] = useState<string | null>(null);
  const [writeError, setWriteError] = useState<unknown>(null);
  const [copyState, setCopyState] = useState<"idle" | "copied" | "failed">("idle");
  const error = writeError ?? tokens.error;
  if (error instanceof ApiError && error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = error instanceof ApiError && error.status === HTTP_STATUS.FORBIDDEN;
  const conflict = error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT;
  const busy = creating || revoking !== null;
  const unavailable = !enabled || tokens.isPending || forbidden || busy;
  const endpoint = new URL("/mcp", window.location.origin).href;
  // A shell environment placeholder is shown; the actual credential is never interpolated here.
  const connectionExample = `claude mcp add --transport http agenvas ${endpoint} --header 'Authorization: Bearer \${AGENVAS_MCP_TOKEN}'`;

  function updateSafeMetadata(value: McpTokenSummary) {
    client.setQueryData<McpTokenSummary[]>(tokensKey, (current = []) => [value, ...current.filter((item) => item.id !== value.id)]);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (unavailable) return;
    setWriteError(null);
    setCopyState("idle");
    setCreating(true);
    try {
      const result = await createMcpToken({ name: name.trim(), scopes, expiresInDays: Number(expiry) });
      updateSafeMetadata(result.credential);
      setCreated(result);
      setName("");
    } catch (cause) {
      setWriteError(cause);
    } finally {
      setCreating(false);
    }
  }

  async function revoke(token: McpTokenSummary) {
    if (busy) return;
    setWriteError(null);
    setRevoking(token.id);
    try {
      const result = await revokeMcpToken(token);
      updateSafeMetadata(result);
      if (created?.credential.id === result.id) setCreated(null);
    } catch (cause) {
      setWriteError(cause);
    } finally {
      setRevoking(null);
    }
  }

  async function copySecret() {
    if (!created) return;
    try {
      await navigator.clipboard.writeText(created.token);
      setCopyState("copied");
    } catch {
      setCopyState("failed");
    }
  }

  return <Panel title={t("mcp.tokens.title")} description={t("mcp.tokens.description")}>
    <div className="flex min-w-0 flex-col gap-5">
      <p className="ui-muted">{t("mcp.tokens.directGenerationHint")}</p>
      {tokens.isPending && enabled ? <LoadingState compact label={t("mcp.tokens.loading")} /> : null}
      {error ? <Notice tone="danger" title={forbidden ? t("mcp.tokens.forbidden") : conflict ? t("mcp.tokens.conflict") : t("mcp.tokens.failed")}>
        <p>{error instanceof ApiError ? error.message : t("mcp.tokens.retryHint")}</p>
        {!forbidden ? <Button variant="outline" type="button" disabled={busy || tokens.isFetching} onClick={() => { setWriteError(null); void tokens.refetch(); }}>{t("common.retry")}</Button> : null}
      </Notice> : null}
      {created ? <Notice tone="warning" title={t("mcp.tokens.created")}>
        <div className="flex min-w-0 flex-col gap-3">
          <p>{t("mcp.tokens.onceHint")}</p>
          <Field>
            <FieldLabel htmlFor={`${id}-secret`}>{t("mcp.tokens.secret")}</FieldLabel>
            <Input id={`${id}-secret`} readOnly value={created.token} autoComplete="off" spellCheck={false} />
          </Field>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" type="button" onClick={() => { void copySecret(); }}>{t("mcp.tokens.copy")}</Button>
            <Button variant="outline" type="button" onClick={() => { setCreated(null); setCopyState("idle"); }}>{t("mcp.tokens.dismissSecret")}</Button>
          </div>
          {copyState !== "idle" ? <p role="status">{copyState === "copied" ? t("mcp.tokens.copied") : t("mcp.tokens.copyFailed")}</p> : null}
        </div>
      </Notice> : null}
      <form className="ui-form" onSubmit={(event) => { void submit(event); }} aria-busy={creating}>
        <FieldGroup>
          <Field data-disabled={unavailable}>
            <FieldLabel htmlFor={`${id}-name`}>{t("mcp.tokens.name")}</FieldLabel>
            <Input id={`${id}-name`} autoComplete="off" required maxLength={MAX_NAME_LENGTH} disabled={unavailable} value={name} onChange={(event) => { setName(event.target.value); }} />
          </Field>
          <Field data-disabled={unavailable}>
            <FieldLabel htmlFor={`${id}-expiry`}>{t("mcp.tokens.expiry")}</FieldLabel>
            <Input id={`${id}-expiry`} type="number" inputMode="numeric" required min={MIN_EXPIRY_DAYS} max={MAX_EXPIRY_DAYS} step={1} disabled={unavailable} value={expiry} onChange={(event) => { setExpiry(event.target.value); }} aria-describedby={`${id}-expiry-hint`} />
            <FieldDescription id={`${id}-expiry-hint`}>{t("mcp.tokens.expiryHint", { "0": MAX_EXPIRY_DAYS })}</FieldDescription>
          </Field>
          <FieldSet>
            <FieldLegend>{t("mcp.tokens.scopes")}</FieldLegend>
            <FieldGroup>
              {SCOPES.map((scope) => <Field orientation="horizontal" key={scope} data-disabled={unavailable || scope === MCP_TOKEN_SCOPES.READ}>
                <Checkbox id={`${id}-${scope}`} checked={scopes.includes(scope)} disabled={unavailable || scope === MCP_TOKEN_SCOPES.READ}
                  onCheckedChange={(checked) => { setScopes((current) => checked === true ? [...current, scope] : current.filter((item) => item !== scope)); }} />
                <FieldContent>
                  <FieldLabel htmlFor={`${id}-${scope}`}>{scopeLabel(scope)}</FieldLabel>
                  {scope === MCP_TOKEN_SCOPES.READ ? <FieldDescription>{t("mcp.tokens.readRequired")}</FieldDescription> : null}
                </FieldContent>
              </Field>)}
            </FieldGroup>
          </FieldSet>
        </FieldGroup>
        <div className="ui-form-actions">
          <Button type="submit" disabled={unavailable || !name.trim() || created !== null}>{creating ? t("mcp.tokens.creating") : t("mcp.tokens.create")}</Button>
        </div>
      </form>
      {tokens.data?.length === 0 ? <EmptyState title={t("mcp.tokens.empty")} /> : null}
      {tokens.data && tokens.data.length > 0 ? <ul className="flex min-w-0 flex-col gap-5" aria-label={t("mcp.tokens.list")}>
        {tokens.data.map((token) => {
          const status = tokenStatus(token);
          return <li key={token.id} className="flex min-w-0 flex-col gap-2">
            <div className="flex flex-wrap items-center justify-between gap-2"><strong className="break-all">{token.name}</strong><StatusBadge tone={status.tone}>{status.label}</StatusBadge></div>
            <p className="ui-muted break-all">{token.tokenHint}</p>
            <p>{token.scopes.map(scopeLabel).join(" · ")}</p>
            <p className="ui-muted">{t("mcp.tokens.createdAt", { "0": formatDate(token.createdAt) })}</p>
            <p className="ui-muted">{t("mcp.tokens.expiresAt", { "0": formatDate(token.expiresAt) })}</p>
            <p className="ui-muted">{t("mcp.tokens.lastUsedAt", { "0": token.lastUsedAt ? formatDate(token.lastUsedAt) : t("mcp.tokens.neverUsed") })}</p>
            <div><Button variant="outline" type="button" disabled={unavailable || !status.active} aria-label={t("mcp.tokens.revokeNamed", { "0": token.name })} onClick={() => { void revoke(token); }}>{revoking === token.id ? t("mcp.tokens.revoking") : t("mcp.tokens.revoke")}</Button></div>
          </li>;
        })}
      </ul> : null}
      <div className="flex min-w-0 flex-col gap-3">
        <h3>{t("mcp.tokens.connect")}</h3>
        <p className="ui-muted">{t("mcp.tokens.connectHint")}</p>
        <Field><FieldLabel htmlFor={`${id}-endpoint`}>{t("mcp.tokens.endpoint")}</FieldLabel><Input id={`${id}-endpoint`} readOnly value={endpoint} /></Field>
        <pre className="min-w-0 whitespace-pre-wrap break-all"><code>{connectionExample}</code></pre>
      </div>
    </div>
  </Panel>;
}
