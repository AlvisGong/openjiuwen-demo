# coding: utf-8
"""Agent 流式输出样例

演示如何在自定义 Agent 中通过 Session 的 write_stream / write_custom_stream
接口实现流式输出，并通过 session.stream_iterator() 消费流式数据。
"""

import asyncio
from typing import Any, AsyncIterator, Optional

from openjiuwen.core.single_agent import AgentCard, BaseAgent
from openjiuwen.core.single_agent import Session, create_agent_session
from openjiuwen.core.session.stream import OutputSchema, CustomSchema, StreamMode, BaseStreamMode


class MockModel:
    """模拟大模型逐 token 生成流式数据。"""

    def __init__(self, model_name: str = "MockModel"):
        self.model_name = model_name

    async def call(self, inputs: dict, session: Session):
        think_mode = inputs.get("think_mode", False)
        if think_mode:
            tokens = ["I", "am", "MockModel,", "please", "waiting", "thinking."]
            for index, token in enumerate(tokens):
                await asyncio.sleep(0.1)
                await session.write_stream(
                    OutputSchema(
                        type="answer",
                        index=index,
                        payload=(self.model_name, {"content": token}),
                    )
                )
        else:
            tokens = ["I", "will", "generate", "a", "picture", "for", "you."]
            for token in tokens:
                await asyncio.sleep(0.1)
                await session.write_custom_stream(data={"answer": token})


class CustomAgent(BaseAgent):
    """自定义 Agent，内部使用 MockModel 产生流式输出。"""

    def __init__(self, card: AgentCard):
        super().__init__(card)
        self._model = MockModel(model_name="MockModel")

    def configure(self, config) -> "BaseAgent":
        return self

    async def invoke(self, inputs, session: Optional[Session] = None):
        pass

    async def stream(
        self,
        inputs: Any,
        session: Optional[Session] = None,
        stream_modes: Optional[list[StreamMode]] = None,
    ) -> AsyncIterator[Any]:
        session_id = inputs.pop("conversation_id", "default_session")
        session = create_agent_session(session_id=session_id, card=self.card, close_stream_on_post_run=False)

        await session.pre_run(inputs=inputs)

        async def stream_process():
            try:
                await self._model.call(inputs, session)
            except Exception as ex:
                print(f"Model call error: {ex}")
            finally:
                await session.close_stream()

        task = asyncio.create_task(stream_process())

        async for chunk in session.stream_iterator():
            yield chunk

        await task
        await session.post_run()


async def run_agent(think_mode: bool):
    card = AgentCard()
    card.id = "CustomAgent"
    agent = CustomAgent(card)

    print(f"\n{'=' * 20} think_mode={think_mode}, begin agent {'=' * 20}\n")
    async for chunk in agent.stream({"conversation_id": "mock_session", "think_mode": think_mode}):
        if isinstance(chunk, OutputSchema):
            print(f"[OutputSchema] type={chunk.type!r} index={chunk.index} payload={chunk.payload!r}")
        elif isinstance(chunk, CustomSchema):
            print(f"[CustomSchema] {chunk.model_dump()}")
        else:
            print(f"[Unknown] {chunk!r}")
    print(f"\n{'=' * 20} think_mode={think_mode}, end agent {'=' * 20}\n")


async def main():
    await run_agent(think_mode=True)
    await run_agent(think_mode=False)


if __name__ == "__main__":
    asyncio.run(main())
