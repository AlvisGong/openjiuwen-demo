# coding: utf-8
"""
上下文引擎示例 - ContextEngine 核心功能演示

核心功能：
1. ContextEngine：Agent 层面的上下文管理器
2. ModelContext：具体对话上下文，管理消息列表
3. ContextWindow：送入模型的上下文窗口快照
4. Token 统计与窗口控制

演示场景：
1. 创建 ContextEngine 并配置
2. 创建 ModelContext 并添加消息
3. 获取 ContextWindow 用于模型调用
4. Token 统计和消息管理
5. 多上下文管理（同一会话多个子任务）
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

# ========== 导入核心组件 ==========
from openjiuwen.core.context_engine import (
    ContextEngine,
    ContextEngineConfig,
    ModelContext,
    ContextWindow,
    ContextStats,
)
from openjiuwen.core.session.agent import Session, create_agent_session
from openjiuwen.core.foundation.llm import (
    UserMessage,
    AssistantMessage,
    SystemMessage,
    ToolMessage,
    ModelConfig,
    BaseModelInfo,
    ModelRequestConfig,
    ModelClientConfig,
    Model,
)
from openjiuwen.core.single_agent import AgentCard


# ========== 1. 基础演示：ContextEngine 创建与配置 ==========
async def demo_basic_context_engine():
    """
    演示 ContextEngine 的基础用法

    展示：
    - ContextEngineConfig 配置
    - ContextEngine 创建
    - 与 Session 的配合
    """
    print("=" * 60)
    print("演示 1: ContextEngine 基础用法")
    print("=" * 60)

    # 1. 创建 ContextEngineConfig
    config = ContextEngineConfig(
        max_context_message_num=1000,       # 最大消息数限制
        default_window_message_num=50,      # 默认窗口消息数
        default_window_round_num=10,        # 默认对话轮数
        enable_kv_cache_release=False,      # 是否释放 KV 缓存
        enable_reload=False,                # 是否启用自动重载
    )
    print(f"[OK] ContextEngineConfig 创建成功")
    print(f"  - max_context_message_num: {config.max_context_message_num}")
    print(f"  - default_window_message_num: {config.default_window_message_num}")
    print(f"  - default_window_round_num: {config.default_window_round_num}")

    # 2. 创建 ContextEngine
    context_engine = ContextEngine(config=config)
    print(f"[OK] ContextEngine 创建成功")

    # 3. 创建 Agent Session
    agent_card = AgentCard(
        id="context_demo_agent",
        description="上下文引擎演示智能体",
    )
    session = create_agent_session(
        session_id="demo-session-001",
        card=agent_card
    )
    print(f"[OK] Session 创建成功")
    print(f"  - session_id: {session.get_session_id()}")

    # 4. 创建 ModelContext
    context = await context_engine.create_context(
        context_id="chat_context",
        session=session,
    )
    print(f"[OK] ModelContext 创建成功")
    print(f"  - context_id: {context.context_id()}")
    print(f"  - session_id: {context.session_id()}")

    # 5. 清理上下文
    context_engine.clear_context(
        session_id=session.get_session_id(),
        context_id=context.context_id(),
    )
    print(f"[OK] 上下文已清理")

    print("=" * 60)
    print("演示 1 完成")
    print("=" * 60)


# ========== 2. 消息管理演示 ==========
async def demo_message_management():
    """
    演示 ModelContext 的消息管理功能

    展示：
    - add_messages：添加消息
    - get_messages：获取消息
    - pop_messages：弹出消息
    - clear_messages：清空消息
    """
    print("\n" + "=" * 60)
    print("演示 2: ModelContext 消息管理")
    print("=" * 60)

    # 创建 ContextEngine 和 Session
    config = ContextEngineConfig(default_window_message_num=20)
    context_engine = ContextEngine(config=config)
    session = create_agent_session(session_id="msg-demo-session")

    # 创建 ModelContext
    context = await context_engine.create_context(
        context_id="message_demo",
        session=session,
    )

    # 1. 添加消息
    print("\n[步骤1] 添加消息到上下文")
    await context.add_messages(SystemMessage(content="你是一个智能助手"))
    await context.add_messages(UserMessage(content="你好，请介绍一下你自己"))
    await context.add_messages(AssistantMessage(content="你好！我是 AI 智能助手..."))
    await context.add_messages(UserMessage(content="你能做什么？"))
    await context.add_messages(AssistantMessage(content="我可以回答问题、提供建议..."))

    messages = context.get_messages()
    print(f"  当前消息数: {len(messages)}")
    for i, msg in enumerate(messages):
        role = msg.role if hasattr(msg, 'role') else 'unknown'
        content_preview = msg.content[:50] if hasattr(msg, 'content') else str(msg)[:50]
        print(f"  [{i}] {role}: {content_preview}...")

    # 2. 获取部分消息
    print("\n[步骤2] 获取最近 3 条消息")
    recent_messages = context.get_messages(size=3)
    print(f"  获取消息数: {len(recent_messages)}")
    for msg in recent_messages:
        role = msg.role if hasattr(msg, 'role') else 'unknown'
        print(f"  - {role}: {msg.content[:30]}...")

    # 3. 弹出消息
    print("\n[步骤3] 弹出最早的 1 条消息")
    popped = context.pop_messages(size=1, with_history=True)
    print(f"  弹出消息数: {len(popped)}")
    print(f"  弹出内容: {popped[0].content[:50]}...")
    remaining = context.get_messages()
    print(f"  剩余消息数: {len(remaining)}")

    # 4. 统计信息
    print("\n[步骤4] 上下文统计信息")
    stats = context.statistic()
    print(f"  - 总消息数: {stats.total_messages}")
    print(f"  - 系统消息: {stats.system_messages}")
    print(f"  - 用户消息: {stats.user_messages}")
    print(f"  - 助手消息: {stats.assistant_messages}")
    print(f"  - 对话轮数: {stats.total_dialogues}")

    # 5. 清空消息（clear_messages 是同步方法）
    print("\n[步骤5] 清空消息")
    context.clear_messages(with_history=True)
    messages_after_clear = context.get_messages()
    print(f"  清空后消息数: {len(messages_after_clear)}")

    # 清理（clear_context 是同步方法）
    context_engine.clear_context(session_id=session.get_session_id())

    print("=" * 60)
    print("演示 2 完成")
    print("=" * 60)


# ========== 3. ContextWindow 演示 ==========
async def demo_context_window():
    """
    演示 ContextWindow 的构建与使用

    展示：
    - get_context_window：构建上下文窗口
    - 系统消息、对话历史、工具定义的组织
    - Token 统计
    """
    print("\n" + "=" * 60)
    print("演示 3: ContextWindow 构建")
    print("=" * 60)

    # 创建 ContextEngine 和 Session
    config = ContextEngineConfig(
        default_window_message_num=30,
        default_window_round_num=5,
    )
    context_engine = ContextEngine(config=config)
    session = create_agent_session(session_id="window-demo-session")

    # 创建 ModelContext
    context = await context_engine.create_context(
        context_id="window_demo",
        session=session,
    )

    # 添加多轮对话消息
    print("\n[步骤1] 构建多轮对话历史")
    await context.add_messages(SystemMessage(content="你是一个专业的技术顾问"))

    # 第 1 轮对话
    await context.add_messages(UserMessage(content="什么是微服务架构？"))
    await context.add_messages(AssistantMessage(content="微服务架构是一种将应用拆分为多个小型服务的架构模式..."))

    # 第 2 轮对话
    await context.add_messages(UserMessage(content="微服务有什么优点？"))
    await context.add_messages(AssistantMessage(content="微服务的优点包括：独立部署、技术多样性、故障隔离..."))

    # 第 3 轮对话
    await context.add_messages(UserMessage(content="如何实现服务间通信？"))
    await context.add_messages(AssistantMessage(content="服务间通信主要有同步（REST/gRPC）和异步（消息队列）两种方式..."))

    messages = context.get_messages()
    print(f"  添加消息总数: {len(messages)}")

    # 构建 ContextWindow
    print("\n[步骤2] 构建 ContextWindow")

    # 不带参数的窗口
    window_default = await context.get_context_window()
    print(f"  默认窗口消息数: {len(window_default.get_messages())}")

    # 指定窗口大小
    window_sized = await context.get_context_window(window_size=4)
    print(f"  指定窗口大小(4)后消息数: {len(window_sized.get_messages())}")

    # 指定对话轮数
    window_rounds = await context.get_context_window(dialogue_round=2)
    print(f"  指定对话轮数(2)后消息数: {len(window_rounds.get_messages())}")

    # 添加系统消息和工具
    print("\n[步骤3] 构建带系统消息和工具的窗口")
    custom_system = [SystemMessage(content="你是一个代码评审专家")]
    from openjiuwen.core.foundation.tool import ToolInfo

    tools = [
        ToolInfo(
            name="python_review",
            description="Python 代码评审工具",
            parameters={"type": "object", "properties": {"code": {"type": "string"}}}
        )
    ]

    window_with_tools = await context.get_context_window(
        system_messages=custom_system,
        tools=tools,
    )
    print(f"  系统消息数: {len(window_with_tools.system_messages)}")
    print(f"  对话消息数: {len(window_with_tools.context_messages)}")
    print(f"  工具数: {len(window_with_tools.tools)}")

    # Token 统计
    print("\n[步骤4] Token 统计")
    stats = window_with_tools.statistic
    print(f"  - 总 Token 数: {stats.total_tokens}")
    print(f"  - 系统消息 Token: {stats.system_message_tokens}")
    print(f"  - 用户消息 Token: {stats.user_message_tokens}")
    print(f"  - 助手消息 Token: {stats.assistant_message_tokens}")
    print(f"  - 工具 Token: {stats.tool_tokens}")

    # 查看最终消息列表
    print("\n[步骤5] 最终送入模型的消息列表")
    final_messages = window_with_tools.get_messages()
    for i, msg in enumerate(final_messages):
        role = msg.role if hasattr(msg, 'role') else 'unknown'
        content_preview = msg.content[:40] if len(msg.content) > 40 else msg.content
        print(f"  [{i}] {role}: {content_preview}")

    # 清理（clear_context 是同步方法）
    context_engine.clear_context(session_id=session.get_session_id())

    print("=" * 60)
    print("演示 3 完成")
    print("=" * 60)


# ========== 4. 多上下文管理演示 ==========
async def demo_multi_context():
    """
    演示同一会话中的多上下文管理

    展示：
    - 同一 Session 创建多个 Context
    - get_context：获取已有上下文
    - save_contexts：保存上下文状态
    - clear_context：清理上下文
    """
    print("\n" + "=" * 60)
    print("演示 4: 多上下文管理")
    print("=" * 60)

    # 创建 ContextEngine
    config = ContextEngineConfig()
    context_engine = ContextEngine(config=config)

    # 创建一个 Session
    session = create_agent_session(session_id="multi-context-session")

    # 1. 创建多个上下文
    print("\n[步骤1] 创建多个上下文")

    # 上下文 1: 主对话
    main_context = await context_engine.create_context(
        context_id="main_chat",
        session=session,
    )
    await main_context.add_messages(UserMessage(content="主对话：你好"))
    await main_context.add_messages(AssistantMessage(content="主对话：你好！"))

    # 上下文 2: 子任务 - 代码评审
    review_context = await context_engine.create_context(
        context_id="code_review",
        session=session,
    )
    await review_context.add_messages(SystemMessage(content="代码评审模式"))
    await review_context.add_messages(UserMessage(content="请评审这段代码"))

    # 上下文 3: 子任务 - 文档生成
    doc_context = await context_engine.create_context(
        context_id="doc_gen",
        session=session,
    )
    await doc_context.add_messages(SystemMessage(content="文档生成模式"))
    await doc_context.add_messages(UserMessage(content="请生成 API 文档"))

    print(f"  创建上下文数: 3")
    print(f"  - main_chat: 消息数 {len(main_context.get_messages())}")
    print(f"  - code_review: 消息数 {len(review_context.get_messages())}")
    print(f"  - doc_gen: 消息数 {len(doc_context.get_messages())}")

    # 2. 获取已存在的上下文
    print("\n[步骤2] 获取已存在的上下文")
    existing_context = context_engine.get_context(
        context_id="code_review",
        session_id=session.get_session_id(),
    )
    if existing_context:
        print(f"  成功获取上下文: {existing_context.context_id()}")
        print(f"  消息数: {len(existing_context.get_messages())}")

    # 3. 复用上下文（再次调用 create_context 会返回已有上下文）
    print("\n[步骤3] 复用已存在的上下文")
    reused_context = await context_engine.create_context(
        context_id="main_chat",
        session=session,
    )
    print(f"  复用上下文: {reused_context.context_id()}")
    print(f"  消息数（应该与之前相同）: {len(reused_context.get_messages())}")

    # 4. 保存上下文状态
    print("\n[步骤4] 保存上下文状态到 Session")
    await context_engine.save_contexts(session)
    print(f"  上下文状态已保存")

    # 查看 Session 状态
    saved_state = session.get_state("context")
    if saved_state:
        print(f"  保存的上下文 IDs: {list(saved_state.keys())}")

    # 5. 清理单个上下文（clear_context 是同步方法）
    print("\n[步骤5] 清理单个上下文 (code_review)")
    context_engine.clear_context(
        session_id=session.get_session_id(),
        context_id="code_review",
    )
    remaining = context_engine.get_context(
        context_id="code_review",
        session_id=session.get_session_id(),
    )
    print(f"  code_review 上下文: {'已删除' if remaining is None else '仍存在'}")

    # 检查其他上下文
    main_after = context_engine.get_context(
        context_id="main_chat",
        session_id=session.get_session_id(),
    )
    doc_after = context_engine.get_context(
        context_id="doc_gen",
        session_id=session.get_session_id(),
    )
    print(f"  main_chat 上下文: {'存在' if main_after else '已删除'}")
    print(f"  doc_gen 上下文: {'存在' if doc_after else '已删除'}")

    # 6. 清理整个 Session 的所有上下文
    print("\n[步骤6] 清理整个 Session 的所有上下文")
    context_engine.clear_context(session_id=session.get_session_id())
    print(f"  所有上下文已清理")

    # 清理后确认
    all_clear = context_engine.get_context(
        context_id="main_chat",
        session_id=session.get_session_id(),
    )
    print(f"  确认 main_chat: {'已清理' if all_clear is None else '仍存在'}")

    print("=" * 60)
    print("演示 4 完成")
    print("=" * 60)


# ========== 5. 与 LLM 模型配合演示 ==========
async def demo_with_llm():
    """
    演示 ContextEngine 与 LLM 模型的配合使用

    展示：
    - 构建 ContextWindow 用于模型调用
    - 多轮对话管理
    - 实际 LLM 调用
    """
    print("\n" + "=" * 60)
    print("演示 5: 与 LLM 模型配合")
    print("=" * 60)

    # 模型配置
    model_config = ModelRequestConfig(
        model=MODEL_NAME,
        temperature=0.7,
        top_p=0.9,
    )
    model_client = ModelClientConfig(
        client_provider=MODEL_PROVIDER,
        api_base=API_BASE,
        api_key=API_KEY,
        verify_ssl=False,
        timeout=60,
    )

    # 创建 Model 实例（注意参数名：model_client_config 和 model_config）
    model = Model(model_client_config=model_client, model_config=model_config)
    print(f"[OK] Model 创建成功: {MODEL_NAME}")

    # 创建 ContextEngine
    config = ContextEngineConfig(default_window_round_num=3)
    context_engine = ContextEngine(config=config)
    session = create_agent_session(session_id="llm-demo-session")

    # 创建上下文
    context = await context_engine.create_context(
        context_id="llm_chat",
        session=session,
    )

    # 添加系统消息
    await context.add_messages(SystemMessage(content="你是一个友好的 AI 助手，请用简洁的语言回答问题。"))

    # 第一轮对话
    print("\n[第 1 轮对话]")
    user_input_1 = "请用一句话介绍 Python 语言"
    print(f"用户: {user_input_1}")

    await context.add_messages(UserMessage(content=user_input_1))
    window_1 = await context.get_context_window()
    messages_1 = window_1.get_messages()

    # 调用 LLM
    response_1 = await model.invoke(messages=messages_1)
    assistant_reply_1 = response_1.content if hasattr(response_1, 'content') else str(response_1)
    print(f"助手: {assistant_reply_1}")

    await context.add_messages(AssistantMessage(content=assistant_reply_1))

    # 第二轮对话
    print("\n[第 2 轮对话]")
    user_input_2 = "Python 有哪些主要应用领域？"
    print(f"用户: {user_input_2}")

    await context.add_messages(UserMessage(content=user_input_2))
    window_2 = await context.get_context_window()
    messages_2 = window_2.get_messages()

    # 显示当前上下文窗口
    print(f"  上下文窗口消息数: {len(messages_2)}")

    # 调用 LLM（此时包含历史对话）
    response_2 = await model.invoke(messages=messages_2)
    assistant_reply_2 = response_2.content if hasattr(response_2, 'content') else str(response_2)
    print(f"助手: {assistant_reply_2}")

    await context.add_messages(AssistantMessage(content=assistant_reply_2))

    # 第三轮对话
    print("\n[第 3 轮对话]")
    user_input_3 = "谢谢你的介绍"
    print(f"用户: {user_input_3}")

    await context.add_messages(UserMessage(content=user_input_3))
    window_3 = await context.get_context_window(dialogue_round=2)  # 只保留最近 2 轮
    messages_3 = window_3.get_messages()

    print(f"  使用对话轮数限制(2)，消息数: {len(messages_3)}")

    response_3 = await model.invoke(messages=messages_3)
    assistant_reply_3 = response_3.content if hasattr(response_3, 'content') else str(response_3)
    print(f"助手: {assistant_reply_3}")

    await context.add_messages(AssistantMessage(content=assistant_reply_3))

    # 最终统计
    print("\n[对话统计]")
    final_stats = context.statistic()
    print(f"  - 总消息数: {final_stats.total_messages}")
    print(f"  - 总对话轮数: {final_stats.total_dialogues}")
    print(f"  - 用户消息: {final_stats.user_messages}")
    print(f"  - 助手消息: {final_stats.assistant_messages}")

    # 保存并清理
    await context_engine.save_contexts(session)
    context_engine.clear_context(session_id=session.get_session_id())

    print("=" * 60)
    print("演示 5 完成")
    print("=" * 60)


# ========== 主入口 ==========
async def main():
    """运行所有演示"""
    print("\n" + "=" * 60)
    print("上下文引擎 (ContextEngine) 功能演示")
    print("=" * 60)

    # 演示 1: 基础用法
    await demo_basic_context_engine()

    # 演示 2: 消息管理
    await demo_message_management()

    # 演示 3: ContextWindow 构建
    await demo_context_window()

    # 演示 4: 多上下文管理
    await demo_multi_context()

    # 演示 5: 与 LLM 配合
    await demo_with_llm()

    print("\n" + "=" * 60)
    print("所有演示完成！")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())