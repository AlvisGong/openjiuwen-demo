# coding: utf-8
"""
使用问答节点（QuestionerComponent）的工作流示例（支持多轮对话交互）

工作流结构：
Start -> Questioner -> End

问答节点功能：
- QuestionerComponent 用于收集用户信息
- 可以设置需要提取的字段（FieldInfo）
- 支持多轮对话来收集必要信息
- 当必需字段缺失时，会进入 USER_INTERACT 状态，等待用户补充信息
- 用户补充信息后，继续执行直到所有必需字段收集完成

示例场景：用户信息收集 -> 输出收集到的用户信息
"""

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common.constant import API_BASE, API_KEY, MODEL_NAME, MODEL_PROVIDER

os.environ.setdefault("LLM_SSL_VERIFY", "false")
# 设置工作流执行超时时间为 300 秒（默认 60 秒），支持多轮对话
os.environ.setdefault("WORKFLOW_EXECUTE_TIMEOUT", "300")

# ========== 导入核心组件 ==========
from openjiuwen.core.workflow import (
    Workflow,
    WorkflowCard,
    Start,
    End,
    QuestionerComponent,
    QuestionerConfig,
    FieldInfo,
    create_workflow_session,
)

from openjiuwen.core.foundation.llm import (
    ModelClientConfig,
    ModelRequestConfig,
)

from openjiuwen.core.runner import Runner

# 导入交互输入类，用于恢复 workflow 执行
from openjiuwen.core.session.interaction.interactive_input import InteractiveInput


# ========== 模型配置 ==========
def create_model_client_config():
    return ModelClientConfig(
        client_provider=MODEL_PROVIDER,
        api_base=API_BASE,
        api_key=API_KEY,
        verify_ssl=False,
        timeout=120,
    )


def create_model_request_config():
    return ModelRequestConfig(
        model=MODEL_NAME,
        temperature=0.7,
        top_p=0.9,
    )


# ========== 问答节点组件 ==========
def create_questioner_component():
    """
    创建问答节点组件

    配置需要收集的字段：
    - name: 用户姓名（必需）
    - age: 用户年龄（必需）
    - hobby: 用户爱好（可选）

    当所有必需字段收集完成后，流转到下一个节点
    """
    config = QuestionerConfig(
        model_client_config=create_model_client_config(),
        model_config=create_model_request_config(),
        # 定义需要从用户输入中提取的字段
        field_names=[
            FieldInfo(
                field_name="name",
                description="用户姓名",
                type="string",
                cn_field_name="姓名",
                required=True,  # 必需字段
                default_value=""
            ),
            FieldInfo(
                field_name="age",
                description="用户年龄",
                type="integer",  # 整数类型
                cn_field_name="年龄",
                required=True,  # 必需字段
                default_value=""
            ),
            FieldInfo(
                field_name="hobby",
                description="用户爱好",
                type="string",
                cn_field_name="爱好",
                required=False,  # 可选字段
                default_value="未知"
            ),
        ],
        # 最大提问次数（用于收集必需字段）
        max_response=3,
        # 是否使用聊天历史
        with_chat_history=True,
        chat_history_max_rounds=5,
        # 额外的提取提示信息
        extra_prompt_for_fields_extraction="请确保姓名是有效的中文或英文名，年龄是合理的整数。",
        # 示例内容
        example_content=[
            '{"name": "张三", "age": 25, "hobby": "阅读"} -> 输入: "我叫张三，今年25岁，我喜欢阅读"',
            '{"name": "李四", "age": 30} -> 输入: "我是李四，今年30岁"',
        ],
        # 语言设置
        accept_language='zh',
        # 从响应中提取字段
        extract_fields_from_response=True,
    )
    return QuestionerComponent(config)


