param([string]$ProjectRoot=(Split-Path $PSScriptRoot -Parent),[switch]$Crash)
$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path -LiteralPath $ProjectRoot).Path
$runtime=Join-Path $ProjectRoot '.runtime\local-kafka'
if (!(Test-Path -LiteralPath (Join-Path $runtime 'ready.txt'))) { throw '没有发现由本工具启动且已就绪的本地 Kafka。' }
if($Crash){
    $brokerProcessId=[int](Get-Content -LiteralPath (Join-Path $runtime 'process.pid'))
    # Win32_Process command-line queries can be denied in restricted Windows sessions.
    # The PID is written by start-local-kafka.ps1; validate the live executable instead.
    $actual=Get-Process -Id $brokerProcessId -ErrorAction SilentlyContinue
    $javaPath=(Join-Path ${env:ProgramFiles} 'Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe')
    if(!$actual -or ($actual.Path -and ((Resolve-Path $actual.Path).Path -ne (Resolve-Path $javaPath).Path))){throw '进程不属于本项目，拒绝终止'}
    Stop-Process -Id $brokerProcessId
    Write-Output '仅终止了本项目开发 Kafka；这是故障注入，持久数据和待发送证据保留。'
    exit
}
[System.IO.File]::WriteAllText((Join-Path $runtime 'stop.signal'),'stop')
Write-Output '已请求本地 Kafka 正常关闭。就绪文件消失表示关闭完成，持久化数据保留。'
