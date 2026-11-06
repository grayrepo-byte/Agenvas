import { queryOptions } from "@tanstack/react-query";
import { listCharacterLibraryLinks } from "../../shared/api/characterLibrary";
export const characterLibraryLinksOptions = (projectId: string) => queryOptions({ queryKey: ["character-library-links", projectId], queryFn: () => listCharacterLibraryLinks(projectId), retry: false });
