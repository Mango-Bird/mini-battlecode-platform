param([int]$Port=8090,[string]$EnvFile='.env',[string]$Python=(Get-Command python.exe -ErrorAction Stop).Source,[string]$Docker=(Get-Command docker.exe -ErrorAction Stop).Source)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(Test-Path -LiteralPath $EnvFile){
 foreach($line in Get-Content -LiteralPath $EnvFile){
  if([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')){continue}
  $pair=$line.Split('=',2);if($pair.Count -ne 2){throw 'Invalid .env line'}
  $key=$pair[0].Trim();$value=$pair[1].Trim()
  if($key -notin @('BATTLECODE_DB_NAME','BATTLECODE_DB_URL','BATTLECODE_DB_USER','BATTLECODE_DB_PASSWORD')){throw 'Unexpected .env key'}
  if($value.StartsWith('"') -and $value.EndsWith('"')){$value=$value.Substring(1,$value.Length-2)}
  [Environment]::SetEnvironmentVariable($key,$value,'Process')
 }
}
foreach($key in @('BATTLECODE_DB_URL','BATTLECODE_DB_USER','BATTLECODE_DB_PASSWORD')){if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key,'Process'))){throw 'Database environment configuration required'}}
if($env:BATTLECODE_DB_PASSWORD -eq 'replace-with-a-long-random-password'){throw 'Replace the example database password first'}
& (Join-Path $PSScriptRoot 'run-secure-local-arena.ps1') -Port $Port -Python $Python -Docker $Docker
