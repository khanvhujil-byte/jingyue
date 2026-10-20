param([string]$ProjectRoot=(Split-Path (Split-Path $PSScriptRoot -Parent) -Parent))
$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path -LiteralPath $ProjectRoot).Path
$runtime=Join-Path $ProjectRoot '.runtime\local-kafka'
if (!(Test-Path -LiteralPath (Join-Path $runtime 'ready.txt'))) { throw '没有发现由本工具启动且已就绪的本地 Kafka。' }
[System.IO.File]::WriteAllText((Join-Path $runtime 'stop.signal'),'stop')
Write-Output '已请求本地 Kafka 正常关闭。就绪文件消失表示关闭完成，持久化数据保留。'
