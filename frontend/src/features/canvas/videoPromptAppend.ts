/** Append only: existing whitespace and exact-version mention marker offsets stay intact. */
export function appendVideoPrompt(prompt: string, suffix: string): string {
  return prompt ? `${prompt}${prompt.endsWith("\n\n") ? "" : prompt.endsWith("\n") ? "\n" : "\n\n"}${suffix}` : suffix;
}
