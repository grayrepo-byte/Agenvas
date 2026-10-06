# 原著分析阶段契约

本阶段处理本轮绑定的输入卡片，产出或修订对应创作文字卡。读取、保存、版本引用、权限与跨轮续接以 [Agenvas 卡片工作流](agenvas-cards.md) 为准；卡片模板见 [本阶段模板](../assets/card-templates.md)。创作者明确决定优先，模型建议不能写成已接受事实。

结构完整性与内容质量由 Agent 对实际读到的版本逐项核对，报告检查范围和未验证项。规则编号用于解释问题，不能宣称本系统执行了未提供的自动检查。

## 本阶段规则

### `NVA`

| ID | Class | Knowledge |
|---|---|---|
| NVA-01 | structural_invariant | All analysis cites one immutable chapter-index card version, source artifactId/versionId and line range. Check sequence, boundaries and provided source coverage against actually read text; changed source versions require a new index even when line counts are unchanged. |
| NVA-02 | structural_invariant | Every extracted claim carries a source locator and span; a claim with no span cannot be cited downstream. |
| NVA-03 | structural_invariant | Full-book aggregation may be claimed only after the provided whole-book coverage has been verified; partial aggregation declares its actual scope and all gaps; missing, unverified and failed chapters are named in every partial aggregate that inherits the gap. |
| NVA-04 | structural_invariant | Analysis records de-quoted function summaries, never copied source paragraphs. |
| NVA-05 | structural_invariant | A sampled stage states its own coverage and confines every claim to the chapters it read. |
| NVA-06 | reviewed_invariant | A function summary states what a passage does to character choice, information, power or relationship, not what happens in it. |
| NVA-07 | reviewed_invariant | Hard facts — levels, counts, distances, who said what, which chapters a character appears in — trace back to a description line or the source; an unsupported one is written as unstated, never filled in by plausibility. |
| NVA-08 | reviewed_invariant | Character merges preserve dramatic role, knowledge scope, relational position and causal bridge; only proper names and evidenced nicknames may merge, never descriptors or titles. |
| NVA-09 | reviewed_invariant | Adaptation value distinguishes what the screen can show from what only prose can deliver, and names the new carrier for each function it keeps. |
| NVA-10 | reviewed_invariant | Episode candidates are cut on local dramatic result and precise handoff, not on chapter count or word budget. |
| NVA-11 | craft_default | Triage a deterministic spread of chapters across the whole book and stop for the creator before committing to a full pass. |
| NVA-12 | taste_option | Where to open, which line to keep and which ending to promise remain creator choices; analysis may argue but never blocks. |
| NVA-13 | craft_default | Before cutting episode candidates, rank the book's plot points by audience payoff — face-slap, spectacle, reversal, identity reveal, reward delivered, crisis — counting payoffs a bystander or crowd delivers; candidates follow the payoff distribution rather than one chapter per episode and cite the payoffs they carry. The opening replacement point is the strongest payoff that can also establish the protagonist's identity, crisis and goal, and a stronger later peak is reported as a cold-open option. |

规则分级由高到低：`structural_invariant`（结构缺陷，阻断）、
`reviewed_invariant`（需证据判断）、`craft_default`（常用做法，可覆盖）、
`taste_option`（创作者选择，不作缺陷）。创作者已接受的事实优先于本表。
