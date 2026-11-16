# 建库流水线：PDF → 结构化 Markdown → 向量库

仓库里已经带了产物（`kb/` 39 篇、`db/ragdemo.dump`），**正常情况下不用跑这里**。只有换手册、改切分策略或重建知识库时才需要。

## 为什么不能直接切

最初的版本用固定字数切分，结果：

- 一条制度被劈成两半，答案永远凑不齐；
- 引用只能给「第 7 段」，没法给「第 5 章第 22 条」；
- 相邻章节的相似条文被混在一起（手册里有大量「……办法」互相重叠）。

所以先按 PDF 自带的目录把 232 页切成 **39 篇制度**，再在每篇内部按「章 / 条」切分，元数据（`chapter`、`article`）随分块入库，回答时可以直接引用。

## 三步

```bash
# 1. 抽文本：PDF -> 每页纯文本 JSON
python pipeline/pdf_extract.py 手册.pdf pipeline/pages.json pipeline/_starts.txt

# 2. 切章节：靠页脚页码 + 目录页定位边界 -> 39 篇 md
python pipeline/build_kb.py pipeline/pages.json kb/

# 3. 入库：服务必须先启动，且知识库为空
RAG_PASS=你的密码 python pipeline/upload_kb.py kb/
```

`upload_kb.py` 打的是 `POST /api/v2/documents`（multipart），每篇一个文档，服务端负责结构化分块 + 向量化。当前参数下 39 篇 → **208 个分块**。

想要干净重来：

```bash
docker compose down -v      # 删掉数据卷（会丢知识库和聊天记录）
docker compose up -d        # 自动重新导入 db/ragdemo.dump
```

## 依赖

```bash
pip install pypdf
```

## 关键前提（换手册时要先确认）

`build_kb.py` 假定 PDF 满足两个条件：

1. **每页有印刷页码**（形如 `-123-`），用来把「印张页码」映射到「PDF 页序」；
2. **前面有目录页**，目录里列了每章起始的印刷页码。

不满足这两条的 PDF，这个脚本切不准。那种情况下建议：要么手工整理章节，要么换成语义切分（`StructuralTextSplitter` 里已有按章/条的正则，可复用）。

## 文件

| 文件 | 说明 |
|---|---|
| `pdf_extract.py` | PDF 逐页抽文本，输出 `pages.json` 和页码映射 |
| `build_kb.py` | 按目录 + 页码切章，输出 `kb/NN_标题.md` |
| `upload_kb.py` | 上传 `kb/*.md` 到服务，写 `_upload.json` 回执 |
| `ask.py` | 命令行问几个问题并落盘，方便人工抽查 |
| `pages.json` | 232 页原始文本（349 KB），保留是为了不用重新抽 |
| `kb_facts.txt` | 建库过程中人工核对过的事实清单（奖金标准、条数等） |