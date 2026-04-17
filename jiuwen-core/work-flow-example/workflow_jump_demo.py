# coding: utf-8
"""
多工作流跳转示例 - WorkflowAgent 实现多工作流切换与恢复

核心功能：
1. 智能路由：根据用户意图自动选择合适的工作流
2. 并发管理：支持多个工作流同时处于中断状态
3. 无缝切换：用户可以在不同工作流间自由切换
4. 状态恢复：中断的工作流可以随时恢复

场景演示：
1. 用户发起天气查询请求 -> 工作流中断等待输入地点
2. 用户转而查询股票信息 -> 另一个工作流也中断等待股票代码
3. 用户提供地点信息 -> 恢复天气查询工作流并完成
4. 用户提供股票代码 -> 恢复股票查询工作流并完成
"""

import asyncio
import os
import sys
import io

# 修复 Windows 控制台中文编码问题
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common.constant import API_BASE, API_KEY, MODEL_NAME, MODEL_PROVIDER

os.environ.setdefault("LLM_SSL_VERIFY", "false")
os.environ.setdefault("IS_SENSITIVE", "false")
os.environ.setdefault("WORKFLOW_EXECUTE_TIMEOUT", "3000")


# ========== 导入核心组件 ==========
from openjiuwen.core.application.workflow_agent import WorkflowAgent
from openjiuwen.core.foundation.llm import (
    ModelConfig,
    BaseModelInfo,
    ModelRequestConfig,
    ModelClientConfig,
)
from openjiuwen.core.runner import Runner
from openjiuwen.core.workflow import (
    End,
    QuestionerComponent,
    QuestionerConfig,
    FieldInfo,
    Start,
    Workflow,
    WorkflowCard,
)
from openjiuwen.core.single_agent.legacy import WorkflowAgentConfig


# ========== 模型配置 ==========
def create_model_config() -> ModelConfig:
    """创建模型配置"""
    return ModelConfig(
        model_provider=MODEL_PROVIDER,
        model_info=BaseModelInfo(
            model=MODEL_NAME,
            api_base=API_BASE,
            api_key=API_KEY,
            temperature=0.7,
            top_p=0.9,
            timeout=300,
        ),
    )


def create_model_request_config() -> ModelRequestConfig:
    """创建模型请求配置"""
    return ModelRequestConfig(
        model=MODEL_NAME,
        temperature=0.8,
        top_p=0.9,
    )


def create_model_client_config() -> ModelClientConfig:
    """创建模型客户端配置"""
    return ModelClientConfig(
        client_provider=MODEL_PROVIDER,
        api_key=API_KEY,
        api_base=API_BASE,
        timeout=60,
        verify_ssl=False,
    )


# ========== 构建提问器工作流 ==========
def build_questioner_workflow(
    workflow_id: str,
    workflow_name: str,
    workflow_description: str,
    question_field: str,
    question_desc: str,
) -> Workflow:
    """
    构建包含提问器的简单工作流

    工作流结构：Start -> Questioner -> End

    Args:
        workflow_id: 工作流ID
        workflow_name: 工作流名称
        workflow_description: 工作流描述（用于意图识别）
        question_field: 提问字段名
        question_desc: 提问字段描述

    Returns:
        Workflow 实例
    """
    # 1. 创建 WorkflowCard
    workflow_card = WorkflowCard(
        id=workflow_id,
        name=workflow_name,
        version="1.0",
        description=workflow_description,
        input_params={
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "用户输入"}
            },
            "required": ["query"],
        },
    )

    workflow = Workflow(card=workflow_card)

    # 2. 创建 Start 组件
    start = Start()

    # 3. 创建提问器组件
    key_fields = [
        FieldInfo(
            field_name=question_field,
            description=question_desc,
            required=True,
        ),
    ]

    questioner_config = QuestionerConfig(
        model_client_config=create_model_client_config(),
        model_config=create_model_request_config(),
        question_content=f"请提供{question_desc}",
        extract_fields_from_response=True,
        field_names=key_fields,
        with_chat_history=False,
        extra_prompt_for_fields_extraction="",
        example_content="",
    )
    questioner = QuestionerComponent(questioner_config)

    # 4. 创建 End 组件
    end = End({"responseTemplate": f"{{{{{question_field}}}}}"})

    # 5. 注册组件到工作流
    workflow.set_start_comp(
        "start",
        start,
        inputs_schema={"query": "${query}"},
    )

    workflow.add_workflow_comp(
        "questioner",
        questioner,
        inputs_schema={"query": "${start.query}"},
    )

    workflow.set_end_comp(
        "end",
        end,
        inputs_schema={question_field: f"${{questioner.{question_field}}}"},
    )

    # 6. 设置连接
    workflow.add_connection("start", "questioner")
    workflow.add_connection("questioner", "end")

    return workflow


