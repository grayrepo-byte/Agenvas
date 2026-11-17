function hex(bytes: Uint8Array): string {
  return Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
}

/** Opaque idempotency key; getRandomValues also works when the UI is served over LAN HTTP. */
export function createCommandKey(): string {
  return hex(crypto.getRandomValues(new Uint8Array(16)));
}

/** Keep UUID-backed API identities compatible without requiring a secure browser context. */
export function createClientId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const value = hex(bytes);
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`;
}
