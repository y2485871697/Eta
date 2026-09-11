$ErrorActionPreference = 'Stop'

$SourceDir = 'E:\codex\rikkahub\Eta'
$CompileDir = 'E:\codex\eta-compile'
$JavaHome = 'E:\Android tools\jdk-25.0.4.1+1'
$AndroidHome = 'E:\Android tools'
$ApkName = 'app-release.apk'

New-Item -ItemType Directory -Force -Path $CompileDir | Out-Null

$robocopy = @(
    $SourceDir,
    $CompileDir,
    '/E',
    '/XD', 'build', '.gradle', '.idea', 'captures', '.git', '.kotlin',
    '/NFL', '/NDL', '/NJH', '/NJS', '/NC', '/NS', '/NP'
)
& robocopy @robocopy
if ($LASTEXITCODE -ge 8) {
    throw "robocopy failed with exit code $LASTEXITCODE"
}

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome
$env:PATH = "$JavaHome\bin;" + $env:PATH

Push-Location $CompileDir
try {
    & .\gradlew.bat --no-daemon --no-configuration-cache :app:assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "assembleRelease failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$srcApkDir = Join-Path $CompileDir 'app\build\outputs\apk\release'
$dstApkDir = Join-Path $SourceDir 'app\build\outputs\apk\release'
$srcApk = Join-Path $srcApkDir $ApkName
if (-not (Test-Path $srcApk)) {
    throw "missing $srcApk"
}

New-Item -ItemType Directory -Force -Path $dstApkDir | Out-Null
Copy-Item -Force (Join-Path $srcApkDir '*') $dstApkDir
Copy-Item -Force $srcApk (Join-Path $SourceDir 'Eta-release.apk')

$mappingSrc = Join-Path $CompileDir 'app\build\outputs\mapping\release'
$mappingDst = Join-Path $SourceDir 'app\build\outputs\mapping\release'
if (Test-Path $mappingSrc) {
    New-Item -ItemType Directory -Force -Path $mappingDst | Out-Null
    Copy-Item -Force (Join-Path $mappingSrc '*') $mappingDst
}

Write-Host "APK: $(Join-Path $dstApkDir $ApkName)"