# ========== 创建 WorkflowAgent ==========
def create_workflow_agent():
    """
    创建包含多个工作流的 WorkflowAgent

    工作流列表：
    - weather_flow: 天气查询工作流
    - stock_flow: 股票查询工作流
    - travel_flow: 旅行规划工作流
    """
    # 1. 创建工作流实例
    weather_workflow = build_questioner_workflow(
        workflow_id="weather_flow",
        workflow_name="天气查询",
        workflow_description="查询某地的天气情况、温度、气象信息，了解天气状况",
        question_field="location",
        question_desc="地点，如城市名称",
    )

    stock_workflow = build_questioner_workflow(
        workflow_id="stock_flow",
        workflow_name="股票查询",
        workflow_description="查询股票价格、股市行情、股票走势等金融信息",
        question_field="stock_code",
        question_desc="股票代码，如 AAPL、TSLA",
    )

    travel_workflow = build_questioner_workflow(
        workflow_id="travel_flow",
        workflow_name="旅行规划",
        workflow_description="规划旅行路线、推荐旅游景点、提供旅行建议",
        question_field="destination",
        question_desc="目的地，如城市或景点名称",
    )

    # 2. 创建 WorkflowAgentConfig
    config = WorkflowAgentConfig(
        id="multi_workflow_jump_agent",
        version="1.0",
        description="多工作流跳转恢复演示智能体",
        model=create_model_config(),
    )

    # 3. 创建 WorkflowAgent
    agent = WorkflowAgent(config)

    # 4. 动态添加工作流
    agent.add_workflows([weather_workflow, stock_workflow, travel_workflow])

    print(f"已创建 WorkflowAgent: {config.id}")
    print(f"已添加工作流数量: 3")
    print(f"  - weather_flow: 天气查询")
    print(f"  - stock_flow: 股票查询")
    print(f"  - travel_flow: 旅行规划")

    return agent


