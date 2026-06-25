# coding: utf-8
"""Checkpointer 持久化检查点样例

演示 PersistenceCheckpointer (SQLite) 的配置与使用，包括：
1. 通过 CheckpointerFactory 创建 SQLite 持久化检查点
2. 跨多次 invoke 的状态持久化与恢复
3. 中断-恢复流程验证（状态在文件系统中持久化）
4. 检查点管理接口 (session_exists / release)

[关键点]
- PersistenceCheckpointer 将状态持久化到 SQLite 数据库文件
- 进程重启后状态仍然保留（与 InMemoryCheckpointer 的区别）
- CheckpointerFactory.create() 是异步方法，需要 await
"""

import asyncio
import os

from openjiuwen.core.workflow import (
    Workflow,
    WorkflowComponent,
    Start,
    End,
    Input,
    Output,
    create_workflow_session,
)
from openjiuwen.core.context_engine import ModelContext
from openjiuwen.core.workflow.components import Session
from openjiuwen.core.session import InteractiveInput
from openjiuwen.core.session.checkpointer import CheckpointerFactory
from openjiuwen.core.session.checkpointer.checkpointer import CheckpointerConfig


# =============================================================================
# AskNameNode: 触发中断询问用户姓名
# =============================================================================
class AskNameNode(WorkflowComponent):
    """询问用户姓名，触发中断等待用户输入。"""

    async def invoke(self, inputs: Input, session: Session, context: ModelContext) -> Output:
        name = await session.interact("请输入您的姓名：")
        print(f"[AskNameNode] 收到姓名: {name}")
        return {"name": name}


# =============================================================================
# GreetNode: 根据姓名生成问候语
# =============================================================================
class GreetNode(WorkflowComponent):
    """根据姓名生成问候语。"""

    async def invoke(self, inputs: Input, session: Session, context: ModelContext) -> Output:
        name = inputs.get("name", "未知")
        greeting = f"你好，{name}！欢迎使用 openJiuwen。"
        print(f"[GreetNode] 生成问候: {greeting}")
        return {"greeting": greeting}


def build_workflow():
    """构建 start → ask_name → greet → end 工作流。"""
    flow = Workflow()
    flow.set_start_comp("start", Start(), inputs_schema={"query": "${query}"})
    flow.add_workflow_comp(
        "ask_name", AskNameNode(), inputs_schema={"query": "${start.query}"}
    )
    flow.add_workflow_comp(
        "greet", GreetNode(), inputs_schema={"name": "${ask_name.name}"}
    )
    flow.set_end_comp(
        "end",
        End(conf={"responseTemplate": "{{greeting}}"}),
        inputs_schema={"greeting": "${greet.greeting}"},
    )
    flow.add_connection("start", "ask_name")
    flow.add_connection("ask_name", "greet")
    flow.add_connection("greet", "end")
    return flow


# =============================================================================
# 场景1：SQLite 持久化检查点 — 中断恢复
# =============================================================================
async def run_sqlite_interrupt_resume():
    """演示 SQLite 持久化检查点的中断-恢复流程。

    [关键点]
    - 通过 CheckpointerFactory.create() 异步创建持久化检查点
    - 状态保存到 SQLite 数据库文件（demo_checkpoint.db）
    - 中断后，检查点将状态写入 SQLite；恢复时从 SQLite 读取
    - 进程重启后状态仍然存在（不同于 InMemoryCheckpointer）
    """
    print("\n" + "=" * 60)
    print("场景1：SQLite 持久化检查点 — 中断恢复")
    print("=" * 60 + "\n")

    db_path = "demo_checkpoint.db"

    # 清理上次运行的数据库文件，确保从干净状态开始
    if os.path.exists(db_path):
        os.remove(db_path)
        print(f"[初始化] 已删除旧数据库文件: {db_path}")

    # 1. 通过工厂方法创建 PersistenceCheckpointer（SQLite 后端）
    #    CheckpointerFactory.create 是异步方法，内部会：
    #    - 创建 SQLite 异步引擎 (aiosqlite)
    #    - 启用 WAL 模式以提高并发写入性能
    #    - 构建 DbBasedKVStore → PersistenceCheckpointer
    config = CheckpointerConfig(
        type="persistence",
        conf={
            "db_type": "sqlite",
            "db_path": db_path,
            "db_timeout": 30,         # SQLite 锁等待超时（秒）
            "db_enable_wal": True,    # 启用 WAL 模式
        },
    )
    checkpointer = await CheckpointerFactory.create(config)  # ← 异步创建
    CheckpointerFactory.set_default_checkpointer(checkpointer)
    print(f"[Step 1] PersistenceCheckpointer (SQLite) 已创建并设为默认")
    print(f"         数据库文件: {os.path.abspath(db_path)}")

    # 2. 构建工作流 + 创建 session
    flow = build_workflow()
    session = create_workflow_session()
    session_id = session.get_session_id()
    print(f"[Step 2] 创建 session，session_id = {session_id}")

    # 3. 首次执行 — 触发中断
    print("\n[Step 3] 首次执行，将在 AskNameNode 处中断...")
    output = await flow.invoke({"query": "打招呼"}, session)
    print(f"  状态: {output.state}  （应显示 INPUT_REQUIRED）")

    # 4. 验证检查点已保存状态
    exists = await checkpointer.session_exists(session_id)
    print(f"\n[Step 4] checkpointer.session_exists('{session_id}') → {exists}")
    print(f"         （中断后状态已写入 {db_path}，所以返回 True）")

    # 5. 恢复执行
    print("\n[Step 5] 恢复执行，传入用户姓名...")
    user_input = InteractiveInput(raw_inputs="张三")
    output = await flow.invoke(user_input, session)
    print(f"  状态: {output.state}  （应显示 COMPLETED）")
    print(f"  结果: {output.result}")

    # 6. 完成后检查点已清理
    exists = await checkpointer.session_exists(session_id)
    print(f"\n[Step 6] checkpointer.session_exists('{session_id}') → {exists}")
    print(f"         （工作流完成后自动清理，所以返回 False）")

    # 关闭引擎连接，释放文件句柄
    kv_store = checkpointer._kv_store
    if hasattr(kv_store, 'engine'):
        await kv_store.engine.dispose()
        print(f"\n[清理] SQLAlchemy 引擎已关闭")

    # 清理测试文件
    if os.path.exists(db_path):
        os.remove(db_path)
        print(f"[清理] 已删除数据库文件: {db_path}")

    print("\n[提示] PersistenceCheckpointer 将状态保存在磁盘上，")
    print("       进程重启后状态仍然保留，适合生产环境使用。")


