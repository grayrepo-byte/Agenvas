import { useState } from "react";
import { assetContentUrl, assetThumbnailUrl, type ArtifactVersionList } from "../../shared/api/client";
import { getFormatLocale, t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { readContentText } from "./artifactContent";
import { VideoReviewControls } from "./VideoReviewControls";

export type VideoReviewVersion = { version: ArtifactVersionList["items"][number]; nodeVersionNo: number };

function resultInputs(version: VideoReviewVersion["version"]): unknown {
  return version.frozenInput ?? { content: version.content, inputReferences: version.inputReferences };
}

function ComparisonSide({ projectId, versions, selectedId, onSelect, onVideo, label }: {
  projectId: string; versions: VideoReviewVersion[]; selectedId: string; onSelect: (id: string) => void;
  onVideo: (element: HTMLVideoElement | null) => void; label: string;
}) {
  const selected = versions.find(({ version }) => version.id === selectedId);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const assetId = readContentText(selected?.version.content, "assetId");
  return <section className="video-comparison-side" aria-label={label}>
    <Field><FieldLabel>{label}</FieldLabel><Select aria-label={label} value={selectedId} onChange={(event) => {
      setFailed(false); setAttempt(0); onSelect(event.target.value);
    }}>{versions.map(({ version, nodeVersionNo }) => <option key={version.id} value={version.id}>v{nodeVersionNo}</option>)}</Select></Field>
    {assetId ? <video key={`${selectedId}:${attempt}`} ref={onVideo} src={assetContentUrl(projectId, assetId)}
      poster={assetThumbnailUrl(projectId, assetId)} aria-label={label} playsInline preload="metadata"
      onError={() => setFailed(true)} /> : null}
    {failed ? <Alert variant="destructive"><AlertDescription>{t("media.preview.loadFailed")}
      <Button type="button" variant="outline" onClick={() => { setFailed(false); setAttempt((value) => value + 1); }}>{t("media.preview.retry")}</Button>
    </AlertDescription></Alert> : null}
    {selected ? <div className="video-comparison-summary">
      <time dateTime={selected.version.createdAt}>{new Date(selected.version.createdAt).toLocaleString(getFormatLocale())}</time>
      <p>{readContentText(selected.version.content, "prompt") || t("video.review.noPrompt")}</p>
      <p>{t("video.review.references", { "0": selected.version.inputReferences.length })}</p>
      <details><summary>{t("video.review.summaryDetails")}</summary><pre>{JSON.stringify(resultInputs(selected.version), null, 2)}</pre></details>
    </div> : null}
  </section>;
}

/** Comparison is read-only: changing either side never selects a canvas result. */
export function VideoComparisonDialog({ projectId, title, versions, selectedVersionId, onClose }: {
  projectId: string; title: string; versions: VideoReviewVersion[]; selectedVersionId: string | null; onClose: () => void;
}) {
  useLocale();
  const initial = versions.find(({ version }) => version.id === selectedVersionId) ?? versions[0];
  const [leftId, setLeftId] = useState(initial?.version.id ?? "");
  const [rightId, setRightId] = useState(versions.find(({ version }) => version.id !== initial?.version.id)?.version.id ?? initial?.version.id ?? "");
  const [left, setLeft] = useState<HTMLVideoElement | null>(null);
  const [right, setRight] = useState<HTMLVideoElement | null>(null);
  return <Dialog title={`${title} · ${t("video.review.compare")}`} className="video-comparison-dialog nodrag nowheel nopan"
    description={t("video.review.syncHint")} onClose={onClose} onSubmit={(event) => event.preventDefault()}>
    <div className="video-comparison-players">
      <ComparisonSide projectId={projectId} versions={versions} selectedId={leftId} onSelect={setLeftId} onVideo={setLeft} label={t("video.review.left")} />
      <ComparisonSide projectId={projectId} versions={versions} selectedId={rightId} onSelect={setRightId} onVideo={setRight} label={t("video.review.right")} />
    </div>
    <VideoReviewControls key={`${leftId}:${rightId}`} video={left} partner={right} />
  </Dialog>;
}
