import { type FormEvent, useState } from "react";
import { ApiError, changePassword } from "../../shared/api/client";

/** Changes the administrator password without persisting either secret in browser storage. */
export function PasswordChangeSection() {
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
      setError("两次输入的新密码不一致。");
      return;
    }
    setSaving(true);
    try {
      await changePassword({ currentPassword, newPassword });
      setSaved(true);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "修改密码失败，请重试。");
    } finally {
      setCurrentPassword("");
      setNewPassword("");
      setConfirmation("");
      setSaving(false);
    }
  }

  return <section className="mt-6 rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-6">
    <h2 className="text-xl font-semibold">修改管理员密码</h2>
    <p className="mt-2 text-sm text-[var(--muted)]">修改成功后，其他已登录会话会失效；当前会话继续有效。</p>
    <form className="mt-5 space-y-4" onSubmit={(event) => { void submit(event); }}>
      <label className="block text-sm font-medium">当前密码
        <input autoComplete="current-password" type="password" required maxLength={128}
          value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} />
      </label>
      <label className="block text-sm font-medium">新密码
        <input autoComplete="new-password" type="password" required minLength={12} maxLength={128}
          value={newPassword} onChange={(event) => setNewPassword(event.target.value)} />
      </label>
      <label className="block text-sm font-medium">确认新密码
        <input autoComplete="new-password" type="password" required minLength={12} maxLength={128}
          value={confirmation} onChange={(event) => setConfirmation(event.target.value)} />
      </label>
      {error ? <p className="rounded-xl bg-red-50 p-3 text-sm text-red-800" role="alert">{error}</p> : null}
      {saved ? <p className="rounded-xl bg-green-50 p-3 text-sm text-green-800" role="status">密码已修改，其他会话已失效。</p> : null}
      <button className="primary-button" type="submit" disabled={saving}>
        {saving ? "正在修改…" : "修改密码"}
      </button>
    </form>
  </section>;
}
