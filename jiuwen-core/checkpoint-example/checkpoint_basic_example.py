# coding: utf-8
"""Checkpointer 检查点基础样例

演示通过 Checkpointer 实现工作流中断-恢复机制。

核心流程：
1. 配置 InMemoryCheckpointer 作为默认检查点
2. 工作流执行到 InteractiveNode 时触发中断（session.interact）
3. 检查点自动保存工作流状态
4. 使用 InteractiveInput 恢复执行，检查点自动恢复状态

包含两个场景：
1. 工作流中断恢复 — 单次中断-恢复完整流程
2. 手动管理检查点 — session_exists / release 等管理接口
"""

import asyncio

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
from openjiuwen.core.session.checkpointer.inmemory import InMemoryCheckpointer


# =============================================================================
# InteractiveNode: 在工作流中触发中断，等待用户输入
# =============================================================================
class InteractiveNode(WorkflowComponent):
    """执行到此处时触发中断，等待用户输入后继续。

    session.interact("提示信息") 会：
    1. 向流输出一条 interaction 事件
    2. 抛出 GraphInterrupt 中断图执行
    3. 检查点的 post_workflow_execute 检测到中断，保存工作流状态
    """

    async def invoke(self, inputs: Input, session: Session, context: ModelContext) -> Output:
        print("======[InteractiveNode] 触发中断，等待用户输入...")
        # 触发中断：框架会保存状态到检查点，并向外抛出 INPUT_REQUIRED
        user_input = await session.interact("请确认是否继续执行：")
        print(f"======[InteractiveNode] 收到用户输入: {user_input}")
        return {"confirmed": True, "user_input": user_input}


def build_workflow():
    """构建 start → interactive → end 工作流。"""
    flow = Workflow()
    flow.set_start_comp("start", Start(), inputs_schema={"query": "${query}"})
    flow.add_workflow_comp(
        "interactive", InteractiveNode(), inputs_schema={"query": "${start.query}"}
    )
    flow.set_end_comp(
        "end",
        End(conf={"responseTemplate": "确认结果: {{confirmed}}, 用户输入: {{user_input}}"}),
        inputs_schema={"confirmed": "${interactive.confirmed}", "user_input": "${interactive.user_input}"},
    )
    flow.add_connection("start", "interactive")
    flow.add_connection("interactive", "end")
    return flow


# =============================================================================
# 场景1：工作流中断恢复
# =============================================================================
async def run_interrupt_resume_scenario():
    """演示完整的中断-恢复流程。

    [关键点]
    - 首次 invoke 会触发中断，返回 INPUT_REQUIRED 状态
    - 第二次 invoke 传入 InteractiveInput，检查点自动恢复状态并继续执行
    - 两次调用必须使用同一个 session 对象
    """
    print("\n" + "=" * 60)
    print("场景1：工作流中断恢复 — 单次中断-恢复完整流程")
    print("=" * 60 + "\n")

    # 1. 创建 InMemoryCheckpointer 并设为默认
    #    CheckpointerFactory.set_default_checkpointer 使后续所有 Agent/Workflow
    #    自动使用该检查点进行状态管理
    checkpointer = InMemoryCheckpointer()
    CheckpointerFactory.set_default_checkpointer(checkpointer)
    print("======[Step 1] InMemoryCheckpointer 已创建并设为默认")

    # 2. 构建工作流 + 创建 session
    flow = build_workflow()
    session = create_workflow_session()

    # 3. 首次执行 — 触发中断
    #    调用 session.interact 后，检查点自动保存状态
    print("\n[Step 2] 首次执行 workfow.invoke()，将在 InteractiveNode 处中断...")
    output = await flow.invoke({"query": "hello"}, session)
    print(f"  ======输出状态: {output.state}")
    print(f"  ======输出内容: {output.result}")

    # 4. 恢复执行 — 传入用户输入
    #    InteractiveInput(raw_inputs="用户的选择") 告诉检查点"这是一次恢复请求"
    #    检查点的 pre_workflow_execute 检测到 InteractiveInput 后恢复状态
    #    并将 raw_inputs 通过 interact() 的返回值传递给组件
    print("\n[Step 3] 恢复执行 workflow.invoke(InteractiveInput(...), session)...")
    user_input = InteractiveInput(raw_inputs="继续执行，用户已确认")
    output = await flow.invoke(user_input, session)
    print(f"  ======输出状态: {output.state}")
    print(f"  ======输出内容: {output.result}")

    print("\n[提示] 首次 invoke 状态为 INPUT_REQUIRED（中断），")
    print("       ======第二次 invoke 状态为 COMPLETED（恢复后成功完成）。")
    print("       ======两次调用使用同一个 session，检查点自动完成状态保存和恢复。")


# =============================================================================
# 场景2：手动管理检查点
# =============================================================================
async def run_checkpointer_management_scenario():
    """演示检查点的手动管理接口。

    [关键点]
    - session_exists(): 检查指定 session 是否有保存的状态
    - release(): 手动释放指定 session 的所有检查点状态
    """
    print("\n" + "=" * 60)
    print("场景2：手动管理检查点 — session_exists / release 接口")
    print("=" * 60 + "\n")

    # 1. 创建独立的 InMemoryCheckpointer 实例
    checkpointer = InMemoryCheckpointer()
    CheckpointerFactory.set_default_checkpointer(checkpointer)
    print("[Step 1] 创建新的 InMemoryCheckpointer")

    # 2. 执行工作流触发中断
    flow = build_workflow()
    session = create_workflow_session()
    session_id = session.get_session_id()
    print(f"[Step 2] 创建 session，session_id = {session_id}")

    print("[Step 3] 触发中断...")
    await flow.invoke({"query": "test"}, session)

    # 3. 检查 session 状态是否存在
    exists = await checkpointer.session_exists(session_id)
    print(f"[Step 4] checkpointer.session_exists('{session_id}') → {exists}")
    print("        （中断后检查点保存了工作流状态，所以返回 True）")

    # 4. 手动释放检查点状态
    print(f"\n[Step 5] 调用 checkpointer.release('{session_id}') 释放状态...")
    await checkpointer.release(session_id)
    print("        release() 完成")

    # 5. 再次检查 — 状态已清除
    exists = await checkpointer.session_exists(session_id)
    print(f"[Step 6] checkpointer.session_exists('{session_id}') → {exists}")
    print("        （release 后状态已清除，所以返回 False）")

    print("\n[提示] release() 可用于定期清理过期/无效的检查点状态，")
    print("       避免状态数据无限增长。")


async def main():
    await run_interrupt_resume_scenario()
    await run_checkpointer_management_scenario()
    print("\n" + "=" * 60)
    print("所有场景执行完毕")


if __name__ == "__main__":
    asyncio.run(main())
