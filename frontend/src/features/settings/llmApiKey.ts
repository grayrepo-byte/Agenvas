export const LLM_API_KEY_MIN_LENGTH = 8;
export const LLM_API_KEY_MAX_LENGTH = 4096;
export const LLM_API_KEY_PATTERN = `[!-~]{${LLM_API_KEY_MIN_LENGTH},${LLM_API_KEY_MAX_LENGTH}}`;
const INVALID_API_KEY_CHARACTER = /[^!-~]/;

/** Match the server credential contract without trimming or replacing the supplied key. */
export function validLlmApiKey(key: string): boolean {
  return (
    key.length >= LLM_API_KEY_MIN_LENGTH && key.length <= LLM_API_KEY_MAX_LENGTH && !INVALID_API_KEY_CHARACTER.test(key)
  );
}
