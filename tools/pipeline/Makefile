# 05 · 题库内容流水线（Python）—— 命令清单
#
# Linux/macOS:  make setup && make sample build serve
# Windows:      没有 GNU make 时用等价的 tasks.py：
#               python tasks.py setup && python tasks.py sample build serve
#
# 所有命令都在 out/tools/pipeline/ 目录内执行。

VENV    ?= .venv
PYTHON  ?= python3
ifeq ($(OS),Windows_NT)
  BIN   := $(VENV)/Scripts
else
  BIN   := $(VENV)/bin
endif
PY      := $(BIN)/python
JOBS    ?= 4
DIST    ?= ./dist
WORK    ?= ./work
STATE   ?= ./state
SAMPLE  ?= ./sample
PORT    ?= 8000
CLI     := $(PY) -m jiakao_pipeline

.DEFAULT_GOAL := help
.PHONY: help setup venv deps sample sample-raw import normalize media validate build serve test bench fmt clean distclean publish-rsync publish-r2 publish-gh

help: ## 显示所有目标
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[1m%-16s\033[0m %s\n", $$1, $$2}'

# ───────── 环境 ─────────

setup: venv deps ## 建虚拟环境并装依赖
venv:
	@test -d $(VENV) || $(PYTHON) -m venv $(VENV)
	@echo "venv: $(BIN)/python"

deps: ## 安装/更新依赖
	$(PY) -m pip install --disable-pip-version-check -r requirements.txt

# ───────── 日常流程 ─────────

sample: sample-raw import normalize media ## 生成样例并跑完到媒体（20 题 + 静图/动图）
sample-raw: ## 只生成样例原始数据（sample/questions.csv|json|sqlite3 + images/）
	$(CLI) sample --out $(SAMPLE)

import: ## 原始题库 → work/（--adapter csv|json|sqlite）
	$(CLI) import --adapter csv --input $(SAMPLE)/questions.csv --images $(SAMPLE)/images --out $(WORK)

normalize: ## 清洗/规范化/分配稳定 id
	$(CLI) normalize --in $(WORK) --chapters chapters.yaml --state $(STATE)

media: ## 转码去重 → dist/media + work/questions.jsonl
	$(CLI) media --in $(WORK) --out $(DIST) --jobs $(JOBS)

validate: ## 合同 §2/§3 校验（失败非零退出）
	$(CLI) validate --in $(WORK) --dist $(DIST)

build: ## 打包 manifest + full + delta + bundle + report
	$(CLI) build --in $(WORK) --dist $(DIST) --state $(STATE)

serve: ## 局域网联调 http://localhost:$(PORT)/（CORS + Range + ETag）
	$(CLI) serve --dist $(DIST) --port $(PORT)

test: ## pytest（含"增量应用 == 全量"合同兼容性测试）
	$(PY) -m pytest -q

bench: ## 性能基准：3000 题 + 500 媒体
	$(CLI) bench --questions 3000 --media 500 --jobs $(JOBS)

# ───────── 发布 ─────────

publish-rsync: ## rsync 到自有服务器（DEST=user@host:/path/）
	@test -n "$(DEST)" || (echo "用法: make publish-rsync DEST=user@host:/var/www/jiakao/"; exit 2)
	bash ../server/publish_rsync.sh $(DEST) $(DIST)

publish-r2: ## 发布到 Cloudflare R2（BUCKET=r2://name）
	@test -n "$(BUCKET)" || (echo "用法: make publish-r2 BUCKET=r2://my-bucket"; exit 2)
	bash ../server/publish_r2.sh $(BUCKET) $(DIST)

publish-gh: ## 发布到 GitHub Pages
	bash ../server/publish_ghpages.sh $(DIST)

# ───────── 清理 ─────────

clean: ## 清掉产物与中间件（保留源码与样例）
	rm -rf $(WORK) $(DIST) $(STATE) .pytest_cache
	find . -name __pycache__ -type d -prune -exec rm -rf {} +

distclean: clean ## 连样例与虚拟环境一起清掉
	rm -rf $(SAMPLE) $(VENV) bench
