#!/usr/bin/env python3
"""从 hmall 商品导出「静态参数文档」，供 RAG 入库。

设计约束（重要）：
  只导出静态属性 —— 名称、品牌、类目、规格。
  **不导出价格和库存**，因为两者会变：知识库是快照，快照过期就会答错。
  价格库存必须由工具实时查 hmall，这是项目第 5 条硬约束。

用法：
    python export_catalog.py --tsv catalog.tsv --out ../data/catalog

输入 TSV 由 mysql 客户端产生（-N 模式默认制表符分隔）：
    id \t name \t brand \t category \t spec
"""

import argparse
import json
import pathlib
import sys
from collections import OrderedDict

HEADER = """# {category} · 商品静态参数目录

> 本目录导出 {count} 款{category}，只包含**静态参数**（名称 / 品牌 / 类目 / 规格）。
> **价格与库存故意不在此处**：它们是实时数据，必须由工具查询 hmall 的 `/items/{{id}}`，
> 否则知识库里的数字会过期，导致回答报错价。
> 「商品 ID」用于工具联动与引用标注。

"""


def parse_spec(raw: str) -> str:
    """spec 是 JSON 字符串，如 {"颜色": "黑色", "尺码": "41"}；解析失败就原样返回。"""
    raw = (raw or "").strip()
    if not raw or raw == "{}":
        return "无"
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return raw
    if not isinstance(data, dict) or not data:
        return "无"
    return "；".join(f"{k} {v}" for k, v in data.items())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tsv", required=True, help="mysql 导出的 TSV 文件")
    ap.add_argument("--out", required=True, help="输出目录")
    args = ap.parse_args()

    src = pathlib.Path(args.tsv)
    if not src.exists():
        print(f"找不到 {src}", file=sys.stderr)
        return 1

    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    groups: "OrderedDict[str, list]" = OrderedDict()
    # utf-8-sig：Windows PowerShell 的 Out-File -Encoding utf8 会写 BOM，
    # 用 utf-8 读会让第一列 id 带上 \ufeff
    for line in src.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 5:
            continue
        pid, name, brand, category, spec = parts[0], parts[1], parts[2], parts[3], parts[4]
        groups.setdefault(category, []).append((pid, name, brand, spec))

    total = 0
    for category, rows in groups.items():
        body = [HEADER.format(category=category, count=len(rows))]
        for idx, (pid, name, brand, spec) in enumerate(rows, 1):
            # 商品 ID 作为二级标题：让切分器有机会按商品边界切，不会把两款商品切成半句
            body.append(f"## 商品 ID {pid}\n\n")
            body.append(f"- 序号：{idx}\n")
            body.append(f"- 名称：{name.strip()}\n")
            body.append(f"- 品牌：{brand.strip()}\n")
            body.append(f"- 类目：{category}\n")
            body.append(f"- 规格：{parse_spec(spec)}\n\n")

        target = out_dir / f"{category}.md"
        target.write_text("".join(body), encoding="utf-8")
        total += len(rows)
        print(f"{target}  <- {len(rows)} 款, {target.stat().st_size / 1024:.0f} KB")

    print(f"合计 {total} 款商品，{len(groups)} 个文档")
    return 0


if __name__ == "__main__":
    sys.exit(main())
