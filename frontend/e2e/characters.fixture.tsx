import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { useState } from "react";
import { CharacterManagerDialog } from "../src/features/characters/CharacterManagerDialog";
import { CharacterBindingsDialog } from "../src/features/characters/CharacterBindingsDialog";
import { useMediaDraftSession, useMediaDraftSessionLifecycle } from "../src/features/canvas/useMediaDraftSession";
import { setLocale } from "../src/shared/i18n";
import { Button } from "../src/shared/ui/primitives/button";
import "../src/styles.css";

// This fixture uses an isolated browser profile and intercepted synthetic HTTP only.
setLocale("zh");
function Fixture() {
  const [manager, setManager] = useState(false);
  const [roles, setRoles] = useState(false);
  const [busy, setBusy] = useState(false);
  const session = useMediaDraftSession("characters-fixture", "shot-one");
  useMediaDraftSessionLifecycle(session, "VIDEO", { readOnly: false, refreshBlocked: roles, autosaveBlocked: roles, closeBlocked: busy });
  return <main id="fixture" className="flex min-h-screen flex-col items-start gap-4 bg-background p-8 text-foreground">
    <Button id="manager-open" onClick={() => setManager(true)}>项目角色</Button><Button id="roles-open" onClick={() => setRoles(true)}>角色关联</Button>
    <p id="draft-prompt">{session.state.fields?.prompt}</p>
    {manager ? <CharacterManagerDialog projectId="characters-fixture" onClose={() => setManager(false)} /> : null}
    {roles ? <CharacterBindingsDialog projectId="characters-fixture" itemId="shot-one" session={session} readOnly={false} onBusy={setBusy} onClose={() => setRoles(false)} /> : null}
  </main>;
}
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={client}><Fixture /></QueryClientProvider>);
