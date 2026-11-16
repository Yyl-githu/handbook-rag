#!/bin/sh
# 只在数据库数据目录为空（容器第一次启动）时执行一次。
# 把知识库（208 个分块 + 闲聊记忆表）恢复进刚初始化好的 ragdemo 库。
set -eu

echo "[init] restoring knowledge base from ragdemo.dump ..."
pg_restore \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --no-owner \
  --no-privileges \
  --clean \
  --if-exists \
  /docker-entrypoint-initdb.d/ragdemo.dump
echo "[init] knowledge base restored."