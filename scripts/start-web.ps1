param([string]$BindIp = '127.0.0.1', [int]$TcpPort = 9000, [int]$WebSocketPort = 8081,
      [string]$Origins = 'http://localhost:5173,http://127.0.0.1:5173')
$ErrorActionPreference = 'Stop'
$env:BATTLECODE_MODE = 'LOCAL'
$env:BATTLECODE_WS_PORT = [string]$WebSocketPort
$env:BATTLECODE_WS_BIND = $BindIp
$env:BATTLECODE_ORIGINS = $Origins
$repoPath = Split-Path -Parent $PSScriptRoot
Push-Location $repoPath
try { & java -cp 'server/target/server-1.0.0-SNAPSHOT.jar;server/target/dependency/*' dev.battlecode.server.ServerMain $BindIp $TcpPort }
finally { Pop-Location }
