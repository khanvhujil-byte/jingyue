param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Test-Preview([int]$PreviewPort) {
    try {
        $page = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$PreviewPort/" -TimeoutSec 2
        return $page.StatusCode -eq 200 -and $page.Content -match 'theme-color" content="#22574c"' -and $page.Content -match './src/app.js'
    } catch { return $false }
}

try {
    foreach ($previewPort in 5188..5198) {
        if (Test-Preview $previewPort) {
            $previewUrl = "http://127.0.0.1:$previewPort/"
            Write-Host $previewUrl
            if (-not $CheckOnly) { Start-Process $previewUrl }
            exit 0
        }
        $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $previewPort)
        try { $probe.Start() } catch { continue } finally { $probe.Stop() }
        $nodeCommand = Get-Command node -ErrorAction SilentlyContinue
        if (-not $nodeCommand) { throw 'Please install Node.js 20 or later, then run this file again.' }
        $runtimeDirectory = Join-Path $projectRoot '.runtime'
        New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
        $env:PORT = [string]$previewPort
        $process = Start-Process -FilePath $nodeCommand.Source -ArgumentList 'scripts/server.mjs' -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDirectory 'preview.stdout.log') -RedirectStandardError (Join-Path $runtimeDirectory 'preview.stderr.log')
        foreach ($attempt in 1..40) {
            if (Test-Preview $previewPort) {
                $previewUrl = "http://127.0.0.1:$previewPort/"
                Write-Host $previewUrl
                if (-not $CheckOnly) { Start-Process $previewUrl }
                exit 0
            }
            if ($process.HasExited) { throw "Preview exited. See $runtimeDirectory/preview.stderr.log" }
            Start-Sleep -Milliseconds 250
        }
        throw "Preview did not start. See $runtimeDirectory/preview.stderr.log"
    }
    throw 'Ports 5188-5198 are busy. Stop an unused preview and try again.'
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
