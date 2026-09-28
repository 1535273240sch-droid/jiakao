#!/usr/bin/env bash
# 发布到 GitHub Pages（把 dist/ 推到 gh-pages 分支）。
#
# 用法：
#   bash publish_ghpages.sh [dist目录] [分支] [远端]
#   bash publish_ghpages.sh ./dist gh-pages origin
#
# ⚠ 仓库体积提示（重要）
#   - media/ 是内容寻址的二进制文件，**会永久留在 git 历史里**。
#     题库一多，仓库容易涨到几百 MB，clone 会变得很慢。
#   - GitHub 硬限制：单文件 > 100 MB 会被拒；仓库建议 < 1 GB；
#     Pages 站点建议 < 1 GB；软性流量限制 100 GB/月、10 万次请求/小时。
#   - 因此：**只有在题库很小（几百题、媒体总量几十 MB）时才建议用 Pages**。
#     题库大请改用 R2 / 自有 Nginx（见同目录另外两个脚本）。
#   - 想控制体积可以在发布前跑：
#       du -sh dist dist/media dist/bundle
#     并考虑 git gc / 用 BFG 清理历史。
#
# 其它现实问题：
#   - Pages 的 Cache-Control 由 GitHub 控制（约 10 分钟），**无法**让 manifest.json 立刻失效；
#     客户端要么带 cache-buster 查询串，要么接受最长 10 分钟的延迟。生产环境建议用自有 CDN。
set -euo pipefail

DIST="${1:-./dist}"
BRANCH="${2:-gh-pages}"
REMOTE="${3:-origin}"

if ! command -v git >/dev/null 2>&1; then
  echo "未找到 git。" >&2
  exit 1
fi
if [[ ! -f "${DIST}/manifest.json" ]]; then
  echo "${DIST}/manifest.json 不存在，先执行 make build" >&2
  exit 1
fi

BANK_VERSION="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1],encoding="utf-8"))["bank_version"])' "${DIST}/manifest.json" 2>/dev/null || echo '?')"

echo "体积检查（超过 ~500MB 建议改用 R2/Nginx）："
du -sh "${DIST}" 2>/dev/null || true
du -sh "${DIST}/media" 2>/dev/null || true
du -sh "${DIST}/bundle" 2>/dev/null || true

# 组装一个干净的发布目录（只含要发布的内容，避免把源码/work/state 推到 Pages）
STAGE="$(mktemp -d)"
trap 'rm -rf "${STAGE}"' EXIT
cp -a "${DIST}/." "${STAGE}/"
# report.md 是给人看的，不发布（也顺手去掉，避免暴露内部统计）
rm -f "${STAGE}/report.md"
printf 'User-agent: *\nDisallow:\n' > "${STAGE}/robots.txt"

cd "${STAGE}"
git init -q
git checkout -q -b "${BRANCH}"
git add -A
git -c user.name="jiakao-pipeline" -c user.email="noreply@example.com" \
    commit -q -m "chore(bank): publish bank_version=${BANK_VERSION}"

echo "推送到 ${REMOTE}/${BRANCH} ..."
if git remote get-url origin >/dev/null 2>&1; then
  git push -f origin "${BRANCH}"
else
  # 本目录是新建仓库，需要显式指定远端
  REPO_ROOT="$(git -C "${OLDPWD}" rev-parse --show-toplevel 2>/dev/null || true)"
  if [[ -n "${REPO_ROOT}" ]]; then
    git remote add origin "$(git -C "${REPO_ROOT}" remote get-url "${REMOTE}")"
    git push -f origin "${BRANCH}"
  else
    echo "没有可用的 git 远端，请手动执行：" >&2
    echo "  cd ${STAGE} && git remote add origin <你的仓库地址> && git push -f origin ${BRANCH}" >&2
    echo "（该临时目录已被保留说明如下：重新运行本脚本并手动推）" >&2
    exit 1
  fi
fi

echo
echo "完成：bank_version=${BANK_VERSION} → ${REMOTE}/${BRANCH}"
echo "Pages 地址形如 https://<user>.github.io/<repo>/ —— 填进 App 的题库源地址（必须以 / 结尾）。"
echo "提醒：GitHub Pages 的响应头无法自定义，manifest.json 最长可能被缓存约 10 分钟。"
