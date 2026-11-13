import { useState } from "react";
import type { LibraryCharacter } from "../../shared/api/characterLibrary";
import { useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { CharacterLibraryBrowser } from "./CharacterLibraryBrowser";
import { CharacterLibraryEditor } from "./CharacterLibraryEditor";
import { cl } from "./characterLibraryMessages";
export function CharacterLibrary() {
  useLocale();
  const [editor, setEditor] = useState<LibraryCharacter | "NEW" | null>(null);
  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">{cl("description")}</p>
      <p className="text-sm text-muted-foreground">{cl("ordinaryHint")}</p>
      <div>
        <Button type="button" onClick={() => setEditor("NEW")}>
          {cl("create")}
        </Button>
      </div>
      <CharacterLibraryBrowser allowArchived onSelect={setEditor} />
      {editor ? (
        <CharacterLibraryEditor initial={editor === "NEW" ? undefined : editor} onClose={() => setEditor(null)} />
      ) : null}
    </div>
  );
}
