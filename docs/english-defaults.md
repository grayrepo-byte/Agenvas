# Interface language and creative instructions

The interface supports English, Chinese, Japanese and Russian. The selected interface language controls registered interface text; user-authored and provider-generated content stays literal.

The Agent execution trace translates only exact, registered server-generated summaries associated with their tool names. This includes historical summaries such as the text-artifact creation receipt. Unknown summaries and user/model text are retained verbatim. Language switching updates the displayed trace without rewriting its saved ledger.

An empty conversation's known installation title is displayed in the selected language. Once it contains a user turn, the title derived from that instruction is retained. The installed local image processor's original name is localized only for its fixed connection/capability identities; renamed and user-created service names stay literal.

The English interface uses Aspect ratio, Status, Actions and Credentials for the settings labels identified in the external review. ComfyUI address guidance explains that a Docker container's loopback address refers to the container itself. It does not configure or broaden outbound access.

## New director defaults and upgrades

Migration V45 replaces the global built-in Director prompt with English instructions that explicitly follow the user's language. The English body stays within the existing 8,000-character instruction limit. The update matches only the exact SHA-256 fingerprint of the untouched V38 global body. Customized content, including a near match with additional whitespace, is preserved. Original installation name/description are changed only when they still match the known original strings; renamed metadata remains.

Newly initialized project directors copy this updated global default. Existing project directors, frozen Runs, conversation history and media approvals are not rewritten. Changing interface language does not translate a saved creative prompt. Administrators can edit the global default in Prompt Management; each project's Director settings retain its own editable instructions. Both editors explain this distinction.

## Verification scope

The migration is checked against isolated PostgreSQL using synthetic project data for fresh installation, exact-default upgrade, customized and near-matching globals, existing project instruction preservation, and repeated startup. Frontend regression checks exercise the actual trace, locale switching, empty-session title, built-in service labels and preservation of authored text. No real model was called to establish creative quality or language-following reliability.
