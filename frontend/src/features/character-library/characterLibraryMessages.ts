import { t, type MessageKey } from "../../shared/i18n";
type LibraryKey = Extract<MessageKey, `characterLibrary.${string}`>;
type ShortKey = LibraryKey extends `characterLibrary.${infer Key}` ? Key : never;
/** Narrow namespaced access to the shared four-language catalog. */
export function cl(key: ShortKey): string {
  return t(`characterLibrary.${key}`);
}