# ========== 演示多工作流跳转 ==========
async def demo_multi_workflow_jump():
    """
    演示多工作流跳转和恢复功能

    场景流程：
    1. 用户: "查询天气" -> weather_flow 中断，询问地点
    2. 用户: "查看股票" -> stock_flow 中断，询问股票代码
    3. 用户: "查询北京天气" -> 恢复 weather_flow，完成
    4. 用户: "查看AAPL股票" -> 恢复 stock_flow，完成
    5. 用户: "规划旅行" -> travel_flow 中断，询问目的地
    6. 用户: "去巴黎旅行" -> 恢复 travel_flow，完成
    """
    print("=" * 60)
    print("多工作流跳转恢复演示")
    print("=" * 60)

    # 启动 Runner
    await Runner.start()
    print("[OK] Runner 已启动")

    # 创建 WorkflowAgent
    agent = create_workflow_agent()

    # 会话 ID（用于跟踪中断状态）
    conversation_id = "demo-jump-recovery-001"

    # ========== 步骤1: 查询天气 -> 中断 ==========
    print("\n" + "-" * 40)
    print("[步骤1] 用户: 查询天气")
    print("预期: weather_flow 中断，询问地点")
    print("-" * 40)

    result1 = await Runner.run_agent(
        agent=agent,
        inputs={
            "query": "查询天气",
            "conversation_id": conversation_id,
        },
    )
    print(f"结果: {result1}")

    # ========== 步骤2: 查看股票 -> 中断（切换工作流）==========
    print("\n" + "-" * 40)
    print("[步骤2] 用户: 查看股票")
    print("预期: stock_flow 中断，询问股票代码")
    print("说明: 此时 weather_flow 和 stock_flow 都处于中断状态")
    print("-" * 40)

    result2 = await Runner.run_agent(
        agent=agent,
        inputs={
            "query": "查看股票",
            "conversation_id": conversation_id,
        },
    )
    print(f"结果: {result2}")

    # ========== 步骤3: 提供地点信息 -> 恢复 weather_flow ==========
    print("\n" + "-" * 40)
    print("[步骤3] 用户: 查询北京天气")
    print("预期: 恢复 weather_flow，返回结果")
    print("-" * 40)

    result3 = await Runner.run_agent(
        agent=agent,
        inputs={
            "query": "查询北京天气",
            "conversation_id": conversation_id,
        },
    )
    print(f"结果: {result3}")

    # ========== 步骤4: 提供股票代码 -> 恢复 stock_flow ==========
    print("\n" + "-" * 40)
    print("[步骤4] 用户: 查看AAPL股票")
    print("预期: 恢复 stock_flow，返回结果")
    print("-" * 40)

    result4 = await Runner.run_agent(
        agent=agent,
        inputs={
            "query": "查看AAPL股票",
            "conversation_id": conversation_id,
        },
    )
    print(f"结果: {result4}")

    # ========== 步骤5: 规划旅行 -> 中断 ==========
    print("\n" + "-" * 40)
    print("[步骤5] 用户: 规划旅行")
    print("预期: travel_flow 中断，询问目的地")
    print("-" * 40)

    result5 = await Runner.run_agent(
        agent=agent,
        inputs={
            "query": "规划旅行",
            "conversation_id": conversation_id,
        },
    )
    print(f"结果: {result5}")

    # ========== 步骤6: 提供目的地 -> 恢复 travel_flow ==========
    print("\n" + "-" * 40)
    print("[步骤6] 用户: 去巴黎旅行")
    print("预期: 恢复 travel_flow，返回结果")
    print("-" * 40)

    result6 = await Runner.run_agent(
        agent=agent,
        inputs={
            "query": "去巴黎旅行",
            "conversation_id": conversation_id,
        },
    )
    print(f"结果: {result6}")

    # 停止 Runner
    await Runner.stop()
    print("\n[OK] Runner 已停止")
    print("=" * 60)
    print("演示完成")
    print("=" * 60)


# ========== 交互式演示 ==========
async def interactive_demo():
    """
    交互式多工作流跳转演示

    用户可以输入查询，体验工作流跳转和恢复功能
    """
    print("=" * 60)
    print("交互式多工作流跳转演示")
    print("=" * 60)
    print("可用工作流:")
    print("  - 天气查询 (weather_flow)")
    print("  - 股票查询 (stock_flow)")
    print("  - 旅行规划 (travel_flow)")
    print("输入 'exit' 或 'quit' 退出")
    print("=" * 60)

    await Runner.start()
    agent = create_workflow_agent()
    conversation_id = "interactive-demo-001"

    while True:
        try:
            user_input = input("\n请输入查询: ").strip()

            if user_input.lower() in ["exit", "quit", "退出"]:
                print("退出演示...")
                break

            if not user_input:
                print("请输入有效的查询内容")
                continue

            print(f"\n处理查询: {user_input}")
            print("-" * 40)

            result = await Runner.run_agent(
                agent=agent,
                inputs={
                    "query": user_input,
                    "conversation_id": conversation_id,
                },
            )

            print(f"结果: {result}")

        except KeyboardInterrupt:
            print("\n退出演示...")
            break
        except Exception as e:
            print(f"错误: {e}")

    await Runner.stop()
    print("Runner 已停止")


# ========== 主入口 ==========
if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="多工作流跳转演示")
    parser.add_argument(
        "--mode",
        "-m",
        choices=["demo", "interactive"],
        default="demo",
        help="运行模式: demo=自动演示, interactive=交互式演示",
    )

    args = parser.parse_args()

    print(f"\n运行模式: {args.mode}")
    print("-" * 40)

    if args.mode == "demo":
        asyncio.run(demo_multi_workflow_jump())
    else:
        asyncio.run(interactive_demo())