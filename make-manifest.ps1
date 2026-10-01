# Regenerates ResourcePack.manifest.json for the resource pack zip. Run it every time the pack changes (double-click make-manifest.bat).
#   -Zip  the pack zip (default: ResourcePack.zip next to this script)
#   -Url  the zip's direct download link, stored in the manifest (default: packShareUrl from gradle.properties, if set)
# The manifest is written next to the zip. Upload the zip first, then the manifest, over the old files (same names, so the links stay the same).
param(
    [string]$Zip = (Join-Path $PSScriptRoot 'ResourcePack.zip'),
    [string]$Url = ''
)
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Zip)) { throw "Pack zip not found: $Zip" }
$Zip = (Resolve-Path -LiteralPath $Zip).Path

# the zip must be a real resource pack
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($Zip)
try {
    if (-not ($z.Entries | Where-Object { $_.FullName -eq 'pack.mcmeta' })) { throw "$Zip has no pack.mcmeta at its root: zip the CONTENTS of the pack folder, not the folder itself." }
} finally { $z.Dispose() }

if ($Url -eq '') {
    $props = Join-Path $PSScriptRoot 'gradle.properties'
    if (Test-Path -LiteralPath $props) {
        $line = Get-Content -LiteralPath $props | Where-Object { $_ -match '^\s*packShareUrl\s*=\s*(\S+)' } | Select-Object -First 1
        if ($line -and $line -match '=\s*(\S+)') { $Url = $Matches[1] }
    }
}

$sha1 = (Get-FileHash -LiteralPath $Zip -Algorithm SHA1).Hash.ToLower()
$size = (Get-Item -LiteralPath $Zip).Length
$json = "{`n  `"sha1`": `"$sha1`",`n  `"size`": $size"
if ($Url -ne '') { $json += ",`n  `"url`": `"$($Url -replace '\\','\\\\' -replace '"','\"')`"" }
$json += "`n}`n"

$out = Join-Path (Split-Path -Parent $Zip) (([System.IO.Path]::GetFileNameWithoutExtension($Zip)) + '.manifest.json')
[System.IO.File]::WriteAllText($out, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host "Pack     : $Zip"
Write-Host ("Size     : {0:N1} MB" -f ($size / 1MB))
Write-Host "SHA-1    : $sha1"
Write-Host "Manifest : $out"
Write-Host ''
Write-Host 'Next: upload the zip, THEN the manifest, over the old files (same names).'
