import { useMutation, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router";
import { login } from "../../shared/api/client";
import { AuthField, AuthLayout, FormError } from "./AuthLayout";

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
    if (loginRequest.isPending) return;
    loginRequest.mutate({ loginName, password });
  }

  return (
    <AuthLayout title="登录 Agenvas" description="回到你的创作空间，继续未完成的灵感。">
      <form className="ui-form" onSubmit={submit} aria-busy={loginRequest.isPending}>
        <AuthField label="登录名"><input autoComplete="username" disabled={loginRequest.isPending} required value={loginName} onChange={(event) => setLoginName(event.target.value)} /></AuthField>
        <AuthField label="密码"><input autoComplete="current-password" disabled={loginRequest.isPending} required type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></AuthField>
        {loginRequest.error ? <FormError error={loginRequest.error} /> : null}
        <button className="primary-button" disabled={loginRequest.isPending} type="submit">{loginRequest.isPending ? "正在登录…" : "登录"}</button>
      </form>
      <p className="auth-footer">尚未初始化？ <Link to="/setup">返回初始化</Link></p>
    </AuthLayout>
  );
}
