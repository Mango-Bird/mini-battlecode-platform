$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$cache=Join-Path $projectRoot '.tools\phase8f'
$context=Join-Path $cache 'build-context'
New-Item -ItemType Directory -Force $context | Out-Null
$archive=Join-Path $cache 'quickjs-ng-0.16.2.tar.gz'
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest 'https://github.com/quickjs-ng/quickjs/archive/refs/tags/v0.16.2.tar.gz' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne '97C80625B26775A4C7CA618C004D4EA24CF99CBF867E4EBA78BD927A8B23D106') { throw 'Source hash mismatch' }
Copy-Item -LiteralPath $archive -Destination (Join-Path $context 'quickjs-ng-0.16.2.tar.gz')
Copy-Item -LiteralPath (Join-Path $projectRoot 'isolated-worker\guest.c') -Destination $context
Copy-Item -LiteralPath (Join-Path $projectRoot 'isolated-worker\Dockerfile') -Destination $context
& docker build --tag mini-battlecode-sandbox:8f $context
if ($LASTEXITCODE -ne 0) { throw 'Isolated guest build failed' }
$imageId=& docker image inspect mini-battlecode-sandbox:8f --format '{{.Id}}'
if ($LASTEXITCODE -ne 0) { throw 'Image verification failed' }
Set-Content -LiteralPath (Join-Path $cache 'image-id.txt') -Value $imageId.Trim() -NoNewline
Write-Output "Pinned image: $imageId"
