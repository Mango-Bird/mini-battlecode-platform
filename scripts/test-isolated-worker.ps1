param([string]$Python=(Get-Command python.exe -ErrorAction Stop).Source,[string]$Docker=(Get-Command docker.exe -ErrorAction Stop).Source)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $projectRoot
try {
    $previousDocker=$env:BATTLECODE_TEST_DOCKER
    try {
        $env:BATTLECODE_TEST_DOCKER=$Docker
        & $Python isolated-worker/test_worker.py
        if ($LASTEXITCODE -ne 0) { throw 'Real worker boundary tests failed.' }
    } finally { $env:BATTLECODE_TEST_DOCKER=$previousDocker }
    & mvn -pl bot-match -am test '-Dtest=VerifiedWorkerSecurityIT,SavedBotBattleSecurityIT,TournamentWorkerSecurityIT' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dbattlecode.worker.python=$Python" "-Dbattlecode.worker.docker=$Docker"
    if ($LASTEXITCODE -ne 0) { throw 'Real Java worker/battle security tests failed.' }
} finally { Pop-Location }
