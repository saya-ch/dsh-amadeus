# 贡献指南

> **这是个 Demo，欢迎一起把它做得更好 🐋**

## 最快的路径

1. **Fork** 本仓库
2. 开一个分支（`feat/xxx` · `fix/xxx`）
3. 改点东西
4. 提 **Pull Request** —— 无需事先请求权限

> 仓库已开放：**public** · `main` 无分支保护 · 无强制 review · auto-merge 可用 · 合并后自动删分支。
> 有写权限的协作者可直接推分支；其他人走 Fork + PR 即可。

## 先聊再动手

大改动（协议、网关结构、素材替换）建议先开 [Issue](https://github.com/saya-ch/dsh-amadeus/issues) 说一声，
避免方向不同白做一遍。

## 改哪里

| 想改 | 位置 | 怎么验证 |
|:---|:---|:---|
| 鲸鱼娘人格 / 演出节奏 | `presets/amadeus/` | 跑一次真实会话 |
| Host 插件逻辑 | `src/` | `npx vitest run` + `npm run build` |
| App 演出层 | `app/amadeus-android/` | `./gradlew :app:assembleDebug` |
| 立绘 / 背景 / 装饰 | `app/amadeus-android/app/src/main/assets/amadeus/` | 同步 `SOURCES.md` |
| 文档 | `docs/` · `README.md` | — |

## 提交前

```bash
npx vitest run     # Host 测试应保持全绿（12 文件 / 99 tests）
npm run build      # 构建 Host 插件 lib/index.mjs + web 控制面板 lib/client.js
```

App 侧：

```bash
cd app/amadeus-android && ./gradlew :app:assembleDebug
```

## ⚠️ 素材红线（重要）

美术素材**不是本仓库原创**，取自 DSH 社区开源项目，许可 **CC BY-NC-SA 4.0**：

| 条款 | 要求 |
|:---|:---|
| **署名 BY** | 新增 / 替换素材必须在 `assets/amadeus/SOURCES.md` 补来源与署名链 |
| **非商用 NC** | 不得用于商业用途 |
| **相同方式共享 SA** | 含素材的衍生作品须以 CC BY-NC-SA 4.0 发布 |

> **代码是 Apache-2.0，素材是 CC BY-NC-SA** —— 两套协议分离，别混用。
> 字体（马路口圆体）为 SIL OFL 1.1，许可文件在 `assets/licenses/`。

## 代码风格

跟随现有文件即可：

- TypeScript 严格模式，不引入多余依赖
- 注释写「**为什么**」而不是「是什么」
- 新增非平凡逻辑请附一个最小可运行的测试

---

有疑问直接开 Issue，或先提一个草稿 PR 讨论。
