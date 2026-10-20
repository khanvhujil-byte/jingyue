param([int]$Port=8089)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$pidFile=Join-Path $project ".runtime/backend-$Port.pid"
if (!(Test-Path -LiteralPath $pidFile)) { throw '无本项目进程记录，未停止任何进程' }
$backendProcessId=[int](Get-Content -LiteralPath $pidFile)
$actual=Get-Process -Id $backendProcessId -ErrorAction SilentlyContinue
if($actual){
    $expectedJava=(Resolve-Path 'C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin/java.exe').Path
    if($actual.Path -and ((Resolve-Path $actual.Path).Path -ne $expectedJava)){throw '进程命令不属于本项目，未停止'}
    Stop-Process -Id $backendProcessId
}
"已停止景约后端 $Port，持久数据保留；未提交事务和未确认消息会恢复重试。"
