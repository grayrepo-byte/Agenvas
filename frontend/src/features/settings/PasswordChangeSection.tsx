import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Key } from "@phosphor-icons/react";
import { type FormEvent,useState } from "react";
import { ApiError,changePassword } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice,Panel } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";

const PASSWORD_MIN_LENGTH = 12;
const PASSWORD_MAX_LENGTH = 128;

/** Credentials stay in form memory and are cleared after every server response. */
export function PasswordChangeSection() {
  useLocale();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving) return;
    setError("");
    setSaved(false);
    if (newPassword !== confirmation) {
      setError(t("两次输入的新密码不一致。"));
      return;
    }
    setSaving(true);
    try {
      await changePassword({ currentPassword, newPassword });
      setSaved(true);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : t("修改密码失败，请重试。"));
    } finally {
      setCurrentPassword("");
      setNewPassword("");
      setConfirmation("");
      setSaving(false);
    }
  }

  return <Panel title={t("修改管理员密码")} description={t("修改成功后，其他已登录会话会失效；当前会话继续有效。")}>
    <form className="ui-form" onSubmit={(event) => { void submit(event); }} aria-busy={saving}>
      <div className="ui-stack password-fields">
        <Field><FieldLabel className="ui-field block">{t("当前密码")}<Input autoComplete="current-password" type="password" required maxLength={PASSWORD_MAX_LENGTH} disabled={saving} value={currentPassword} onChange={(event) => { setCurrentPassword(event.target.value); setSaved(false); }} />
        </FieldLabel></Field>
        <div className="ui-form-grid">
          <Field><FieldLabel className="ui-field block">{t("新密码")}<Input autoComplete="new-password" type="password" required minLength={PASSWORD_MIN_LENGTH} maxLength={PASSWORD_MAX_LENGTH} disabled={saving} value={newPassword} onChange={(event) => { setNewPassword(event.target.value); setSaved(false); }} aria-describedby="password-length-hint" />
          </FieldLabel></Field>
          <Field><FieldLabel className="ui-field block">{t("确认新密码")}<Input autoComplete="new-password" type="password" required minLength={PASSWORD_MIN_LENGTH} maxLength={PASSWORD_MAX_LENGTH} disabled={saving} value={confirmation} onChange={(event) => { setConfirmation(event.target.value); setSaved(false); }} />
          </FieldLabel></Field>
        </div>
        <p className="ui-muted" id="password-length-hint">{t("密码长度为 {0}–{1} 个字符。", { "0": PASSWORD_MIN_LENGTH, "1": PASSWORD_MAX_LENGTH })}</p>
      </div>
      {error ? <Notice tone="danger">{error}</Notice> : null}
      {saved ? <Notice tone="success">{t("密码已修改，其他会话已失效。")}</Notice> : null}
      <div className="ui-form-actions">
        {saving ? <LoadingState compact label={t("正在修改密码…")} /> : <span className="ui-muted">{t("提交完成后，密码输入会自动清空。")}</span>}
        <Button variant="default"  type="submit" disabled={saving}><Key size={16} aria-hidden />{saving ? t("正在修改…") : t("修改密码")}</Button>
      </div>
    </form>
  </Panel>;
}
