import { ApiError } from "../../shared/api/client";
import { t, type MessageKey } from "../../shared/i18n";

const DOWNLOAD_REASONS: Record<string, MessageKey> = {
  REFERENCE_CATALOG_DNS_BLOCKED: "reference.network.dnsBlocked",
  REFERENCE_CATALOG_DNS_FAILED: "reference.network.dnsFailed",
  REFERENCE_CATALOG_PROXY_UNREACHABLE: "reference.network.proxyUnreachable",
  REFERENCE_CATALOG_PROXY_AUTH_REQUIRED: "reference.network.proxyAuthRequired",
  REFERENCE_CATALOG_PROXY_ERROR: "reference.network.proxyError",
  REFERENCE_CATALOG_TLS_FAILED: "reference.network.tlsFailed",
  REFERENCE_CATALOG_REMOTE_RATE_LIMITED: "reference.network.rateLimited",
  REFERENCE_CATALOG_REMOTE_UNAVAILABLE: "reference.network.unavailable",
};

/** Stable sanitized download codes get actionable text; other domain failures retain their message. */
export function referenceFailureMessage(failure: Error | string): string {
  const code = typeof failure === "string" ? failure : failure instanceof ApiError ? failure.code : undefined;
  const key = code ? DOWNLOAD_REASONS[code] : undefined;
  return key ? t(key) : typeof failure === "string" ? failure : failure.message;
}
