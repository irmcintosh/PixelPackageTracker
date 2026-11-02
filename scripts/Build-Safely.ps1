[CmdletBinding()]
param(
    [ValidateSet(':app:assembleDebug', ':app:testDebugUnitTest', ':app:assembleDebugAndroidTest')]
    [string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest'),
    [string]$GradleBat = '',
    [string]$JdkHome = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $GradleBat) {
    $wrapper = Join-Path $projectRoot 'gradlew.bat'
    if (Test-Path -LiteralPath $wrapper) {
        $GradleBat = $wrapper
    } else {
        # This source tree has wrapper properties but no wrapper launcher/JAR.
        $distribution = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.11.1-bin'
        $candidate = @(Get-ChildItem -LiteralPath $distribution -Filter gradle.bat -Recurse -File)
        if ($candidate.Count -ne 1) { throw 'Specify -GradleBat with the verified Gradle 8.11.1 launcher path.' }
        $GradleBat = $candidate[0].FullName
    }
}
$GradleBat = (Resolve-Path -LiteralPath $GradleBat).Path
if (-not $JdkHome) { $JdkHome = Join-Path $env:USERPROFILE '.jdks\jbr-21.0.11' }
if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\java.exe'))) { throw 'Specify -JdkHome with a compatible installed JDK.' }
$previousJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $projectRoot
try {
    $env:JAVA_HOME = $JdkHome
    # Never execute ADB, start a device, install an APK, or run a connected task.
    & $GradleBat @Tasks '--console=plain' '--no-configuration-cache'
    if ($LASTEXITCODE -ne 0) { throw "Non-device Gradle build failed ($LASTEXITCODE). No install fallback is permitted." }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
