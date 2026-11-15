/** Copy from a user action on HTTPS or LAN HTTP; callers keep their existing failure feedback. */
export async function copyText(text: string): Promise<void> {
  if (typeof navigator.clipboard?.writeText === "function") {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // A denied asynchronous write can still fall back to the current user action.
    }
  }
  // Selection-based copying is still needed on HTTP, where Async Clipboard is unavailable.
  const active = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const input = active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement ? active : null;
  const inputSelection = input ? ([input.selectionStart, input.selectionEnd, input.selectionDirection] as const) : null;
  const selection = document.getSelection();
  const ranges = selection
    ? Array.from({ length: selection.rangeCount }, (_, index) => selection.getRangeAt(index).cloneRange())
    : [];
  const temporary = document.createElement("textarea");
  temporary.value = text;
  temporary.readOnly = true;
  temporary.tabIndex = -1;
  Object.assign(temporary.style, { position: "fixed", top: "0", left: "0", opacity: "0", pointerEvents: "none" });
  // Modal focus traps reject a temporary field outside their dialog.
  const parent = active?.closest('[role="dialog"], [role="alertdialog"]') ?? document.body;
  parent.append(temporary);
  try {
    temporary.focus({ preventScroll: true });
    temporary.select();
    if (typeof document.execCommand !== "function" || !document.execCommand("copy")) {
      throw new Error("Clipboard copy failed");
    }
  } finally {
    temporary.value = "";
    temporary.remove();
    if (selection) {
      selection.removeAllRanges();
      for (const range of ranges) selection.addRange(range);
    }
    if (active?.isConnected) active.focus({ preventScroll: true });
    if (input?.isConnected && inputSelection && inputSelection[0] !== null && inputSelection[1] !== null) {
      input.setSelectionRange(inputSelection[0], inputSelection[1], inputSelection[2] ?? undefined);
    }
  }
}
