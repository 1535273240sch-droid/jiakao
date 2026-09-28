"""``python -m jiakao_pipeline`` 入口。

注意：``if __name__ == "__main__"`` 守卫是**必须的** ——
媒体转码用 ``ProcessPoolExecutor``，Windows 的 spawn 启动方式会重新导入主模块，
没有守卫会导致无限递归。
"""

from __future__ import annotations

from .cli import app

if __name__ == "__main__":
    app()
