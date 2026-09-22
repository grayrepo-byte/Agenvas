import { useQuery } from "@tanstack/react-query";
import { getSetupStatus } from "../../shared/api/client";

export function SetupPage() {
  const setupStatus = useQuery({
    queryKey: ["auth", "setup-status"],
    queryFn: getSetupStatus,
  });

  return (
    <main className="min-h-screen bg-[var(--canvas)] text-[var(--ink)]">
      <div className="mx-auto grid min-h-screen max-w-6xl items-center gap-12 px-6 py-12 lg:grid-cols-[1.15fr_0.85fr]">
        <section aria-labelledby="product-title" className="max-w-2xl">
          <div className="mb-8 inline-flex items-center gap-2 rounded-full border border-[var(--line)] bg-white/70 px-3 py-1 text-sm shadow-sm">
            <span className="h-2 w-2 rounded-full bg-amber-500" aria-hidden="true" />
            Mock 媒体模式 · 不会调用外部模型
          </div>
          <p className="mb-3 text-sm font-semibold uppercase tracking-[0.24em] text-[var(--muted)]">
            Agent Canvas
          </p>
          <h1 id="product-title" className="text-5xl font-semibold leading-[1.05] tracking-[-0.04em] sm:text-7xl">
            把创作过程
            <span className="block text-[var(--accent)]">铺在画布上。</span>
          </h1>
          <p className="mt-7 max-w-xl text-lg leading-8 text-[var(--muted)]">
            Agenvas 是可自托管的 AI 创作画布。首个纵向切片已经连通浏览器、权威 API 合约、Spring Boot 与 PostgreSQL。
          </p>
        </section>

        <section className="rounded-[2rem] border border-[var(--line)] bg-[var(--panel)] p-8 shadow-[0_24px_80px_rgba(20,28,42,0.12)]" aria-label="系统状态">
          <p className="text-sm font-medium text-[var(--muted)]">系统基线</p>
          <h2 className="mt-2 text-3xl font-semibold tracking-tight">初始化检查</h2>

          {setupStatus.isPending ? (
            <StatusMessage title="正在连接服务端" detail="读取初始化状态…" tone="neutral" />
          ) : setupStatus.isError ? (
            <StatusMessage
              title="服务端暂不可用"
              detail="本地草稿不会丢失。请确认 PostgreSQL 与 server 已启动后重试。"
              tone="danger"
            />
          ) : setupStatus.data.setupRequired ? (
            <StatusMessage
              title="需要创建管理员"
              detail="初始化写入能力将在身份纵向切片中开放；当前骨架只提供安全的只读状态检查。"
              tone="warning"
            />
          ) : (
            <StatusMessage
              title="系统已初始化"
              detail="请前往登录页继续。登录行为将在后续身份切片中实现。"
              tone="success"
            />
          )}

          <dl className="mt-8 grid grid-cols-2 gap-3 text-sm">
            <Baseline label="前端" value="React + Vite" />
            <Baseline label="后端" value="Spring Boot 4" />
            <Baseline label="状态源" value="PostgreSQL" />
            <Baseline label="媒体" value="Mock" />
          </dl>
        </section>
      </div>
    </main>
  );
}

function StatusMessage({
  title,
  detail,
  tone,
}: {
  title: string;
  detail: string;
  tone: "neutral" | "warning" | "danger" | "success";
}) {
  return (
    <div className={`status status-${tone}`} role={tone === "danger" ? "alert" : "status"}>
      <span className="status-dot" aria-hidden="true" />
      <div>
        <p className="font-semibold">{title}</p>
        <p className="mt-1 text-sm leading-6 opacity-75">{detail}</p>
      </div>
    </div>
  );
}

function Baseline({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-2xl border border-[var(--line)] bg-white/60 p-4">
      <dt className="text-[var(--muted)]">{label}</dt>
      <dd className="mt-1 font-semibold">{value}</dd>
    </div>
  );
}
