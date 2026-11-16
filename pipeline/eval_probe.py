# -*- coding: utf-8 -*-
"""Dump fact-bearing lines from selected chapters so eval questions can be grounded."""
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
KB = os.path.join(HERE, "..", "kb")
OUT = os.path.join(HERE, "kb_facts.txt")

# chapter index -> regex of interest
TARGETS = {
    3: r"学位|英语|绩点|平均分|条件",
    5: r"取消|入学资格|复查",
    6: r"测评|德育|智育|体育|比例|%|分",
    8: r"重修|不及格|补考",
    12: r"档案|材料|归档",
    13: r"元|补贴|标准",
    14: r"交流|申请|条件",
    21: r"奖学金|元|等级|条件",
    22: r"贷款|元|额度|期限",
    23: r"补偿|代偿|元|标准",
    24: r"资助|元|标准|学费",
    25: r"奖学金|元|等级|比例",
    26: r"奖学金|元|名额|条件",
    27: r"减免|%|比例|条件",
    28: r"补助|元|标准|条件",
    29: r"补助|元|标准|条件",
    31: r"三好|条件|比例|评选",
    33: r"优秀毕业生|条件|比例",
    36: r"申诉|日|期限|受理",
    37: r"学分|毕业|要求|分",
    39: r"竞赛|赛事|项目",
}

lines_out = []
for path in sorted(glob.glob(os.path.join(KB, "*.md"))):
    base = os.path.basename(path)
    idx = int(base.split("_", 1)[0])
    if idx not in TARGETS:
        continue
    pat = re.compile(TARGETS[idx])
    text = open(path, encoding="utf-8").read()
    body = text.split("---", 2)[-1] if text.startswith("---") else text
    hits = [ln.strip() for ln in body.splitlines() if ln.strip() and pat.search(ln)]
    lines_out.append(f"\n=== {base}")
    for ln in hits[:6]:
        lines_out.append("  " + ln[:180])

open(OUT, "w", encoding="utf-8").write("\n".join(lines_out))
print("wrote", OUT, len(lines_out), "lines")