import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import { SetupPage } from "./SetupPage";

describe("SetupPage", () => {
  it("shows the server-backed initialization state and the mock warning", async () => {
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter>
          <SetupPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(screen.getByText("自托管模式 · Provider 状态登录后可查看")).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("不会调用外部模型");
    expect(await screen.findByRole("button", { name: "创建管理员" })).toBeInTheDocument();
  });
});
