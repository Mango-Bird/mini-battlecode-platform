param([string]$Docker=(Get-Command docker.exe -ErrorAction Stop).Source)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot=Join-Path $projectRoot '.tools\phase10'
New-Item -ItemType Directory -Force $testRoot | Out-Null
$existing=& $Docker ps --all --filter 'name=^battlecode-phase10-tests$' --format '{{.ID}}'
if ($existing) { throw 'Test container already exists; inspect it before restarting this script.' }
$password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$envFile=Join-Path $testRoot 'postgres-test.env'
[IO.File]::WriteAllLines($envFile,@('POSTGRES_DB=battlecode_tests','POSTGRES_USER=battlecode_tests',"POSTGRES_PASSWORD=$password"))
& $Docker run --detach --name battlecode-phase10-tests --label battlecode.phase10.tests=true --memory 256m --cpus 1 --env-file $envFile --publish '127.0.0.1::5432' postgres:17 | Out-Null
if($LASTEXITCODE -ne 0){throw 'PostgreSQL test container failed'}
$ready=$false
for($i=0;$i -lt 30;$i++) {
  & $Docker exec battlecode-phase10-tests pg_isready -U battlecode_tests -d battlecode_tests *> $null
  if($LASTEXITCODE -eq 0){$ready=$true;break}
  Start-Sleep -Seconds 1
}
if(-not $ready){throw 'PostgreSQL readiness timeout'}
$binding=(& $Docker port battlecode-phase10-tests '5432/tcp').Trim()
$port=$binding.Split(':')[-1]
$config=@{url="jdbc:postgresql://127.0.0.1:$port/battlecode_tests";user='battlecode_tests';password=$password}
[IO.File]::WriteAllText((Join-Path $testRoot 'postgres-test.json'),($config | ConvertTo-Json))
Write-Output "PostgreSQL 17 ready on loopback port $port; test credentials stored only under ignored .tools/phase10."
