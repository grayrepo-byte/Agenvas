import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { SetupPage } from "./SetupPage";

describe("SetupPage", () => {
  it("shows the server-backed initialization state and the mock warning", async () => {
    render(
      <QueryClientProvider client={createQueryClient()}>
        <SetupPage />
      </QueryClientProvider>,
    );

    expect(screen.getByText("Mock 媒体模式 · 不会调用外部模型")).toBeInTheDocument();
    expect(await screen.findByText("需要创建管理员")).toBeInTheDocument();
  });
});
