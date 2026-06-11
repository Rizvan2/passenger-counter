param(
    [int]$Port = 8080,
    [string]$JavaHome = "$env:USERPROFILE\.jdks\ms-21.0.11-1",
    [string]$ModelsDir = ".\models",
    [string]$VideosDir = ".\videos",
    [string]$JavaOpts = "-Xms256m -Xmx2g",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$resolvedModels = (New-Item -ItemType Directory -Force -Path $ModelsDir).FullName
$resolvedVideos = (New-Item -ItemType Directory -Force -Path $VideosDir).FullName

if (!(Test-Path "$JavaHome\bin\java.exe")) {
    throw "JDK not found at '$JavaHome'. Install JDK 21 or pass -JavaHome C:\path\to\jdk."
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:SERVER_PORT = "$Port"
$env:PC_MODELS_DIR = $resolvedModels
$env:PC_VIDEOS_DIR = $resolvedVideos

if (!$SkipBuild) {
    & .\gradlew.bat bootJar
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if (!(Test-Path ".\build\libs")) {
    throw "Application jar was not found. Run without -SkipBuild first."
}

$jar = Get-ChildItem -Path ".\build\libs" -Filter "*.jar" |
    Where-Object { $_.Name -notlike "*plain*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $jar) {
    throw "Application jar was not found in build\libs. Run without -SkipBuild first."
}

Write-Host "Passenger Counter local run"
Write-Host "  URL:        http://localhost:$Port"
Write-Host "  JAVA_HOME:  $env:JAVA_HOME"
Write-Host "  Models:     $env:PC_MODELS_DIR"
Write-Host "  Videos:     $env:PC_VIDEOS_DIR"
Write-Host ""
Write-Host "Use an absolute video path in the UI, for example:"
Write-Host "  $($resolvedVideos -replace '\\','/')/bus.mp4"
Write-Host ""

$javaArgs = @()
if ($JavaOpts.Trim()) {
    $javaArgs += $JavaOpts.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
}
$javaArgs += "-jar"
$javaArgs += $jar.FullName

& "$env:JAVA_HOME\bin\java.exe" @javaArgs
