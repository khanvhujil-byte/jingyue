param([switch]$Build,[switch]$PrepareDemo)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'start-data-services.ps1')
if(!(Test-Path -LiteralPath (Join-Path $project '.runtime/test-classpath.txt'))){
    $env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot'
    Push-Location (Join-Path $project 'backend')
    try{& 'C:/Program Files/JetBrains/IntelliJ IDEA 2025.1.1.1/plugins/maven/lib/maven3/bin/mvn.cmd' -B org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath '-Dmdep.outputFile=../.runtime/test-classpath.txt' '-DincludeScope=test';if($LASTEXITCODE -ne 0){throw 'Kafka测试依赖准备失败'}}finally{Pop-Location}
}
if(!(Get-NetTCPConnection -State Listen -LocalPort 19092 -ErrorAction SilentlyContinue)){ & (Join-Path $PSScriptRoot 'start-local-kafka.ps1') }

$ready=Join-Path $project '.runtime/local-kafka/ready.txt'
$deadline=(Get-Date).AddSeconds(50)
while(!(Test-Path -LiteralPath $ready)){if((Get-Date)-gt $deadline){throw 'Kafka 启动未就绪，检查本项目日志'};Start-Sleep -Milliseconds 500}
$backendRunning=Get-NetTCPConnection -State Listen -LocalPort 8089 -ErrorAction SilentlyContinue
if($Build -and $backendRunning){& (Join-Path $PSScriptRoot 'stop-backend.ps1');$backendRunning=$null}
if(!$backendRunning){ & (Join-Path $PSScriptRoot 'start-backend.ps1') -Build:$Build }

$deadline=(Get-Date).AddSeconds(45)
while($true){try{$health=Invoke-RestMethod -Uri 'http://127.0.0.1:8089/api/v1/health' -TimeoutSec 2;if($health.name -eq '景约'){break}}catch{};if((Get-Date)-gt $deadline){throw '景约后端未就绪'};Start-Sleep -Milliseconds 500}
if($PrepareDemo){& 'C:/node-v20.18.0-win-x64/node.exe' (Join-Path $PSScriptRoot 'prepare-demo.mjs');if($LASTEXITCODE -ne 0){throw '演示预热发布未完成'}}
if(!(Get-NetTCPConnection -State Listen -LocalPort 5190 -ErrorAction SilentlyContinue)){
    $node='C:/node-v20.18.0-win-x64/node.exe';$web=Start-Process -FilePath $node -ArgumentList ('"'+(Join-Path $project 'frontend/server.mjs')+'"') -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput (Join-Path $project '.runtime/frontend.log') -RedirectStandardError (Join-Path $project '.runtime/frontend.err.log') -PassThru
    $web.Id | Set-Content -LiteralPath (Join-Path $project '.runtime/frontend.pid')
}
Write-Output '景约已启动：http://127.0.0.1:5190 。初次使用可指定 -PrepareDemo 显式预热并发布演示票种。'
