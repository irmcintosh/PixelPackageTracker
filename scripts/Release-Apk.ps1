[CmdletBinding()]
param(
    [string]$GradleBat = '',
    [string]$JdkHome = '',
    [string]$BuildTools = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$packageId = 'com.example.pixelpackagetracker'
# Compared with the main application APK in the supplied reference source ZIP.
$expectedCertificate = 'b526e739f5be7935e957e9f7d9e3126922913e7628417db208ed2d15f57b39c7'
$sourceApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$distributionDir = Join-Path $projectRoot 'dist'
$destination = Join-Path $distributionDir 'PixelTrack-v1.0.9.apk'
if (-not $JdkHome) { $JdkHome = Join-Path $env:USERPROFILE '.jdks\jbr-21.0.11' }
if (-not $BuildTools) { $BuildTools = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\35.0.0' }
$aapt = Join-Path $BuildTools 'aapt2.exe'
$signer = Join-Path $BuildTools 'apksigner.bat'
foreach ($tool in @($aapt, $signer)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required APK verification tool not found: $tool" }
}

# Fixed main-app path only: no APK discovery, wildcard selection or test APK input.
& (Join-Path $PSScriptRoot 'Build-Safely.ps1') -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest') -GradleBat $GradleBat -JdkHome $JdkHome
if (-not (Test-Path -LiteralPath $sourceApk)) { throw 'Main application APK was not produced.' }
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JdkHome
    $badging = (& $aapt dump badging $sourceApk) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'APK metadata inspection failed.' }
    if ($badging -notmatch "(?m)^package: name='com\.example\.pixelpackagetracker' ") { throw 'Refusing to distribute an unexpected or test package.' }
    if ($badging -notmatch "(?m)^launchable-activity: name='com\.example\.pixelpackagetracker\.MainActivity'") { throw 'Expected launcher activity is missing.' }
    if ($badging -notmatch "(?m)^application: label='Pixel Tracker' icon='res/mipmap-anydpi-v26/ic_launcher.xml'") { throw 'Expected application label/custom adaptive icon is missing.' }
    $versionCode = [regex]::Match($badging, "versionCode='([^']*)'").Groups[1].Value
    $versionName = [regex]::Match($badging, "versionName='([^']*)'").Groups[1].Value
    $signature = (& $signer verify --print-certs $sourceApk) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $certificates = @([regex]::Matches($signature, 'certificate SHA-256 digest: ([0-9a-fA-F]+)'))
    if ($certificates.Count -ne 1 -or $certificates[0].Groups[1].Value.ToLowerInvariant() -ne $expectedCertificate) {
        throw 'Signing identity differs from the reference main APK. Stop; never uninstall or change signing keys to resolve this.'
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($sourceApk)
    try {
        # Check the unchanged custom bitmap resources, not just an icon resource name.
        $resRoot = Join-Path $projectRoot 'app\src\main\res'
        $iconFiles = @(Get-ChildItem -LiteralPath $resRoot -Recurse -File -Filter 'ic_launcher*.png')
        if ($iconFiles.Count -ne 11) { throw 'Expected eleven original launcher/foreground PNG resources.' }
        $hashAlgorithm = [System.Security.Cryptography.SHA256]::Create()
        try {
            foreach ($icon in $iconFiles) {
                $qualifier = $icon.Directory.Name
                $entries = @($zip.Entries | Where-Object { $_.FullName.StartsWith("res/$qualifier") -and $_.FullName.EndsWith('/' + $icon.Name) })
                if ($entries.Count -ne 1) { throw "Missing or ambiguous packaged icon: $($icon.Name) in $qualifier" }
                $stream = $entries[0].Open()
                try { $packedHash = [BitConverter]::ToString($hashAlgorithm.ComputeHash($stream)).Replace('-', '') } finally { $stream.Dispose() }
                if ($packedHash -ne (Get-FileHash -LiteralPath $icon.FullName -Algorithm SHA256).Hash) { throw "Icon bytes differ: $($icon.FullName)" }
            }
        } finally { $hashAlgorithm.Dispose() }
        foreach ($xml in @('res/mipmap-anydpi-v26/ic_launcher.xml', 'res/mipmap-anydpi-v26/ic_launcher_round.xml')) {
            $tree = (& $aapt dump xmltree $sourceApk --file $xml) -join "`n"
            if ($LASTEXITCODE -ne 0 -or $tree -notmatch 'E: adaptive-icon' -or $tree -notmatch 'E: foreground' -or $tree -notmatch 'E: monochrome') { throw "Adaptive/round icon verification failed: $xml" }
        }
        # Ensure the delivered bytecode includes the recent archive/Settings controls.
        $featureMarkers = @('reconcileArchives', 'deleteArchived', 'GmailAccountsSection', 'Disconnect Gmail session?', 'Delete all archived records?', 'Archive shipment', 'Delete shipment', 'Shipment information needed', 'Copy shipment details', 'CopySelectionDialog', 'formatCopySelection')
        $dexText = New-Object System.Text.StringBuilder
        foreach ($entry in @($zip.Entries | Where-Object { $_.FullName -match '^classes[0-9]*\.dex$' })) {
            $stream = $entry.Open()
            $memory = New-Object System.IO.MemoryStream
            try { $stream.CopyTo($memory); [void]$dexText.Append([System.Text.Encoding]::GetEncoding(28591).GetString($memory.ToArray())) } finally { $memory.Dispose(); $stream.Dispose() }
        }
        $bytecode = $dexText.ToString()
        foreach ($marker in $featureMarkers) { if (-not $bytecode.Contains($marker)) { throw "Recent application feature marker missing: $marker" } }
    } finally { $zip.Dispose() }

    $sourceHash = (Get-FileHash -LiteralPath $sourceApk -Algorithm SHA256).Hash
    New-Item -ItemType Directory -Path $distributionDir -Force | Out-Null
    $staged = Join-Path $distributionDir 'PixelTrack-v1.0.9.apk.pending'
    Copy-Item -LiteralPath $sourceApk -Destination $staged -Force
    if ((Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash -ne $sourceHash) { throw 'Distribution copy verification failed; final APK was not replaced.' }
    Move-Item -LiteralPath $staged -Destination $destination -Force
    $artifact = Get-Item -LiteralPath $destination
    $metadata = [ordered]@{
        file = $artifact.FullName
        source = $sourceApk
        packageId = $packageId
        launcher = 'com.example.pixelpackagetracker.MainActivity'
        label = 'Pixel Tracker'
        versionName = $versionName
        versionCode = $versionCode
        bytes = $artifact.Length
        apkLastWriteTimeUtc = $artifact.LastWriteTimeUtc.ToString('o')
        verifiedAtUtc = [DateTime]::UtcNow.ToString('o')
        sha256 = $sourceHash.ToLowerInvariant()
        signingCertificateSha256 = $expectedCertificate
        originalIconPngsVerified = $iconFiles.Count
        featureMarkersVerified = $featureMarkers
        installedOnDevice = $false
    }
    $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $distributionDir 'PixelTrack-v1.0.9.verification.json') -Encoding UTF8
    Write-Output "Verified main application: $destination"
    Write-Output "Package: $packageId; internal version: $versionName ($versionCode); bytes: $($artifact.Length)"
    Write-Output "SHA-256: $($sourceHash.ToLowerInvariant())"
    Write-Output 'No device installation, uninstallation, data clear or instrumentation was performed.'
} finally { $env:JAVA_HOME = $previousJavaHome }
