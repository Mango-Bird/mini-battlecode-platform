param([Parameter(Mandatory=$true)][string]$Docker,[Parameter(Mandatory=$true)][string]$OpenSsl)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tlsRoot=Join-Path $projectRoot '.tools\phase11b1-tls'
$labels=(& $Docker inspect --format '{{json .Config.Labels}}' battlecode-phase10-tests)|ConvertFrom-Json
if($LASTEXITCODE -ne 0 -or $labels.'battlecode.phase10.tests' -ne 'true'){throw 'Only the owned disposable test container may be configured'}
New-Item -ItemType Directory -Force $tlsRoot | Out-Null
[IO.File]::WriteAllText("$tlsRoot\openssl.cnf","[req]`ndistinguished_name=dn`n[dn]`n")
& $OpenSsl req -x509 -newkey rsa:2048 -nodes -config "$tlsRoot\openssl.cnf" -keyout "$tlsRoot\server.key" -out "$tlsRoot\server.crt" -days 2 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost' *> "$tlsRoot\openssl.log"
if($LASTEXITCODE -ne 0){throw 'Test certificate generation failed'}
& $Docker cp "$tlsRoot\server.key" battlecode-phase10-tests:/var/lib/postgresql/server.key
& $Docker cp "$tlsRoot\server.crt" battlecode-phase10-tests:/var/lib/postgresql/server.crt
& $Docker exec -u root battlecode-phase10-tests chown postgres:postgres /var/lib/postgresql/server.key /var/lib/postgresql/server.crt
& $Docker exec -u root battlecode-phase10-tests chmod 600 /var/lib/postgresql/server.key
foreach($sql in @("ALTER SYSTEM SET ssl = 'on'","ALTER SYSTEM SET ssl_cert_file = '/var/lib/postgresql/server.crt'","ALTER SYSTEM SET ssl_key_file = '/var/lib/postgresql/server.key'")){
 & $Docker exec battlecode-phase10-tests psql -U battlecode_tests -d battlecode_tests -c $sql | Out-Null
 if($LASTEXITCODE -ne 0){throw 'TLS test setup failed'}
}
& $Docker restart battlecode-phase10-tests | Out-Null
$testPath=Join-Path $projectRoot '.tools\phase10\postgres-test.json'
$testConfig=Get-Content $testPath -Raw|ConvertFrom-Json
$binding=(& $Docker port battlecode-phase10-tests '5432/tcp').Trim()
$testConfig.url="jdbc:postgresql://127.0.0.1:$($binding.Split(':')[-1])/battlecode_tests"
[IO.File]::WriteAllText($testPath,($testConfig|ConvertTo-Json))
$storePassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$keytool=(Get-Command keytool.exe -ErrorAction Stop).Source
if(Test-Path -LiteralPath "$tlsRoot\trust.p12"){Remove-Item -LiteralPath "$tlsRoot\trust.p12"}
& $keytool -importcert -noprompt -alias staging-test -file "$tlsRoot\server.crt" -keystore "$tlsRoot\trust.p12" -storetype PKCS12 -storepass $storePassword *> "$tlsRoot\keytool.log"
if($LASTEXITCODE -ne 0){throw 'Test trust setup failed'}
[IO.File]::WriteAllText("$tlsRoot\config.json",(@{trustStore="$tlsRoot\trust.p12";trustPassword=$storePassword;url=$testConfig.url.Replace('127.0.0.1','localhost')}|ConvertTo-Json))
Write-Output 'Disposable PostgreSQL TLS and isolated test trust store prepared; credentials remain in ignored .tools only.'
