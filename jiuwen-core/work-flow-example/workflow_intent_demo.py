# coding: utf-8
"""
完整的工作流 Agent 用例
包含：Start -> IntentDetection -> LLM -> End

工作流流程：
1. Start: 接收用户输入 query
2. IntentDetection: 识别用户意图（天气查询、计算、闲聊等）
3. LLM: 根据意图生成响应
4. End: 输出最终结果

运行前请确保：
1. 已安装 openjiuwen 包
2. 配置正确的 API_BASE, API_KEY, MODEL_NAME, MODEL_PROVIDER
"""

import asyncio
import os, sys
sys.path.insert(0,os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common.constant import API_BASE,API_KEY,MODEL_NAME,MODEL_PROVIDER

os.environ.setdefault("LLM_SSL_VERIFY", "false")

# ========== 导入核心组件 ==========
from openjiuwen.core.workflow import (
    Workflow,
    WorkflowCard,
    Start,
    End,
    LLMComponent,
    LLMCompConfig,
    IntentDetectionComponent,
    IntentDetectionCompConfig,
    create_workflow_session,
)

from openjiuwen.core.foundation.llm import (
    ModelClientConfig,
    ModelRequestConfig,
    SystemMessage,
    UserMessage,
)

from openjiuwen.core.runner import Runner


def create_model_client_config():
    """创建模型客户端配置"""
    return ModelClientConfig(
        client_provider=MODEL_PROVIDER,
        api_base=API_BASE,
        api_key=API_KEY,
        verify_ssl=False,
        timeout=120,
    )


def create_model_request_config():
    """创建模型请求配置"""
    return ModelRequestConfig(
        model=MODEL_NAME,
        temperature=0.7,
        top_p=0.9,
    )


def create_intent_detection_component():
    """创建意图识别组件

    IntentDetectionCompConfig 配置说明：
    - model_client_config: LLM 客户端配置
    - model_config: 模型请求参数配置
    - category_name_list: 意图分类名称列表
    - user_prompt: 用户提示词
    - example_content: Few-shot 示例内容
    - enable_history: 是否启用对话历史
    - accept_language: 语言设置 ('zh' 或 'en')
    """
    config = IntentDetectionCompConfig(
        model_client_config=create_model_client_config(),
        model_config=create_model_request_config(),
        # 定义意图分类列表（分类0为默认分类）
        category_name_list=[
            "天气查询",    # 分类1 - 询问天气相关
            "数学计算",    # 分类2 - 数学运算请求
            "闲聊问候",    # 分类3 - 打招呼、闲聊
            "信息查询",    # 分类4 - 查询信息
        ],
        user_prompt="请分析用户输入，识别用户意图并选择最合适的分类。",
        enable_history=False,
        accept_language='zh',
        # 添加 Few-shot 示例帮助模型理解
        example_content=[
            '{"class": "分类1", "reason": "用户询问天气"} -> 输入: "北京今天天气怎么样？"',
            '{"class": "分类2", "reason": "用户需要计算"} -> 输入: "计算 100 + 200"',
            '{"class": "分类3", "reason": "用户打招呼"} -> 输入: "你好，你是谁？"',
        ]
    )
    return IntentDetectionComponent(config)


def create_llm_component():
    """创建 LLM 组件

    LLMCompConfig 配置说明：
    - model_client_config: LLM 客户端配置
    - model_config: 模型请求参数配置
    - system_prompt_template: 系统提示词模板
    - user_prompt_template: 用户提示词模板（可使用 {{变量}} 语法）
    - response_format: 响应格式 ('text', 'markdown', 'json')
    - output_config: 输出字段配置
    - enable_history: 是否启用对话历史
    """
    config = LLMCompConfig(
        model_client_config=create_model_client_config(),
        model_config=create_model_request_config(),
        # 使用 system_prompt_template 和 user_prompt_template 方式
        system_prompt_template=SystemMessage(
            content="你是一个智能助手，请根据用户的意图提供友好、专业的帮助。"
        ),
        user_prompt_template=UserMessage(
            content="用户意图分类：{{category_name}}\n"
                    "意图识别原因：{{reason}}\n"
                    "用户原始输入：{{query}}\n\n"
                    "请根据以上信息，提供合适的回复。"
        ),
        # 输出配置：text 类型只需一个字段
        response_format={"type": "text"},
        output_config={
            "response": {
                "type": "string",
                "description": "助手回复内容"
            }
        },
        enable_history=False,
    )
    return LLMComponent(config)


def build_workflow():
    """构建完整工作流

    工作流结构：
    Start -> IntentDetection -> LLM -> End

    数据流：
    - query 从外部输入传入 Start
    - Start 输出 query 到 IntentDetection
    - IntentDetection 输出 classification_id, reason, category_name 到 LLM
    - LLM 输出 response 到 End
    - End 格式化输出最终结果
    """
    # 1. 创建 WorkflowCard（工作流元数据）
    card = WorkflowCard(
        id="intent_workflow_demo",
        name="意图识别工作流",
        version="1.0",
        description="包含意图识别节点的完整工作流示例",
    )

    # 2. 创建 Workflow 实例
    workflow = Workflow(card=card)

    # 3. 创建各组件
    start = Start()                                          # 开始组件
    intent_detection = create_intent_detection_component()   # 意图识别组件
    llm = create_llm_component()                             # LLM 组件
    end = End({"responseTemplate": "意图识别结果\n分类: {{intent.category_name}}\n原因: {{intent.reason}}\n\n助手回复\n{{llm.response}}"})  # 结束组件

    # 4. 为 IntentDetection 组件添加分支路由（所有意图都路由到同一个 LLM）
    # IntentDetectionComponent 使用 BranchRouter，必须添加分支才能继续执行
    from openjiuwen.core.workflow import AlwaysTrue
    intent_detection.add_branch(AlwaysTrue(), "llm")

    # 5. 将组件添加到工作流
    # set_start_comp: 设置开始节点，接收外部输入
    workflow.set_start_comp(
        "start",                      # 节点 ID
        start,                        # 组件实例
        inputs_schema={"query": "${query}"}  # 输入映射：${query} 表示外部输入
    )

    # add_workflow_comp: 添加中间节点
    workflow.add_workflow_comp(
        "intent",                     # 节点 ID
        intent_detection,             # 组件实例
        inputs_schema={"query": "${start.query}"}  # 输入映射：从 start 获取 query
    )

    workflow.add_workflow_comp(
        "llm",                        # 节点 ID
        llm,                          # 组件实例
        inputs_schema={
            "query": "${start.query}",          # 从 start 获取原始 query
            "category_name": "${intent.category_name}",  # 从 intent 获取分类名
            "reason": "${intent.reason}",       # 从 intent 获取识别原因
        }
    )

    # set_end_comp: 设置结束节点，输出最终结果
    workflow.set_end_comp(
        "end",                        # 节点 ID
        end,                          # 组件实例
        inputs_schema={
            "intent": "${intent}",    # 意图识别结果
            "llm": "${llm}",          # LLM 响应结果
        }
    )

    # 6. 设置节点连接关系（拓扑结构）
    workflow.add_connection("start", "intent")      # start -> intent
    # intent -> llm 通过 intent_detection.add_branch(AlwaysTrue(), "llm") 设置
    workflow.add_connection("llm", "end")           # llm -> end

    return workflow


async def run_workflow_invoke():
    """运行工作流 - invoke 方式（同步执行）"""
    print("=" * 60)
    print("工作流示例 - invoke 方式")
    print("=" * 60)

    # 1. 启动 Runner（必须先启动）
    await Runner.start()
    print("[OK] Runner 已启动")

    # 2. 构建工作流
    workflow = build_workflow()
    print(f"[OK] 工作流已创建: {workflow.card.name}")

    # 3. 测试多个输入
    test_inputs = [
        {"query": "北京今天天气怎么样？"},
        {"query": "计算 123 + 456 等于多少？"},
        {"query": "你好，请介绍一下你自己"},
    ]

    for i, inputs in enumerate(test_inputs, 1):
        print("-" * 40)
        print(f"[测试 {i}] 输入: {inputs['query']}")

        try:
            # 为每次执行创建新的 session（避免状态冲突）
            session = create_workflow_session(session_id=f"demo_session_{i}")

            # invoke: 同步执行，返回完整结果
            result = await workflow.invoke(inputs, session=session)

            print(f"执行状态: {result.state}")

            if result.state == "COMPLETED":
                print(f"执行结果:")
                if isinstance(result.result, dict):
                    for key, value in result.result.items():
                        print(f"  {key}: {value}")
                else:
                    print(f"  {result.result}")
            elif result.state == "INPUT_REQUIRED":
                print("需要用户输入（交互式节点）")
            else:
                print(f"执行失败: {result.state}")

        except Exception as e:
            print(f"[ERROR] 执行出错: {e}")
            import traceback
            traceback.print_exc()

    # 5. 停止 Runner
    await Runner.stop()
    print("\n[OK] Runner 已停止")


async def run_workflow_streaming():
    """运行工作流 - stream 方式（流式输出）"""
    print("=" * 60)
    print("工作流示例 - stream 方式")
    print("=" * 60)

    await Runner.start()
    print("[OK] Runner 已启动")

    workflow = build_workflow()
    print(f"[OK] 工作流已创建: {workflow.card.name}")

    session = create_workflow_session(session_id="stream_session_001")

    inputs = {"query": "介绍一下人工智能的发展历程"}
    print(f"\n输入: {inputs['query']}")
    print("流式输出:")
    print("-" * 40)

    try:
        # stream: 流式执行，逐步输出结果
        async for chunk in workflow.stream(inputs, session=session):
            # chunk 可能是 OutputSchema, CustomSchema, TraceSchema 等类型
            chunk_type = getattr(chunk, 'type', 'unknown')
            chunk_index = getattr(chunk, 'index', None)
            chunk_payload = getattr(chunk, 'payload', chunk)

            print(f"[{chunk_type}] {chunk_payload}")
    except Exception as e:
        print(f"[ERROR] 执行出错: {e}")
        import traceback
        traceback.print_exc()

    await Runner.stop()
    print("\n[OK] Runner 已停止")


async def run_with_runner_api():
    """使用 Runner.run_workflow API 运行（最简方式）"""
    print("=" * 60)
    print("工作流示例 - Runner.run_workflow API")
    print("=" * 60)

    await Runner.start()
    print("[OK] Runner 已启动")

    workflow = build_workflow()
    print(f"[OK] 工作流已创建: {workflow.card.name}")

    inputs = {"query": "你好，今天天气怎么样？"}
    print(f"\n输入: {inputs['query']}")

    try:
        # Runner.run_workflow: 无需手动创建 session
        result = await Runner.run_workflow(workflow, inputs)
        print(f"结果: {result}")
    except Exception as e:
        print(f"[ERROR] 执行出错: {e}")
        import traceback
        traceback.print_exc()

    await Runner.stop()
    print("\n[OK] Runner 已停止")


def print_workflow_structure(workflow):
    """打印工作流结构"""
    print("\n工作流结构:")
    print(f"  ID: {workflow.card.id}")
    print(f"  Name: {workflow.card.name}")
    print(f"  Version: {workflow.card.version}")
    print(f"  Description: {workflow.card.description}")

    # 绘制 Mermaid 流程图
    print("\n流程图 (Mermaid):")
    mermaid = workflow.draw(title="Intent Workflow", output_format="mermaid")
    print(mermaid)


# ========== 主入口 ==========
if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="工作流意图识别示例")
    parser.add_argument("--mode", "-m", choices=["1", "2", "3"], default="1",
                        help="运行模式: 1=invoke, 2=stream, 3=Runner API")
    args = parser.parse_args()

    print(f"\n运行模式: {args.mode}")
    print("-" * 40)

    if args.mode == "1":
        asyncio.run(run_workflow_invoke())
    elif args.mode == "2":
        asyncio.run(run_workflow_streaming())
    elif args.mode == "3":
        asyncio.run(run_with_runner_api())