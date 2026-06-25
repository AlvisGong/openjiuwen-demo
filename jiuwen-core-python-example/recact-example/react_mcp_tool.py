# coding: utf-8
"""
react agent 调用 MCP Server 工具示例

运行前请确保：
1. 已安装 openjiuwen 包
2. 配置正确的 API_BASE, API_KEY, MODEL_NAME, MODEL_PROVIDER
3. 启动 MCP 天气服务，运行 python tools/mcp_server.py
"""
import os, sys
import io

# 修复 Windows 控制台中文编码问题
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common.constant import API_BASE, API_KEY, MODEL_NAME, MODEL_PROVIDER

os.environ.setdefault("LLM_SSL_VERIFY", "false")

# MCP Server 地址
MCP_SERVER_URL = "http://127.0.0.1:8002/sse"

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

from openjiuwen.core.foundation.tool import MCPTool, SseClient
from openjiuwen.core.foundation.tool.mcp.base import McpServerConfig

async def create_mcp_tool():
    """创建 MCP Tool 实例"""
    # 配置 MCP Server
    mcp_config = McpServerConfig(
        server_name="WeatherMCPServer",
        server_path=MCP_SERVER_URL,
        server_id="weather_mcp_server"
    )

    # 创建 SSE 客户端并连接
    mcp_client = SseClient(mcp_config)
    connected = await mcp_client.connect(timeout=10)

    if not connected:
        raise Exception("无法连接到 MCP Server，请确保服务器已启动")

    print(f"MCP Server 连接成功: {MCP_SERVER_URL}")

    # 获取工具列表
    tool_info_list = await mcp_client.list_tools()
    print(f"发现 {len(tool_info_list)} 个工具:")
    for tool_info in tool_info_list:
        print(f"  - {tool_info.name}: {tool_info.description}")

    # 使用第一个工具创建 MCPTool 实例
    mcp_tool = MCPTool(mcp_client=mcp_client, tool_info=tool_info_list[0])

    return mcp_tool, mcp_client

from openjiuwen.core.single_agent import AgentCard, ReActAgentConfig, ReActAgent
from openjiuwen.core.runner import Runner

import asyncio

async def main():
    # 创建 MCP Tool
    mcp_tool, mcp_client = await create_mcp_tool()

    # 创建 Agent
    agent_card = AgentCard(
        id="react_mcp_agent_001",
        description="MCP天气查询助手",
    )
    prompt_template = create_prompt_template()

    react_agent_config = ReActAgentConfig(
        model_client_config=model_client,
        model_config_obj=model_config,
        prompt_template=prompt_template
    )

    react_agent = ReActAgent(card=agent_card).configure(react_agent_config)

    # 注册工具
    Runner.resource_mgr.add_tool(mcp_tool)
    react_agent.ability_manager.add(mcp_tool.card)

    print("Agent 配置完成，开始执行...")

    # 运行 Agent
    result = await Runner.run_agent(
        agent=react_agent,
        inputs={
            "query": "查询一下北京今天的天气情况",
            "conversation_id": "mcp_001"
        }
    )

    print("=" * 50)
    print("执行结果:")
    print(result)

    # 断开 MCP 连接
    await mcp_client.disconnect()
    print("MCP Server 连接已断开")

if __name__ == "__main__":
    asyncio.run(main())