param(
    [Parameter(Mandatory=$true)][ValidateSet('setup','install-schema','reset','audit','cleanup','kafka-lag','bind-kafka')][string]$Action,
    [Parameter(Mandatory=$true)][string]$FixtureDirectory,
    [string]$Project=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$ApplicationJar,
    [string]$JdkPath=$env:JAVA_HOME,
    [string[]]$Extra=@()
)
$ErrorActionPreference='Stop'
$prepared=& (Join-Path $PSScriptRoot 'prepare.ps1') -Project $Project -ApplicationJar $ApplicationJar -JdkPath $JdkPath
& $prepared.JavaExecutable -cp $prepared.Classpath HighConcurrencyFixture $Action $Project $FixtureDirectory @Extra
if($LASTEXITCODE -ne 0){throw "Fixture action failed: $Action"}
