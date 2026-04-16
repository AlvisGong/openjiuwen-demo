# 开发者自定义实现部署weather MCP服务，再创建天气查询插件的MCPTool实例。
import asyncio
import sys
import io

# 修复 Windows 控制台中文编码问题
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

from openjiuwen.core.foundation.tool import MCPTool, SseClient
from openjiuwen.core.foundation.tool.mcp.base import McpServerConfig

SERVER_URL = "http://127.0.0.1:8002/sse"


async def main():
    """主函数，使用单一事件循环运行所有异步操作"""
    mcp_config = McpServerConfig(
        server_name="WeatherMCPServer",
        server_path=SERVER_URL,
        server_id="weather_mcp_server"
    )
    mcp_client = SseClient(mcp_config)

    # 连接 MCP Server
    connected = await mcp_client.connect(timeout=10)
    print(f"连接状态: {'成功' if connected else '失败'}")

    if not connected:
        print("无法连接到 MCP Server，请确保服务器已启动")
        return

    # 获取工具列表
    tool_info_list = await mcp_client.list_tools()
    for tool_info in tool_info_list:
        print("工具信息:", tool_info.model_dump_json())

    # 使用第一个工具创建 MCPTool 实例
    mcp_tool = MCPTool(mcp_client=mcp_client, tool_info=tool_info_list[0])

    # MCPTool实例，可被LLM识别与调用
    tool_info = mcp_tool.card.tool_info()
    print(f"工具详情: {tool_info}")

    # 调用工具
    inputs = {
        "location": "beijing"
    }

    result = await mcp_tool.invoke(inputs=inputs)
    print(f"调用成功，返回结果: {result}")

    # 断开连接
    await mcp_client.disconnect()
    print("已断开连接")


if __name__ == "__main__":
    asyncio.run(main())
