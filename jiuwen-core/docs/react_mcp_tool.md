# React Agent使用MCP Server 工具示例

## 前置准备
- 安装openjiuwen agent-core
> pip install openjiuwen

- 准备模型信息
> 参考样例：
> 
> API_BASE = "https://coding.dashscope.aliyuncs.com/v1";
> 
> API_KEY = "************************";
> 
> MODEL_NAME = "MiniMax-M2.5";
> 
> MODEL_PROVIDER = "OpenAI";

- 准备MCP服务
> 使用MCP服务，用来查询天气信息.
> 
> 代码参考：https://github.com/AlvisGong/openjiuwen-demo/blob/main/jiuwen-core/tools/mcp_server.py
>
> 服务启动：python tools/mcp_server.py

## React Agent调AIP工具样例构建

### 1. 定义模型配置
```python
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
```

### 2. 定义MCP工具
```python
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
```

### 3. 定义ReActAgent和注册工具运行
```python
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

```

> 代码实现参考：https://github.com/AlvisGong/openjiuwen-demo/blob/main/jiuwen-core/recact-example/react_mcp_tool.py