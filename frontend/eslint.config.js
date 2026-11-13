import js from "@eslint/js";
import prettier from "eslint-config-prettier/flat";
import globals from "globals";
import tseslint from "typescript-eslint";

const sharedControlSyntax = [
  {
    selector: "JSXOpeningElement[name.name='select']",
    message: "Use shared/ui/Select. Dropdown colors, spacing and options must stay in the shared UI layer.",
  },
  {
    selector: "JSXOpeningElement[name.name='div']:has(JSXAttribute[name.name='role'][value.value=/^(menu|listbox)$/])",
    message: "Use shared/ui/primitives/dropdown-menu or command for menu and listbox surfaces.",
  },
  {
    selector: "JSXOpeningElement[name.name=/^(input|Input)$/]:has(JSXAttribute[name.name='type'][value.value='range'])",
    message: "Use shared/ui/Range. Slider tracks, thumbs, focus and disabled states belong to the shared UI layer.",
  },
];

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
    rules: {
      "no-restricted-imports": [
        "error",
        {
          paths: [
            {
              name: "@phosphor-icons/react",
              message:
                "Import from @/shared/ui/icons; add icons there with individual CSR paths to avoid loading the full catalog.",
            },
          ],
        },
      ],
    },
  },
  {
    files: ["src/**/*.tsx"],
    ignores: ["src/shared/ui/Select.tsx", "src/shared/ui/Range.tsx"],
    rules: {
      "no-restricted-syntax": ["error", ...sharedControlSyntax],
    },
  },
  {
    files: ["src/features/**/*.tsx"],
    ignores: ["src/**/*.test.tsx"],
    rules: {
      "no-restricted-syntax": [
        "error",
        ...sharedControlSyntax,
        {
          selector:
            "JSXOpeningElement[name.name='input']:not(:has(JSXAttribute[name.name='type'][value.value=/^(hidden|file)$/]))",
          message: "Use shared shadcn Input, Checkbox or Range; field appearance belongs to the shared control system.",
        },
        {
          selector: "JSXOpeningElement[name.name='textarea']",
          message:
            "Use shared shadcn Textarea; editor typography and field states belong to the shared control system.",
        },
        {
          selector:
            "JSXOpeningElement[name.name='Input']:has(JSXAttribute[name.name='type'][value.value=/^(checkbox|radio)$/])",
          message: "Use the matching shadcn selection primitive instead of restyling a text Input.",
        },
      ],
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
  // Formatting belongs to Prettier; retain all project and correctness rules above.
  prettier,
);
