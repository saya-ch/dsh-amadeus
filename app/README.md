# Amadeus Whale APP

Fork 自 `dsh-mobile/apps/mobile`，复用其 TLS/配对/远程通道，仅新增 Galgame 层。

## 新增

- **选档页**: `GET /amadeus/sessions?mode=amadeus` 列表 + `POST /amadeus/sessions` 新建
- **剧场页**: 全屏立绘 + 底部对话框，解析 `[[AMW:{...}]]` 标签驱动立绘/音效/打字机

## 复用

- `android/` 完整复用 dsh-mobile 的 WebView 薄壳、证书固定、配对流程
- 仅在 WebView 内的前端路由增加 `/amadeus` 前缀的选档与剧场

## 资源

- `assets/sprites/` — 鲸鱼娘差分 (base/shy/think/tool/wag/gray/talk.webp)
- `assets/backgrounds/` — 月夜礁石等背景
- `assets/ui/` — 对话框等 UI
