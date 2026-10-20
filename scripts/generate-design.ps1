param(
    [ValidateSet('home','detail','orders','admin','redeem','mobile')][string]$Page='home',
    [switch]$DryRun
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$names=@{home='01-home';detail='02-detail';orders='03-orders';admin='04-admin';redeem='05-redeem';mobile='06-mobile'}
$name=$names[$Page]
$prompt=Join-Path $project ('docs/design-prompts/'+$name+'.txt')
if (!(Test-Path -LiteralPath $prompt)) { throw "尚未准备此页面的设计提示词：$Page" }
$outputDir=Join-Path $project 'output/imagegen'
$existing=Get-ChildItem -LiteralPath $outputDir -Filter ($name+'.*') -ErrorAction SilentlyContinue
if ($DryRun) {
    Write-Output '参数检查完成；将使用用户指定的 sevnx-imagegen 技能，未发出 API 请求。'
    return
}
if ($existing) { throw '设计稿已存在。请先检查，避免误重复计费。' }
$skillScript=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.codex/skills/sevnx-imagegen/scripts/Generate-Image.ps1'
if (!(Test-Path -LiteralPath $skillScript)) { throw '缺少 sevnx-imagegen 技能，请检查安装位置。' }
$temporary=Join-Path $project ('.runtime/imagegen-page-'+$Page)
$saved=@(& $skillScript -Prompt (Get-Content -LiteralPath $prompt -Raw -Encoding UTF8) -OutputDirectory $temporary)
if ($saved.Count -ne 1 -or !(Test-Path -LiteralPath $saved[0] -PathType Leaf)) { throw '没有返回明确的图片路径，请核对结果后再操作，避免重复计费。' }
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$target=Join-Path $outputDir ($name+[IO.Path]::GetExtension($saved[0]))
Copy-Item -LiteralPath $saved[0] -Destination $target -ErrorAction Stop
Write-Output $target
