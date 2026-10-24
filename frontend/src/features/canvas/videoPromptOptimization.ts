import type { SaveMediaDraftRequest } from "../../shared/api/client";
import { MENTION_MARKER } from "./mediaPrompt";

type Mentions = SaveMediaDraftRequest["mentions"];
const TOKEN = /\[\[REF_(\d+)\]\]/gu;

/** Stable tokens let a text model reorder mentions without changing their exact media identities. */
export function encodeOptimizationPrompt(prompt: string, mentions: Mentions): string | null {
  if (/\[\[REF_\d+\]\]/u.test(prompt)) return null;
  let index = 0;
  const encoded = prompt.replaceAll(MENTION_MARKER, () => `[[REF_${++index}]]`);
  return index === mentions.length ? encoded : null;
}

export function decodeOptimizationPrompt(prompt: string, mentions: Mentions): { prompt: string; mentions: Mentions } | null {
  const ordered: Mentions = [];
  const seen = new Set<number>();
  let invalid = prompt.includes(MENTION_MARKER);
  const decoded = prompt.replace(TOKEN, (_, number: string) => {
    const index = Number(number) - 1;
    const mention = mentions[index];
    if (!mention || seen.has(index)) invalid = true;
    else { seen.add(index); ordered.push(mention); }
    return MENTION_MARKER;
  });
  return invalid || seen.size !== mentions.length ? null : { prompt: decoded, mentions: ordered };
}
