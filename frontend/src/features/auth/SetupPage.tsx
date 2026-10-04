import { ArrowClockwise } from "@phosphor-icons/react";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { type FormEvent,useState } from "react";
import { Link,useNavigate } from "react-router";
import { ApiError,getSetupStatus,setupAdministrator } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { AuthField,AuthLayout,FormError } from "./AuthLayout";

const LOGIN_NAME_MIN_LENGTH = 3;
const LOGIN_NAME_MAX_LENGTH = 64;
const PASSWORD_MIN_LENGTH = 12;
const PASSWORD_MAX_LENGTH = 128;

/** First-run screen that creates exactly one administrator through the secured API. */
export function SetupPage() {
  useLocale();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [loginName, setLoginName] = useState("admin");
  const [password, setPassword] = useState("");
  const setupStatus = useQuery({ queryKey: ["auth", "setup-status"], queryFn: getSetupStatus, retry: false });
  const setup = useMutation({
    mutationFn: setupAdministrator,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["auth", "setup-status"] });
      navigate("/login", { replace: true });
    },
    onError: async (error) => {
      // Another browser can win initialization while this form is still open.
      if (error instanceof ApiError && error.code === "SETUP_ALREADY_COMPLETED") {
        await queryClient.invalidateQueries({ queryKey: ["auth", "setup-status"] });
      }
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (setup.isPending || !setupStatus.data?.setupRequired) return;
    setup.mutate({ loginName, password });
  }

  return (
    <AuthLayout title={t("auth.setup.createAdmin")} description={t("auth.setup.description")}>
      {setupStatus.isPending ? (
        <LoadingState label={t("auth.setup.statusLoading")} compact />
      ) : setupStatus.isError ? (
        <div className="ui-stack">
          <Notice title={t("auth.setup.serviceUnavailable")} tone="danger">{t("auth.setup.statusFailed")}</Notice>
          <Button variant="outline"  disabled={setupStatus.isFetching} onClick={() => void setupStatus.refetch()} type="button"><ArrowClockwise size={15} aria-hidden="true" />{setupStatus.isFetching ? t("common.retrying") : t("auth.setup.reconnect")}</Button>
        </div>
      ) : !setupStatus.data.setupRequired ? (
        <div className="ui-stack">
          <Notice title={t("auth.setup.initialized")} tone="success">{t("auth.setup.adminExists")}</Notice>
          <Link className="primary-button" to="/login">{t("auth.setup.loginLink")}</Link>
        </div>
      ) : (
        <form className="ui-form" onSubmit={submit} aria-busy={setup.isPending}>
          <AuthField label={t("auth.setup.username")}>
            <Input autoComplete="username" disabled={setup.isPending} maxLength={LOGIN_NAME_MAX_LENGTH} minLength={LOGIN_NAME_MIN_LENGTH} pattern="[A-Za-z0-9._-]+" required value={loginName} onChange={(event) => setLoginName(event.target.value)} />
          </AuthField>
          <AuthField label={t("auth.setup.password")} hint={t("auth.setup.passwordHint")}>
            <Input autoComplete="new-password" disabled={setup.isPending} maxLength={PASSWORD_MAX_LENGTH} minLength={PASSWORD_MIN_LENGTH} required type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
          </AuthField>
          {setup.error ? <FormError error={setup.error} /> : null}
          <Button variant="default"  disabled={setup.isPending} type="submit">{setup.isPending ? t("common.creating") : t("auth.setup.createAdmin")}</Button>
        </form>
      )}
      {setupStatus.data?.setupRequired ? <p className="auth-footer">{t("auth.setup.loginPrompt")}<Link to="/login">{t("auth.setup.loginLink")}</Link></p> : null}
    </AuthLayout>
  );
}
