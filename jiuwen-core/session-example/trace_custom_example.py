# coding: utf-8
"""自定义调测能力样例

演示通过 Session 的 trace 接口向工作流注入自定义调测信息。

- session.trace(data): 在工作流的 onInvokeData 中注入自定义 key/value 数据

包含两个场景：
1. 正常计算场景 — 通过 trace 记录输入和耗时
2. 参数缺失场景 — 通过 trace 记录参数校验失败信息
"""

import asyncio
import time

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
from openjiuwen.core.session.stream import BaseStreamMode, TraceSchema


class AddComponent(WorkflowComponent):
    """执行加法运算，通过 session.trace() 注入自定义调测数据。"""

    async def invoke(self, inputs: Input, session: Session, context: ModelContext) -> Output:
        a = inputs.get("a")
        b = inputs.get("b")

        if a is None:
            # 将参数校验失败信息注入到调测事件流
            await session.trace({"error": "a is not exist", "inputs": {"a": None, "b": b}})
            return {"error": "a is not exist"}

        if b is None:
            await session.trace({"error": "b is not exist", "inputs": {"a": a, "b": None}})
            return {"error": "b is not exist"}

        start_time = time.time()
        result = a + b
        cost = time.time() - start_time

        # 注入自定义调测信息：记录输入参数和计算耗时
        await session.trace({"a": a, "b": b, "cost": cost})
        return {"output": result}


def build_workflow():
    flow = Workflow()
    flow.set_start_comp("start", Start())
    flow.add_workflow_comp(
        "add", AddComponent(), inputs_schema={"a": "${inputs.a}", "b": "${inputs.b}"}
    )
    flow.set_end_comp("end", End(), inputs_schema={"result": "${add.output}"})
    flow.add_connection("start", "add")
    flow.add_connection("add", "end")
    return flow


def print_trace_chunk(chunk):
    """格式化打印 TraceSchema 的关键字段。"""
    if not isinstance(chunk, TraceSchema):
        print(f"[OTHER] {chunk}")
        return
    payload = chunk.payload
    invoke_id = payload.get("invokeId", "N/A")
    status = payload.get("status", "N/A")
    comp_type = payload.get("componentType", "N/A")
    on_invoke_data = payload.get("onInvokeData", [])
    error = payload.get("error")

    line = f"======[TRACE] invokeId={invoke_id:20s} status={status:8s} componentType={comp_type}"
    if on_invoke_data:
        line += f" onInvokeData={on_invoke_data}"
    if error:
        line += f" error={error}"
    print(line)


async def run_success_scenario():
    """场景1：正常计算，传入 a=1, b=2。"""
    print("\n" + "=" * 60)
    print("场景1：正常计算 (a=1, b=2) — 自定义 trace 记录计算耗时")
    print("=" * 60 + "\n")

    flow = build_workflow()
    async for chunk in flow.stream(
        inputs={"inputs": {"a": 1, "b": 2}},
        session=create_workflow_session(),
        stream_modes=[BaseStreamMode.TRACE],
    ):
        print_trace_chunk(chunk)


async def run_error_scenario():
    """场景2：参数缺失，a 未传入 — trace_error 记录错误。"""
    print("\n" + "=" * 60)
    print("场景2：参数缺失 (b=2, 缺少 a) — trace 记录参数校验失败信息")
    print("=" * 60 + "\n")

    flow = build_workflow()
    async for chunk in flow.stream(
        inputs={"inputs": {"b": 2}},
        session=create_workflow_session(),
        stream_modes=[BaseStreamMode.TRACE],
    ):
        print_trace_chunk(chunk)
    print("\n[提示] AddComponent 检测到参数缺失，trace 已记录错误信息。")


async def main():
    await run_success_scenario()
    await run_error_scenario()


if __name__ == "__main__":
    asyncio.run(main())
