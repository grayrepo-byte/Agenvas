import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";

const TEST_NOW = "2026-09-26T10:00:00.000Z";
const ONE_SECOND_MS = 1_000;

afterEach(() => vi.useRealTimers());

describe("CanvasLoadingState", () => {
  it("announces the actual task state without inventing elapsed time or progress", () => {
    render(<CanvasLoadingState label="正在核对外部结果" />);

    expect(screen.getByRole("status", { name: "正在核对外部结果" })).toBeInTheDocument();
    expect(screen.queryByTitle("自任务开始至今的时间，不代表完成进度")).not.toBeInTheDocument();
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
  });

  it("uses the task start time and releases its clock when unmounted", () => {
    vi.useFakeTimers();
    vi.setSystemTime(TEST_NOW);
    const { unmount } = render(<CanvasLoadingState label="正在生成图片" startedAt="2026-09-26T09:58:55.000Z" />);

    expect(screen.getByText("1m 5s")).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(ONE_SECOND_MS));
    expect(screen.getByText("1m 6s")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveAccessibleName("正在生成图片");

    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("does not display a made-up duration for invalid dates and tolerates clock skew", () => {
    vi.useFakeTimers();
    vi.setSystemTime(TEST_NOW);
    const { rerender } = render(<CanvasLoadingState label="排队中" startedAt="invalid" />);

    expect(screen.queryByTitle("自任务开始至今的时间，不代表完成进度")).not.toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(0);

    rerender(<CanvasLoadingState label="排队中" startedAt="2026-09-26T10:00:01.000Z" />);
    expect(screen.getByText("0s")).toBeInTheDocument();
  });
});
