import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import * as api from "../../shared/api/client";
import { setLocale } from "../../shared/i18n";
import { server } from "../../test/server";
import { LocalServiceDiscovery } from "./LocalServiceDiscovery";

type Result = {
  service: "OLLAMA" | "COMFYUI";
  runtime: "CONTAINER" | "NATIVE" | "UNKNOWN";
  status:
    "FOUND" | "NOT_FOUND" | "UNRESOLVED" | "TIMEOUT" | "UNEXPECTED_RESPONSE" | "AUTH_REQUIRED" | "UNSUPPORTED_RUNTIME";
  endpoint: string | null;
};
const found: Result = {
  service: "OLLAMA",
  runtime: "CONTAINER",
  status: "FOUND",
  endpoint: "http://host.docker.internal:11434/v1",
};

function mount(props: Partial<Parameters<typeof LocalServiceDiscovery>[0]> = {}) {
  const select = vi.fn();
  const client = createQueryClient();
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<LocalServiceDiscovery service="OLLAMA" onSelect={select} {...props} />} />
          <Route path="/login" element={<p>Sign in again</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, select };
}
beforeEach(() => {
  setLocale("en");
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })));
});

describe("LocalServiceDiscovery", () => {
  it("only probes on demand and fills after an explicit choice", async () => {
    const requests: unknown[] = [];
    server.use(
      http.post("/api/v1/settings/local-services/discover", async ({ request }) => {
        requests.push(await request.json());
        expect(request.headers.get("X-XSRF-TOKEN")).toBe("test");
        return HttpResponse.json(found);
      }),
    );
    const { select } = mount();
    const user = userEvent.setup();
    expect(requests).toEqual([]);
    expect(screen.queryByRole("button", { name: "Fill address" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
    await screen.findByText(found.endpoint!);
    expect(requests).toEqual([{ service: "OLLAMA" }]);
    expect(select).not.toHaveBeenCalled();
    expect(screen.getByText(/computer running Docker/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Fill address" }));
    expect(select).toHaveBeenCalledExactlyOnceWith(found.endpoint);
  });
  it("keeps detailed guidance in accessible click help without starting a check", async () => {
    const requests: unknown[] = [];
    server.use(
      http.post("/api/v1/settings/local-services/discover", async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json(found);
      }),
    );
    mount();
    const user = userEvent.setup();
    expect(screen.queryByText(/Custom ports, other containers/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Local discovery help" }));
    const help = await screen.findByRole("dialog", { name: "Local discovery help" });
    expect(help).toHaveTextContent("Custom ports, other containers");
    expect(help).toHaveTextContent("No proxy is started");
    expect(help).toHaveTextContent("access permissions are not changed");
    expect(help).toHaveTextContent("127.0.0.1");
    expect(help).toHaveTextContent("Windows/WSL");
    expect(help).toHaveTextContent("Cancelling stops waiting here");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(requests).toEqual([]);
  });
  it.each([
    ["NOT_FOUND", "No service was found"],
    ["UNRESOLVED", "host address could not be resolved"],
    ["TIMEOUT", "check timed out"],
    ["UNEXPECTED_RESPONSE", "response did not identify the expected service"],
    ["AUTH_REQUIRED", "service requires authentication"],
    ["UNSUPPORTED_RUNTIME", "deployment could not determine where to look"],
  ] as const)("explains %s without offering an address", async (status, message) => {
    server.use(
      http.post("/api/v1/settings/local-services/discover", () =>
        HttpResponse.json({
          ...found,
          runtime: status === "UNSUPPORTED_RUNTIME" ? "UNKNOWN" : "NATIVE",
          status,
          endpoint: null,
        }),
      ),
    );
    mount();
    await userEvent.setup().click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
    expect(await screen.findByText(new RegExp(message))).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Fill address" })).not.toBeInTheDocument();
  });
  it.each([
    [403, "Only an administrator"],
    [429, "Another check is running"],
    [503, "Discovery is unavailable"],
  ] as const)("explains HTTP %s and permits explicit retry", async (status, message) => {
    let count = 0;
    server.use(
      http.post("/api/v1/settings/local-services/discover", () => {
        count++;
        return HttpResponse.json(
          { title: "Request failed", detail: "internal text must not appear", code: "TEST", retryable: true },
          { status, headers: { "Content-Type": "application/problem+json" } },
        );
      }),
    );
    mount();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(document.body).not.toHaveTextContent("internal text must not appear");
    await user.click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
    await waitFor(() => expect(count).toBe(2));
  });
  it("does not offer Fill for a missing endpoint, even with a found status", async () => {
    server.use(
      http.post("/api/v1/settings/local-services/discover", () => HttpResponse.json({ ...found, endpoint: null })),
    );
    mount();
    await userEvent.setup().click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
    await screen.findByText("Service found.");
    expect(screen.queryByRole("button", { name: "Fill address" })).not.toBeInTheDocument();
  });
  it("keeps a failed transport actionable without exposing exception details", async () => {
    server.use(http.post("/api/v1/settings/local-services/discover", () => HttpResponse.error()));
    mount();
    await userEvent.setup().click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The local service check failed.");
  });
  it("redirects an expired session to login", async () => {
    server.use(http.post("/api/v1/settings/local-services/discover", () => new HttpResponse(null, { status: 401 })));
    mount();
    await userEvent.setup().click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
    expect(await screen.findByText("Sign in again")).toBeInTheDocument();
  });
  it("cancels and ignores a late response after a new check", async () => {
    let finish!: (result: Result) => void;
    const probe = vi
      .spyOn(api, "discoverLocalService")
      .mockImplementationOnce(
        () =>
          new Promise(resolve => {
            finish = resolve;
          }),
      )
      .mockResolvedValueOnce({ ...found, runtime: "NATIVE", endpoint: "http://127.0.0.1:11434/v1" });
    try {
      const { select } = mount();
      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
      expect(screen.getByRole("status")).toHaveTextContent("Checking");
      await user.click(screen.getByRole("button", { name: "Cancel check" }));
      expect(probe.mock.calls[0]![1]!.aborted).toBe(true);
      await user.click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
      await screen.findByText("http://127.0.0.1:11434/v1");
      await act(async () => finish(found));
      expect(screen.queryByText(found.endpoint!)).not.toBeInTheDocument();
      expect(select).not.toHaveBeenCalled();
    } finally {
      probe.mockRestore();
    }
  });
  it("aborts on disable and unmount without filling", async () => {
    let finish!: (result: Result) => void;
    const probe = vi.spyOn(api, "discoverLocalService").mockImplementation(
      () =>
        new Promise(resolve => {
          finish = resolve;
        }),
    );
    try {
      const client = createQueryClient();
      const select = vi.fn();
      const control = (disabled: boolean) => (
        <QueryClientProvider client={client}>
          <MemoryRouter>
            <LocalServiceDiscovery service="OLLAMA" onSelect={select} disabled={disabled} />
          </MemoryRouter>
        </QueryClientProvider>
      );
      const view = render(control(false));
      await userEvent.setup().click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
      view.rerender(control(true));
      expect(probe.mock.calls[0]![1]!.aborted).toBe(true);
      await act(async () => finish(found));
      expect(screen.queryByRole("button", { name: "Fill address" })).not.toBeInTheDocument();
      view.rerender(control(false));
      await userEvent.setup().click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
      view.unmount();
      expect(probe.mock.calls[1]![1]!.aborted).toBe(true);
      expect(select).not.toHaveBeenCalled();
    } finally {
      probe.mockRestore();
    }
  });
  it("aborts a previous service on a prop change", async () => {
    const probe = vi.spyOn(api, "discoverLocalService").mockImplementation(() => new Promise(() => {}));
    try {
      const client = createQueryClient();
      const select = vi.fn();
      const control = (service: "OLLAMA" | "COMFYUI") => (
        <QueryClientProvider client={client}>
          <MemoryRouter>
            <LocalServiceDiscovery service={service} onSelect={select} />
          </MemoryRouter>
        </QueryClientProvider>
      );
      const view = render(control("OLLAMA"));
      await userEvent.setup().click(screen.getByRole("button", { name: "Find Ollama on this machine" }));
      view.rerender(control("COMFYUI"));
      expect(probe.mock.calls[0]![1]!.aborted).toBe(true);
      await waitFor(() => expect(screen.getByRole("button", { name: "Find ComfyUI on this machine" })).toBeEnabled());
    } finally {
      probe.mockRestore();
    }
  });
});
