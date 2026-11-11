import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createDirectorScene } from "./directorScene";
import { exportDirectorVideo } from "./directorVideoExport";

const rendering = vi.hoisted(() => ({ frames: vi.fn(), dispose: vi.fn(), create: vi.fn() }));
vi.mock("./directorRenderer", () => ({ createDirectorRenderer: (...args: unknown[]) => {
  rendering.create(...args);
  return { renderReferenceFrame: rendering.frames, dispose: rendering.dispose, setDisabled: vi.fn() };
} }));
let tracks: { stop: ReturnType<typeof vi.fn> }[];
class FakeRecorder extends EventTarget {
  static instances: FakeRecorder[] = [];
  static isTypeSupported = vi.fn((type: string) => type === "video/webm;codecs=vp8");
  state = "inactive";
  mimeType = "video/webm;codecs=vp8";
  constructor() { super(); FakeRecorder.instances.push(this); }
  start() { this.state = "recording"; }
  stop() {
    this.state = "inactive";
    this.dispatchEvent(Object.assign(new Event("dataavailable"), { data: new Blob(["synthetic WebM bytes"]) }));
    this.dispatchEvent(new Event("stop"));
  }
}
function scene() {
  const value = createDirectorScene();
  value.motion = { durationSeconds: 1, keyframes: [
    { id: "a", name: "Front", timeSeconds: 0, transition: "LINEAR", camera: { ...value.camera, azimuth: 0 } },
    { id: "b", name: "Side", timeSeconds: 1, transition: "LINEAR", camera: { ...value.camera, azimuth: 90 } },
  ] };
  return value;
}
beforeEach(() => {
  vi.useFakeTimers();
  rendering.frames.mockClear(); rendering.dispose.mockClear(); rendering.create.mockClear();
  tracks = []; FakeRecorder.instances = [];
  vi.stubGlobal("MediaRecorder", FakeRecorder);
  Object.defineProperty(HTMLCanvasElement.prototype, "captureStream", { configurable: true, value: vi.fn(() => {
    const track = { stop: vi.fn() }; tracks.push(track);
    return { getTracks: () => [track], getVideoTracks: () => [track] };
  }) });
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); Reflect.deleteProperty(HTMLCanvasElement.prototype, "captureStream"); });
const options = () => ({ signal: new AbortController().signal, onProgress: vi.fn() });
async function started() { await vi.advanceTimersByTimeAsync(1); }

describe("local 3D reference video export", () => {
  it("returns an explicitly labelled WebM, samples real camera times at bounded dimensions and frees its renderer and stream", async () => {
    const input = scene(), original = structuredClone(input), progress = vi.fn();
    const result = exportDirectorVideo(input, { ...options(), onProgress: progress });
    await started(); await vi.advanceTimersByTimeAsync(1200);
    const file = await result;
    expect(file.name).toBe("director-motion-reference.webm");
    expect(file.type).toBe("video/webm");
    expect(file.size).toBeGreaterThan(0);
    expect(rendering.frames.mock.calls[0]).toEqual([0, 960, 540]);
    expect(rendering.frames.mock.calls.at(-1)).toEqual([1, 960, 540]);
    expect(rendering.dispose).toHaveBeenCalledOnce();
    expect(tracks[0]!.stop).toHaveBeenCalledOnce();
    expect(progress).toHaveBeenLastCalledWith(100);
    expect(vi.getTimerCount()).toBe(0);
    expect(input).toEqual(original);
  });

  it("fails clearly when WebM recording is unsupported without allocating a renderer", async () => {
    vi.stubGlobal("MediaRecorder", undefined);
    await expect(exportDirectorVideo(scene(), options())).rejects.toMatchObject({ code: "UNSUPPORTED" });
    expect(rendering.create).not.toHaveBeenCalled();
  });

  it("cancels without returning a partial file and can export again with fresh resources", async () => {
    const controller = new AbortController();
    const result = exportDirectorVideo(scene(), { ...options(), signal: controller.signal });
    const rejected = expect(result).rejects.toMatchObject({ code: "CANCELLED" });
    await started(); controller.abort(); await rejected;
    expect(rendering.dispose).toHaveBeenCalledOnce(); expect(tracks[0]!.stop).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
    const second = exportDirectorVideo(scene(), options());
    await started(); await vi.advanceTimersByTimeAsync(1200);
    expect((await second).size).toBeGreaterThan(0);
    expect(rendering.dispose).toHaveBeenCalledTimes(2); expect(tracks).toHaveLength(2);
  });

  it("releases all resources on encoder failure and on backgrounding", async () => {
    const result = exportDirectorVideo(scene(), options());
    const failed = expect(result).rejects.toMatchObject({ code: "FAILED" });
    await started(); FakeRecorder.instances.at(-1)!.dispatchEvent(new Event("error")); await failed;
    expect(tracks[0]!.stop).toHaveBeenCalledOnce();
    const next = exportDirectorVideo(scene(), options());
    const hiddenResult = expect(next).rejects.toMatchObject({ code: "HIDDEN" });
    await started();
    const hidden = vi.spyOn(document, "hidden", "get").mockReturnValue(true);
    document.dispatchEvent(new Event("visibilitychange")); await hiddenResult; hidden.mockRestore();
    expect(rendering.dispose).toHaveBeenCalledTimes(2); expect(vi.getTimerCount()).toBe(0);
  });
  it("rejects oversized output and a stalled clock rather than downloading an incomplete take", async () => {
    const result = exportDirectorVideo(scene(), options());
    const failed = expect(result).rejects.toMatchObject({ code: "LIMIT" });
    await started();
    const oversized = new Blob(["synthetic"]);
    Object.defineProperty(oversized, "size", { value: 49 * 1024 * 1024 });
    FakeRecorder.instances.at(-1)!.dispatchEvent(Object.assign(new Event("dataavailable"), { data: oversized }));
    await failed;
    expect(tracks[0]!.stop).toHaveBeenCalledOnce();
    vi.stubGlobal("requestAnimationFrame", vi.fn(() => 12));
    const stalled = exportDirectorVideo(scene(), options());
    const timeout = expect(stalled).rejects.toMatchObject({ code: "FAILED" });
    await started(); await vi.advanceTimersByTimeAsync(17000); await timeout;
    expect(tracks[1]!.stop).toHaveBeenCalledOnce(); expect(rendering.dispose).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
  });

});
