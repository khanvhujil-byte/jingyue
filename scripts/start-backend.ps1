param([int]$Port=8089,[switch]$Build)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $project '.runtime/env.ps1')
$env:JINGYUE_PORT="$Port"
$java='C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin/java.exe'
if ($Build) {
    $env:JAVA_HOME=Split-Path (Split-Path $java -Parent) -Parent
    Push-Location (Join-Path $project 'backend')
    try { & 'C:/Program Files/JetBrains/IntelliJ IDEA 2025.1.1.1/plugins/maven/lib/maven3/bin/mvn.cmd' -B -s C:/Users/34397/.m2/settings.xml '-Dmaven.repo.local=C:/Users/34397/.m2/repository' package '-DskipTests'; if($LASTEXITCODE -ne 0){throw '编译失败'} } finally { Pop-Location }
}
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw "端口 $Port 已占用，不覆盖其他进程"}
$jar=Join-Path $project 'backend/target/jingyue-ticket-0.0.1-SNAPSHOT.jar'
$runningJar=Join-Path $project ".runtime/run-$Port.jar"
Copy-Item -LiteralPath $jar -Destination $runningJar -Force
$jar=$runningJar
$p=Start-Process -FilePath $java -ArgumentList @('-Xms256m','-Xmx768m','-jar',('"'+$jar+'"')) -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput (Join-Path $project ".runtime/backend-$Port.log") -RedirectStandardError (Join-Path $project ".runtime/backend-$Port.err.log") -PassThru
$p.Id | Set-Content -LiteralPath (Join-Path $project ".runtime/backend-$Port.pid")
"景约后端正在启动：127.0.0.1:$Port，进程 $($p.Id)"
