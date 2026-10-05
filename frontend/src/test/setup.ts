import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, beforeEach } from "vitest";
import { server } from "./server";
import { DEFAULT_LOCALE, setLocale } from "../shared/i18n";

class TestResizeObserver implements ResizeObserver {
  disconnect(): void {}
  observe(): void {}
  unobserve(): void {}
}

globalThis.ResizeObserver = TestResizeObserver;
// jsdom has no layout or pointer capture; Radix calls these browser APIs.
// user-event must dispatch real pointer fields (button, ctrlKey, pointerType).
if (!window.PointerEvent) window.PointerEvent = MouseEvent as typeof PointerEvent;
HTMLElement.prototype.hasPointerCapture = () => false;
HTMLElement.prototype.setPointerCapture = () => {};
HTMLElement.prototype.releasePointerCapture = () => {};
HTMLElement.prototype.scrollIntoView = () => {};

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => setLocale(DEFAULT_LOCALE));
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => server.close());
