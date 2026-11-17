import { useInfiniteQuery } from "@tanstack/react-query";
import { useId, useState } from "react";
import {
  characterLibraryMediaUrl,
  listLibraryCharacters,
  type LibraryCharacter,
} from "../../shared/api/characterLibrary";
import { t, useLocale } from "../../shared/i18n";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { Button } from "../../shared/ui/primitives/button";
import { Empty, EmptyDescription, EmptyHeader } from "../../shared/ui/primitives/empty";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { cl } from "./characterLibraryMessages";
export function CharacterLibraryBrowser({
  onSelect,
  disabled = false,
  allowArchived = false,
}: {
  onSelect: (profile: LibraryCharacter) => void;
  disabled?: boolean;
  allowArchived?: boolean;
}) {
  useLocale();
  const id = useId();
  const [query, setQuery] = useState("");
  const [archived, setArchived] = useState(false);
  const list = useInfiniteQuery({
    queryKey: ["character-library", { query, archived }],
    queryFn: ({ pageParam }) => listLibraryCharacters(query, pageParam, archived),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: page => page.nextCursor ?? undefined,
    retry: false,
  });
  const profiles = list.data?.pages.flatMap(page => page.items) ?? [];
  return (
    <section className="flex flex-col gap-4" aria-label={cl("title")}>
      <FieldGroup>
        <Field>
          <FieldLabel htmlFor={`${id}-search`}>{cl("search")}</FieldLabel>
          <Input
            id={`${id}-search`}
            type="search"
            maxLength={160}
            value={query}
            onChange={event => setQuery(event.target.value)}
          />
        </Field>
      </FieldGroup>
      {allowArchived ? (
        <Button type="button" variant="outline" aria-pressed={archived} onClick={() => setArchived(!archived)}>
          {cl("showArchived")}
        </Button>
      ) : null}
      {list.isPending ? <p role="status">{t("common.loading")}</p> : null}
      {list.error ? (
        <Alert variant="destructive">
          <AlertDescription>
            {list.error.message}
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                void list.refetch();
              }}
            >
              {t("common.retry")}
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}
      {!list.isPending && !list.error && !profiles.length ? (
        <Empty>
          <EmptyHeader>
            <EmptyDescription>{cl("empty")}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {profiles.map(profile => {
          const mediaId =
            profile.content.identityViews[0]?.mediaId ?? profile.content.looks.flatMap(look => look.views)[0]?.mediaId;
          return (
            <Button
              className="justify-start p-3"
              size="content"
              key={profile.id}
              type="button"
              variant="outline"
              disabled={disabled}
              onClick={() => onSelect(profile)}
              aria-label={profile.content.name}
            >
              {mediaId ? (
                <img className="size-16 rounded object-cover" src={characterLibraryMediaUrl(mediaId, true)} alt="" />
              ) : null}
              <span className="flex min-w-0 flex-col items-start gap-1">
                <strong className="truncate">{profile.content.name}</strong>
                <span className="line-clamp-2 text-left text-xs text-muted-foreground">{profile.content.persona}</span>
                <Badge variant="secondary">v{profile.version}</Badge>
                {profile.archived ? <Badge variant="outline">{cl("archived")}</Badge> : null}
              </span>
            </Button>
          );
        })}
      </div>
      {list.hasNextPage ? (
        <Button
          type="button"
          variant="ghost"
          disabled={list.isFetchingNextPage}
          onClick={() => {
            void list.fetchNextPage();
          }}
        >
          {cl("loadMore")}
        </Button>
      ) : null}
    </section>
  );
}
