import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { Checkbox } from "./primitives/checkbox";
import { Field, FieldLabel } from "./primitives/field";
import { Input } from "./primitives/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "./primitives/tabs";

describe("shared controls", () => {
  it("preserves native field validation and form values with compact fields", async () => {
    const user = userEvent.setup();
    const { container } = render(<form>
      <Field><FieldLabel htmlFor="email">Email</FieldLabel>
        <Input id="email" name="email" type="email" density="compact" required />
      </Field>
    </form>);
    const input = screen.getByLabelText("Email");
    expect(input).toBeInvalid();
    await user.type(input, "creator@example.com");
    expect(input).toBeValid();
    expect(new FormData(container.querySelector("form")!).get("email")).toBe("creator@example.com");
  });

  it("moves a mixed checkbox to checked with Space and submits its value", async () => {
    const user = userEvent.setup();
    function Selection() {
      const [checked, setChecked] = useState<boolean | "indeterminate">("indeterminate");
      return <form><Field orientation="horizontal">
        <Checkbox id="selection" name="selection" checked={checked} onCheckedChange={setChecked} value="selected" />
        <FieldLabel htmlFor="selection">Select items</FieldLabel>
      </Field><Checkbox aria-label="Unavailable" name="disabled" disabled defaultChecked /></form>;
    }
    const { container } = render(<Selection />);
    const checkbox = screen.getByRole("checkbox", { name: "Select items" });
    expect(checkbox).toBePartiallyChecked();
    await user.tab();
    expect(checkbox).toHaveFocus();
    await user.keyboard(" ");
    expect(checkbox).toBeChecked();
    const values = new FormData(container.querySelector("form")!);
    expect(values.get("selection")).toBe("selected");
    expect(values.has("disabled")).toBe(false);
    await user.click(screen.getByText("Select items"));
    expect(checkbox).not.toBeChecked();
  });

  it("keeps draft fields mounted while nested tabs use their own arrow keys", async () => {
    const user = userEvent.setup();
    render(<Tabs defaultValue="settings" orientation="vertical">
      <TabsList variant="navigation" aria-label="Categories">
        <TabsTrigger value="settings">Settings</TabsTrigger><TabsTrigger value="other">Other</TabsTrigger>
      </TabsList>
      <TabsContent value="settings" forceMount>
        <Tabs defaultValue="draft">
          <TabsList size="sm" aria-label="Editor"><TabsTrigger value="draft">Draft</TabsTrigger><TabsTrigger value="preview">Preview</TabsTrigger></TabsList>
          <TabsContent value="draft" forceMount><Input aria-label="Draft name" defaultValue="Initial" /></TabsContent>
          <TabsContent value="preview">Preview content</TabsContent>
        </Tabs>
      </TabsContent>
      <TabsContent value="other">Other content</TabsContent>
    </Tabs>);
    await user.type(screen.getByLabelText("Draft name"), " edited");
    await user.click(screen.getByRole("tab", { name: "Draft" }));
    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "Preview" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Settings" })).toHaveAttribute("aria-selected", "true");
    await user.keyboard("{ArrowLeft}");
    expect(screen.getByLabelText("Draft name")).toHaveValue("Initial edited");
  });
});
