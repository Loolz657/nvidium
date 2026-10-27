param([string]$OutputDirectory = (Join-Path $PSScriptRoot '../../reference-packs'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$referenceDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $referenceDirectory | Out-Null
$references = @(
    @{ id = 'Bqen1mJX'; filename = 'ComplementaryReimagined_r5.9.3.zip'; patcher = $false },
    @{ id = 'B1kyfoUZ'; filename = 'ComplementaryUnbound_r5.9.3.zip'; patcher = $false },
    @{ id = 'YYczPGkW'; filename = 'EuphoriaPatcher-1.10.5-r5.9.3-fabric.jar'; patcher = $true }
)
$records = foreach ($reference in $references) {
    $version = Invoke-RestMethod -Uri "https://api.modrinth.com/v2/version/$($reference.id)" -Headers @{ 'User-Agent' = 'Nvidium-Iris-Reference-Tests' }
    $file = $version.files | Where-Object { $_.primary -and $_.filename -eq $reference.filename } | Select-Object -First 1
    if (-not $file -or ([Uri]$file.url).Host -ne 'cdn.modrinth.com') { throw 'Unexpected official reference download' }
    $directory = if ($reference.patcher) { Join-Path $referenceDirectory 'patcher' } else { $referenceDirectory }
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $destination = Join-Path $directory $reference.filename
    if (-not (Test-Path -LiteralPath $destination)) { Invoke-WebRequest -Uri $file.url -OutFile $destination }
    $algorithm = [Security.Cryptography.SHA512]::Create()
    try { $hash = [BitConverter]::ToString($algorithm.ComputeHash([IO.File]::ReadAllBytes($destination))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
    if ($hash -ne $file.hashes.sha512) { throw "SHA512 mismatch: $destination" }
    if (-not $reference.patcher) {
        $unpacked = Join-Path $referenceDirectory ([IO.Path]::GetFileNameWithoutExtension($reference.filename))
        if (-not (Test-Path -LiteralPath $unpacked)) { [IO.Compression.ZipFile]::ExtractToDirectory($destination, $unpacked) }
    }
    [PSCustomObject]@{ version = $version.version_number; url = $file.url; sha512 = $hash }
}
$records | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $referenceDirectory 'references.json') -Encoding UTF8
$javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java' } else { 'java' }
$patcherJar = Join-Path $referenceDirectory 'patcher/EuphoriaPatcher-1.10.5-r5.9.3-fabric.jar'
$generator = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../src/reference/java/GenerateEuphoriaReferences.java'))
& $javaExecutable -cp $patcherJar $generator $referenceDirectory
if ($LASTEXITCODE -ne 0) { throw 'Local Euphoria reference generation failed' }
Write-Output 'Official reference downloads verified; generated shaders stay local and must not be redistributed.'
