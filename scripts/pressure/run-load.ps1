param(
    [Parameter(Mandatory=$true)][string]$FixtureDirectory,
    [ValidateSet('Both','Likes','Seckill')][string]$Kind='Both',
    [string]$Project=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$JdkPath=$env:JAVA_HOME,
    [string]$NodeExecutable='node',
    [string]$Base='http://127.0.0.1:8081/api',
    [ValidateRange(1,6000)][int]$Clients=3500,
    [ValidateRange(1,20000)][int]$Rate=3500,
    [ValidateRange(1,120)][int]$Seconds=30,
    [ValidateRange(100,20000)][int]$TimeoutMilliseconds=8000,
    [ValidateRange(1,600)][int]$AuditWaitSeconds=180,
    [string]$OutputDirectory,
    [switch]$SkipWarmup
)
$ErrorActionPreference='Stop'
$suiteArguments=@((Join-Path $PSScriptRoot 'http-suite.js'),'--fixture',$FixtureDirectory,'--project',$Project,'--runner',$PSScriptRoot,'--base',$Base,'--clients',$Clients.ToString(),'--rate',$Rate.ToString(),'--seconds',$Seconds.ToString(),'--timeout',$TimeoutMilliseconds.ToString(),'--audit-wait',$AuditWaitSeconds.ToString(),'--kind',$Kind.ToLowerInvariant())
if($JdkPath){$suiteArguments+=@('--jdk',$JdkPath)}
if($OutputDirectory){$suiteArguments+=@('--output',$OutputDirectory)}
if($SkipWarmup){$suiteArguments+=@('--warmup','false')}
& $NodeExecutable @suiteArguments
if($LASTEXITCODE -ne 0){throw 'Shared-connection HTTP pressure suite failed; original reports are preserved.'}
