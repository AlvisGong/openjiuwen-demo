# coding: utf-8
"""组件状态管理样例

演示组件私有状态 (update_state / get_state) 的隔离性，以及全局状态
(update_global_state / get_global_state) 的跨组件共享。

- AddTenComponent: 通过组件状态记录私有执行次数 call_times，
  通过全局状态维护共享累加值 num。
- CustomEnd: 验证无法读取其他组件的私有状态 call_times，
  但可以读取全局共享状态 num。

Start → AddTenComponent("a") → BranchComponent("sw") ─┬─ num <= 30 → 回到 "a" (循环)
                                                      └─ num > 30  → CustomEnd("end")

两个组件
AddTenComponent：每次执行时：

读取自己的私有 call_times，+1 后写回（只有自己能读写）
读取全局 num，+10 后写回（所有组件共享）
CustomEnd：作为结束节点，尝试读取 call_times 和 num：

call_times → 读到 None（因为私有状态是隔离的，CustomEnd 读不到 AddTenComponent 的私有数据）
num → 读到最终累加值（全局状态跨组件共享）
执行结果
BranchComponent 的条件判断使用 ${num} 引用全局状态。由于每次循环 num += 10，执行轨迹为：

a: call_times=1, num=10
a: call_times=2, num=20
a: call_times=3, num=30
a: call_times=4, num=40 → num > 30，跳出循环
end: call_times=None (隔离验证), num=40 (共享验证)
"""

import asyncio

from openjiuwen.core.workflow import (
    Workflow,
    WorkflowComponent,
    Start,
    BranchComponent,
    Input,
    Output,
    create_workflow_session,
)
from openjiuwen.core.context_engine import ModelContext
from openjiuwen.core.workflow.components import Session


class AddTenComponent(WorkflowComponent):
    """对全局 num 累加 10，并在组件私有状态中记录执行次数。"""

    def __init__(self, node_id: str):
        super().__init__()
        self._node_id = node_id

    async def invoke(self, inputs: Input, session: Session, context: ModelContext) -> Output:
        call_times = session.get_state("call_times") or 0
        num = session.get_global_state("num") or 0

        call_times += 1
        session.update_state({"call_times": call_times})

        num += 10
        session.update_global_state({"num": num})

        print(f"======[{self._node_id}] 执行第 {call_times} 次，num = {num - 10} → {num}")
        return inputs


class CustomEnd(WorkflowComponent):
    """验证组件状态隔离性：尝试读取 AddTenComponent 的私有 call_times。"""

    def __init__(self, node_id: str):
        super().__init__()
        self._node_id = node_id

    async def invoke(self, inputs: Input, session: Session, context: ModelContext) -> Output:
        call_times = session.get_state("call_times")
        print(f"------>[{self._node_id}] 获取 call_times = {call_times}")

        num = session.get_global_state("num")
        print(f"------>[{self._node_id}] 获取 num = {num}")

        return inputs


async def main():
    flow = Workflow()
    flow.set_start_comp("start", Start())
    flow.set_end_comp("end", CustomEnd("end"))
    flow.add_workflow_comp("a", AddTenComponent("a"))

    sw = BranchComponent()
    sw.add_branch("${num} <= 30", ["a"], "1")
    sw.add_branch("${num} > 30", ["end"], "2")
    flow.add_workflow_comp("sw", sw)

    flow.add_connection("start", "a")
    flow.add_connection("a", "sw")

    await flow.invoke({}, create_workflow_session())


if __name__ == "__main__":
    asyncio.run(main())
