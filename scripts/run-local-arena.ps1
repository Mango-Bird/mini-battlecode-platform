param([string]$QuickJs = (Join-Path $PSScriptRoot '..\.tools\quickjs\qjs.exe'), [int]$Port = 8090)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$quickJsPath = [IO.Path]::GetFullPath($QuickJs)
$arenaJar = Join-Path $projectRoot 'bot-match\target\bot-match-1.0.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $arenaJar)) { throw 'Run mvn -Plocal-bots -pl bot-match -am package first.' }
& java -cp "$arenaJar;$(Join-Path $projectRoot 'bot-match\target\dependency\*')" dev.battlecode.botmatch.LocalArenaMain --local-development $quickJsPath $Port
if ($LASTEXITCODE -ne 0) { throw 'Local arena stopped with an error.' }
