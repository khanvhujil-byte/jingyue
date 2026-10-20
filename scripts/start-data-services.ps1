param(
    [string]$MysqlBin='C:/Program Files/MySQL/MySQL Server 8.0/bin',
    [string]$RedisBin='C:/redis'
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$runtime=Join-Path $project '.runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$envFile=Join-Path $runtime 'env.ps1'
if (!(Test-Path -LiteralPath $envFile)) {
    $random=[Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes=New-Object byte[] 36
    $random.GetBytes($bytes); $password=[Convert]::ToBase64String($bytes)
    $random.GetBytes($bytes); $signing=[Convert]::ToBase64String($bytes)
    @(
        '$env:JINGYUE_DATABASE=''jingyue_ticket_dev''',
        ('$env:JINGYUE_MYSQL_PASSWORD='''+$password+''''),
        ('$env:JINGYUE_TICKET_SIGNING_KEY='''+$signing+''''),
        '$env:JINGYUE_DEVELOPMENT=''true''',
        '$env:JINGYUE_MOCK_PAYMENT=''true''',
        ('$env:JINGYUE_UPLOAD_DIR='''+(Join-Path $runtime 'uploads')+'''')
    ) | Set-Content -LiteralPath $envFile -Encoding UTF8
}
. $envFile
if ($env:JINGYUE_DATABASE -notmatch '^jingyue_[a-z0-9_]+$') { throw '独立库名必须以 jingyue_ 开头。' }
$mysqlRuntime=Join-Path $runtime 'mysql'
$mysqlData=Join-Path $mysqlRuntime 'data'
New-Item -ItemType Directory -Path $mysqlRuntime -Force | Out-Null
$mysqlConfig=Join-Path $mysqlRuntime 'my.ini'
@"
[mysqld]
basedir=$($MysqlBin.Replace('\','/').Replace('/bin',''))
datadir=$($mysqlData.Replace('\','/'))
port=3318
bind-address=127.0.0.1
mysqlx=0
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
max_connections=300
innodb_buffer_pool_size=256M
log_error=$((Join-Path $mysqlRuntime 'error.log').Replace('\','/'))
"@ | Set-Content -LiteralPath $mysqlConfig -Encoding ASCII
if (!(Test-Path -LiteralPath (Join-Path $mysqlData 'mysql'))) {
    & (Join-Path $MysqlBin 'mysqld.exe') "--defaults-file=$mysqlConfig" --initialize-insecure
    if ($LASTEXITCODE -ne 0) { throw '独立 MySQL 初始化失败，请查看 .runtime/mysql/error.log。' }
}
$mysqlPort=Get-NetTCPConnection -State Listen -LocalPort 3318 -ErrorAction SilentlyContinue
if (!$mysqlPort) {
    $mysqlProcess=Start-Process -FilePath (Join-Path $MysqlBin 'mysqld.exe') -ArgumentList ('"--defaults-file='+$mysqlConfig+'"') -WindowStyle Hidden -PassThru
    $mysqlProcess.Id | Set-Content -LiteralPath (Join-Path $mysqlRuntime 'process.pid')
} else {
    $recordedPidFile=Join-Path $mysqlRuntime 'process.pid'
    if (!(Test-Path -LiteralPath $recordedPidFile)) { throw '3318 已被其他 MySQL 使用，未修改该服务。' }
    $recordedPid=[int](Get-Content -LiteralPath $recordedPidFile)
    foreach ($listener in $mysqlPort) {
        $actual=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        if (($actual.ProcessId -ne $recordedPid -and $actual.ParentProcessId -ne $recordedPid) -or
            !$actual.CommandLine.Contains($mysqlConfig.Replace('\','/'))) {
            # Native MySQL may normalize the path separator in its child process.
            if (($actual.ProcessId -ne $recordedPid -and $actual.ParentProcessId -ne $recordedPid) -or
                !$actual.CommandLine.Replace('\','/').Contains($mysqlConfig.Replace('\','/'))) {
                throw '3318 进程与本项目记录不匹配，停止初始化。'
            }
        }
    }
}
$mysqlClient=Join-Path $mysqlRuntime 'client.ini'
@"
[client]
host=127.0.0.1
port=3318
user=jingyue
password=$env:JINGYUE_MYSQL_PASSWORD
default-character-set=utf8mb4
"@ | Set-Content -LiteralPath $mysqlClient -Encoding ASCII
$deadline=(Get-Date).AddSeconds(40)
while (!(Get-NetTCPConnection -State Listen -LocalPort 3318 -ErrorAction SilentlyContinue)) {
    if ((Get-Date) -gt $deadline) { throw '独立 MySQL 启动超时。' }
    Start-Sleep -Milliseconds 250
}
$initialized=Join-Path $mysqlRuntime 'initialized.txt'
if (!(Test-Path -LiteralPath $initialized)) {
    $sql="CREATE DATABASE IF NOT EXISTS ``$env:JINGYUE_DATABASE`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS 'jingyue'@'127.0.0.1' IDENTIFIED BY '$env:JINGYUE_MYSQL_PASSWORD'; GRANT ALL ON ``$env:JINGYUE_DATABASE``.* TO 'jingyue'@'127.0.0.1'; ALTER USER 'root'@'localhost' IDENTIFIED BY '$env:JINGYUE_MYSQL_PASSWORD';"
    $sql | & (Join-Path $MysqlBin 'mysql.exe') --host=127.0.0.1 --port=3318 --user=root --default-character-set=utf8mb4
    if ($LASTEXITCODE -ne 0) { throw '新库账户初始化失败。仅操作3318独立实例。' }
    'jingyue isolated mysql 3318' | Set-Content -LiteralPath $initialized
}
Get-ChildItem -LiteralPath (Join-Path $project 'backend/src/main/resources/db') -Filter '*.sql' | Sort-Object Name | ForEach-Object {
    Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 | & (Join-Path $MysqlBin 'mysql.exe') "--defaults-extra-file=$mysqlClient" $env:JINGYUE_DATABASE
    if ($LASTEXITCODE -ne 0) { throw "表结构初始化失败：$($_.Name)" }
}
$redisRuntime=Join-Path $runtime 'redis'
New-Item -ItemType Directory -Path $redisRuntime -Force | Out-Null
$redisConfig=Join-Path $redisRuntime 'redis.conf'
@"
bind 127.0.0.1
port 6398
dir .
appendonly yes
appendfsync everysec
save ""
maxmemory 512mb
maxmemory-policy noeviction
logfile redis.log
"@ | Set-Content -LiteralPath $redisConfig -Encoding ASCII
$redisPort=Get-NetTCPConnection -State Listen -LocalPort 6398 -ErrorAction SilentlyContinue
if (!$redisPort) {
    $redisProcess=Start-Process -FilePath (Join-Path $RedisBin 'redis-server.exe') -ArgumentList 'redis.conf' -WorkingDirectory $redisRuntime -WindowStyle Hidden -PassThru
    $redisProcess.Id | Set-Content -LiteralPath (Join-Path $redisRuntime 'process.pid')
} elseif (!(Test-Path -LiteralPath (Join-Path $redisRuntime 'process.pid')) -or
          $redisPort.OwningProcess -notcontains [int](Get-Content -LiteralPath (Join-Path $redisRuntime 'process.pid'))) {
    throw '6398 已被其他服务占用，未操作该服务。'
}
$redisDeadline=(Get-Date).AddSeconds(15)
while (!(Get-NetTCPConnection -State Listen -LocalPort 6398 -ErrorAction SilentlyContinue)) {
    if ((Get-Date) -gt $redisDeadline) { throw 'Redis 6398 启动失败，请检查 .runtime/redis/redis.log。' }
    Start-Sleep -Milliseconds 200
}
Write-Output '独立 MySQL 3318 / jingyue_ticket_dev 与独立 Redis 6398 已就绪。原服务未修改。'
