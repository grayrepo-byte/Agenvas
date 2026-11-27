import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { components } from "../../shared/api/schema";
import { t } from "../../shared/i18n";
import { selectValue } from "../../test/controls";
import { VideoStudioResults } from "./VideoStudioResults";

type Generation = components["schemas"]["VideoStudioGeneration"];
type VideoResult = components["schemas"]["VideoStudioResult"];

function archived(id: string): VideoResult {
  return {
    artifactId: "artifact",
    artifactVersionId: `version-${id}`,
    assetId: id,
    width: 360,
    height: 640,
    durationMs: 5000,
    contentType: "video/mp4",
  };
}

function generation(
  status: Generation["task"]["status"],
  results: VideoResult[] = [],
  errorCode: string | null = null,
) {
  return {
    id: "generation",
    projectId: "private-scope",
    capabilityId: "mock",
    capabilityVersion: 1,
    prompt: "A paper boat",
    mode: "TEXT",
    durationSeconds: 4,
    aspectRatio: "9:16",
    videoResolution: null,
    dynamicValues: {},
    references: [],
    startFrame: null,
    endFrame: null,
    createdAt: "2026-11-22T00:00:00Z",
    task: { id: "task", version: 1, status, errorCode, results },
  } satisfies Generation;
}

const source = (asset: string) => `/api/v1/projects/private-scope/assets/${asset}/content`;

function player(container: HTMLElement) {
  const video = container.querySelector("video");
  if (!video) throw new Error("Expected a video player");
  return video;
}

function load(video: HTMLVideoElement) {
  let paused = true;
  Object.defineProperties(video, {
    duration: { configurable: true, value: 5 },
    paused: { configurable: true, get: () => paused },
  });
  const play = vi.spyOn(video, "play").mockImplementation(async () => {
    paused = false;
    fireEvent.play(video);
  });
  const pause = vi.spyOn(video, "pause").mockImplementation(() => {
    if (paused) return;
    paused = true;
    fireEvent.pause(video);
  });
  fireEvent.loadedMetadata(video);
  return { play, pause };
}

beforeEach(() => {
  vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
});

