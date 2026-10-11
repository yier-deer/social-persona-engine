# 真实模型与 NapCat/QQ 演示

管理面板默认使用 `http://127.0.0.1:5173/`，Python 默认使用 `8000`。端口被占用时，启动器在 `5173–5176`、`8000–8003` 中选择空闲端口，并将 Python 地址传给 Java。Java 固定使用 `8080`，NapCat 反向 WebSocket 使用 `ws://127.0.0.1:8080/ws/qq`。以启动器实际输出为准。

## 环境准备

安装 JDK 21、Maven、Python 3.12，以及 Node.js 20.19+ 或 22.12+。在仓库根目录执行：

```powershell
python -m venv python-core/.venv
python-core/.venv/Scripts/python.exe -m pip install -r python-core/requirements-dev.txt
Push-Location frontend
npm ci
Pop-Location
python-core/.venv/Scripts/python.exe python-core/startup_check.py
```

最后一条命令检查并下载本地嵌入模型 `BAAI/bge-small-zh-v1.5`。模型、API Key、数据库和日志均保留在本地，不提交到仓库。

## 启动与配置

双击 `start.bat`，等待 Python、Java、前端健康检查通过后打开管理面板。重复启动会复用本项目已经运行的服务；启动失败时仅清理本次新启动的进程。`stop.bat` 只停止状态文件中能核实归属的项目进程。

```powershell
powershell -File scripts/services.ps1 -Action status
powershell -File scripts/services.ps1 -Action url
```

在面板中保存聊天模型和主人 QQ。DeepSeek、OpenAI 与自定义模型使用 OpenAI 兼容接口，Anthropic 使用原生 Messages API。自定义模型必须填写完整接口地址和模型名。图片模型可跳过。

API Key 存在被 Git 忽略的 `java-manager/data/system_config.json`。旧配置的 `*Encrypted` 字段使用 Base64 编码，不能视作加密；读取配置的接口仅返回密钥是否存在。

在自行安装的 NapCat 中登录机器人 QQ，添加 OneBot V11 **反向 WebSocket 客户端**，地址设为 `ws://127.0.0.1:8080/ws/qq`。AI QQ 是机器人账号，主人 QQ 是实际交互的个人账号。健康面板应显示 NapCat 已连接，并列出机器人 ID。

## 演示步骤

1. 完成模型和主人 QQ 配置；失败请求会显示错误并停留在当前步骤。
2. 通过牵线人访谈或手动表单创建角色，在详情页确认 AI QQ 与主人 QQ。
3. 用主人 QQ 私聊该 AI QQ，查看回复及关系状态、内心活动和消息记录。
4. 自动事件调度默认开启。可在详情页触发测试事件，观察主动联系和事件状态；暂停角色后停止新的推理与发送。

启动器不会主动发送测试 QQ 消息。健康接口中的模型“已配置”表示存在配置；真实模型连通性通过面板连接测试确认。OneBot 当前只能确认 WebSocket 发送成功，实际收到 QQ 消息需要在账号侧核对。

## 验证与排查

```powershell
mvn -f java-manager/pom.xml verify -B --no-transfer-progress
Push-Location python-core
.venv/Scripts/python.exe -m pytest -q
Pop-Location
npm --prefix frontend test
npm --prefix frontend run build
powershell -File scripts/test-lifecycle.ps1
```

Python 测试从 `python-core` 目录运行，其 `pytest.ini` 只收集离线回归。不要直接从仓库根目录执行默认收集，旧集成脚本可能在导入时访问网络。

服务失败时查看 `.runtime/logs` 和 `-Action status`。若 `services.json` 损坏，启动器会拒绝继续；先核对实际进程再修复状态。NapCat 显示未连接时，检查登录状态、反向客户端类型和 `/ws/qq` 地址。
