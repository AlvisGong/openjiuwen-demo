# coding: utf-8
"""
根据不同意图执行不同 Agent 的工作流示例

工作流结构：
Start -> IntentDetection -> [天气Agent | 计算Agent | 闲聊Agent | 默认Agent] -> End

分支路由机制：
- IntentDetection 输出 classification_id (0=默认, 1=天气, 2=计算, 3=闲聊, 4=信息)
- 通过 ExpressionCondition 表达式判断意图 ID
- 不同意图路由到不同的下游节点
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


# ========== 意图识别组件 ==========
def create_intent_detection_component():
    """创建意图识别组件，定义分类列表"""
    config = IntentDetectionCompConfig(
        model_client_config=create_model_client_config(),
        model_config=create_model_request_config(),
        # 分类列表（classification_id: 0=默认, 1=天气, 2=计算, 3=闲聊, 4=信息）
        category_name_list=[
            "天气查询",    # classification_id = 1
            "数学计算",    # classification_id = 2
            "闲聊问候",    # classification_id = 3
            "信息查询",    # classification_id = 4
        ],
        user_prompt="请分析用户输入，识别用户意图并选择最合适的分类。",
        enable_history=False,
        accept_language='zh',
        example_content=[
            '{"class": "分类1", "reason": "用户询问天气"} -> 输入: "北京今天天气怎么样？"',
            '{"class": "分类2", "reason": "用户需要计算"} -> 输入: "计算 100 + 200"',
            '{"class": "分类3", "reason": "用户打招呼"} -> 输入: "你好，你是谁？"',
            '{"class": "分类4", "reason": "用户查询信息"} -> 输入: "介绍一下人工智能"',
        ]
    )
    return IntentDetectionComponent(config)


# ========== 不同意图对应的 Agent/LLM 组件 ==========
def create_weather_agent():
    """天气查询 Agent - 专门处理天气相关问题"""
    config = LLMCompConfig(
        model_client_config=create_model_client_config(),
        model_config=create_model_request_config(),
        system_prompt_template=SystemMessage(
            content="你是一个专业的天气助手。当用户询问天气时，请提供友好的回复，"
                    "并建议用户查询具体城市的天气信息。如果用户没有指定城市，请询问用户想查询哪个城市的天气。"
        ),
        user_prompt_template=UserMessage(
            content="用户询问：{{query}}\n请提供天气相关的帮助和回复。"
        ),
        response_format={"type": "text"},
        output_config={
            "response": {
                "type": "string",
                "description": "天气助手回复"
            }
        },
        enable_history=False,
    )
    return LLMComponent(config)


def create_calculator_agent():
    """数学计算 Agent - 专门处理数学计算问题"""
    config = LLMCompConfig(
        model_client_config=create_model_client_config(),
        model_config=create_model_request_config(),
        system_prompt_template=SystemMessage(
            content="你是一个专业的数学计算助手。请帮助用户进行数学计算，"
                    "并给出清晰的计算过程和结果。对于复杂表达式，请分步计算。"
        ),
        user_prompt_template=UserMessage(
            content="用户需要计算：{{query}}\n请进行计算并给出结果。"
        ),
        response_format={"type": "text"},
        output_config={
            "response": {
                "type": "string",
                "description": "计算结果"
            }
        },
        enable_history=False,
    )
    return LLMComponent(config)


def create_chat_agent():
    """闲聊 Agent - 处理问候、闲聊类问题"""
    config = LLMCompConfig(
        model_client_config=create_model_client_config(),
        model_config=create_model_request_config(),
        system_prompt_template=SystemMessage(
            content="你是一个友好、热情的聊天助手。请与用户进行自然、亲切的对话，"
                    "介绍自己的能力，并引导用户说出他们的需求。"
        ),
        user_prompt_template=UserMessage(
            content="用户说：{{query}}\n请友好地回复，并与用户进行对话。"
        ),
        response_format={"type": "text"},
        output_config={
            "response": {
                "type": "string",
                "description": "聊天回复"
            }
        },
        enable_history=False,
    )
    return LLMComponent(config)


def create_info_agent():
    """信息查询 Agent - 处理信息查询、知识问答"""
    config = LLMCompConfig(
        model_client_config=create_model_client_config(),
        model_config=create_model_request_config(),
        system_prompt_template=SystemMessage(
            content="你是一个知识渊博的信息助手。请为用户提供准确、详细的信息，"
                    "包括定义、历史、应用等方面的内容。"
        ),
        user_prompt_template=UserMessage(
            content="用户查询：{{query}}\n请提供详细的信息和解释。"
        ),
        response_format={"type": "text"},
        output_config={
            "response": {
                "type": "string",
                "description": "信息回复"
            }
        },
        enable_history=False,
    )
    return LLMComponent(config)


def create_default_agent():
    """默认 Agent - 处理无法分类的请求"""
    config = LLMCompConfig(
        model_client_config=create_model_client_config(),
        model_config=create_model_request_config(),
        system_prompt_template=SystemMessage(
            content="你是一个全能助手，可以处理各种类型的请求。"
                    "请尽可能帮助用户解决问题。"
        ),
        user_prompt_template=UserMessage(
            content="用户输入：{{query}}\n请提供帮助和回复。"
        ),
        response_format={"type": "text"},
        output_config={
            "response": {
                "type": "string",
                "description": "默认回复"
            }
        },
        enable_history=False,
    )
    return LLMComponent(config)


# ========== 构建带分支的工作流 ==========
def build_multi_agent_workflow():
    """
    构建多 Agent 分支工作流

    工作流结构：
    ┌─────────────────────────────────────────────────┐
    │                                                 │
    │  Start ──> IntentDetection ──┬──> weather_agent │
    │                              ├──> calc_agent    │
    │                              ├──> chat_agent    │
    │                              ├──> info_agent    │
    │                              └──> default_agent │
    │                                     │           │
    │                                     v           │
    │                                   End           │
    └─────────────────────────────────────────────────┘

    分支规则：
    - classification_id == 1 -> weather_agent (天气)
    - classification_id == 2 -> calc_agent (计算)
    - classification_id == 3 -> chat_agent (闲聊)
    - classification_id == 4 -> info_agent (信息)
    - classification_id == 0 -> default_agent (默认)
    """
    # 1. 创建 WorkflowCard
    card = WorkflowCard(
        id="multi_agent_workflow",
        name="多Agent分支工作流",
        version="1.0",
        description="根据意图识别结果路由到不同的Agent",
    )

    # 2. 创建 Workflow
    workflow = Workflow(card=card)

    # 3. 创建所有组件
    start = Start()
    intent_detection = create_intent_detection_component()

    # 创建不同的 Agent
    weather_agent = create_weather_agent()
    calc_agent = create_calculator_agent()
    chat_agent = create_chat_agent()
    info_agent = create_info_agent()
    default_agent = create_default_agent()

    # End 组件 - 统一输出格式
    end = End({"responseTemplate": "{{agent_response}}"})

    # 4. 为 IntentDetection 添加分支路由
    # 使用表达式条件 ExpressionCondition（字符串形式）
    # 格式: "${intent.classification_id} == N" 判断意图 ID

    # 分类 ID 说明：
    # 0 = 默认意图（未匹配到其他分类）
    # 1 = 天气查询
    # 2 = 数学计算
    # 3 = 闲聊问候
    # 4 = 信息查询

    intent_detection.add_branch("${intent.classification_id} == 1", "weather_agent")
    intent_detection.add_branch("${intent.classification_id} == 2", "calc_agent")
    intent_detection.add_branch("${intent.classification_id} == 3", "chat_agent")
    intent_detection.add_branch("${intent.classification_id} == 4", "info_agent")
    intent_detection.add_branch("${intent.classification_id} == 0", "default_agent")

    # 5. 添加组件到工作流
    workflow.set_start_comp(
        "start",
        start,
        inputs_schema={"query": "${query}"}
    )

    workflow.add_workflow_comp(
        "intent",
        intent_detection,
        inputs_schema={"query": "${start.query}"}
    )

    # 添加各个 Agent
    workflow.add_workflow_comp(
        "weather_agent",
        weather_agent,
        inputs_schema={"query": "${start.query}"}
    )

    workflow.add_workflow_comp(
        "calc_agent",
        calc_agent,
        inputs_schema={"query": "${start.query}"}
    )

    workflow.add_workflow_comp(
        "chat_agent",
        chat_agent,
        inputs_schema={"query": "${start.query}"}
    )

    workflow.add_workflow_comp(
        "info_agent",
        info_agent,
        inputs_schema={"query": "${start.query}"}
    )

    workflow.add_workflow_comp(
        "default_agent",
        default_agent,
        inputs_schema={"query": "${start.query}"}
    )

    # 6. 设置 End 组件
    # 注意：End 组件需要接收所有可能的 agent 输出
    workflow.set_end_comp(
        "end",
        end,
        inputs_schema={
            "agent_response": "${weather_agent.response}",  # 会根据实际路由的节点动态获取
            # 也可以使用合并方式，这里简化处理
        }
    )

    # 7. 设置连接
    workflow.add_connection("start", "intent")
    # intent -> 各个 agent 通过 add_branch 已设置

    # 所有 agent -> end
    workflow.add_connection("weather_agent", "end")
    workflow.add_connection("calc_agent", "end")
    workflow.add_connection("chat_agent", "end")
    workflow.add_connection("info_agent", "end")
    workflow.add_connection("default_agent", "end")

    return workflow


# ========== 使用 FuncCondition 的方式（更灵活）==========
def build_workflow_with_func_condition():
    """
    使用函数条件（FuncCondition）构建分支

    FuncCondition 更灵活，可以编写复杂的判断逻辑
    """
    from openjiuwen.core.workflow.components.condition.condition import Condition
    from openjiuwen.core.session import BaseSession
    from openjiuwen.core.graph.executable import Input, Output

    # 自定义 Condition 类
    class IntentCondition(Condition):
        """根据意图 ID 判断的条件类"""
        def __init__(self, target_id: int):
            super().__init__()
            self.target_id = target_id

        def invoke(self, inputs: Input, session: BaseSession) -> Output:
            # 从 session 中获取 intent 节点的输出
            intent_output = session.state().get_outputs("intent")
            if intent_output and "classification_id" in intent_output:
                return intent_output["classification_id"] == self.target_id
            return False

        def trace_info(self, session: BaseSession = None):
            return f"classification_id == {self.target_id}"

    # 创建工作流
    card = WorkflowCard(
        id="func_condition_workflow",
        name="函数条件分支工作流",
        version="1.0",
    )

    workflow = Workflow(card=card)

    # 创建组件
    start = Start()
    intent_detection = create_intent_detection_component()
    weather_agent = create_weather_agent()
    calc_agent = create_calculator_agent()
    chat_agent = create_chat_agent()
    end = End({"responseTemplate": "{{agent_response}}"})

    # 使用自定义 Condition 类添加分支
    intent_detection.add_branch(IntentCondition(1), "weather_agent")
    intent_detection.add_branch(IntentCondition(2), "calc_agent")
    intent_detection.add_branch(IntentCondition(3), "chat_agent")
    intent_detection.add_branch(IntentCondition(0), "end")  # 默认直接到 end

    # 添加组件
    workflow.set_start_comp("start", start, inputs_schema={"query": "${query}"})
    workflow.add_workflow_comp("intent", intent_detection, inputs_schema={"query": "${start.query}"})
    workflow.add_workflow_comp("weather_agent", weather_agent, inputs_schema={"query": "${start.query}"})
    workflow.add_workflow_comp("calc_agent", calc_agent, inputs_schema={"query": "${start.query}"})
    workflow.add_workflow_comp("chat_agent", chat_agent, inputs_schema={"query": "${start.query}"})
    workflow.set_end_comp("end", end, inputs_schema={"agent_response": "${weather_agent.response}"})

    # 连接
    workflow.add_connection("start", "intent")
    workflow.add_connection("weather_agent", "end")
    workflow.add_connection("calc_agent", "end")
    workflow.add_connection("chat_agent", "end")

    return workflow


# ========== 运行测试 ==========
async def run_multi_agent_demo():
    """运行多 Agent 工作流示例"""
    print("=" * 60)
    print("多 Agent 分支工作流示例")
    print("=" * 60)

    await Runner.start()
    print("[OK] Runner 已启动")

    workflow = build_multi_agent_workflow()
    print(f"[OK] 工作流已创建: {workflow.card.name}")

    # 测试不同意图的输入
    test_inputs = [
        {"query": "北京今天天气怎么样？"},      # 应路由到 weather_agent (id=1)
        {"query": "计算 123 + 456 等于多少？"}, # 应路由到 calc_agent (id=2)
        {"query": "你好，请介绍一下你自己"},    # 应路由到 chat_agent (id=3)
        {"query": "介绍一下人工智能的发展历程"}, # 应路由到 info_agent (id=4)
    ]

    for i, inputs in enumerate(test_inputs, 1):
        print("-" * 40)
        print(f"[测试 {i}] 输入: {inputs['query']}")

        try:
            session = create_workflow_session(session_id=f"test_session_{i}")
            result = await workflow.invoke(inputs, session=session)

            print(f"执行状态: {result.state}")
            if result.state.value == "COMPLETED":
                print(f"执行结果: {result.result}")
        except Exception as e:
            print(f"[ERROR] 执行出错: {e}")

    await Runner.stop()
    print("\n[OK] Runner 已停止")


# ========== 主入口 ==========
if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="多Agent分支工作流示例")
    parser.add_argument("--mode", "-m", choices=["1", "2"], default="1",
                        help="运行模式: 1=表达式条件, 2=函数条件")
    args = parser.parse_args()

    print(f"\n运行模式: {args.mode}")
    print("-" * 40)

    if args.mode == "1":
        asyncio.run(run_multi_agent_demo())
    else:
        print("函数条件方式（更灵活的自定义判断）")
        asyncio.run(run_multi_agent_demo())