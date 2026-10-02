import { t,useLocale } from "../../shared/i18n";

function record(value:unknown):Readonly<Record<string,unknown>> {
  return typeof value==="object" && value!==null && !Array.isArray(value) ? value as Readonly<Record<string,unknown>> : {};
}
function text(value:unknown) {return typeof value==="string" ? value : "";}
function rows(value:unknown) {return Array.isArray(value) ? value.map(record) : [];}
/** Display only versioned creative content; archive paths and arbitrary provider fields stay private. */
export function CreativeSkillSource({source}:{source:unknown}) {
  useLocale();
  const skill=record(source);
  if (!text(skill.skillVersionId))return null;
  const version=typeof skill.versionNumber==="number" ? skill.versionNumber : "";
  const resources=rows(skill.resources),inputs=rows(skill.inputs),assets=rows(skill.assets);
  return <details className="skills-source-detail nodrag nowheel nopan"><summary>{t("skills.source")} · {text(skill.name)} · {t("skills.version",{"0":version})}</summary>
    <p>{text(skill.description)}</p><p>{text(skill.bundleHash)}</p>
    {text(skill.skillMd) ? <pre className="skills-source-preview">{text(skill.skillMd)}</pre> : null}
    {resources.map((resource,index)=><details key={`${text(resource.path)}:${index}`}><summary>{text(resource.path)} · {text(resource.contentHash)}</summary><pre className="skills-source-preview">{text(resource.content)}</pre></details>)}
    {inputs.length || assets.length ? <ul>{[...inputs,...assets].map((input,index)=><li key={`${text(input.alias)}:${index}`}>
      {text(input.alias)} · {text(input.purpose)} · {text(input.artifactVersionId)}
    </li>)}</ul> : null}
  </details>;
}
