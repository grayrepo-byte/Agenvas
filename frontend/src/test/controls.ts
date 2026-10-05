import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

/** Exercise the visible shadcn control rather than mutating its hidden form bridge. */
export async function selectValue(control: HTMLElement, value: string | number) {
  const user = userEvent.setup();
  await user.click(control);
  const option = within(screen.getByRole("listbox")).getAllByRole("option")
    .find((item) => item.dataset.value === String(value));
  if (!option) throw new Error(`Missing select option: ${value}`);
  await user.click(option);
}

export async function changeControl(control: Element, init: { target: { value?: string | number; files?: File[] } }) {
  if (control instanceof HTMLElement && control.getAttribute("role") === "combobox") {
    await selectValue(control, init.target.value ?? "");
  } else fireEvent.change(control, init);
}

export async function clickControl(control: Element, init?: MouseEventInit) {
  if (init) fireEvent.click(control, init);
  else await userEvent.setup().click(control);
}
