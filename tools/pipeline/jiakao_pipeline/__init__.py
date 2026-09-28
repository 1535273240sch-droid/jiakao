"""05-content-pipeline · 题库内容流水线（Python）。

把原始题库（CSV/JSON/SQLite）变成 App 可增量更新的静态题库包（合同 §2/§3）。

模块地图
    adapters/   原始格式 → RawQuestion
    normalize   清洗/选项答案规范化/题型推断/章节映射 + 稳定 id
    idregistry  稳定 id 注册表（state/id_registry.json）
    media       转码（WebP/mp4）、内容寻址去重、缓存、多进程
    validate    合同 §2/§3 校验 + dist/report.md
    build_pack  全量/增量/离线包/manifest
    diff        增量计算（含"客户端应用增量"的参考实现）
    serve       局域网联调服务器（CORS/Range/ETag/缓存头）
    sample      20 道自编样例题 + Pillow 绘制素材
    bench       3000 题 + 500 媒体 性能基准
"""

from __future__ import annotations

__version__ = "1.0.0"
__all__ = ["__version__"]
