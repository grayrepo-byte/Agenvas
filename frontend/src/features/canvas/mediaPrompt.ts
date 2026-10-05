import type { SaveMediaDraftRequest } from "../../shared/api/client";

type PromptFields = Pick<SaveMediaDraftRequest, "prompt" | "mentions">;
type InputFields = PromptFields & Pick<SaveMediaDraftRequest, "mediaInputs">;

// Each marker maps positionally to one structured, exact-version prompt mention.
export const MENTION_MARKER = "\uFFFC";

/** Removes all mentions of the selected versions in one pass, including repeated mentions. */
export function removePromptReferences(prompt: string, mentions: PromptFields["mentions"],
  ...versionIds: string[]): PromptFields {
  const removed = new Set(versionIds);
  let mentionIndex = 0;
  let nextPrompt = "";
  const nextMentions: PromptFields["mentions"] = [];
  for (const character of prompt) {
    if (character !== MENTION_MARKER) {
      nextPrompt += character;
      continue;
    }
    const mention = mentions[mentionIndex++];
    if (mention && !removed.has(mention.versionId)) {
      nextPrompt += MENTION_MARKER;
      nextMentions.push(mention);
    }
  }
  return { prompt: nextPrompt, mentions: nextMentions };
}

/** Mode/capability changes prune removed versions and rebind the roles of surviving mentions. */
export function promptForMediaInputs(fields: InputFields, inputs: InputFields["mediaInputs"]): PromptFields {
  const roles = new Map(inputs.map((input) => [input.versionId, input.role]));
  const removed = fields.mediaInputs.filter((input) => !roles.has(input.versionId))
    .map((input) => input.versionId);
  const result = removed.length ? removePromptReferences(fields.prompt, fields.mentions, ...removed) : fields;
  return { prompt: result.prompt, mentions: result.mentions.map((mention) => ({ ...mention,
    role: roles.get(mention.versionId) ?? mention.role })) };
}
