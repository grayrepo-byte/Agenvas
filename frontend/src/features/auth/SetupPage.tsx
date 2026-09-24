import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, type ReactNode, useState } from "react";
import { Link, useNavigate } from "react-router";
import { ApiError, getSetupStatus, setupAdministrator } from "../../shared/api/client";

/** First-run screen that creates exactly one administrator through the secured API. */
export function SetupPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [bootstrapSecret, setBootstrapSecret] = useState("");
  const [loginName, setLoginName] = useState("admin");
  const [password, setPassword] = useState("");
  const setupStatus = useQuery({ queryKey: ["auth", "setup-status"], queryFn: getSetupStatus });
  const setup = useMutation({
    mutationFn: setupAdministrator,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["auth", "setup-status"] });
      navigate("/login", { replace: true });
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setup.mutate({ bootstrapSecret, loginName, password });
  }

  return (
    <AuthLayout title="把创作过程铺在画布上。" eyebrow="Agent Canvas">
      <p className="text-sm font-medium text-[var(--muted)]">系统初始化</p>
      <h2 className="mt-2 text-3xl font-semibold tracking-tight">创建管理员</h2>
      {setupStatus.isPending ? (
        <StatusMessage title="正在连接服务端" detail="读取初始化状态…" tone="neutral" />
      ) : setupStatus.isError ? (
        <StatusMessage title="服务端暂不可用" detail="请确认 PostgreSQL 与 server 已启动后重试。" tone="danger" />
      ) : !setupStatus.data.setupRequired ? (
        <div>
          <StatusMessage title="系统已初始化" detail="管理员已经存在，请直接登录。" tone="success" />
          <Link className="primary-button mt-6 inline-flex" to="/login">前往登录</Link>
        </div>
      ) : (
        <form className="mt-8 space-y-5" onSubmit={submit}>
          <Field label="初始化密钥" hint="来自部署环境的 AGENVAS_BOOTSTRAP_SECRET">
            <input autoComplete="off" minLength={24} required type="password" value={bootstrapSecret} onChange={(event) => setBootstrapSecret(event.target.value)} />
          </Field>
          <Field label="管理员登录名">
            <input autoComplete="username" maxLength={64} minLength={3} pattern="[A-Za-z0-9._-]+" required value={loginName} onChange={(event) => setLoginName(event.target.value)} />
          </Field>
          <Field label="管理员密码" hint="至少 12 个字符，不会写入浏览器持久存储">
            <input autoComplete="new-password" maxLength={128} minLength={12} required type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
          </Field>
          {setup.error ? <FormError error={setup.error} /> : null}
          <button className="primary-button w-full" disabled={setup.isPending} type="submit">{setup.isPending ? "正在创建…" : "创建管理员"}</button>
        </form>
      )}
    </AuthLayout>
  );
}

/** Shared two-column shell for setup and login. */
export function AuthLayout({ title, eyebrow, children }: { title: string; eyebrow: string; children: ReactNode }) {
  return (
    <main className="min-h-screen bg-[var(--canvas)] text-[var(--ink)]">
      <div className="mx-auto grid min-h-screen max-w-6xl items-center gap-12 px-6 py-12 lg:grid-cols-[1.15fr_0.85fr]">
        <section className="max-w-2xl">
          <div className="mb-8 inline-flex items-center gap-2 rounded-full border border-[var(--line)] bg-white/70 px-3 py-1 text-sm shadow-sm">
            <span className="h-2 w-2 rounded-full bg-amber-500" aria-hidden="true" />自托管模式 · Provider 状态登录后可查看
          </div>
          <p className="mb-3 text-sm font-semibold uppercase tracking-[0.24em] text-[var(--muted)]">{eyebrow}</p>
          <h1 className="text-5xl font-semibold leading-[1.05] tracking-[-0.04em] sm:text-7xl">{title}</h1>
          <p className="mt-7 max-w-xl text-lg leading-8 text-[var(--muted)]">Agenvas 将创作指令、产物版本、审批和执行状态放在同一张可恢复的画布上。</p>
        </section>
        <section className="rounded-[2rem] border border-[var(--line)] bg-[var(--panel)] p-8 shadow-[0_24px_80px_rgba(20,28,42,0.12)]">{children}</section>
      </div>
    </main>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return <label className="block text-sm font-medium"><span>{label}</span>{children}{hint ? <span className="mt-1 block text-xs font-normal text-[var(--muted)]">{hint}</span> : null}</label>;
}

export function FormError({ error }: { error: Error }) {
  const detail = error instanceof ApiError ? error.message : "请求未完成，请稍后重试。";
  return <p className="rounded-xl bg-red-50 p-3 text-sm text-red-800" role="alert">{detail}</p>;
}

function StatusMessage({ title, detail, tone }: { title: string; detail: string; tone: "neutral" | "danger" | "success" }) {
  return <div className={`status status-${tone}`} role={tone === "danger" ? "alert" : "status"}><span className="status-dot" aria-hidden="true" /><div><p className="font-semibold">{title}</p><p className="mt-1 text-sm leading-6 opacity-75">{detail}</p></div></div>;
}
