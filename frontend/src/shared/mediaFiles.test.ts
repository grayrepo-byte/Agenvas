import { describe, expect, it } from "vitest";
import { isVideoFile, MEDIA_FILE_ACCEPT } from "./mediaFiles";

describe("local video uploads", () => {
  it("offers WebM and routes files without a browser MIME type", () => {
    expect(MEDIA_FILE_ACCEPT.VIDEO).toContain("video/webm");
    expect(isVideoFile(new File(["synthetic"], "director.WEBM"))).toBe(true);
  });
});
