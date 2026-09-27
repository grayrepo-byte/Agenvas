/** Read optional fields across the versioned content schemas without unsafe casts. */
export function readContentText(content: unknown, key: string): string {
  if (!content || typeof content !== "object" || !(key in content)) return "";
  const value: unknown = content[key as keyof typeof content];
  return typeof value === "string" ? value : "";
}