describe("VideoStudioResults", () => {
  it("uses the reported task state, without media or generation actions for empty and unresolved results", async () => {
    const user = userEvent.setup();
    const { container, rerender } = render(<VideoStudioResults />);
    expect(screen.getByText(t("videoStudio.emptyPreviewHint"))).toBeVisible();
    rerender(<VideoStudioResults generation={generation("READY")} />);
    expect(screen.getByRole("status", { name: t("tasks.status.queued") })).toBeVisible();
    rerender(<VideoStudioResults generation={generation("SUBMITTING")} />);
    expect(screen.getByRole("status", { name: t("logs.calls.submitting") })).toBeVisible();
    rerender(<VideoStudioResults generation={generation("WAITING_PROVIDER")} />);
    expect(screen.getByRole("status", { name: t("logs.calls.waitingForExternal") })).toBeVisible();
    rerender(<VideoStudioResults generation={generation("UNKNOWN", [], "PROVIDER_CALL_TIMEOUT")} />);
    expect(screen.getByRole("status")).toHaveTextContent(t("tasks.status.unknown"));
    expect(screen.getByRole("alert")).toHaveTextContent(t("imageStudio.unknownHint"));
    expect(screen.getByText("PROVIDER_CALL_TIMEOUT")).not.toBeVisible();
    await user.click(screen.getByText(t("imageStudio.errorDetails")));
    expect(screen.getByText("PROVIDER_CALL_TIMEOUT")).toBeVisible();
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(container.querySelector("video")).not.toBeInTheDocument();
  });

  it("keeps the archive and original download independent from media loading, errors, and GET retry", async () => {
    const request = vi.spyOn(globalThis, "fetch");
    const user = userEvent.setup();
    const { container } = render(<VideoStudioResults generation={generation("SUCCEEDED", [archived("first")])} />);
    const video = player(container);
    const download = screen.getByRole("link", { name: t("videoStudio.download") });
    expect(video).toHaveAttribute("src", source("first"));
    expect(video).toHaveAttribute("poster", "/api/v1/projects/private-scope/assets/first/thumbnail");
    expect(video).toHaveAttribute("preload", "metadata");
    expect(video).not.toHaveAttribute("autoplay");
    expect(download).toHaveAttribute("href", source("first"));
    expect(download).toHaveAttribute("download", "video-first.mp4");
    expect(screen.getByText(t("videoStudio.resultSaved"))).toBeVisible();
    expect(screen.getByText(`360 × 640 · ${t("agent.approval.seconds", { 0: 5 })}`)).toBeVisible();
    expect(screen.getByRole("status", { name: t("videoStudio.previewLoading") })).toBeVisible();
    expect(screen.queryByRole("button", { name: t("video.review.play") })).not.toBeInTheDocument();

    fireEvent.error(video);
    expect(screen.getByRole("alert")).toHaveTextContent(t("videoStudio.previewFailed"));
    expect(screen.getByText(t("videoStudio.resultSaved"))).toBeVisible();
    expect(download).toHaveAttribute("href", source("first"));
    const retry = screen.getByRole("button", { name: t("videoStudio.previewReload") });
    retry.focus();
    await user.keyboard("{Enter}");
    const replacement = player(container);
    expect(screen.getByRole("button", { name: t("videoStudio.previewReload") })).toBe(retry);
    expect(retry).toHaveFocus();
    expect(replacement).not.toBe(video);
    expect(replacement).toHaveAttribute("src", `${source("first")}?previewRetry=1`);
    expect(download).toHaveAttribute("href", source("first"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    const { play } = load(replacement);
    expect(screen.getByRole("slider", { name: t("video.review.progress") })).toHaveAttribute("max", "5");
    expect(play).not.toHaveBeenCalled();
    expect(request).not.toHaveBeenCalled();
  });

  it("continues playing the first displayed result when polling inserts an earlier archive", async () => {
    const user = userEvent.setup();
    const first = archived("first-visible");
    const late = archived("late");
    const { container, rerender } = render(<VideoStudioResults generation={generation("WAITING_PROVIDER")} />);
    rerender(<VideoStudioResults generation={generation("SUCCEEDED", [first])} />);
    const inline = player(container);
    load(inline);
    await user.click(screen.getByRole("button", { name: t("video.review.play") }));
    inline.currentTime = 2;
    rerender(<VideoStudioResults generation={generation("SUCCEEDED", [late, first])} />);
    expect(player(container)).toBe(inline);
    expect(inline.paused).toBe(false);
    expect(inline.currentTime).toBe(2);
    expect(screen.getByRole("combobox", { name: t("videoStudio.resultTitle") })).toHaveTextContent(
      t("videoStudio.resultPosition", { 0: 2, 1: 2 }),
    );
  });

  it("sizes an unmeasured archive from the media file and keeps its frame when playback fails", () => {
    const clip = { ...archived("unmeasured"), width: null, height: null };
    const { container } = render(<VideoStudioResults generation={generation("SUCCEEDED", [clip])} />);
    const video = player(container);
    const frame = video.parentElement!;
    Object.defineProperties(video, {
      videoWidth: { configurable: true, value: 640 },
      videoHeight: { configurable: true, value: 360 },
    });
    expect(frame.style.getPropertyValue("--video-studio-preview-ratio")).toBe(String(9 / 16));
    load(video);
    expect(frame.style.getPropertyValue("--video-studio-preview-ratio")).toBe(String(16 / 9));
    fireEvent.error(video);
    expect(frame.style.getPropertyValue("--video-studio-preview-ratio")).toBe(String(16 / 9));
  });

  it("reports buffering only during playback and keeps keyboard recovery reachable on a later media error", async () => {
    const user = userEvent.setup();
    const { container } = render(<VideoStudioResults generation={generation("SUCCEEDED", [archived("clip")])} />);
    const video = player(container);
    load(video);
    const buffering = () => screen.queryByRole("status", { name: t("videoStudio.previewBuffering") });
    fireEvent.waiting(video);
    expect(buffering()).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: t("video.review.play") }));
    fireEvent.waiting(video);
    expect(buffering()).toBeVisible();
    expect(screen.getByRole("button", { name: t("video.review.pause") })).toBeEnabled();
    fireEvent.playing(video);
    expect(buffering()).not.toBeInTheDocument();
    fireEvent.waiting(video);
    await user.click(screen.getByRole("button", { name: t("video.review.pause") }));
    expect(buffering()).not.toBeInTheDocument();
    screen.getByRole("slider", { name: t("video.review.progress") }).focus();
    fireEvent.error(video);
    expect(screen.getByRole("button", { name: t("videoStudio.previewReload") })).toHaveFocus();
  });

  it("preserves the chosen archive through late reordered results and stops it when switching", async () => {
    const user = userEvent.setup();
    const first = archived("first");
    const second = archived("second");
    const late = archived("late");
    const { container, rerender } = render(<VideoStudioResults generation={generation("CANCELED")} />);
    expect(screen.getByRole("status")).toHaveTextContent(t("common.canceled"));
    rerender(<VideoStudioResults generation={generation("CANCELED", [first, second])} />);
    const choices = screen.getByRole("combobox", { name: t("videoStudio.resultTitle") });
    expect(player(container)).toHaveAttribute("src", source("first"));
    await selectValue(choices, second.artifactVersionId);
    const video = player(container);
    expect(video).toHaveAttribute("src", source("second"));
    const { play } = load(video);
    await user.hover(video);
    expect(play).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: t("video.review.play") }));
    expect(video.paused).toBe(false);
    fireEvent.change(screen.getByRole("slider"), { target: { value: "2" } });
    expect(video.currentTime).toBe(2);
    await user.click(screen.getByRole("button", { name: t("video.review.mute") }));
    expect(video.muted).toBe(true);
    await user.click(screen.getByRole("button", { name: t("video.review.unmute") }));
    expect(video.muted).toBe(false);

    rerender(<VideoStudioResults generation={generation("CANCELED", [late, first, second])} />);
    expect(player(container)).toBe(video);
    expect(video.paused).toBe(false);
    expect(choices).toHaveTextContent(t("videoStudio.resultPosition", { "0": 3, "1": 3 }));
    await selectValue(choices, late.artifactVersionId);
    expect(video.paused).toBe(true);
    const replacement = player(container);
    expect(replacement).toHaveAttribute("src", source("late"));
    expect(replacement.paused).toBe(true);
    expect(screen.getByRole("link", { name: t("videoStudio.download") })).toHaveAttribute("href", source("late"));
  });

  it("pauses inline on expansion and closes the shared modal without restarting inline playback", async () => {
    vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(1024);
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(640);
    const user = userEvent.setup();
    const { container } = render(<VideoStudioResults generation={generation("SUCCEEDED", [archived("first")])} />);
    const inline = player(container);
    const { play } = load(inline);
    await user.click(screen.getByRole("button", { name: t("video.review.play") }));
    const expand = screen.getByRole("button", { name: t("videoStudio.expand") });
    await user.click(expand);
    expect(inline.paused).toBe(true);

    const dialog = await screen.findByRole("dialog");
    const modal = player(dialog);
    expect(modal.querySelector("source")).toHaveAttribute("src", source("first"));
    expect(modal).not.toHaveAttribute("autoplay");
    load(modal);
    await user.click(within(dialog).getByRole("button", { name: t("video.review.play") }));
    expect(modal.paused).toBe(false);
    await user.click(within(dialog).getByRole("button", { name: t("media.preview.close") }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(modal.paused).toBe(true);
    expect(inline.paused).toBe(true);
    expect(play).toHaveBeenCalledTimes(1);
    expect(expand).toHaveFocus();
  });
});
