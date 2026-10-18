param([int]$Port=8090,[string]$Python=(Get-Command python.exe -ErrorAction Stop).Source,[string]$Docker=(Get-Command docker.exe -ErrorAction Stop).Source)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$imageFile=Join-Path $projectRoot '.tools\phase8f\image-id.txt'
if (-not (Test-Path -LiteralPath $imageFile)) { throw 'Run scripts/build-isolated-worker.ps1 first.' }
$imageId=(Get-Content -LiteralPath $imageFile -Raw).Trim()
$arenaJar=Join-Path $projectRoot 'bot-match\target\bot-match-1.0.0-SNAPSHOT.jar'
& java -cp "$arenaJar;$(Join-Path $projectRoot 'bot-match\target\dependency\*')" dev.battlecode.botmatch.SecureLocalArenaMain --local-development (Join-Path $projectRoot '.tools\quickjs\qjs.exe') $Port $Python (Join-Path $projectRoot 'isolated-worker\worker.py') $Docker $imageId
if ($LASTEXITCODE -ne 0) { throw 'Secure local arena failed.' }
