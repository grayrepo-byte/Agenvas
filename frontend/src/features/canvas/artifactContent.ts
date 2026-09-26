/** Read optional fields across the versioned content schemas without unsafe casts. */
export function readContentText(content: unknown, key: string): string {
  if (!content || typeof content !== "object" || !(key in content)) return "";
  const value: unknown = content[key as keyof typeof content];
  return typeof value === "string" ? value : "";
}

export function readContentNumber(content: unknown, key: string): number {
  if (!content || typeof content !== "object" || !(key in content)) return 0;
  const value: unknown = content[key as keyof typeof content];
  return typeof value === "number" ? value : 0;
}
