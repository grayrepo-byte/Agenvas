import { useRef } from "react";
import { t, useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "../../shared/ui/primitives/dialog";
import { cl } from "./characterLibraryMessages";
/** Cancel owns initial focus; Escape and outside dismissal never discard the editor draft. */
export function DiscardCharacterLibraryDialog({
  onContinue,
  onDiscard,
}: {
  onContinue: () => void;
  onDiscard: () => void;
}) {
  useLocale();
  const cancel = useRef<HTMLButtonElement>(null);
  return (
    <Dialog
      open
      onOpenChange={open => {
        if (!open) onContinue();
      }}
    >
      <DialogContent
        className="app-page"
        showCloseButton={false}
        onOpenAutoFocus={event => {
          event.preventDefault();
          cancel.current?.focus();
        }}
        onEscapeKeyDown={event => event.stopPropagation()}
        onPointerDownOutside={event => event.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>{t("common.unsavedChanges")}</DialogTitle>
          <DialogDescription>{cl("discardHint")}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button ref={cancel} type="button" variant="outline" onClick={onContinue}>
            {t("common.continueEditing")}
          </Button>
          <Button type="button" variant="destructive" onClick={onDiscard}>
            {t("common.discardChanges")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
