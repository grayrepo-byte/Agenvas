import { describe,expect,it } from "vitest";
import { readFileSync } from "node:fs";
import { SUPPORTED_LOCALES,setLocale,translate,type MessageKey } from "../../shared/i18n";
import en from "../../shared/i18n/locales/en.json";
import { packagedSkills,packagedSkillVersions } from "../../test/builtinSkills";
import { getSkillLabels,getSkillVersionLabels,needsSkillOrigin } from "./builtinSkillLabels";

describe("packaged Skill display metadata",()=>{
  it.each(SUPPORTED_LOCALES)("keeps %s search aliases identical to the displayed catalog metadata",(locale)=>{
    const file=`../../../../backend/src/main/resources/i18n/messages${locale==="zh" ? "":`_${locale}`}.properties`;
    const properties=new Map(readFileSync(new URL(file,import.meta.url),"utf8").split(/\r?\n/)
      .filter((line)=>line.startsWith("skills.builtin.")).map((line)=>{
        const separator=line.indexOf("=");
        return [line.slice(0,separator),line.slice(separator+1)];
      }));
    const keys=Object.keys(en).filter((key)=>key.startsWith("skills.builtin.")) as MessageKey[];
    expect(keys).toHaveLength(packagedSkills.length*2);
    expect([...properties.keys()].sort()).toEqual([...keys].sort());
    for(const key of keys)expect(properties.get(key),key).toBe(translate(locale,key));
  });

  it("covers every installed title and description without changing versioned content",()=>{
    setLocale("en");
    expect(packagedSkills).toHaveLength(17);
    const source=JSON.stringify({packagedSkills,packagedSkillVersions});
    for(const [index,skill] of packagedSkills.entries()){
      const labels=getSkillLabels(skill),version=packagedSkillVersions[index]!;
      expect(labels.title).not.toMatch(/\p{Script=Han}/u);
      expect(labels.description).not.toMatch(/\p{Script=Han}/u);
      expect(labels.description).not.toBe("");
      expect(getSkillVersionLabels(version,skill)).toEqual(labels);
      expect(needsSkillOrigin(version)).toBe(true);
    }
    expect(JSON.stringify({packagedSkills,packagedSkillVersions})).toBe(source);
  });

  it("preserves personal copies, unknown origins, near matches and custom fields",()=>{
    setLocale("en");
    const skill=packagedSkills[0]!,version=packagedSkillVersions[0]!;
    const original={title:skill.title,description:skill.description};
    for(const value of [{...skill,builtin:false},{...skill,builtinSource:null},
      {...skill,builtinSource:{...skill.builtinSource!,repository:"https://example.test/custom-skills"}},
      {...skill,title:`${skill.title} `}]){
      expect(getSkillLabels(value)).toEqual({title:value.title,description:value.description});
      expect(getSkillVersionLabels(version,value)).toEqual({title:version.name,description:version.description});
    }
    expect(getSkillLabels({...skill,description:"我的说明"})).toEqual({title:"Visual asset design · short-drama-assets",description:"我的说明"});
    expect(getSkillVersionLabels({...version,description:"我的版本说明"},skill).description).toBe("我的版本说明");
    expect(getSkillVersionLabels(version)).toEqual({title:version.name,description:version.description});
    expect(getSkillVersionLabels({...version,skillId:"another-skill"},skill).description).toBe(version.description);
    expect(getSkillLabels(skill)).not.toEqual(original);
    expect(getSkillLabels({...skill,builtin:false})).toEqual(original);
  });

  it.each([
    ["en","Visual asset design · short-drama-assets"],
    ["zh","视觉资产设定 · short-drama-assets"],
    ["ru","Визуальные материалы · short-drama-assets"],
    ["ja","ビジュアル素材設計 · short-drama-assets"],
  ] as const)("resolves built-in metadata when switching to %s",(locale,title)=>{
    setLocale(locale);
    expect(getSkillLabels(packagedSkills[0]!).title).toBe(title);
  });
});
