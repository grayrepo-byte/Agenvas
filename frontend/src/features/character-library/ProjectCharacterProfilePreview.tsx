import type { CharacterProfile } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Badge } from "../../shared/ui/primitives/badge";
import { FieldDescription, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { getVoiceDisplayName, VOICES } from "../canvas/voiceCatalog";
import { CharacterReferencePreview } from "../characters/CharacterReferencePicker";
import { cl } from "./characterLibraryMessages";
/** The project side includes local customizations that synchronization will replace or remove. */
export function ProjectCharacterProfilePreview({
  projectId,
  profile,
}: {
  projectId: string;
  profile: CharacterProfile;
}) {
  useLocale();
  const content = profile.content;
  const voice = VOICES.find(entry => entry.id === content.voice?.speakerId);
  return (
    <div className="flex min-w-0 flex-col gap-4">
      <strong>
        {content.name} · v{profile.version}
      </strong>
      <FieldSet>
        <FieldLegend>{t("character.characterDescription")}</FieldLegend>
        <FieldDescription className="whitespace-pre-wrap">{content.description}</FieldDescription>
      </FieldSet>
      <FieldSet>
        <FieldLegend>{t("character.looks")}</FieldLegend>
        {content.looks.length ? (
          content.looks.map(look => (
            <FieldSet key={look.id}>
              <FieldLegend>{look.name}</FieldLegend>
              <FieldDescription>
                {[look.clothing, look.expression, look.description].filter(Boolean).join(" · ")}
              </FieldDescription>
              {look.views.length ? (
                look.views.map((view, index) => (
                  <div className="flex min-w-0 flex-col gap-1" key={`${view.versionId}-${index}`}>
                    <CharacterReferencePreview projectId={projectId} versionId={view.versionId} kind="IMAGE" />
                    <FieldDescription>
                      {view.purpose}
                      {view.description ? ` · ${view.description}` : ""}
                    </FieldDescription>
                  </div>
                ))
              ) : (
                <FieldDescription>{cl("noneImages")}</FieldDescription>
              )}
            </FieldSet>
          ))
        ) : (
          <FieldDescription>{cl("noneLooks")}</FieldDescription>
        )}
      </FieldSet>
      <FieldSet>
        <FieldLegend>{cl("voice")}</FieldLegend>
        {content.voice ? (
          <>
            {content.voice.versionId ? (
              <CharacterReferencePreview projectId={projectId} versionId={content.voice.versionId} kind="AUDIO" />
            ) : null}
            {content.voice.speakerId ? (
              <Badge variant="secondary">{voice ? getVoiceDisplayName(voice) : content.voice.speakerId}</Badge>
            ) : null}
            <FieldDescription>{content.voice.description}</FieldDescription>
          </>
        ) : (
          <FieldDescription>{cl("noneVoice")}</FieldDescription>
        )}
      </FieldSet>
    </div>
  );
}
