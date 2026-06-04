1. 配置模型 example/apiconfig.json
2. 启动服务(mock 两个低码工作流)
workflaw_a2a/run_server.sh 8080
workflaw_a2a/run_server.sh 8081
3. 启动a2a服务
deep_agent/a2a_server.sh

4. 启动a2a客户端，进行交互
deep_agent/a2a_cli.py 
进入交互页面
第一轮输入： 我想给李四转账，先查询余额，如果大于100元，则转100元，如果小于100元，则转50元。
第二论输入： 账号是 123456