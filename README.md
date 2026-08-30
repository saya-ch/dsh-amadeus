# Amadeus: Whale

> DSH 高权限 Galgame Mode `amadeus` + 独立移动端 APP

躺在床上，用手机与住在电脑里的鲸鱼娘对话。基于 `dsh-mobile` 的安全连接层，复用其 TLS/配对/远程通道，在手机上以 Galgame 的形式呈现 Agent 的思考与工具调用。

- **Mode:** `amadeus` (`danger-full-access`, 自动审批)
- **协议:** 每段公开文本末尾 `[[AMW:{...}]]` 驱动立绘/音效
- **APP:** 选档式会话管理，全屏立绘 + 对话框

详见 `docs/understanding.md`。

## 目录

- `docs/` — 理解与设计文档
- `src/` — Host 插件 (Fork dsh-mobile gateway + amadeus Mode)
- `app/` — Android APP (Fork dsh-mobile/apps/mobile)

## 快速开始 (待实现)

```sh
# 安装插件
dsh plugin --profile web add dsh-amadeus-whale

# 启动 DSH 后，手机端通过 cpolar/tailscale/frp 配对
# 新建 amadeus 会话，开始 Galgame 对话
```
