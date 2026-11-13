import type { RunAction } from "../../shared/api/client";
import { t, translate, type MessageKey } from "../../shared/i18n";

/** Only tool-specific, exact server-generated summaries are presentation; all other text stays literal. */
const GENERATED_SUMMARIES: Readonly<Record<string, readonly MessageKey[]>> = {
  load_toolset: ["agent.action.loadTools"],
  read_project_summary: ["agent.action.readProject"],
  read_skill: ["agent.action.readSkill"],
  read_skill_asset: ["agent.action.readSkillAsset"],
  read_skill_resource: ["agent.action.readSkillResource", "agent.action.readSkillResourceLimited"],
  read_selection: ["agent.action.readSelection"],
  read_artifacts: ["agent.action.readArtifacts", "agent.action.readArtifactsLimited"],
  read_media_sample: ["agent.action.readSample"],
  read_task_status: ["agent.action.readTask"],
  list_media_capabilities: ["agent.action.listCapabilities"],
  create_text: ["agent.action.createText"],
  revise_artifact: ["agent.action.reviseArtifact"],
  arrange_items: ["agent.action.arrangeItems"],
  read_canvas: ["agent.action.readCanvas"],
  manage_canvas_node: ["agent.action.updateNode", "agent.action.removeNode", "agent.action.copyNode"],
  update_media_draft: ["agent.action.updateDraft"],
  read_storyboard: ["agent.action.readStoryboard"],
  write_storyboard_shots: ["agent.action.writeStoryboard"],
  propose_media_generation: ["agent.action.proposeMedia"],
  extract_audio: ["agent.action.extractAudio"],
  trim_audio: ["agent.action.trimAudio"],
  compose_video: ["agent.action.composeVideo"],
};

export function runActionSummary(action: Pick<RunAction, "toolName" | "summary">): string {
  if (["extract_audio", "trim_audio", "compose_video"].includes(action.toolName)) {
    if (action.summary === translate("zh", "agent.action.localMediaCompleted"))
      return t("agent.action.localMediaCompleted");
    for (const status of ["FAILED", "CANCELED", "BLOCKED", "UNKNOWN"]) {
      if (action.summary === translate("zh", "agent.action.localMediaEnded", { status })) {
        return t("agent.action.localMediaEnded", { status });
      }
    }
  }
  const key = GENERATED_SUMMARIES[action.toolName]?.find(candidate => translate("zh", candidate) === action.summary);
  return key ? t(key) : action.summary;
}
