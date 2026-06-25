# coding: utf-8
"""全局状态管理样例

演示工作流中跨组件共享全局状态数据：通过 update_global_state /
get_global_state 在多组件间传递 debug_messages，记录执行轨迹。
"""

import asyncio

from openjiuwen.core.workflow import (
    Workflow,
    WorkflowComponent,
    Input,
    Output,
    create_workflow_session,
)
from openjiuwen.core.context_engine import ModelContext
from openjiuwen.core.workflow.components import Session


class NodeDemo(WorkflowComponent):
    """获取并更新全局状态 debug_messages 的自定义组件。"""

    def __init__(self, node_id: str):
        super().__init__()
        self.node_id = node_id

    async def invoke(self, inputs: Input, session: Session, context: ModelContext) -> Output:
        debug_messages = session.get_global_state("debug_messages")
        print(f"====={self.node_id}: debug_messages = {debug_messages}")

        new_debug_messages = list(debug_messages) if debug_messages else []
        new_debug_messages.append({self.node_id: {"inputs": inputs}})
        session.update_global_state({"debug_messages": new_debug_messages})

        print(f"====={self.node_id}: after update, debug_messages = {session.get_global_state('debug_messages')}")
        return {"output": inputs}


async def main():
    flow = Workflow()
    flow.set_start_comp("start", NodeDemo("start"), inputs_schema={"a": "${user_inputs.a}"})
    flow.add_workflow_comp("node", NodeDemo("node"), inputs_schema={"a": "${start.output.a}"})
    flow.set_end_comp("end", NodeDemo("end"), inputs_schema={"a": "${node.output.a}"})
    flow.add_connection("start", "node")
    flow.add_connection("node", "end")

    session = create_workflow_session()
    result = await flow.invoke({"user_inputs": {"a": 1}}, session)
    print(f"\nresult = {result}")


if __name__ == "__main__":
    asyncio.run(main())
