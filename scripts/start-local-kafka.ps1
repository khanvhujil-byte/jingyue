param(
    [string]$ProjectRoot=(Split-Path $PSScriptRoot -Parent),
    [string]$Topic='jingyue-ticket-orders-v1',
    [int]$Port=19092,
    [int]$ReadyTimeoutSeconds=120,
    [string]$JavaBin='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin'
)
$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path -LiteralPath $ProjectRoot).Path
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "端口 $Port 已被占用，请确认现有服务，禁止覆盖启动。" }
$report=Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'backend\target\surefire-reports') -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
$classpathFile=Join-Path $ProjectRoot '.runtime/test-classpath.txt'
if(Test-Path -LiteralPath $classpathFile){$classpath=(Get-Content -LiteralPath $classpathFile -Raw).Trim()}
elseif($report){[xml]$xml=Get-Content -LiteralPath $report.FullName -Raw;$classpath=($xml.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value}
else { throw '请先在 backend 执行 mvn dependency:build-classpath -Dmdep.outputFile=../.runtime/test-classpath.txt -DincludeScope=test 。无需先启动业务或运行集成测试。' }
if (!$classpath) { throw '测试报告缺少 java.class.path。' }
$runtime=Join-Path $ProjectRoot '.runtime\local-kafka'
$classes=Join-Path $runtime 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& (Join-Path $JavaBin 'javac.exe') -encoding UTF-8 -cp $classpath -d $classes (Join-Path $PSScriptRoot 'LocalKafkaMain.java')
if ($LASTEXITCODE -ne 0) { throw '本地 Kafka 启动程序编译失败。' }
$ready=Join-Path $runtime 'ready.txt'
if (Test-Path -LiteralPath $ready) { Remove-Item -LiteralPath $ready }
$stopSignal=Join-Path $runtime 'stop.signal'
if (Test-Path -LiteralPath $stopSignal) { Remove-Item -LiteralPath $stopSignal }
$process=Start-Process -FilePath (Join-Path $JavaBin 'java.exe') -ArgumentList @('-Xms256m','-Xmx768m','-cp',('"'+$classes+';'+$classpath+'"'),'LocalKafkaMain',('"'+$runtime+'"'),$Topic,$Port) -WorkingDirectory $ProjectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'stdout.log') -RedirectStandardError (Join-Path $runtime 'stderr.log') -PassThru
$process.Id | Set-Content -LiteralPath (Join-Path $runtime 'process.pid')
$deadline=(Get-Date).AddSeconds($ReadyTimeoutSeconds)
while(!(Test-Path -LiteralPath $ready)){
    if($process.HasExited){throw '本地 Kafka 启动退出，请检查 .runtime/local-kafka/stderr.log；未删除持久数据。'}
    if((Get-Date)-gt $deadline){throw '本地 Kafka 尚未就绪，请检查日志；未宣告启动成功。'}
    Start-Sleep -Milliseconds 250
}
Write-Output "本地单节点 Kafka 已就绪，进程 $($process.Id)，地址 127.0.0.1:$Port；持久数据位于 $runtime。"
