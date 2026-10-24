import { queryOptions } from "@tanstack/react-query";
import { listCharacters, type CharacterBinding, type CharacterProfile } from "../../shared/api/client";

/** One project projection supplies every card badge and both character dialogs. */
export function characterQueryOptions(projectId: string) {
  return queryOptions({ queryKey: ["project-characters", projectId] as const, queryFn: () => listCharacters(projectId), retry: false });
}
export function characterBindingStale(binding: CharacterBinding, characters: CharacterProfile[]): boolean {
  return binding.snapshots.some((snapshot) => {
    const current = characters.find((character) => character.id === snapshot.characterId);
    return !current || current.archived || current.version !== snapshot.characterVersion;
  });
}
