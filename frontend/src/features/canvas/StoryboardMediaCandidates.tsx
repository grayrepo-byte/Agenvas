import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { assetContentUrl, listCanvasMediaVersions, type AgentMediaApproval, type CanvasItem, type StoryboardShot } from "../../shared/api/client";
import { t, type MessageKey } from "../../shared/i18n";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { Button } from "../../shared/ui/primitives/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "../../shared/ui/primitives/dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import { MediaPreviewDialog } from "./MediaPreviewDialog";
import { readContentText } from "./artifactContent";
import { productionNeedsReview, type ProductionInput, type ShotInput } from "./storyboardDraft";
import { approvalMatchesShot, currentShotRevision, outputStoryboard, shotMediaCandidates, shotMediaTaskStatuses, type StoryboardMediaCandidate } from "./storyboardMedia";

const STATUS_KEYS: Record<AgentMediaApproval["status"], MessageKey> = {
  PENDING: "agent.approval.pending", APPROVED: "agent.approval.approved", SUCCEEDED: "agent.approval.succeeded",
  FAILED: "agent.approval.failed", REJECTED: "agent.approval.rejected", EXPIRED: "agent.approval.expired", CANCELED: "agent.approval.canceled",
};
const KIND_ROLE = { IMAGE: "KEYFRAME", VIDEO: "VIDEO", AUDIO: "AUDIO" } as const;
const ROLE_KEYS: Record<ProductionInput["role"], MessageKey> = {
  KEYFRAME: "storyboard.role.keyframe", VIDEO: "storyboard.role.video", AUDIO: "storyboard.role.audio",
};

function Candidate({ projectId, candidate, shot, base, approvals, items, disabled, onChoose }: {
  projectId: string; candidate: StoryboardMediaCandidate; shot: ShotInput; base?: StoryboardShot; items: CanvasItem[];
  approvals: readonly AgentMediaApproval[]; disabled: boolean; onChoose: (production: ProductionInput) => void;
}) {
  const item = items.find((entry) => entry.id === candidate.canvasItemId);
  const role = item && item.artifact.kind !== "TEXT" ? KIND_ROLE[item.artifact.kind] : undefined;
  const history = useQuery({ queryKey: ["canvas-media-versions", projectId, item?.id],
    queryFn: () => listCanvasMediaVersions(projectId, item!.id), enabled: Boolean(item && role) });
  const version = history.data?.items.find((entry) => entry.id === candidate.versionId);
  const [preview, setPreview] = useState(false);
  const selected = shot.productions.some((entry) => entry.canvasItemId === candidate.canvasItemId && entry.selectedVersionId === candidate.versionId);
  const currentRevision = currentShotRevision(shot, base);
  const stale = candidate.storyboard.shotRevision < currentRevision;
  const selectedProduction = shot.productions.find((entry) => entry.canvasItemId === candidate.canvasItemId && entry.selectedVersionId === candidate.versionId);
  const review = stale && (!selectedProduction || productionNeedsReview(shot, selectedProduction, base, approvals));
  const sourceUrl = version ? assetContentUrl(projectId, readContentText(version.content, "assetId")) : "";
  return <section className="storyboard-media-candidate" data-result-version={candidate.versionId}>
    <div className="storyboard-inline"><strong>{role ? t(ROLE_KEYS[role]) : candidate.title}</strong>
      {selected ? <Badge variant="secondary">{t("storyboard.candidate.selected")}</Badge> : null}
      {review ? <Badge variant="secondary">{t("storyboard.status.review")}</Badge> : null}</div>
    <p>{t("storyboard.candidate.basis", { "0": candidate.storyboard.shotCode, "1": candidate.storyboard.shotRevision, "2": currentRevision })}</p>
    <code>{version ? `v${version.versionNo} · ` : ""}{candidate.versionId}</code>
    {review ? <p>{t("storyboard.candidate.stale")}</p> : null}
    {!item ? <p>{t("storyboard.missingNode")}</p> : null}
    {item && history.isPending ? <LoadingState compact label={t("common.versionLoading")} /> : null}
    {history.isError ? <Alert variant="destructive"><AlertDescription>{t("storyboard.candidate.readFailed")}
      <Button variant="outline" size="sm" onClick={() => void history.refetch()}>{t("common.retryRead")}</Button></AlertDescription></Alert> : null}
    {history.isSuccess && !version ? <p>{t("storyboard.candidate.unavailable")}</p> : null}
    <div className="storyboard-inline"><Button variant="outline" size="sm" disabled={!version || !sourceUrl} onClick={() => setPreview(true)}>{t("storyboard.preview")}</Button>
      <Button variant="outline" size="sm" disabled={disabled || selected || !version || !role} onClick={() => {
        if (role && version) onChoose({ role, canvasItemId: candidate.canvasItemId, selectedVersionId: candidate.versionId, confirmReviewed: false });
      }}>{t("storyboard.candidate.choose")}</Button></div>
    {preview && role && role !== "AUDIO" ? <MediaPreviewDialog kind={role === "KEYFRAME" ? "image" : "video"} title={candidate.title}
      sourceUrl={sourceUrl} onClose={() => setPreview(false)} /> : null}
    {preview && role === "AUDIO" ? <Dialog open onOpenChange={(open) => { if (!open) setPreview(false); }}><DialogContent className="app-page" aria-describedby={undefined}>
      <DialogHeader><DialogTitle>{candidate.title}</DialogTitle></DialogHeader><audio controls src={sourceUrl} />
    </DialogContent></Dialog> : null}
  </section>;
}

/** A batch is always approved as a whole in the existing approval card; this view only adopts archived versions. */
export function StoryboardMediaCandidates({ projectId, shot, base, approvals, items, disabled, onChoose, onReview }: {
  projectId: string; shot: ShotInput; base?: StoryboardShot; approvals: readonly AgentMediaApproval[]; items: CanvasItem[];
  disabled: boolean; onChoose: (production: ProductionInput) => void; onReview: () => void;
}) {
  const associated = approvals.filter((approval) => approvalMatchesShot(approval, shot));
  const candidates = shotMediaCandidates(associated, shot);
  if (!associated.length) return null;
  return <section className="storyboard-media-return" aria-label={t("storyboard.candidate.title")}>
    <div className="storyboard-inline"><h3>{t("storyboard.candidate.title")}</h3>
      <Button variant="outline" size="sm" disabled={disabled} onClick={onReview}>{t("storyboard.candidate.reviewBatch")}</Button></div>
    <p>{t("storyboard.candidate.hint")}</p>
    <div className="storyboard-media-attempts">{associated.map((approval) => <div className="storyboard-inline" key={approval.id}>
      <Badge variant={approval.status === "FAILED" ? "destructive" : "secondary"}>{t(STATUS_KEYS[approval.status])}</Badge>
      {shotMediaTaskStatuses(approval, shot).includes("UNKNOWN") ? <Badge variant="destructive">{t("tasks.status.unknown")}</Badge> : null}
      <span>{approval.outputs.filter((output) => outputStoryboard(output)?.shotId === shot.id
        || !outputStoryboard(output) && shot.productions.some((entry) => entry.canvasItemId === output.canvasItemId)).map((output) => output.title).join(" · ")}</span>
    </div>)}</div>
    {candidates.length ? <div className="storyboard-media-candidates">{candidates.map((candidate) => <Candidate key={`${candidate.canvasItemId}:${candidate.versionId}`}
      projectId={projectId} candidate={candidate} shot={shot} base={base} approvals={associated} items={items} disabled={disabled} onChoose={onChoose} />)}</div> : <p>{t("storyboard.candidate.empty")}</p>}
  </section>;
}
