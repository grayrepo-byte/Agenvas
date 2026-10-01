import { useMutation,useQueryClient } from "@tanstack/react-query";
import { type FormEvent,useState } from "react";
import { Link,useNavigate } from "react-router";
import { login } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { AuthField,AuthLayout,FormError } from "./AuthLayout";

/** Administrator login backed by the server-side JDBC session. */
export function LoginPage() {
  useLocale();
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
    <AuthLayout title={t("登录 Agenvas")} description={t("回到你的创作空间，继续未完成的灵感。")}>
      <form className="ui-form" onSubmit={submit} aria-busy={loginRequest.isPending}>
        <AuthField label={t("登录名")}><Input autoComplete="username" disabled={loginRequest.isPending} required value={loginName} onChange={(event) => setLoginName(event.target.value)} /></AuthField>
        <AuthField label={t("密码")}><Input autoComplete="current-password" disabled={loginRequest.isPending} required type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></AuthField>
        {loginRequest.error ? <FormError error={loginRequest.error} /> : null}
        <Button variant="default"  disabled={loginRequest.isPending} type="submit">{loginRequest.isPending ? t("正在登录…") : t("登录")}</Button>
      </form>
      <p className="auth-footer">{t("尚未初始化？ ")}<Link to="/setup">{t("返回初始化")}</Link></p>
    </AuthLayout>
  );
}
