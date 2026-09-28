#!/usr/bin/env bash
# 发布到自有服务器 / Nginx（rsync over ssh）。
#
# 用法：
#   bash publish_rsync.sh user@host:/var/www/jiakao/ [dist目录]
#   bash publish_rsync.sh user@host:/var/www/jiakao/ ./dist
#
# 说明：
#   - 刻意**不带 --delete**：media/ 是内容寻址（文件名即内容哈希）且永不覆盖，
#     full/ delta/ bundle/ 也都是按版本号命名的新文件。真正需要清理的是过老的
#     版本包，请人工确认后再删（或交给 CDN 生命周期规则）。
#   - 上传顺序：先 media → 再 full/delta/bundle → **最后 manifest.json**。
#     manifest 是"开关"，它最后落地才能保证客户端不会拉到还没传完的包。
#   - 传完建议验证：curl -sI <base>/manifest.json（看 ETag / Cache-Control）。
#
# 典型 Nginx 配置见同目录 nginx.conf.example。
set -euo pipefail

DEST="${1:-}"
DIST="${2:-./dist}"

if [[ -z "${DEST}" ]]; then
  echo "用法: bash publish_rsync.sh user@host:/var/www/jiakao/ [dist目录]" >&2
  exit 2
fi

if ! command -v rsync >/dev/null 2>&1; then
  echo "未找到 rsync。安装：apt install rsync / brew install rsync" >&2
  exit 1
fi

if [[ ! -f "${DIST}/manifest.json" ]]; then
  echo "${DIST}/manifest.json 不存在，先执行 make build" >&2
  exit 1
fi

# 目标必须以 / 结尾，rsync 才会把内容放进目录而不是改名
case "${DEST}" in
  */) ;;
  *) echo "提示：目标建议以 / 结尾（当前 ${DEST}）" >&2 ;;
esac

RSYNC_OPTS=(-az --human-readable --info=progress2 --partial)

echo "==> 1/4 媒体（内容寻址，永不覆盖）"
if [[ -d "${DIST}/media" ]]; then
  rsync "${RSYNC_OPTS[@]}" "${DIST}/media/" "${DEST%/}/media/"
else
  echo "（无 media/，跳过）"
fi

echo "==> 2/4 全量快照 full/"
rsync "${RSYNC_OPTS[@]}" "${DIST}/full/" "${DEST%/}/full/"

echo "==> 3/4 增量 delta/ 与离线包 bundle/"
if [[ -d "${DIST}/delta" ]]; then
  rsync "${RSYNC_OPTS[@]}" "${DIST}/delta/" "${DEST%/}/delta/"
fi
if [[ -d "${DIST}/bundle" ]]; then
  rsync "${RSYNC_OPTS[@]}" "${DIST}/bundle/" "${DEST%/}/bundle/"
fi

echo "==> 4/4 manifest.json（最后上传）"
rsync "${RSYNC_OPTS[@]}" "${DIST}/manifest.json" "${DEST%/}/manifest.json"

BANK_VERSION="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1],encoding="utf-8"))["bank_version"])' "${DIST}/manifest.json" 2>/dev/null || echo '?')"
echo
echo "完成：bank_version=${BANK_VERSION} → ${DEST}"
echo "验证：curl -sI ${DEST%/}/manifest.json     # 应看到 ETag 与 Cache-Control: no-cache"
echo "提示：media/ 可以永久缓存；manifest.json 必须不缓存（见 nginx.conf.example）。"
