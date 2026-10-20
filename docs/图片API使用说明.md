# 独立图片 API

本机生图使用单独的 `IMAGE_API_KEY`。按用户新增的 AGENTS.md 指令，默认通过 `C:/Users/34397/.codex/skills/sevnx-imagegen/SKILL.md` 及其 PowerShell 脚本调用。`OPENAI_API_KEY` 是另一份配置，不能据此推断生图平台和接口地址。

技能已配置用户选择的 `https://www.sevnx.lol/v1/images/generations` 和 `gpt-image-2.5`。项目脚本委托该技能生成，不另行覆盖其模型或平台配置。`.runtime/imagegen-provider.json` 仅保留此前调用的非密钥配置记录。

密钥只由技能从本机环境读取，不写入配置、脚本、提示词或提交包。项目脚本不修改 Codex 全局模型配置。

```powershell
# 仅检查参数，不调用 API
./scripts/generate-design.ps1 -Page home -DryRun

# 生成首页；实际调用会产生平台费用，已有图片时拒绝重复生成
./scripts/generate-design.ps1 -Page home
```

图片位于 `output/imagegen/`，原始提示词位于 `docs/design-prompts/`。设计稿需经过人工与浏览器对照，不能直接作为页面图片代替 Vue 界面。

2026-10-20：已使用该平台、模型及专用密钥成功生成六张页面设计稿，并逐张读取检查。最初调用 OpenAI 官方地址得到的地区 403，与用户这套兼容平台的可用性不同；切换正确平台后已成功。
