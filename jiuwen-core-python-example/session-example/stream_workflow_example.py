# coding: utf-8
"""工作流流式输出样例

演示如何在自定义工作流组件中使用 Session 的 write_stream /
write_custom_stream 接口实现流式输出，并通过 workflow.stream() 消费流式数据。
"""

import asyncio

from openjiuwen.core.workflow import (
    Workflow,
    Start,
    End,
    WorkflowComponent,
    Input,
    Output,
    create_workflow_session,
)
from openjiuwen.core.context_engine import ModelContext
from openjiuwen.core.session.stream import OutputSchema, BaseStreamMode, CustomSchema


class ThinkComponent(WorkflowComponent):
    """根据 think_mode 输出不同格式的流式数据。"""

    def __init__(self, component_id: str):
        super().__init__()
        self.component_id = component_id

    async def invoke(self, inputs: Input, session, context: ModelContext) -> Output:
        think_mode = inputs.get("think_mode", False)
        if think_mode:
            await session.write_stream(
                OutputSchema(
                    type="answer",
                    index=0,
                    payload=(self.component_id, {"content": f"I am {self.component_id}, please waiting thinking."}),
                )
            )
        else:
            await session.write_custom_stream(data={"answer": "I will generate a picture for you."})
        return {}


async def run_workflow(think_mode: bool):
    workflow = Workflow()
    workflow.set_start_comp("start", Start(), inputs_schema={"query": "${user_inputs.query}"})
    workflow.set_end_comp(
        "end", End(), inputs_schema={"query": "${start.query}", "answer": "${think_comp.answer}"}
    )
    think_comp = ThinkComponent(component_id="think_comp")
    workflow.add_workflow_comp(
        "think_comp", think_comp, inputs_schema={"think_mode": "${user_inputs.think_mode}"}
    )
    workflow.add_connection("start", "think_comp")
    workflow.add_connection("think_comp", "end")

    inputs = {
        "user_inputs": {
            "query": "Help me generate a picture of West Lake.",
            "think_mode": think_mode,
        }
    }
    session = create_workflow_session()

    print(f"\n{'=' * 20} think_mode={think_mode}, begin workflow {'=' * 20}\n")
    async for chunk in workflow.stream(inputs, session, stream_modes=[BaseStreamMode.OUTPUT, BaseStreamMode.CUSTOM]):
        if isinstance(chunk, OutputSchema):
            print(f"[OutputSchema] type={chunk.type!r} index={chunk.index} payload={chunk.payload!r}")
        elif isinstance(chunk, CustomSchema):
            print(f"[CustomSchema] {chunk.model_dump()}")
        else:
            print(f"[Unknown] {chunk!r}")
    print(f"\n{'=' * 20} think_mode={think_mode}, end workflow {'=' * 20}\n")


async def main():
    await run_workflow(think_mode=True)
    await run_workflow(think_mode=False)


if __name__ == "__main__":
    asyncio.run(main())
