param(
    [Parameter(Mandatory = $true)][string]$ServerAddress,
    [int]$Port = 7777
)

$ErrorActionPreference = 'Stop'
Write-Host "Local IPv4 addresses:"
Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.ValidLifetime -gt 0 } |
    Select-Object InterfaceAlias, IPAddress, PrefixLength

Write-Host "Testing TCP route to ${ServerAddress}:$Port"
$result = Test-NetConnection -ComputerName $ServerAddress -Port $Port -WarningAction SilentlyContinue
$result | Select-Object ComputerName, RemoteAddress, RemotePort, InterfaceAlias, SourceAddress, TcpTestSucceeded
if (-not $result.TcpTestSucceeded) {
    Write-Error "TCP connection failed. Check server bind address, route, Wi-Fi client isolation, and operator-managed firewall policy."
}
