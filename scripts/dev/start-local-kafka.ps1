param(
    [string]$ProjectRoot=(Split-Path (Split-Path $PSScriptRoot -Parent) -Parent),
    [string]$Topic='seckill-voucher-order',
    [int]$Port=9092,
    [string]$JavaBin='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin'
)
$ErrorActionPreference='Stop'
$ProjectRoot=(Resolve-Path -LiteralPath $ProjectRoot).Path
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "端口 $Port 已被占用，请确认现有服务，禁止覆盖启动。" }
$report=Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'target\surefire-reports') -Filter 'TEST-*.xml' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$report) { throw '请先运行项目测试，以生成已解析依赖的测试类路径。' }
[xml]$xml=Get-Content -LiteralPath $report.FullName -Raw
$classpath=($xml.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value
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
Write-Output "本地单节点 Kafka 已启动，进程 $($process.Id)，请等待 $ready 就绪；数据保存在 $runtime，禁止将此配置当作生产高可用方案。"
