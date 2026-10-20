param(
    [string]$Project=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$ApplicationJar,
    [string]$JdkPath=$env:JAVA_HOME
)
$ErrorActionPreference='Stop'
if(!$ApplicationJar){
    $application=Get-ChildItem -LiteralPath (Join-Path $Project 'target') -Filter '*.jar' -File | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if(!$application){throw 'Package the application first; target must contain a Spring Boot jar.'}
    $ApplicationJar=$application.FullName
}
$application=Get-Item -LiteralPath $ApplicationJar
if($JdkPath){$javaCompiler=Join-Path $JdkPath 'bin\javac.exe'; $javaRuntime=Join-Path $JdkPath 'bin\java.exe'}
else {
    $compilerCommand=Get-Command javac.exe -ErrorAction SilentlyContinue
    if(!$compilerCommand){throw 'Set JAVA_HOME or pass -JdkPath for an installed JDK 17+.'}
    $javaCompiler=$compilerCommand.Source
    $javaRuntime=Join-Path (Split-Path -Parent $javaCompiler) 'java.exe'
}
if(!(Test-Path -LiteralPath $javaCompiler)||!(Test-Path -LiteralPath $javaRuntime)){throw 'JDK compiler/runtime not found.'}
$toolCache=Join-Path $Project 'target\pressure-tools'
$libraryDirectory=Join-Path $toolCache 'lib'
$classDirectory=Join-Path $toolCache 'classes'
New-Item -ItemType Directory -Force -Path $libraryDirectory,$classDirectory | Out-Null
$sourceNames=@('LiveLikeMigration.java','HighConcurrencyFixture.java')
$stamp=[ordered]@{jar=$application.FullName;jarLastWriteTicks=$application.LastWriteTimeUtc.Ticks}
foreach($name in $sourceNames){$stamp[$name]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $name) -Algorithm SHA256).Hash}
$stampText=$stamp | ConvertTo-Json -Compress
$stampFile=Join-Path $toolCache 'build-inputs.json'
$classFile=Join-Path $classDirectory 'HighConcurrencyFixture.class'
$alreadyBuilt=(Test-Path -LiteralPath $stampFile) -and (Test-Path -LiteralPath $classFile) -and ((Get-Content -LiteralPath $stampFile -Raw).Trim() -eq $stampText)
if(!$alreadyBuilt){
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[System.IO.Compression.ZipFile]::OpenRead($application.FullName)
    try {
        # Exact library families only: no network downloads and no vendored jars in source.
        $allowed=@('mysql-connector-java','snakeyaml','jackson-annotations','jackson-core','jackson-databind','kafka-clients','slf4j-api')
        foreach($library in $allowed){
            $matches=@($archive.Entries | Where-Object { $_.FullName -match ('^BOOT-INF/lib/'+[regex]::Escape($library)+'-[0-9][^/]*\.jar$') })
            if($matches.Count -ne 1){throw "Expected exactly one packaged dependency: $library"}
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($matches[0],(Join-Path $libraryDirectory $matches[0].Name),$true)
        }
    }finally{$archive.Dispose()}
    $dependencyClasspath=(Get-ChildItem -LiteralPath $libraryDirectory -Filter '*.jar' | ForEach-Object FullName) -join ';'
    $sources=$sourceNames | ForEach-Object {Join-Path $PSScriptRoot $_}
    & $javaCompiler -encoding UTF-8 -cp $dependencyClasspath -d $classDirectory @sources
    if($LASTEXITCODE -ne 0){throw 'Pressure fixture helper compilation failed.'}
    Set-Content -LiteralPath $stampFile -Value $stampText
}
$dependencyClasspath=(Get-ChildItem -LiteralPath $libraryDirectory -Filter '*.jar' | ForEach-Object FullName) -join ';'
[pscustomobject]@{JavaExecutable=$javaRuntime;Classpath=$classDirectory+';'+$dependencyClasspath;CacheDirectory=$toolCache}
