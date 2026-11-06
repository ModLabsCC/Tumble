$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

& .\gradlew.bat runAssemblyCrashBaseline -PacceptMinecraftEula=true --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Crash baseline failed' }

& .\gradlew.bat runAssemblyCrashInject -PacceptMinecraftEula=true --console=plain
$crashExit = $LASTEXITCODE
$marker = 'build/run/assemblyCrash/assembly-crash-reached.txt'
if ($crashExit -eq 0 -or !(Test-Path -LiteralPath $marker) -or !(Get-Content -LiteralPath $marker -Raw).StartsWith('entity-load: ')) {
    throw 'Server did not reach the expected injected crash'
}
Write-Host "Injected crash at $(Get-Content -LiteralPath $marker -Raw)"

& .\gradlew.bat runAssemblyCrashVerify -PacceptMinecraftEula=true --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Crash recovery verification failed' }
Write-Host 'Assembly crash test passed'
