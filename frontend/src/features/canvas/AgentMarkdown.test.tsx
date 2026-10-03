import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AgentMarkdown } from "./AgentMarkdown";

describe("AgentMarkdown", () => {
  it("formats model replies with headings, emphasis, nested lists, quotes and code", () => {
    const { container } = render(<AgentMarkdown text={'## 结果\n\n**已完成**，*可继续编辑*。\n\n- 产物\n  - 图片\n\n1. 查看\n2. 选用\n\n> 请确认效果\n\n版本 `version-id`\n\n```json\n{"status":"SUCCEEDED"}\n```'} />);
    expect(screen.getByRole("heading", { name: "结果", level: 2 })).toBeInTheDocument();
    expect(screen.getByText("已完成").tagName).toBe("STRONG");
    expect(screen.getByText("可继续编辑").tagName).toBe("EM");
    expect(screen.getAllByRole("list")).toHaveLength(3);
    expect(container.querySelector("ul ul li")).toHaveTextContent("图片");
    expect(container.querySelector("blockquote")).toHaveTextContent("请确认效果");
    expect(screen.getByText("version-id").tagName).toBe("CODE");
    expect(container.querySelector("pre code")).toHaveTextContent('{"status":"SUCCEEDED"}');
  });

  it("renders GFM tables, task lists and strikethrough", () => {
    const { container } = render(<AgentMarkdown text={"| 产物 | 状态 |\n| --- | --- |\n| 图片 | 成功 |\n\n- [x] 生成\n- [ ] 确认\n\n~~旧版本~~"} />);
    expect(screen.getByRole("columnheader", { name: "状态" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "成功" })).toBeInTheDocument();
    const checkboxes = screen.getAllByRole("checkbox");
    expect(checkboxes[0]).toBeChecked();
    expect(checkboxes[1]).not.toBeChecked();
    for (const checkbox of checkboxes) expect(checkbox).toBeDisabled();
    expect(container.querySelector("del")).toHaveTextContent("旧版本");
    expect(screen.getByRole("table").parentElement).toHaveClass("nowheel");
  });

  it("keeps plain-text line breaks and handles incomplete streaming Markdown", () => {
    const { container, rerender } = render(<AgentMarkdown text={"第一行\n第二行\n\n**正在"} />);
    expect(container.querySelector("p br")).toBeInTheDocument();
    expect(screen.getByText("**正在")).toBeInTheDocument();
    rerender(<AgentMarkdown text={"第一行\n第二行\n\n**正在输出**\n\n```text\n未结束的代码"} />);
    expect(screen.getByText("正在输出").tagName).toBe("STRONG");
    expect(container.querySelector("pre code")).toHaveTextContent("未结束的代码");
    rerender(<AgentMarkdown text={"**输出完成**\n\n```text\n完整代码\n```"} />);
    expect(screen.getByText("输出完成").tagName).toBe("STRONG");
    expect(container.querySelectorAll("pre")).toHaveLength(1);
    expect(container.querySelector("pre code")).toHaveTextContent("完整代码");
    expect(container).not.toHaveTextContent("未结束的代码");
  });

  it("ignores HTML and unsafe links and presents images without loading remote bytes", () => {
    const { container } = render(<AgentMarkdown text={'<script>alert("synthetic")</script>\n\n<img src="https://example.com/tracking.png" onerror="alert(1)">\n\n[危险](javascript:alert%281%29) [数据](data:text/html,synthetic) [应用操作](/api/v1/action)\n\n[文档](https://example.com/docs)\n\n![参考图片](https://example.com/reference.png)'} />);
    expect(container.querySelector("script, img")).toBeNull();
    for (const label of ["危险", "数据", "应用操作"]) {
      expect(screen.getByText(label).tagName).toBe("SPAN");
    }
    expect(screen.getByRole("link", { name: "文档" })).toHaveAttribute("href", "https://example.com/docs");
    expect(screen.getByRole("link", { name: "文档" })).toHaveAttribute("rel", "noopener noreferrer");
    expect(screen.getByRole("link", { name: "参考图片" })).toHaveAttribute("href", "https://example.com/reference.png");
  });
});
