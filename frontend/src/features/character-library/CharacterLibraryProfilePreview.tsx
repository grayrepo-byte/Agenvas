import type { LibraryCharacterContent, LibraryCharacterView } from "../../shared/api/characterLibrary";
import { characterLibraryMediaUrl } from "../../shared/api/characterLibrary";
import { t, useLocale } from "../../shared/i18n";
import { Badge } from "../../shared/ui/primitives/badge";
import { FieldDescription, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { getVoiceDisplayName, VOICES } from "../canvas/voiceCatalog";
import { cl } from "./characterLibraryMessages";
function Views({ views }: { views: LibraryCharacterView[] }) {
  return <div className="flex flex-wrap gap-3">{views.map((view, index) => <figure className="flex min-w-0 flex-col gap-1" key={`${view.mediaId}-${index}`}><img className="size-24 rounded object-contain" src={characterLibraryMediaUrl(view.mediaId, true)} alt={view.purpose} /><figcaption className="max-w-48 text-sm">{view.purpose}{view.description ? ` · ${view.description}` : ""}</figcaption></figure>)}</div>;
}
/** Show the actual frozen candidate, including identity/look media and voice, before transfer approval. */
export function CharacterLibraryProfilePreview({ content }: { content: LibraryCharacterContent }) {
  useLocale(); const voice = VOICES.find((entry) => entry.id === content.voice?.speakerId);
  return <div className="flex flex-col gap-4"><strong>{content.name}</strong>
    {content.persona ? <FieldSet><FieldLegend>{cl("persona")}</FieldLegend><FieldDescription className="whitespace-pre-wrap">{content.persona}</FieldDescription></FieldSet> : null}
    {content.appearance ? <FieldSet><FieldLegend>{cl("appearance")}</FieldLegend><FieldDescription className="whitespace-pre-wrap">{content.appearance}</FieldDescription></FieldSet> : null}
    <FieldSet><FieldLegend>{cl("identity")}</FieldLegend>{content.identityViews.length ? <Views views={content.identityViews} /> : <FieldDescription>{cl("noneImages")}</FieldDescription>}</FieldSet>
    {!content.looks.length ? <FieldDescription>{cl("noneLooks")}</FieldDescription> : null}
    {content.looks.map((look) => <FieldSet key={look.id}><FieldLegend>{look.name}</FieldLegend><FieldDescription>{[look.clothing, look.expression, look.description].filter(Boolean).join(" · ")}</FieldDescription>{look.views.length ? <Views views={look.views} /> : <FieldDescription>{cl("noneImages")}</FieldDescription>}</FieldSet>)}
    {content.voice ? <FieldSet><FieldLegend>{cl("voice")}</FieldLegend>{content.voice.mediaId ? <audio controls preload="none" aria-label={t("character.audioReference")} src={characterLibraryMediaUrl(content.voice.mediaId)} /> : null}{content.voice.speakerId ? <Badge variant="secondary">{voice ? getVoiceDisplayName(voice) : content.voice.speakerId}</Badge> : null}<FieldDescription>{content.voice.description}</FieldDescription></FieldSet> : <FieldSet><FieldLegend>{cl("voice")}</FieldLegend><FieldDescription>{cl("noneVoice")}</FieldDescription></FieldSet>}
  </div>;
}
