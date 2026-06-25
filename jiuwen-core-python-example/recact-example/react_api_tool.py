# coding: utf-8
"""
react agent 调用 API Server 工具示例

运行前请确保：
1. 已安装 openjiuwen 包
2. 配置正确的 API_BASE, API_KEY, MODEL_NAME, MODEL_PROVIDER
3. 启动天气获取服务，运行 python tools/weather_server.py
"""
import os, sys
sys.path.insert(0,os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common.constant import API_BASE,API_KEY,MODEL_NAME,MODEL_PROVIDER

os.environ.setdefault("LLM_SSL_VERIFY", "false")

WEATHER_URL = "http://127.0.0.1:8000/"

from openjiuwen.core.foundation.llm import ModelRequestConfig, ModelClientConfig

model_config = ModelRequestConfig(
    model=MODEL_NAME,
    temperature=0.6,
    top_p=0.8,
)
model_client = ModelClientConfig(
    client_provider=MODEL_PROVIDER,
    api_base=API_BASE,
    api_key=API_KEY,
    verify_ssl=False
)

def create_prompt_template():
    system_prompt = "你是一个AI助手，在适当的时候调用合适的工作流，帮助我完成任务！注意：只需要调用一次工作流后就进行总结，不要重复调用！"
    return [
        dict(role="system", content=system_prompt)
    ]

from openjiuwen.core.foundation.tool import RestfulApiCard, RestfulApi

def create_tool():
    weather_card = RestfulApiCard(
            id="weather_tool",
            name="WeatherReporter",
            description="天气查询插件",
            input_params={
                "type": "object",
                "properties": {
                    "location": {"description": "地点", "type": "string"},
                    "date": {"description": "日期", "type": "string"},
                },
                "required": ["location", "date"],
            },
            url=WEATHER_URL,
            headers={},
            method="GET",
        )
    weather_tool = RestfulApi(
        card=weather_card,
    )

    return weather_tool

from openjiuwen.core.single_agent import AgentCard, ReActAgentConfig, ReActAgent
from openjiuwen.core.runner import Runner

agent_card = AgentCard(
        id="react_agent_1234",
        description="天气查询助手",
    )
prompt_template = create_prompt_template()
react_agent_config = ReActAgentConfig(
    model_client_config=model_client,
    model_config_obj=model_config,
    prompt_template=prompt_template
)
react_agent = ReActAgent(card=agent_card).configure(react_agent_config)
tool = create_tool()
Runner.resource_mgr.add_tool(tool)
react_agent.ability_manager.add(tool.card)

import asyncio

async def main():
    result = await Runner.run_agent(agent=react_agent,
                                    inputs={"query": "查询一下beijing 2026-04-01这天的天气", "conversation_id": "013"})
    print(result)

if __name__ == "__main__":
    asyncio.run(main())
