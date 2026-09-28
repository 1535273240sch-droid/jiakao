#!/usr/bin/env bash
# 局域网/本机联调：把 dist/ 用 HTTP 托管起来，行为尽量贴近生产（Nginx）。
#
# 用法：
#   bash serve.sh [dist目录] [端口] [绑定地址]
#   bash serve.sh                 # 默认 ./dist 8000 0.0.0.0
#
# 手机端"题库源地址"：
#   Android 模拟器 → http://10.0.2.2:8000/
#   真机（同一局域网）→ http://<本机IP>:8000/
#   注意：地址必须以 "/" 结尾（合同 §3）。
#
# 本脚本优先调用流水线自带的联调服务器（jiakao_pipeline serve），它额外提供了：
#   - CORS（Access-Control-Allow-Origin: *，浏览器/WebView 联调必需）
#   - Range 断点续传（206，合同 §3.5 要求 App 支持续传，服务端也必须支持）
#   - ETag / If-None-Match → 304（合同 §3.5 要求 manifest 用 ETag 校验）
#   - 缓存头：media/ 永久缓存（immutable）、manifest.json 不缓存、full/delta/ 短缓存
# python -m http.server 默认**没有** CORS、**不支持** Range、也没有缓存头 ——
# 只在纯粹"能下载就行"的场合当兜底。
set -euo pipefail

DIST="${1:-./dist}"
PORT="${2:-8000}"
HOST="${3:-0.0.0.0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PIPELINE_DIR="${SCRIPT_DIR}/../tools/pipeline"

if [[ ! -d "${DIST}" ]]; then
  echo "发布目录不存在: ${DIST}" >&2
  echo "先构建：cd ${PIPELINE_DIR} && make sample build   （或 make build）" >&2
  exit 1
fi

if [[ ! -f "${DIST}/manifest.json" ]]; then
  echo "警告：${DIST}/manifest.json 不存在，App 将无法识别题库源。" >&2
fi

# 选一个能 import jiakao_pipeline 的 python
PY=""
for candidate in \
  "${PIPELINE_DIR}/.venv/bin/python" \
  "${PIPELINE_DIR}/.venv/Scripts/python.exe" \
  "$(command -v python3 || true)" \
  "$(command -v python || true)"
do
  if [[ -n "${candidate}" && -x "${candidate}" ]]; then
    if (cd "${PIPELINE_DIR}" && "${candidate}" -c "import jiakao_pipeline" >/dev/null 2>&1); then
      PY="${candidate}"
      break
    fi
  fi
done

echo "发布目录: $(cd "${DIST}" && pwd)"
echo "题库源地址（必须以 / 结尾）: http://<本机IP>:${PORT}/"

if [[ -n "${PY}" ]]; then
  echo "服务器: jiakao_pipeline serve（CORS + Range + ETag + 缓存头）"
  cd "${PIPELINE_DIR}"
  exec "${PY}" -m jiakao_pipeline serve --dist "${DIST}" --port "${PORT}" --host "${HOST}"
else
  echo "未找到装有依赖的 Python（jiakao_pipeline 不可导入），回退到 python -m http.server。" >&2
  echo "回退方案的限制：无 CORS 头、不支持 Range 断点续传、无 ETag/缓存头 —— 仅供临时下载验证。" >&2
  echo "正确做法：cd ${PIPELINE_DIR} && python -m venv .venv && make setup" >&2
  exec python3 -m http.server "${PORT}" --bind "${HOST}" --directory "${DIST}"
fi