# ========== 构建工作流 ==========
def build_questioner_workflow():
    """
    构建使用问答节点的工作流（简化版本）

    工作流结构：
    ┌─────────────────────────────┐
    │                             │
    │  Start ──> Questioner ──> End │
    │                             │
    └─────────────────────────────┘

    流程说明：
    1. Start：接收用户初始输入
    2. Questioner：通过问答收集用户信息（姓名、年龄、爱好）
       - 如果必需字段缺失，会向用户提问
       - 直到所有必需字段收集完成
    3. End：输出收集到的用户信息
    """
    # 1. 创建 WorkflowCard
    card = WorkflowCard(
        id="questioner_workflow",
        name="问答节点工作流",
        version="1.0",
        description="使用问答节点收集用户信息",
    )

    # 2. 创建 Workflow
    workflow = Workflow(card=card)

    # 3. 创建所有组件
    start = Start()
    questioner = create_questioner_component()
    # End 组件直接输出收集到的字段
    end = End({"responseTemplate": "收集到的用户信息: name={{name}}, age={{age}}, hobby={{hobby}}"})

    # 4. 添加组件到工作流
    workflow.set_start_comp(
        "start",
        start,
        inputs_schema={"query": "${query}"}
    )

    # 问答节点 - 接收用户的输入并收集信息
    workflow.add_workflow_comp(
        "questioner",
        questioner,
        inputs_schema={"query": "${start.query}"}
    )

    # End 组件 - 直接接收问答节点提取的字段
    workflow.set_end_comp(
        "end",
        end,
        inputs_schema={
            "name": "${questioner.name}",
            "age": "${questioner.age}",
            "hobby": "${questioner.hobby}",
        }
    )

    # 5. 设置连接
    workflow.add_connection("start", "questioner")
    workflow.add_connection("questioner", "end")

    return workflow


# ========== 运行测试 ==========
async def run_questioner_workflow_with_interaction(workflow, session, initial_input: dict, user_responses: list = None):
    """
    运行带交互的工作流

    当问答节点需要更多信息时，会进入 INPUT_REQUIRED 状态，
    此函数会模拟用户提供补充信息，继续执行工作流。

    Args:
        workflow: 工作流实例
        session: 会话实例
        initial_input: 初始输入
        user_responses: 用户补充信息的列表（用于模拟多轮对话）

    Returns:
        最终执行结果
    """
    # 第一次执行，使用初始输入
    result = await workflow.invoke(initial_input, session=session)

    # 检查是否需要用户交互（信息不完整）
    # 注意：状态值是 INPUT_REQUIRED，不是 USER_INTERACT
    if result.state.value == "INPUT_REQUIRED":
        # 从 result 中获取提问内容
        question = ""
        if result.result and len(result.result) > 0:
            # result.result 是一个列表，包含 OutputSchema 对象
            first_output = result.result[0]
            if hasattr(first_output, 'payload') and hasattr(first_output.payload, 'value'):
                question = first_output.payload.value
        print(f"\n[问答节点] 需要更多信息: {question}")

        # 如果提供了用户补充信息，逐轮进行交互
        if user_responses:
            for i, user_response in enumerate(user_responses, 1):
                print(f"\n[用户回复 {i}] {user_response}")

                # 使用 InteractiveInput 恢复工作流执行
                # questioner 是问答节点的 id，将用户回复绑定到该节点
                interactive_input = InteractiveInput()
                interactive_input.update("questioner", user_response)

                # 继续执行工作流
                result = await workflow.invoke(interactive_input, session=session)

                if result.state.value == "COMPLETED":
                    print("\n[成功] 所有信息已收集完成！")
                    return result
                elif result.state.value == "INPUT_REQUIRED":
                    # 还需要更多信息
                    question = ""
                    if result.result and len(result.result) > 0:
                        first_output = result.result[0]
                        if hasattr(first_output, 'payload') and hasattr(first_output.payload, 'value'):
                            question = first_output.payload.value
                    print(f"\n[问答节点] 还需要信息: {question}")
                else:
                    print(f"\n[状态] {result.state}")
                    return result

        # 如果没有提供更多用户回复，返回当前状态
        print("\n[提示] 没有更多用户回复，工作流处于等待状态")
        return result

    return result


