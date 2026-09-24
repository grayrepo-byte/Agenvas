import { useMutation, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router";
import { login } from "../../shared/api/client";
import { AuthLayout, FormError } from "./SetupPage";

/** Administrator login backed by the server-side JDBC session. */
export function LoginPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [loginName, setLoginName] = useState("admin");
  const [password, setPassword] = useState("");
  const loginRequest = useMutation({
    mutationFn: login,
    onSuccess: (user) => {
      queryClient.setQueryData(["auth", "me"], user);
      navigate("/projects", { replace: true });
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    loginRequest.mutate({ loginName, password });
  }

  return (
    <AuthLayout eyebrow="Agent Canvas" title="欢迎回来。">
      <p className="text-sm font-medium text-[var(--muted)]">管理员会话</p>
      <h2 className="mt-2 text-3xl font-semibold tracking-tight">登录 Agenvas</h2>
      <form className="mt-8 space-y-5" onSubmit={submit}>
        <label className="block text-sm font-medium">登录名<input autoComplete="username" required value={loginName} onChange={(event) => setLoginName(event.target.value)} /></label>
        <label className="block text-sm font-medium">密码<input autoComplete="current-password" required type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></label>
        {loginRequest.error ? <FormError error={loginRequest.error} /> : null}
        <button className="primary-button w-full" disabled={loginRequest.isPending} type="submit">{loginRequest.isPending ? "正在登录…" : "登录"}</button>
      </form>
      <p className="mt-6 text-center text-sm text-[var(--muted)]">尚未初始化？ <Link className="underline" to="/setup">返回初始化</Link></p>
    </AuthLayout>
  );
}
