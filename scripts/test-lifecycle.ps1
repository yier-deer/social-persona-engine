$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$lifecycle = Join-Path $PSScriptRoot 'services.ps1'
if (-not (Test-Path -LiteralPath $lifecycle)) { throw 'Missing owned-process lifecycle script' }
. $lifecycle -Action Library

# A recorded PID is insufficient: PID reuse and edited state must not kill others.
$self = Get-Process -Id $PID
$record = [pscustomobject]@{
    Id = $PID
    StartedAt = $self.StartTime.ToUniversalTime().ToString('o')
    Executable = $self.Path
    CommandLine = 'not-a-project-service'
    Workspace = $workspace
}
if (Test-OwnedProcess $record) { throw 'Unrelated process incorrectly considered project-owned' }
$record.StartedAt = '2000-01-01T00:00:00.0000000Z'
if (Test-OwnedProcess $record) { throw 'Reused PID incorrectly considered project-owned' }

$portProbe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$portProbe.Start()
try {
    $port = $portProbe.LocalEndpoint.Port
    if (-not (Test-ListeningPort $port)) { throw 'Port occupancy was not detected' }
} finally { $portProbe.Stop() }
if (Test-ListeningPort $port) { throw 'Closed port incorrectly considered occupied' }

$statePath = $script:StatePath
$temporaryState = [IO.Path]::GetTempFileName()
try {
    Set-Content -LiteralPath $temporaryState -Value '{}' -Encoding utf8
    $script:StatePath = $temporaryState
    try {
        Read-State | Out-Null
        throw 'Malformed state was accepted'
    } catch {
        if ($_.Exception.Message -notlike 'Unreadable service state*') { throw }
    }
} finally {
    $script:StatePath = $statePath
    Remove-Item -LiteralPath $temporaryState -Force -ErrorAction SilentlyContinue
}
$failures = @()

# Mock only OS process discovery: reused PIDs must fail at the kill selector too.
try {
    & {
        $exe = 'C:\tools\python.exe'
        $oldTime = [DateTime]::UtcNow.AddDays(-1)
        $reusedRecord = [pscustomobject]@{
            Id = 7331; LauncherId = 7331; Workspace = $workspace
            Executable = $exe; CommandLine = "$exe $workspace\server.py"
            StartedAt = $oldTime.ToString('o')
            LauncherExecutable = $exe; LauncherCommandLine = "$exe $workspace\server.py"
            LauncherStartedAt = $oldTime.ToString('o')
        }
        function Get-ProcessInfo([int]$Id) {
            [pscustomobject]@{
                Id = $Id; Path = $exe; CommandLine = "$exe $workspace\other_job.py"
                Process = [pscustomobject]@{ StartTime = [DateTime]::UtcNow }
            }
        }
        function Get-DescendantProcessIds([int[]]$RootIds) { @($RootIds) }
        if (Test-OwnedProcess $reusedRecord) { throw 'Reused launcher PID was accepted' }
        if (@(Get-OwnedProcessIds $reusedRecord).Count -ne 0) { throw 'Reused PID was selected for termination' }
    }
} catch { $failures += "PID reuse: $($_.Exception.Message)" }

# Exercise real Start-All and state persistence without spawning services or building Java.
$runtimeBefore = $script:RuntimeDir
$logsBefore = $script:LogDir
$stateBefore = $script:StatePath
$temporaryRuntime = Join-Path ([IO.Path]::GetTempPath()) ('persona-lifecycle-' + [Guid]::NewGuid().ToString('N'))
try {
    & {
        $script:RuntimeDir = $temporaryRuntime
        $script:LogDir = Join-Path $temporaryRuntime 'logs'
        $script:StatePath = Join-Path $temporaryRuntime 'services.json'
        Ensure-Runtime
        $mavenStub = Join-Path $temporaryRuntime 'maven.ps1'
        Set-Content -LiteralPath $mavenStub -Value '$global:LASTEXITCODE = 0' -Encoding utf8
        $started = [DateTime]::UtcNow
        $serviceRecords = @()
        $serviceInfos = @{}
        foreach ($item in @(@('python', 1001, 8001), @('java', 1002, 8080), @('frontend', 1003, 5174))) {
            $exe = 'C:\tools\service.exe'
            $command = "$exe $workspace\$($item[0])"
            $serviceRecords += [pscustomobject]@{
                Name = $item[0]; Id = $item[1]; Port = $item[2]
                Url = "http://127.0.0.1:$($item[2])/"
                StartedAt = $started.ToString('o'); Executable = $exe
                CommandLine = $command; Workspace = $workspace
            }
            $serviceInfos[[int]$item[1]] = [pscustomobject]@{
                Id = $item[1]; Path = $exe; CommandLine = $command
                Process = [pscustomobject]@{ StartTime = $started }
            }
        }
        Write-State $serviceRecords
        function Get-ProcessInfo([int]$Id) { $serviceInfos[$Id] }
        function Get-Command { [pscustomobject]@{ Source = $mavenStub } }
        function Test-Path { $true }
        function Test-ListeningPort([int]$Port) { $true }
        function Wait-Ready { }
        function Resolve-ListeningRecord($Record) { $Record }
        function Stop-All { throw 'Repeated start attempted cleanup of existing services' }
        Start-All
        $after = @(Read-State)
        if ($after.Count -ne 3 -or @($after.Name | Select-Object -Unique).Count -ne 3) {
            throw 'Repeated start lost an existing service record'
        }
    }
} catch { $failures += "Repeated start: $($_.Exception.Message)" }
finally {
    $script:RuntimeDir = $runtimeBefore
    $script:LogDir = $logsBefore
    $script:StatePath = $stateBefore
    $cleanupPath = [IO.Path]::GetFullPath($temporaryRuntime)
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $cleanupPath.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($cleanupPath) -notlike 'persona-lifecycle-*') {
        throw 'Refused to remove an unexpected lifecycle test directory'
    }
    Remove-Item -LiteralPath $cleanupPath -Recurse -Force -ErrorAction SilentlyContinue
}
if ($failures.Count) { throw ($failures -join "`n") }
Write-Host 'Lifecycle safety checks passed.'
