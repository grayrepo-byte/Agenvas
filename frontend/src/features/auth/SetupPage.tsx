import { t, useLocale } from "../../shared/i18n";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowClockwise } from "@phosphor-icons/react";
import { type FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router";
import { getSetupStatus, setupAdministrator } from "../../shared/api/client";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice } from "../../shared/ui/PagePrimitives";
import { AuthField, AuthLayout, FormError } from "./AuthLayout";

const BOOTSTRAP_SECRET_MIN_LENGTH = 24;
const LOGIN_NAME_MIN_LENGTH = 3;
const LOGIN_NAME_MAX_LENGTH = 64;
const PASSWORD_MIN_LENGTH = 12;
const PASSWORD_MAX_LENGTH = 128;

/** First-run screen that creates exactly one administrator through the secured API. */
export function SetupPage() {
  useLocale();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [bootstrapSecret, setBootstrapSecret] = useState("");
  const [loginName, setLoginName] = useState("admin");
  const [password, setPassword] = useState("");
  const setupStatus = useQuery({ queryKey: ["auth", "setup-status"], queryFn: getSetupStatus, retry: false });
  const setup = useMutation({
    mutationFn: setupAdministrator,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["auth", "setup-status"] });
      navigate("/login", { replace: true });
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (setup.isPending || !setupStatus.data?.setupRequired) return;
    setup.mutate({ bootstrapSecret, loginName, password });
  }

  return (
    <AuthLayout title={t("创建管理员")} description={t("完成一次初始化，开启你的 AI 创作画布。")}>
      {setupStatus.isPending ? (
        <LoadingState label={t("正在读取初始化状态…")} compact />
      ) : setupStatus.isError ? (
        <div className="ui-stack">
          <Notice title={t("服务端暂不可用")} tone="danger">{t("无法读取初始化状态，请稍后重试。")}</Notice>
          <button className="secondary-button" disabled={setupStatus.isFetching} onClick={() => void setupStatus.refetch()} type="button"><ArrowClockwise size={15} aria-hidden="true" />{setupStatus.isFetching ? t("正在重试…") : t("重新连接")}</button>
        </div>
      ) : !setupStatus.data.setupRequired ? (
        <div className="ui-stack">
          <Notice title={t("系统已初始化")} tone="success">{t("管理员已经存在，请直接登录。")}</Notice>
          <Link className="primary-button" to="/login">{t("前往登录")}</Link>
        </div>
      ) : (
        <form className="ui-form" onSubmit={submit} aria-busy={setup.isPending}>
          <AuthField label={t("初始化密钥")} hint={t("填写部署时配置的初始化密钥。")}>
            <input autoComplete="off" disabled={setup.isPending} minLength={BOOTSTRAP_SECRET_MIN_LENGTH} required type="password" value={bootstrapSecret} onChange={(event) => setBootstrapSecret(event.target.value)} />
          </AuthField>
          <AuthField label={t("管理员登录名")}>
            <input autoComplete="username" disabled={setup.isPending} maxLength={LOGIN_NAME_MAX_LENGTH} minLength={LOGIN_NAME_MIN_LENGTH} pattern="[A-Za-z0-9._-]+" required value={loginName} onChange={(event) => setLoginName(event.target.value)} />
          </AuthField>
          <AuthField label={t("管理员密码")} hint={t("至少 12 个字符。")}>
            <input autoComplete="new-password" disabled={setup.isPending} maxLength={PASSWORD_MAX_LENGTH} minLength={PASSWORD_MIN_LENGTH} required type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
          </AuthField>
          {setup.error ? <FormError error={setup.error} /> : null}
          <button className="primary-button" disabled={setup.isPending} type="submit">{setup.isPending ? t("正在创建…") : t("创建管理员")}</button>
        </form>
      )}
      {setupStatus.data?.setupRequired ? <p className="auth-footer">{t("已完成初始化？ ")}<Link to="/login">{t("前往登录")}</Link></p> : null}
    </AuthLayout>
  );
}
