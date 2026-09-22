import type { paths } from "./schema";

type SetupStatus = paths["/api/v1/auth/setup-status"]["get"]["responses"][200]["content"]["application/json"];

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export async function getSetupStatus(): Promise<SetupStatus> {
  const response = await fetch("/api/v1/auth/setup-status", {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    throw new ApiError(response.status, "无法读取系统初始化状态");
  }

  return (await response.json()) as SetupStatus;
}
