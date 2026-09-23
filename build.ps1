param(
    [string] $JavaHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSCommandPath

if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if ($JavaHome) {
    $javaBin = Join-Path $JavaHome 'bin'
    $javac = Join-Path $javaBin 'javac.exe'
    $jar = Join-Path $javaBin 'jar.exe'
} else {
    $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
    if (-not $javacCommand) {
        throw 'JDK 21 was not found. Set JAVA_HOME or pass -JavaHome to build.ps1.'
    }
    $javaBin = Split-Path -Parent $javacCommand.Source
    $javac = $javacCommand.Source
    $jar = Join-Path $javaBin 'jar.exe'
}
if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $jar)) {
    throw "JDK tools were not found in $javaBin"
}

$classes = Join-Path $projectRoot ('build\classes-' + [guid]::NewGuid().ToString('N'))
$dist = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $classes, $dist -Force | Out-Null

& $javac -encoding UTF-8 -d $classes (Join-Path $projectRoot 'src\StarCleanupGui.java')
if ($LASTEXITCODE -ne 0) { throw 'GUI compilation failed.' }

$jarFile = Join-Path $dist 'StarCleanupGui.jar'
& $jar --create --file $jarFile --main-class StarCleanupGui -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }

Copy-Item -LiteralPath (Join-Path $projectRoot 'macro\StarCleanup.java') -Destination $dist -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'Start-StarCleanup.bat') -Destination $dist -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'local-env.example.bat') -Destination $dist -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination $dist -Force
Write-Host "Built $jarFile"
Write-Host "Copy local-env.example.bat to local-env.bat in dist, edit the paths, then run Start-StarCleanup.bat."
