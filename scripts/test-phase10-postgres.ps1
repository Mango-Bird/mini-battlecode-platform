param([switch]$Worker,[string]$Python=(Get-Command python.exe -ErrorAction Stop).Source,[string]$Docker=(Get-Command docker.exe -ErrorAction Stop).Source)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$config=Get-Content -LiteralPath (Join-Path $projectRoot '.tools\phase10\postgres-test.json') -Raw | ConvertFrom-Json
$oldUrl=$env:BATTLECODE_TEST_DB_URL;$oldUser=$env:BATTLECODE_TEST_DB_USER;$oldPassword=$env:BATTLECODE_TEST_DB_PASSWORD
try {
 $env:BATTLECODE_TEST_DB_URL=$config.url;$env:BATTLECODE_TEST_DB_USER=$config.user;$env:BATTLECODE_TEST_DB_PASSWORD=$config.password
 $tests=if($Worker){'PostgresPersistenceIT,RatingPostgresIT,RankedPostgresIT,CompetitiveReadPostgresIT,PostgresWorkerIT'}else{'PostgresPersistenceIT,RatingPostgresIT,RankedPostgresIT,CompetitiveReadPostgresIT'}
 & mvn -Plocal-bots -pl bot-match -am test "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dbattlecode.worker.python=$Python" "-Dbattlecode.worker.docker=$Docker"
 if($LASTEXITCODE -ne 0){throw 'PostgreSQL integration tests failed'}
}finally{$env:BATTLECODE_TEST_DB_URL=$oldUrl;$env:BATTLECODE_TEST_DB_USER=$oldUser;$env:BATTLECODE_TEST_DB_PASSWORD=$oldPassword}
