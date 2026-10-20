param(
    [string]$ProjectRoot=(Split-Path $PSScriptRoot -Parent),
    [string]$JavaExecutable='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe',
    [string[]]$Extra=@()
)
$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path -LiteralPath $ProjectRoot).Path
$jar=Join-Path $ProjectRoot 'target\hm-dianping-0.0.1-SNAPSHOT.jar'
if(!(Test-Path -LiteralPath $jar)){throw '请先执行 Maven package -DskipTests 构建项目。'}
if(Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue){throw '8081 已占用，请先确认正在运行的后端。'}
Write-Output '请确认已执行 db/blog_like_async.sql，Redis 开启持久化且使用 noeviction，Kafka 已就绪。'
Write-Output '所有写入实例需统一使用 high-concurrency 配置；切换模式必须先停写、排空事件并完成对账。'
Push-Location -LiteralPath $ProjectRoot
try { & $JavaExecutable '-Xms512m' '-Xmx2g' '-jar' $jar '--spring.profiles.active=high-concurrency' @Extra }
finally { Pop-Location }