# =============================================================================
# 场景2：CheckpointerFactory 对比 — InMemory vs Persistence
# =============================================================================
async def run_factory_comparison():
    """演示 CheckpointerFactory 创建不同类型检查点的方式。

    [关键点]
    - 所有检查点类型都通过 CheckpointerFactory.create(config) 统一创建
    - 只需改变 config.type 和 config.conf 即可切换存储后端
    - CheckpointerFactory.get_checkpointer() 获取当前默认检查点
    """
    print("\n" + "=" * 60)
    print("场景2：工厂方法对比 — InMemory vs Persistence")
    print("=" * 60 + "\n")

    # ---- 方式1：InMemory ----
    print("--- 方式1：InMemory 检查点 ---")
    config_inmem = CheckpointerConfig(type="in_memory", conf={})
    cp_inmem = await CheckpointerFactory.create(config_inmem)
    print(f"  类型: {type(cp_inmem).__name__}")
    print(f"  适用: 开发/测试环境，状态不持久化")
    print(f"  特点: 无需额外配置，性能最高")

    # ---- 方式2：Persistence (SQLite) ----
    print("\n--- 方式2：Persistence 检查点 (SQLite) ---")
    db_path = "demo_factory.db"
    config_sqlite = CheckpointerConfig(
        type="persistence",
        conf={"db_type": "sqlite", "db_path": db_path},
    )
    cp_sqlite = await CheckpointerFactory.create(config_sqlite)
    print(f"  类型: {type(cp_sqlite).__name__}")
    print(f"  适用: 单机生产环境")
    print(f"  特点: 状态持久化到 SQLite 文件，支持 WAL 模式")

    # ---- 方式3：Persistence (Shelve) ----
    print("\n--- 方式3：Persistence 检查点 (Shelve) ---")
    config_shelve = CheckpointerConfig(
        type="persistence",
        conf={"db_type": "shelve", "db_path": "demo_checkpoint_shelve"},
    )
    cp_shelve = await CheckpointerFactory.create(config_shelve)
    print(f"  类型: {type(cp_shelve).__name__}")
    print(f"  适用: 单机生产环境（Python 标准库方案）")
    print(f"  特点: 基于 Python shelve 模块，无需额外依赖")

    # 清理 — 需先关闭数据库引擎再删除文件
    for cp_obj, file_path in [
        (cp_sqlite, db_path),
        (cp_shelve, "demo_checkpoint_shelve.db"),
    ]:
        kv_store = cp_obj._kv_store
        if hasattr(kv_store, 'engine'):
            await kv_store.engine.dispose()
        if os.path.exists(file_path):
            os.remove(file_path)
    print(f"\n[清理] 已删除测试数据库文件")

    print("\n[对比总结]")
    print("  InMemory:        简单快速，无持久化 → 适合开发测试")
    print("  Persistence(SQLite): 持久化到 SQLite → 适合单机生产")
    print("  Persistence(Shelve): 持久化到 Shelve → 适合轻量部署")
    print("  Redis:             持久化到 Redis → 适合分布式生产（需要 Redis 服务）")


async def main():
    await run_sqlite_interrupt_resume()
    await run_factory_comparison()
    print("\n" + "=" * 60)
    print("所有场景执行完毕")


if __name__ == "__main__":
    asyncio.run(main())
