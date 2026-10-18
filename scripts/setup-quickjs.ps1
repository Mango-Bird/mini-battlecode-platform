param([string]$Destination = (Join-Path $PSScriptRoot '..\.tools\quickjs\qjs.exe'))
$ErrorActionPreference = 'Stop'
# Official QuickJS-NG v0.16.2 Windows x64 artifact, pinned after retrieval in Phase 8C.
$expectedSha256 = '7B27412DE844403545BD151FBE49191B4D5B91A9E15B5DB7C863FEA54639A82B'
$absoluteDestination = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force (Split-Path $absoluteDestination) | Out-Null
if (-not (Test-Path -LiteralPath $absoluteDestination)) {
    Invoke-WebRequest -Uri 'https://github.com/quickjs-ng/quickjs/releases/download/v0.16.2/qjs-windows-x86_64.exe' -OutFile $absoluteDestination
}
if ((Get-FileHash -LiteralPath $absoluteDestination -Algorithm SHA256).Hash -ne $expectedSha256) {
    throw 'QuickJS artifact hash mismatch. Do not execute this file.'
}
& $absoluteDestination --version
if ($LASTEXITCODE -ne 0) { throw 'QuickJS capability probe failed.' }
Write-Output 'TRUSTED_FIXTURES_ONLY; AppContainer/Job Object isolation NOT verified; CPU/process-memory quotas NOT enforced.'