async def run_questioner_workflow_demo():
    """运行问答节点工作流示例（支持多轮对话交互）"""
    print("=" * 60)
    print("问答节点（QuestionerComponent）工作流示例")
    print("支持多轮对话交互，自动补充缺失信息")
    print("=" * 60)
    print("\n说明：问答节点会收集用户姓名、年龄、爱好等信息")
    print("如果信息不完整，会进入 USER_INTERACT 状态等待用户补充")
    print("-" * 60)

    await Runner.start()
    print("[OK] Runner 已启动")

    workflow = build_questioner_workflow()
    print(f"[OK] 工作流已创建: {workflow.card.name}")

    # 测试场景 1: 用户提供完整信息 - 一次性完成
    print("\n" + "-" * 40)
    print("[测试 1] 用户提供完整信息")
    print("-" * 40)

    session1 = create_workflow_session(session_id="test_session_complete")
    initial_input1 = {"query": "我叫张三，今年25岁，我喜欢阅读书籍"}
    print(f"[用户输入] {initial_input1['query']}")

    result1 = await run_questioner_workflow_with_interaction(
        workflow, session1, initial_input1
    )

    if result1.state.value == "COMPLETED":
        print(f"\n[执行状态] {result1.state}")
        print(f"[输出结果] {result1.result}")
    else:
        print(f"\n[执行状态] {result1.state}")

    # 测试场景 2: 用户只提供部分信息 - 需要多轮对话补充
    # 注释：由于 API 调用较慢，完整多轮对话需要较长时间
    print("\n" + "-" * 40)
    print("[测试 2] 用户只提供部分信息，演示多轮对话")
    print("-" * 40)

    session2 = create_workflow_session(session_id="test_session_partial")
    initial_input2 = {"query": "我叫李四"}  # 只提供了姓名，缺少年龄
    print(f"[用户输入] {initial_input2['query']}")

    # 模拟用户补充信息的回复列表
    user_responses2 = ["我今年30岁"]  # 用户补充年龄

    result2 = await run_questioner_workflow_with_interaction(
        workflow, session2, initial_input2, user_responses2
    )

    if result2.state.value == "COMPLETED":
        print(f"\n[执行状态] {result2.state}")
        print(f"[输出结果] {result2.result}")
    else:
        print(f"\n[执行状态] {result2.state}")

    await Runner.stop()
    print("\n[OK] Runner 已停止")


# ========== 交互式运行（可选，用于实际命令行交互） ==========
async def run_interactive_demo():
    """
    运行交互式问答工作流示例（真实命令行交互）

    用户可以在命令行中输入回复，与问答节点进行真实交互
    """
    print("=" * 60)
    print("交互式问答节点工作流示例")
    print("用户可以在命令行中输入回复")
    print("=" * 60)

    await Runner.start()
    print("[OK] Runner 已启动")

    workflow = build_questioner_workflow()
    session = create_workflow_session(session_id="interactive_session")

    # 获取用户初始输入
    print("\n请输入您的信息（例如：我叫张三，今年25岁）：")
    user_input = input("> ").strip()

    if not user_input:
        user_input = "你好"  # 默认输入

    initial_input = {"query": user_input}
    print(f"\n[用户输入] {user_input}")

    # 第一次执行
    result = await workflow.invoke(initial_input, session=session)

    # 循环处理交互 - 使用 INPUT_REQUIRED 状态判断
    while result.state.value == "INPUT_REQUIRED":
        # 从 result 中获取提问内容
        question = ""
        if result.result and len(result.result) > 0:
            first_output = result.result[0]
            if hasattr(first_output, 'payload') and hasattr(first_output.payload, 'value'):
                question = first_output.payload.value

        print(f"\n[系统提问] {question}")
        print("请输入您的回复：")

        user_response = input("> ").strip()
        if not user_response:
            print("未输入内容，退出交互")
            break

        print(f"\n[用户回复] {user_response}")

        # 使用 InteractiveInput 恢复执行
        interactive_input = InteractiveInput()
        interactive_input.update("questioner", user_response)

        result = await workflow.invoke(interactive_input, session=session)

    # 输出最终结果
    print("\n" + "-" * 40)
    if result.state.value == "COMPLETED":
        print(f"[执行状态] {result.state}")
        print(f"[收集结果] {result.result}")
    else:
        print(f"[执行状态] {result.state}")
        print(f"[中间结果] {result.result}")

    await Runner.stop()
    print("\n[OK] Runner 已停止")


# ========== 主入口 ==========
if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="问答节点工作流示例")
    parser.add_argument("--mode", "-m", choices=["demo", "interactive"], default="demo",
                        help="运行模式: demo=自动演示多轮对话, interactive=命令行真实交互")
    args = parser.parse_args()

    print(f"\n运行模式: {args.mode}")
    print("-" * 40)

    if args.mode == "interactive":
        asyncio.run(run_interactive_demo())
    else:
        asyncio.run(run_questioner_workflow_demo())