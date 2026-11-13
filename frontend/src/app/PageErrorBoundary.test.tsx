import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { expect, it } from "vitest";
import { setLocale } from "../shared/i18n";
import { PageErrorBoundary } from "./PageErrorBoundary";

function BrokenPage(): never {
  throw new Error("Synthetic private render details");
}
function Editor() {
  const [value, setValue] = useState("");
  return <input aria-label="Synthetic draft" value={value} onChange={event => setValue(event.target.value)} />;
}

it.each([
  ["zh", "页面无法显示", "重新加载页面"],
  ["en", "This page could not be displayed", "Reload page"],
  ["ru", "Не удалось отобразить страницу", "Перезагрузить страницу"],
  ["ja", "ページを表示できません", "ページを再読み込み"],
] as const)(
  "provides a focused, localized recovery screen in %s without exposing the error",
  (locale, title, reload) => {
    setLocale(locale);
    render(
      <PageErrorBoundary resetKey="/broken">
        <BrokenPage />
      </PageErrorBoundary>,
      { onCaughtError: () => {} },
    );
    expect(screen.getByRole("heading", { name: title })).toHaveFocus();
    expect(screen.getByRole("alert")).toBeVisible();
    expect(screen.getByRole("button", { name: reload })).toBeEnabled();
    expect(document.body).not.toHaveTextContent("Synthetic private render details");
  },
);

it("keeps the failure visible until the route changes, then displays the new page", () => {
  const { rerender } = render(
    <PageErrorBoundary resetKey="/broken">
      <BrokenPage />
    </PageErrorBoundary>,
    { onCaughtError: () => {} },
  );
  rerender(
    <PageErrorBoundary resetKey="/broken">
      <p>New healthy content</p>
    </PageErrorBoundary>,
  );
  expect(screen.getByRole("alert")).toBeInTheDocument();
  expect(screen.queryByText("New healthy content")).not.toBeInTheDocument();
  rerender(
    <PageErrorBoundary resetKey="/healthy">
      <p>New healthy content</p>
    </PageErrorBoundary>,
  );
  expect(screen.getByText("New healthy content")).toBeVisible();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

it("preserves mounted editor state across healthy boundary updates", () => {
  const { rerender } = render(
    <PageErrorBoundary resetKey="/first">
      <Editor />
    </PageErrorBoundary>,
  );
  const input = screen.getByRole("textbox", { name: "Synthetic draft" });
  fireEvent.change(input, { target: { value: "Unsaved synthetic input" } });
  rerender(
    <PageErrorBoundary resetKey="/first">
      <Editor />
    </PageErrorBoundary>,
  );
  expect(screen.getByRole("textbox", { name: "Synthetic draft" })).toBe(input);
  rerender(
    <PageErrorBoundary resetKey="/second">
      <Editor />
    </PageErrorBoundary>,
  );
  expect(screen.getByRole("textbox", { name: "Synthetic draft" })).toBe(input);
  expect(input).toHaveValue("Unsaved synthetic input");
});
