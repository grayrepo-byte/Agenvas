import { readJson, writeJson } from "./client";
import type { components } from "./schema";
import { t } from "../i18n";
export type LibraryCharacter = components["schemas"]["CharacterLibraryProfile"];
export type LibraryCharacterContent = components["schemas"]["CharacterLibraryContent"];
export type LibraryCharacterLook = components["schemas"]["CharacterLibraryLook"];
export type LibraryCharacterView = components["schemas"]["CharacterLibraryView"];
export type CharacterLibraryCommand = components["schemas"]["CharacterLibraryCommand"];
export type LibraryTransferPreview = components["schemas"]["CharacterLibraryTransferPreview"];
export type LibraryTransferRequest = components["schemas"]["CharacterLibraryTransfer"];
export type LibraryMediaSource = components["schemas"]["CharacterLibraryMediaSource"];
const ROOT = "/api/v1/character-library";
const profilePath = (id: string) => `${ROOT}/profiles/${encodeURIComponent(id)}`;
const importsPath = (projectId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/character-library/imports`;
export function listLibraryCharacters(query = "", cursor?: string, archived = false) {
  const params = new URLSearchParams({ query, archived: String(archived), limit: "30" });
  if (cursor) params.set("cursor", cursor);
  return readJson<components["schemas"]["CharacterLibraryPage"]>(
    `${ROOT}/profiles?${params}`,
    t("character.loadFailed"),
  );
}
export const getLibraryCharacter = (id: string) =>
  readJson<LibraryCharacter>(profilePath(id), t("character.loadFailed"));
export const saveLibraryCharacter = (id: string, request: components["schemas"]["CharacterLibrarySave"]) =>
  writeJson<LibraryCharacter>(profilePath(id), { method: "PUT", body: JSON.stringify(request) });
export const archiveLibraryCharacter = (id: string, request: components["schemas"]["CharacterLibraryArchive"]) =>
  writeJson<LibraryCharacter>(`${profilePath(id)}/archive`, { method: "POST", body: JSON.stringify(request) });
export const getCharacterLibraryCommand = (id: string) =>
  readJson<CharacterLibraryCommand>(`${ROOT}/commands/${encodeURIComponent(id)}`, t("character.loadFailed"));
export const retryCharacterLibraryCommand = (id: string) =>
  writeJson<CharacterLibraryCommand>(`${ROOT}/commands/${encodeURIComponent(id)}/retry`, { method: "POST" });
export const archiveCharacterMedia = (request: LibraryMediaSource) =>
  writeJson<CharacterLibraryCommand>(`${ROOT}/media`, { method: "POST", body: JSON.stringify(request) });
export function uploadCharacterMedia(request: { commandId: string; kind: "IMAGE" | "AUDIO"; file: File }) {
  const body = new FormData();
  body.set("commandId", request.commandId);
  body.set("kind", request.kind);
  body.set("file", request.file);
  return writeJson<CharacterLibraryCommand>(`${ROOT}/media/uploads`, { method: "POST", body });
}
export const publishProjectCharacter = (id: string, request: components["schemas"]["CharacterLibraryPublish"]) =>
  writeJson<CharacterLibraryCommand>(`${profilePath(id)}/from-project`, {
    method: "POST",
    body: JSON.stringify(request),
  });
export const previewLibraryCharacterImport = (projectId: string, request: LibraryTransferRequest) =>
  writeJson<LibraryTransferPreview>(`${importsPath(projectId)}/preview`, {
    method: "POST",
    body: JSON.stringify(request),
  });
export const importLibraryCharacter = (projectId: string, request: components["schemas"]["CharacterLibraryImport"]) =>
  writeJson<CharacterLibraryCommand>(importsPath(projectId), { method: "POST", body: JSON.stringify(request) });
export const listCharacterLibraryLinks = (projectId: string) =>
  readJson<components["schemas"]["CharacterLibraryLinks"]>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/character-library/links`,
    t("character.loadFailed"),
  );
export const characterLibraryMediaUrl = (id: string, thumbnail = false) =>
  `${ROOT}/media/${encodeURIComponent(id)}/${thumbnail ? "thumbnail" : "content"}`;
