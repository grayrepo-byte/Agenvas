import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "src/shared/api/schema.ts"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
  },
  {
    files: ["src/**/*.tsx"],
    ignores: ["src/shared/ui/Select.tsx"],
    rules: {
      "no-restricted-syntax": ["error", {
        selector: "JSXOpeningElement[name.name='select']",
        message: "Use shared/ui/Select. Dropdown colors, spacing and options must stay in the shared UI layer.",
      }, {
        selector: "JSXOpeningElement[name.name='div']:has(JSXAttribute[name.name='role'][value.value=/^(menu|listbox)$/])",
        message: "Use shared/ui/DropdownMenu for menu and listbox surfaces.",
      }],
    },
  },
  {
    files: ["e2e/**/*.mjs"],
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
  },
);
