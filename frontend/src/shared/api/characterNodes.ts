import { readJson, writeJson, writeEmpty, type CanvasItem, type CharacterProfile } from "./client";
import type { components } from "./schema";
import { t } from "../i18n";

type CharacterBindingApplied = components["schemas"]["CharacterBindingApplied"];
export type CharacterNode = components["schemas"]["CharacterNode"];
export type CharacterNodeSelection = components["schemas"]["CharacterNodeSelection"];
export type CharacterConnection = components["schemas"]["CharacterNodeConnection"];
export type CharacterNodeBindingPreview = components["schemas"]["CharacterNodeBindingPreview"];
export type CreateCharacterNode = components["schemas"]["CreateCharacterNode"];
export type SaveCharacterNodeSelection = components["schemas"]["SaveCharacterNodeSelection"];
export type ConnectCharacterNode = components["schemas"]["ConnectCharacterNode"];
export type PreviewCharacterNodeBindings = components["schemas"]["PreviewCharacterNodeBindings"];
export type ApplyCharacterNodeBindings = components["schemas"]["ApplyCharacterNodeBindings"];
const base = (project: string) => `/api/v1/projects/${encodeURIComponent(project)}`;
export async function createCharacterNode(project: string, item: string, request: CreateCharacterNode): Promise<CanvasItem> {
  const entry = await writeJson<components["schemas"]["CanvasCharacterNodeEntry"]>(`${base(project)}/canvas/character-nodes/${encodeURIComponent(item)}`, { method: "PUT", body: JSON.stringify(request) }, t("character.saveFailed"));
  return { ...entry.item, artifact: entry.artifact, selectedVersion: entry.selectedVersion, characterNode: entry.characterNode };
}
export function saveCharacterNodeSelection(project: string, item: string, request: SaveCharacterNodeSelection): Promise<CharacterNode> {
  return writeJson(`${base(project)}/canvas/character-nodes/${encodeURIComponent(item)}/selection`, { method: "PUT", body: JSON.stringify(request) }, t("character.saveFailed"));
}
export function listCharacterConnections(project: string): Promise<{ items: CharacterConnection[] }> {
  return readJson(`${base(project)}/canvas/character-connections`, t("character.loadFailed"));
}
export function connectCharacterNode(project: string, request: ConnectCharacterNode): Promise<CharacterConnection> {
  return writeJson(`${base(project)}/canvas/character-connections`, { method: "POST", body: JSON.stringify(request) }, t("character.saveFailed"));
}
export async function disconnectCharacterNode(project: string, connection: CharacterConnection): Promise<void> {
  // The shared empty-response transport retains CSRF/auth handling for 204.
  await writeEmpty(`${base(project)}/canvas/character-connections/${encodeURIComponent(connection.id)}/disconnect`, {
    method: "POST", body: JSON.stringify({ expectedVersion: connection.version }),
  });
}
export function previewCharacterNodeBindings(project: string, item: string, request: PreviewCharacterNodeBindings): Promise<CharacterNodeBindingPreview> {
  return writeJson(`${base(project)}/canvas-items/${encodeURIComponent(item)}/character-node-bindings/preview`, { method: "POST", body: JSON.stringify(request) }, t("character.previewFailed"));
}
export function applyCharacterNodeBindings(project: string, item: string, request: ApplyCharacterNodeBindings): Promise<CharacterBindingApplied> {
  return writeJson(`${base(project)}/canvas-items/${encodeURIComponent(item)}/character-node-bindings/apply`, { method: "POST", body: JSON.stringify(request) }, t("character.applyFailed"));
}

/** Reads a complete immutable project revision for editing an older pinned node. */
export function getCharacterRevision(project: string, character: string, version: number): Promise<CharacterProfile> {
  return readJson(`${base(project)}/characters/${encodeURIComponent(character)}/revisions/${version}`, t("character.loadFailed"));
}

/** Read-only historical acknowledgement, also available after locking or project archival. */
export function getCharacterConnectionReceipt(project: string, connectionId: string): Promise<CharacterConnection> {
  return readJson(`${base(project)}/canvas/character-connections/${encodeURIComponent(connectionId)}/receipt`, t("character.loadFailed"));
}
export function getCharacterApplicationReceipt(project: string, targetId: string, commandId: string): Promise<CharacterBindingApplied> {
  return readJson(`${base(project)}/canvas-items/${encodeURIComponent(targetId)}/character-node-bindings/receipts/${encodeURIComponent(commandId)}`, t("character.loadFailed"));
}
