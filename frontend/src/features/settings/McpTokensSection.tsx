import { useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useId, useRef, useState } from "react";
import { Navigate } from "react-router";
import { ApiError, HTTP_STATUS, MCP_TOKEN_SCOPES, createMcpToken, listMcpTokens, revokeMcpToken, type McpTokenCreatedResponse, type McpTokenScope, type McpTokenSummary } from "../../shared/api/client";
import { formatDate, t, useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Field, FieldContent, FieldDescription, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../shared/ui/primitives/table";

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
  const [copyPending, setCopyPending] = useState(false);
  const copyRequest = useRef({ epoch: 0, pending: false });
  const error = writeError ?? tokens.error;
  if (error instanceof ApiError && error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = error instanceof ApiError && error.status === HTTP_STATUS.FORBIDDEN;
  const conflict = error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT;
  const busy = creating || revoking !== null;
  const unavailable = !enabled || tokens.isPending || forbidden || busy;
  const endpoint = new URL("/mcp", window.location.origin).href;
  // A shell environment placeholder is shown; the actual credential is never interpolated here.
  const connectionExample = `claude mcp add --transport http agenvas ${endpoint} --header 'Authorization: Bearer \${AGENVAS_MCP_TOKEN}'`;

  async function updateSafeMetadata(value: McpTokenSummary) {
    // A list read started before this command must not replace its confirmed result.
    await client.cancelQueries({ queryKey: tokensKey, exact: true });
    const updated = client.setQueryData<McpTokenSummary[]>(tokensKey, (current = []) => {
      const known = current.find((item) => item.id === value.id);
      // A completed refresh may already know revocation before the old create response arrives.
      const confirmed = known && known.version >= value.version ? known : value;
      return [confirmed, ...current.filter((item) => item.id !== value.id)];
    });
    return updated?.[0] ?? value;
  }

  function dismissSecret() {
    copyRequest.current.epoch++;
    setCreated(null);
    setCopyState("idle");
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (unavailable) return;
    setWriteError(null);
    copyRequest.current.epoch++;
    setCopyState("idle");
    setCreating(true);
    try {
      const result = await createMcpToken({ name: name.trim(), scopes, expiresInDays: Number(expiry) });
      const credential = await updateSafeMetadata(result.credential);
      setCreated(tokenStatus(credential).active ? { ...result, credential } : null);
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
      await updateSafeMetadata(result);
      if (created?.credential.id === result.id) dismissSecret();
    } catch (cause) {
      setWriteError(cause);
    } finally {
      setRevoking(null);
    }
  }

  async function copySecret() {
    const request = copyRequest.current;
    if (!created || request.pending) return;
    const epoch = ++request.epoch;
    request.pending = true;
    setCopyPending(true);
    setCopyState("idle");
    try {
      await navigator.clipboard.writeText(created.token);
      if (request.epoch === epoch) setCopyState("copied");
    } catch {
      if (request.epoch === epoch) setCopyState("failed");
    } finally {
      // Clipboard writes cannot be canceled. Finish the old write before enabling another secret's copy.
      request.pending = false;
      setCopyPending(false);
    }
  }

  return <Panel className="mcp-settings-panel" title={t("mcp.tokens.title")} description={t("mcp.tokens.description")}>
    <div className="ui-stack">
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
            <Button variant="outline" type="button" disabled={copyPending} onClick={() => { void copySecret(); }}>{t("mcp.tokens.copy")}</Button>
            <Button variant="outline" type="button" onClick={dismissSecret}>{t("mcp.tokens.dismissSecret")}</Button>
          </div>
          {copyState !== "idle" ? <p role="status">{copyState === "copied" ? t("mcp.tokens.copied") : t("mcp.tokens.copyFailed")}</p> : null}
        </div>
      </Notice> : null}
      <form className="ui-form mcp-token-create" onSubmit={(event) => { void submit(event); }} aria-busy={creating}>
        <FieldGroup className="mcp-token-create-fields">
          <Field data-disabled={unavailable}>
            <FieldLabel htmlFor={`${id}-name`}>{t("mcp.tokens.name")}</FieldLabel>
            <Input id={`${id}-name`} autoComplete="off" required maxLength={MAX_NAME_LENGTH} disabled={unavailable} value={name} onChange={(event) => { setName(event.target.value); }} />
          </Field>
          <Field data-disabled={unavailable}>
            <FieldLabel htmlFor={`${id}-expiry`}>{t("mcp.tokens.expiry")}</FieldLabel>
            <Input id={`${id}-expiry`} type="number" inputMode="numeric" required min={MIN_EXPIRY_DAYS} max={MAX_EXPIRY_DAYS} step={1} disabled={unavailable} value={expiry} onChange={(event) => { setExpiry(event.target.value); }} aria-describedby={`${id}-expiry-hint`} />
            <FieldDescription id={`${id}-expiry-hint`}>{t("mcp.tokens.expiryHint", { "0": MAX_EXPIRY_DAYS })}</FieldDescription>
          </Field>
          <FieldSet className="mcp-token-scopes">
            <FieldLegend>{t("mcp.tokens.scopes")}</FieldLegend>
            <FieldGroup className="mcp-token-scope-options">
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
      {tokens.data && tokens.data.length > 0 ? <div className="mcp-tokens-scroll" tabIndex={0} role="region" aria-label={t("mcp.tokens.list")}>
        <Table className="mcp-token-table" aria-label={t("mcp.tokens.list")}>
        <TableHeader><TableRow>
          <TableHead scope="col">{t("mcp.tokens.name")}</TableHead>
          <TableHead scope="col">{t("mcp.tokens.hintColumn")}</TableHead>
          <TableHead scope="col">{t("mcp.tokens.scopes")}</TableHead>
          <TableHead scope="col">{t("mcp.tokens.createdColumn")}</TableHead>
          <TableHead scope="col">{t("mcp.tokens.expiresColumn")}</TableHead>
          <TableHead scope="col">{t("mcp.tokens.lastUsedColumn")}</TableHead>
          <TableHead scope="col">{t("settings.mediaSettings.status")}</TableHead>
          <TableHead scope="col">{t("settings.mediaSettings.operations")}</TableHead>
        </TableRow></TableHeader><TableBody>
        {tokens.data.map((token) => {
          const status = tokenStatus(token);
          const scopes = token.scopes.map(scopeLabel).join(" · ");
          return <TableRow key={token.id}>
            <TableCell data-label={t("mcp.tokens.name")}><strong title={token.name}>{token.name}</strong></TableCell>
            <TableCell data-label={t("mcp.tokens.hintColumn")}><span title={token.tokenHint}>{token.tokenHint}</span></TableCell>
            <TableCell data-label={t("mcp.tokens.scopes")}><span title={scopes}>{scopes}</span></TableCell>
            <TableCell data-label={t("mcp.tokens.createdColumn")}><time dateTime={token.createdAt} title={formatDate(token.createdAt)}>{formatDate(token.createdAt)}</time></TableCell>
            <TableCell data-label={t("mcp.tokens.expiresColumn")}><time dateTime={token.expiresAt} title={formatDate(token.expiresAt)}>{formatDate(token.expiresAt)}</time></TableCell>
            <TableCell data-label={t("mcp.tokens.lastUsedColumn")}>{token.lastUsedAt ? <time dateTime={token.lastUsedAt} title={formatDate(token.lastUsedAt)}>{formatDate(token.lastUsedAt)}</time> : t("mcp.tokens.neverUsed")}</TableCell>
            <TableCell data-label={t("settings.mediaSettings.status")}><StatusBadge tone={status.tone}>{status.label}</StatusBadge></TableCell>
            <TableCell><Button variant="outline" size="sm" type="button" disabled={unavailable || !status.active} aria-label={t("mcp.tokens.revokeNamed", { "0": token.name })} onClick={() => { void revoke(token); }}>{revoking === token.id ? t("mcp.tokens.revoking") : t("mcp.tokens.revoke")}</Button></TableCell>
          </TableRow>;
        })}
        </TableBody></Table>
      </div> : null}
      <div className="flex min-w-0 flex-col gap-3">
        <h3>{t("mcp.tokens.connect")}</h3>
        <p className="ui-muted">{t("mcp.tokens.connectHint")}</p>
        <Field><FieldLabel htmlFor={`${id}-endpoint`}>{t("mcp.tokens.endpoint")}</FieldLabel><Input id={`${id}-endpoint`} readOnly value={endpoint} /></Field>
        <pre className="min-w-0 whitespace-pre-wrap break-all"><code>{connectionExample}</code></pre>
      </div>
    </div>
  </Panel>;
}
