# 评估集

两套题，用途不同：

| 文件 | 题量 | 用途 |
|---|---|---|
| `handbook_eval_questions.jsonl` | 20 | **v1 基础集**：每题对应一个章节，用来做「不退化」检查 |
| `handbook_eval_v2.jsonl` | 22 | **v2 难例集**：易混章节、精确数字、跨章、负例，用来区分改进 |

v1 的 hit@3 早就到 1.00，已经饱和；v2 是补上来真正能拉动优化的那套。

## 字段

v1 沿用 `manufacturing-rag-starter` 的 schema；v2 在其上加了四个字段：

| 字段 | 说明 |
|---|---|
| `id` | 题号 |
| `category` | 制度类别 |
| `style` | `verbatim` 原文问法 / `paraphrase` 换说法 |
| `question` | 问题 |
| `expected_source_ids` | 正确章节号（`kb/NN_*.md` 的前缀），可多个 |
| `expected_answer_points` | 答案要点，留给后续生成质量打分 |
| `risk_if_wrong` | 答错的业务后果 |
| `hard_type` | **v2**：`confusable` 易混 / `precise` 精确 / `multi_hop` 跨章 / `negative` 负例 / `regression` 回归 |
| `expect` | **v2**：`answer` 或 `no_answer`（手册根本没写，必须拒答） |
| `must_not_be_top1` | **v2**：这些章节若排第一就算错（易混对的错误答案） |

## 怎么跑

接口现在有登录，脚本用 HTTP Basic：

```powershell
$env:RAG_USER = "admin"
$env:RAG_PASS = "你的密码"

# v2 难例集（推荐日常用这个）
python eval\eval_runner_v2.py eval\handbook_eval_v2.jsonl eval\v2.json 8 hybrid_rerank

# v1 基础集
python eval_runner.py eval\handbook_eval_questions.jsonl eval\v1.json 8 0.3 hybrid_rerank
```

两个脚本都不调对话模型，只调检索/路由接口，所以一轮几秒到十几秒。

## 指标

- **gate_accuracy**（v2）：该答的答、该拒的拒，比例越高越好
- **negative_rejection**（v2）：负例被正确拒答的比例
- **hit@k**：正确章节是否出现在前 k 个不同章节里
- **MRR**：正确章节排名倒数的平均
- **top1_forbidden**（v2）：`must_not_be_top1` 被命中的题目

## 结果（2026-11-14，topK=8，threshold=0.3）

### v1 基础集：20 题

| 指标 | ① 向量(token) | ② 向量(结构) | ③ 混合 | ④ 混合+rerank |
|---|---|---|---|---|
| hit@1 | 0.95 | 0.95 | 0.95 | **1.00** |
| hit@3 | 1.00 | 1.00 | 1.00 | 1.00 |
| MRR | 0.975 | 0.975 | 0.975 | **1.000** |
| 单题耗时 | 0.32s | 0.22s | 0.36s | 0.46s |

### v2 难例集：22 题（17 可答 + 5 负例）

| 指标 | ③ 混合 | ④ 混合+rerank |
|---|---|---|
| gate_accuracy | 1.00 | 1.00 |
| negative_rejection | 1.00 | 1.00 |
| hit@1 | 0.8824 | **1.000** |
| hit@3 | 1.00 | 1.00 |
| MRR | 0.9412 | **1.000** |
| 单题耗时 | 0.26s | 0.43s |

分类型（④ 混合+rerank）：confusable 6/6、precise 6/6、multi_hop 3/3、regression 2/2、negative 5/5。

## 结论

1. **rerank 是有用的，之前的判断错了。** 在 v2 上它把 hit@1 从 0.88 拉到 1.00，
   修好了「十佳三好 vs 三好」「第二课堂办法 vs 认定标准」两组易混题。
   现在 `rag.retrieval.mode` 默认就是 `hybrid_rerank`，失败时自动退回融合顺序。
2. **之前的"rerank 回退"是标准答案写错了。** `EVAL-HB-011`（新生入学资格审查）
   我原本只标了第 05 章，但第 02 章学籍管理办法第 18 行有**一模一样**的规定：
   「审查发现新生的录取通知、考生信息等证明材料，与本人实际情况不符……取消入学资格」。
   rerank 排到 02 是对的，是我的标注错。同类错误还有 v2 的 `HB2-M02`
   （「受处分还能评奖学金吗」）：第 35 章第七条和第 25 章参评条件都回答了，
   原本只标 35 章，导致把正确答案判成失败。修正标注后 rerank 两套题都是 1.00。
   **教训：评估集的标准答案必须回原文核对，不能凭"这题应该属于哪章"想当然。**
3. **结构切分**（301 → 208 块，按「第X条」对齐，块首加章节标题）不改排名，但修好了
   `EVAL-HB-010`——原先 500 token 切分把奖励标准那句话拦腰截断。
4. **负例靠余弦阈值挡不住。** 「宿舍报修电话是多少」的余弦是 0.55，因为"宿舍"这个主题很近。
   真正挡住它的是**请求属性检查**：问题在问联系方式（电话/号码/邮箱/网址），
   而召回条文里一个联系方式都没有 → 直接拒答。阈值仍保持 0.45。

## ⚠️ 局限

1. **v2 在 rerank 下也饱和了**（17 题全中）。要再往上需要更多题和更刁的题，
   或者转向**答案质量**评估（是否忠实原文、有没有编造），这需要 LLM-as-judge。
2. **只测检索与路由，不测生成**。`expected_answer_points` 已经写好，但还没有打分器。
3. **负例只有 5 条**，且都是"手册完全没有的主题"。更难的负例是"手册里有相关条文但不回答该问题"，
   例如「奖学金什么时候发到卡上」。
4. **单轮评估，无多轮**。追问、指代（"那硕士呢"）没有覆盖。