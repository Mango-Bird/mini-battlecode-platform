param([string]$QuickJs = (Join-Path $PSScriptRoot '..\.tools\quickjs\qjs.exe'))
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$quickJsPath = [IO.Path]::GetFullPath($QuickJs)
$demoJar = Join-Path $projectRoot 'bot-match\target\bot-match-1.0.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $demoJar)) { throw 'Run mvn package on Windows, or enable -Plocal-bots (tests enabled).' }
& java -cp "$demoJar;$(Join-Path $projectRoot 'bot-match\target\dependency\*')" dev.battlecode.botmatch.LocalBotDemoMain $quickJsPath
if ($LASTEXITCODE -ne 0) { throw 'Local JavaScript bot demo failed.' }
