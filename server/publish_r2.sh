#!/usr/bin/env bash
# 发布到 Cloudflare R2（对象存储 + 可选自定义域名/CDN）。
#
# 用法：
#   bash publish_r2.sh r2://my-bucket [dist目录] [--public]
#   bash publish_r2.sh my-bucket     ./dist --public      # 自动补 r2:// 前缀
#
# 二选一工具（脚本自动探测，也可用 R2_TOOL 环境变量强制指定）：
#   1) rclone（推荐，整目录同步、支持并发与断点）
#        rclone config 里配好一个 r2 类型 remote，或用环境变量：
#        RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare \
#        RCLONE_CONFIG_R2_ACCESS_KEY_ID=... RCLONE_CONFIG_R2_SECRET_ACCESS_KEY=... \
#        RCLONE_CONFIG_R2_ENDPOINT=https://<accountid>.r2.cloudflarestorage.com
#        RCLONE_CONFIG_R2_ACL=private
#   2) wrangler（Cloudflare 官方 CLI，逐文件 put）
#        npx wrangler r2 object put <bucket>/<key> --file <local> --remote
#
# 注意（R2 特有）：
#   - R2 默认按对象返回，**不会**自动给 .gz 加 Content-Encoding；我们也不希望加
#     （full/delta 是给客户端自己解压的产物，应保持 application/gzip）。
#   - 缓存：media/ 用 immutable 长缓存；manifest.json 必须短缓存/no-cache；
#     rclone 可用 --header-upload 无法按目录区分，建议在 R2 控制台或用 wrangler 逐类设置，
#     或直接在自定义域名前挂 Cloudflare Cache Rules（按路径匹配，见脚本末尾提示）。
set -euo pipefail

BUCKET="${1:-}"
DIST="${2:-./dist}"
FLAG="${3:-}"

if [[ -z "${BUCKET}" ]]; then
  echo "用法: bash publish_r2.sh r2://my-bucket [dist目录] [--public]" >&2
  exit 2
fi
[[ "${BUCKET}" == r2://* ]] || BUCKET="r2://${BUCKET}"

if [[ ! -f "${DIST}/manifest.json" ]]; then
  echo "${DIST}/manifest.json 不存在，先执行 make build" >&2
  exit 1
fi

BANK_VERSION="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1],encoding="utf-8"))["bank_version"])' "${DIST}/manifest.json" 2>/dev/null || echo '?')"

pick_tool() {
  if [[ -n "${R2_TOOL:-}" ]]; then echo "${R2_TOOL}"; return; fi
  if command -v rclone >/dev/null 2>&1; then echo rclone; return; fi
  if command -v wrangler >/dev/null 2>&1; then echo wrangler; return; fi
  if command -v npx >/dev/null 2>&1; then echo "npx-wrangler"; return; fi
  echo ""
}

TOOL="$(pick_tool)"

upload_rclone() {
  local extra=()
  if [[ "${FLAG}" == "--public" ]]; then
    extra=(--s3-acl public-read)   # 仅在 bucket 允许 public 时有意义
    echo "注意：--public 需要 R2 bucket 已开启公共访问（或走自定义域名）。"
  fi
  echo "==> rclone：先媒体、再包体、最后 manifest"
  if [[ -d "${DIST}/media" ]]; then
    rclone copy "${DIST}/media" "${BUCKET}/media" --transfers 16 --checkers 32 "${extra[@]:-}" --progress
  fi
  rclone copy "${DIST}/full" "${BUCKET}/full" --transfers 8 --progress
  [[ -d "${DIST}/delta" ]] && rclone copy "${DIST}/delta" "${BUCKET}/delta" --transfers 8 --progress
  [[ -d "${DIST}/bundle" ]] && rclone copy "${DIST}/bundle" "${BUCKET}/bundle" --transfers 4 --progress
  rclone copyto "${DIST}/manifest.json" "${BUCKET}/manifest.json" --progress
}

upload_wrangler() {
  local bin=("$1")
  echo "==> wrangler：逐文件上传（大媒体较多时会慢，建议改用 rclone）"
  local file key
  while IFS= read -r file; do
    key="${file#"${DIST}"/}"
    "${bin[@]}" r2 object put "${BUCKET#r2://}/${key}" --file "${file}" --remote >/dev/null
    echo "  ↑ ${key}"
  done < <(find "${DIST}" -type f ! -name manifest.json | sort)
  "${bin[@]}" r2 object put "${BUCKET#r2://}/manifest.json" --file "${DIST}/manifest.json" --remote >/dev/null
  echo "  ↑ manifest.json"
}

case "${TOOL}" in
  rclone)       upload_rclone ;;
  wrangler)     upload_wrangler "wrangler" ;;
  npx-wrangler) upload_wrangler "npx wrangler" ;;
  "")
    echo "未找到 rclone 或 wrangler/npx。任选其一安装：" >&2
    echo "  rclone:   https://rclone.org/install/" >&2
    echo "  wrangler: npm i -g wrangler 或直接 npx wrangler" >&2
    exit 1
    ;;
  *) echo "R2_TOOL=${TOOL} 不支持（可选 rclone / wrangler）" >&2; exit 2 ;;
esac

echo
echo "完成：bank_version=${BANK_VERSION} → ${BUCKET}"
echo
echo "建议在 Cloudflare 控制台按路径配置缓存（或用 Cache Rules）："
echo "  /media/*        → Cache Everything, Edge TTL 1 年（文件名即内容哈希，可永久缓存）"
echo "  /manifest.json  → Bypass cache（或 TTL 30 秒）"
echo "  /full/* /delta/* → TTL 5 分钟"
echo "  /bundle/*       → TTL 1 天"
echo "别忘了给 .gz/.webp/.mp4 正确的 Content-Type（media .webp=image/webp，.mp4=video/mp4，full/delta .gz=application/gzip）。"
