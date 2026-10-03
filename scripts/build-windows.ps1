<#
Builds what people on Windows download: the installer of the app and the command line daemon, with a checksum each.

    pwsh scripts/build-windows.ps1 -Version 0.1.26

The files land in dist-windows (or -Out). Run it on Windows; CI does it on a Windows runner. The version of the app is
written into windows/src-tauri/tauri.conf.json first, so the installer, the About page and the engine agree with the tag.
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Out = "dist-windows"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root $Out
New-Item -ItemType Directory -Force $dist | Out-Null

# ---- The app -----------------------------------------------------------------------
Push-Location (Join-Path $root "windows")
try {
    $conf = "src-tauri/tauri.conf.json"
    $text = (Get-Content $conf -Raw) -replace '"version":\s*"[^"]*"', ('"version": "' + $Version + '"')
    Set-Content $conf $text -NoNewline

    npx --yes "@tauri-apps/cli@2" build --ci
    if ($LASTEXITCODE -ne 0) { throw "the app did not build" }

    $setup = Get-ChildItem "src-tauri/target/release/bundle/nsis" -Filter "*-setup.exe" | Select-Object -First 1
    if (-not $setup) { throw "the build made no installer" }
    Copy-Item $setup.FullName (Join-Path $dist "Tandem-Windows-x64-setup.exe")
    Copy-Item $setup.FullName (Join-Path $dist "Tandem-v$Version-Windows-x64-setup.exe")
}
finally { Pop-Location }

# ---- The command line daemon --------------------------------------------------------
Push-Location $root
try {
    cargo build --release --locked -p tandemd
    if ($LASTEXITCODE -ne 0) { throw "tandemd did not build" }

    $pack = Join-Path $dist "tandemd-windows-x86_64"
    New-Item -ItemType Directory -Force $pack | Out-Null
    Copy-Item "target/release/tandemd.exe" $pack
    Copy-Item "LICENSE" $pack
    Compress-Archive -Path (Join-Path $pack "*") -DestinationPath "$pack.zip" -Force
    Remove-Item -Recurse -Force $pack
    Copy-Item "$pack.zip" (Join-Path $dist "tandemd-v$Version-windows-x86_64.zip")
}
finally { Pop-Location }

# ---- Checksums, in the format sha256sum writes --------------------------------------
foreach ($name in "Tandem-Windows-x64-setup.exe", "tandemd-windows-x86_64.zip") {
    $hash = (Get-FileHash (Join-Path $dist $name) -Algorithm SHA256).Hash.ToLower()
    Set-Content (Join-Path $dist "$name.sha256") "$hash  $name`n" -NoNewline
}

Get-ChildItem $dist | Format-Table Name, Length
