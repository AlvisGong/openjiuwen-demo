# coding: utf-8
"""基础调测能力样例

演示 Session 的基础 Trace 能力，通过 stream_modes=[BaseStreamMode.TRACE]
获取工作流执行过程中的自动调测事件流。

包含三个场景：
1. 基础工作流调测
2. 循环工作流调测
3. 嵌套子工作流调测
"""

import asyncio

from openjiuwen.core.workflow import (
    Workflow,
    WorkflowComponent,
    Start,
    End,
    LoopGroup,
    LoopComponent,
    SubWorkflowComponent,
    Input,
    Output,
    create_workflow_session,
)
from openjiuwen.core.context_engine import ModelContext
from openjiuwen.core.workflow.components import Session
from openjiuwen.core.session.stream import BaseStreamMode, TraceSchema


# =============================================================================
# 自定义组件：透传输入作为输出
# =============================================================================
class CustomComponent(WorkflowComponent):
    """将输入透传为输出的简单组件。"""

    async def invoke(self, inputs: Input, session: Session, context: ModelContext) -> Output:
        return inputs


class ValueComponent(WorkflowComponent):
    """返回输入中 value 字段的值，赋值给 output。"""

    async def invoke(self, inputs: Input, session: Session, context: ModelContext) -> Output:
        return {"output": inputs["value"]}


# =============================================================================
# 场景1：基础工作流调测 (start -> a -> end)
# =============================================================================
async def run_basic_trace():
    print("\n" + "=" * 60)
    print("场景1：基础工作流调测 (start -> a -> end)")
    print("=" * 60 + "\n")

    workflow = Workflow()
    workflow.set_start_comp("start", Start(), inputs_schema={"num": "${num}"})
    workflow.add_workflow_comp("a", CustomComponent(), inputs_schema={"a_num": "${start.num}"})
    workflow.set_end_comp(
        "end",
        End(conf={"responseTemplate": "hello:{{result}}"}),
        inputs_schema={"result": "${a.a_num}"},
    )
    workflow.add_connection("start", "a")
    workflow.add_connection("a", "end")

    async for chunk in workflow.stream(
        {"num": 1}, create_workflow_session(), stream_modes=[BaseStreamMode.TRACE]
    ):
        if isinstance(chunk, TraceSchema):
            payload = chunk.payload
            print(
                f"===111===[TRACE] invokeId={payload.get('invokeId', 'N/A'):20s} "
                f"===111===status={payload.get('status', 'N/A'):8s} "
                f"===111===componentType={payload.get('componentType', 'N/A')}"
            )


# =============================================================================
# 场景2：循环工作流调测
# =============================================================================
async def run_loop_trace():
    print("\n" + "=" * 60)
    print("场景2：循环工作流调测 (数组循环: a -> b -> c)")
    print("=" * 60 + "\n")

    loop_group = LoopGroup()
    loop_group.add_workflow_comp("a", ValueComponent(), inputs_schema={"value": "${loop.item}"})
    loop_group.add_workflow_comp("b", ValueComponent(), inputs_schema={"value": "${loop.item}"})
    loop_group.add_workflow_comp("c", ValueComponent(), inputs_schema={"value": "${loop.item}"})
    loop_group.start_nodes(["a"])
    loop_group.end_nodes(["c"])
    loop_group.add_connection("a", "b")
    loop_group.add_connection("b", "c")

    loop_component = LoopComponent(
        loop_group,
        {"output": {"a": "${a.output}", "b": "${b.output}", "c": "${c.output}"}},
    )

    workflow = Workflow()
    workflow.set_start_comp("start", Start(), inputs_schema={"query": "${array}"})
    workflow.set_end_comp("end", End(), inputs_schema={"user_var": "${loop.output}"})
    workflow.add_workflow_comp(
        "loop",
        loop_component,
        inputs_schema={"loop_type": "array", "loop_array": {"item": "${start.query}"}},
    )
    workflow.add_connection("start", "loop")
    workflow.add_connection("loop", "end")

    async for chunk in workflow.stream(
        {"array": [1, 2, 3]}, create_workflow_session(), stream_modes=[BaseStreamMode.TRACE]
    ):
        if isinstance(chunk, TraceSchema):
            payload = chunk.payload
            loop_index = payload.get("loopIndex", "")
            loop_info = f"loopIndex={loop_index}" if loop_index != "" else ""
            print(
                f"[TRACE] invokeId={payload.get('invokeId', 'N/A'):20s} "
                f"status={payload.get('status', 'N/A'):8s} "
                f"componentType={payload.get('componentType', 'N/A'):20s} "
                f"{loop_info}"
            )


# =============================================================================
# 场景3：嵌套子工作流调测
# =============================================================================
async def run_sub_workflow_trace():
    print("\n" + "=" * 60)
    print("场景3：嵌套子工作流调测")
    print("=" * 60 + "\n")

    # 子工作流: sub_start -> sub_a -> sub_end
    sub_workflow = Workflow()
    sub_workflow.set_start_comp("sub_start", Start(), inputs_schema={"num": "${a_num}"})
    sub_workflow.add_workflow_comp(
        "sub_a", CustomComponent(), inputs_schema={"a_num": "${sub_start.num}"}
    )
    sub_workflow.set_end_comp(
        "sub_end",
        End(conf={"responseTemplate": "hello:{{result}}"}),
        inputs_schema={"result": "${sub_a.a_num}"},
    )
    sub_workflow.add_connection("sub_start", "sub_a")
    sub_workflow.add_connection("sub_a", "sub_end")

    # 主工作流: start -> a(子工作流) -> end
    main_workflow = Workflow()
    main_workflow.set_start_comp("start", Start(), inputs_schema={"num": "${num}"})
    main_workflow.add_workflow_comp(
        "a", SubWorkflowComponent(sub_workflow), inputs_schema={"a_num": "${start.num}"}
    )
    main_workflow.set_end_comp(
        "end",
        End(conf={"responseTemplate": "hello:{{result}}"}),
        inputs_schema={"result": "${a.a_num}"},
    )
    main_workflow.add_connection("start", "a")
    main_workflow.add_connection("a", "end")

    async for chunk in main_workflow.stream(
        {"num": 1}, create_workflow_session(), stream_modes=[BaseStreamMode.TRACE]
    ):
        if isinstance(chunk, TraceSchema):
            payload = chunk.payload
            parent_node = payload.get("parentNodeId", "")
            parent_info = f"parentNodeId={parent_node}" if parent_node else ""
            print(
                f"[TRACE] invokeId={payload.get('invokeId', 'N/A'):20s} "
                f"status={payload.get('status', 'N/A'):8s} "
                f"componentType={payload.get('componentType', 'N/A'):20s} "
                f"{parent_info}"
            )


async def main():
    await run_basic_trace()
    # await run_loop_trace()
    # await run_sub_workflow_trace()


if __name__ == "__main__":
    asyncio.run(main())
