[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'status', 'url', 'library')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'
$script:Workspace = Split-Path $PSScriptRoot -Parent
$script:RuntimeDir = Join-Path $script:Workspace '.runtime'
$script:StatePath = Join-Path $script:RuntimeDir 'services.json'
$script:LogDir = Join-Path $script:RuntimeDir 'logs'

function Ensure-Runtime {
    New-Item -ItemType Directory -Path $script:RuntimeDir, $script:LogDir -Force | Out-Null
}

function Get-ProcessInfo([int]$Id) {
    try {
        $process = Get-Process -Id $Id -ErrorAction Stop
        $wmi = Get-CimInstance Win32_Process -Filter "ProcessId=$Id" -ErrorAction Stop
        [pscustomobject]@{
            Id = $Id
            Process = $process
            Path = [string]$wmi.ExecutablePath
            CommandLine = [string]$wmi.CommandLine
            ParentProcessId = [int]$wmi.ParentProcessId
        }
    } catch {
        $null
    }
}

function Get-RecordIds($Record) {
    $ids = @()
    foreach ($propertyName in @('Id', 'ListenerId', 'LauncherId')) {
        if ($Record -and ($Record.PSObject.Properties.Name -contains $propertyName)) {
            $value = 0
            if ([int]::TryParse([string]$Record.$propertyName, [ref]$value) -and $value -gt 0) {
                $ids += $value
            }
        }
    }
    if ($Record -and ($Record.PSObject.Properties.Name -contains 'ProcessIds')) {
        foreach ($value in @($Record.ProcessIds)) {
            $parsed = 0
            if ([int]::TryParse([string]$value, [ref]$parsed) -and $parsed -gt 0) {
                $ids += $parsed
            }
        }
    }
    @($ids | Select-Object -Unique)
}

function Test-ProcessInfoMatches($Info, $Record, [switch]$IgnoreStartedAt) {
    if ($null -eq $Info -or $null -eq $Record) { return $false }
    if (-not $Record.Workspace -or -not $Record.Executable -or -not $Record.CommandLine -or
        -not $Record.StartedAt -or -not $Info.Path) { return $false }
    if (-not ($Info.CommandLine -like "*$($Record.Workspace)*")) { return $false }
    if (
        ([IO.Path]::GetFullPath($Info.Path) -ne [IO.Path]::GetFullPath([string]$Record.Executable))) { return $false }

    $expectedCommand = (([string]$Record.CommandLine).Replace('"', '') -replace '\s+', ' ').Trim()
    $actualCommand = (([string]$Info.CommandLine).Replace('"', '') -replace '\s+', ' ').Trim()
    if ($expectedCommand -ne $actualCommand) { return $false }

    if (-not $IgnoreStartedAt -and $Record.StartedAt) {
        try {
            $expected = [DateTime]::Parse([string]$Record.StartedAt).ToUniversalTime()
            $actual = $Info.Process.StartTime.ToUniversalTime()
            if ([Math]::Abs(($actual - $expected).TotalSeconds) -gt 1) { return $false }
        } catch { return $false }
    }
    return $true
}

function Test-WorkspaceProcess($Info, $Record) {
    return $null -ne $Info -and $Record.Workspace -and
        ($Info.CommandLine -like "*$($Record.Workspace)*")
}

function Test-OwnedProcess($Record) {
    foreach ($id in (Get-RecordIds $Record)) {
        $info = Get-ProcessInfo ([int]$id)
        if ((Test-ProcessInfoMatches $info $Record) -or
            (Test-LauncherProcess $info $Record)) { return $true }
    }
    return $false
}

function Test-LauncherProcess($Info, $Record) {
    if ($null -eq $Info -or $null -eq $Record -or -not $Record.LauncherId -or
        ([int]$Info.Id -ne [int]$Record.LauncherId) -or -not $Record.LauncherCommandLine) {
        return $false
    }
    $launcherRecord = [pscustomobject]@{
        Workspace = $Record.Workspace
        Executable = $Record.LauncherExecutable
        CommandLine = $Record.LauncherCommandLine
        StartedAt = $Record.LauncherStartedAt
    }
    return Test-ProcessInfoMatches $Info $launcherRecord
}

function Get-ListeningProcessInfo($Record) {
    if ($null -eq $Record -or -not $Record.Port) { return $null }
    $connections = Get-NetTCPConnection -State Listen -LocalPort ([int]$Record.Port) -ErrorAction SilentlyContinue
    foreach ($connection in @($connections)) {
        $info = Get-ProcessInfo ([int]$connection.OwningProcess)
        $rootIds = @(Get-RecordIds $Record | Where-Object {
            $rootInfo = Get-ProcessInfo ([int]$_)
            (Test-ProcessInfoMatches $rootInfo $Record) -or (Test-LauncherProcess $rootInfo $Record)
        })
        $descendantIds = if ($rootIds.Count) { @(Get-DescendantProcessIds $rootIds) } else { @() }
        if ((Test-ProcessInfoMatches $info $Record) -or
            ($descendantIds -contains [int]$info.Id -and (Test-WorkspaceProcess $info $Record))) {
            return $info
        }
    }
    return $null
}

function Test-ListeningPort([int]$Port) {
    return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Get-FreePort([int[]]$Candidates) {
    foreach ($candidate in $Candidates) {
        if (-not (Test-ListeningPort $candidate)) { return $candidate }
    }
    throw "No free port in the requested range: $($Candidates -join ', ')"
}

function Read-State {
    if (-not (Test-Path -LiteralPath $script:StatePath)) { return @() }
    try {
        $content = Get-Content -LiteralPath $script:StatePath -Raw
        if ([string]::IsNullOrWhiteSpace($content)) { throw 'state file is empty' }
        if ($content.TrimStart()[0] -ne '[') { throw 'state root must be a JSON array' }
        $parsed = ConvertFrom-Json -InputObject $content
        if ($null -eq $parsed) { return @() }
        return @($parsed)
    } catch {
        throw "Unreadable service state '$($script:StatePath)': $($_.Exception.Message). Verify running processes before repairing it."
    }
}

function Write-State($Records) {
    Ensure-Runtime
    $temporary = "$($script:StatePath).$PID.tmp"
    try {
        $json = ConvertTo-Json -InputObject @($Records) -Depth 8
        Set-Content -LiteralPath $temporary -Value $json -Encoding utf8
        Move-Item -LiteralPath $temporary -Destination $script:StatePath -Force
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
}

function Start-OwnedService($Name, $FilePath, $Arguments, $WorkingDirectory, [int]$Port, $Url) {
    $existing = (Read-State | Where-Object Name -eq $Name | Select-Object -First 1)
    if ($existing -and (Test-OwnedProcess $existing)) { return $existing }
    if (Test-ListeningPort $Port) { throw "$Name port $Port is already occupied by an unrelated process" }
    $resolved = (Resolve-Path -LiteralPath $FilePath -ErrorAction Stop).Path
    $stdout = Join-Path $script:LogDir "$Name.out.log"
    $stderr = Join-Path $script:LogDir "$Name.err.log"
    $resolvedWorkingDirectory = (Resolve-Path -LiteralPath $WorkingDirectory -ErrorAction Stop).Path
    $process = Start-Process -FilePath $resolved -ArgumentList $Arguments -WorkingDirectory $resolvedWorkingDirectory -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    [pscustomobject]@{
        Name = $Name
        Id = $process.Id
        LauncherId = $process.Id
        ProcessIds = @($process.Id)
        StartedAt = $process.StartTime.ToUniversalTime().ToString('o')
        Executable = $resolved
        CommandLine = ($resolved + ' ' + ($Arguments -join ' '))
        Workspace = $resolvedWorkingDirectory
        Port = $Port
        Url = $Url
    }
}

function Resolve-ListeningRecord($Record) {
    $info = Get-ListeningProcessInfo $Record
    if ($null -eq $info) { throw "$($Record.Name) did not expose an owned listener on port $($Record.Port)" }
    $ids = @([int]$info.Id)
    foreach ($oldId in (Get-RecordIds $Record)) {
        $oldInfo = Get-ProcessInfo ([int]$oldId)
        if ((Test-ProcessInfoMatches $oldInfo $Record) -or (Test-LauncherProcess $oldInfo $Record)) { $ids += [int]$oldId }
    }
    [pscustomobject]@{
        Name = $Record.Name
        Id = [int]$info.Id
        ListenerId = [int]$info.Id
        LauncherId = if ($Record.LauncherId) { [int]$Record.LauncherId } else { [int]$info.Id }
        LauncherExecutable = if ($Record.LauncherExecutable) { $Record.LauncherExecutable } else { $Record.Executable }
        LauncherCommandLine = if ($Record.LauncherCommandLine) { $Record.LauncherCommandLine } else { $Record.CommandLine }
        LauncherStartedAt = if ($Record.LauncherStartedAt) { $Record.LauncherStartedAt } else { $Record.StartedAt }
        ProcessIds = @($ids | Select-Object -Unique)
        StartedAt = $info.Process.StartTime.ToUniversalTime().ToString('o')
        Executable = $info.Path
        CommandLine = $info.CommandLine
        Workspace = $Record.Workspace
        Port = [int]$Record.Port
        Url = $Record.Url
    }
}

function Wait-Ready($Record, [int]$TimeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $graceDeadline = (Get-Date).AddSeconds(10)
    do {
        $owned = Test-OwnedProcess $Record
        $listener = Get-ListeningProcessInfo $Record
        if (-not $owned -and $null -eq $listener -and (Get-Date) -gt $graceDeadline) {
            throw "$($Record.Name) stopped before readiness"
        }
        try {
            $response = Invoke-WebRequest -Uri $Record.Url -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300 -and $listener) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "$($Record.Name) did not become ready at $($Record.Url)"
}

function Get-DescendantProcessIds([int[]]$RootIds) {
    $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
    $known = @($RootIds | Select-Object -Unique)
    $pending = @($known)
    while ($pending.Count -gt 0) {
        $parent = [int]$pending[0]
        if ($pending.Count -eq 1) { $pending = @() } else { $pending = @($pending | Select-Object -Skip 1) }
        foreach ($child in ($all | Where-Object { [int]$_.ParentProcessId -eq $parent })) {
            $childId = [int]$child.ProcessId
            if ($known -notcontains $childId) {
                $known += $childId
                $pending += $childId
            }
        }
    }
    @($known | Select-Object -Unique)
}

function Get-OwnedProcessIds($Record) {
    $rootIds = @(Get-RecordIds $Record | Where-Object {
        $info = Get-ProcessInfo ([int]$_)
        (Test-ProcessInfoMatches $info $Record) -or (Test-LauncherProcess $info $Record)
    })
    if (-not $rootIds.Count) { return @() }
    $owned = @()
    foreach ($id in (Get-DescendantProcessIds $rootIds)) {
        $info = Get-ProcessInfo ([int]$id)
        if ($null -eq $info) { continue }
        $isRoot = $rootIds -contains [int]$id
        if ((Test-ProcessInfoMatches $info $Record) -or
            (Test-LauncherProcess $info $Record) -or
            (-not $isRoot -and (Test-WorkspaceProcess $info $Record))) {
            $owned += [int]$id
        }
    }
    @($owned | Select-Object -Unique)
}

function Start-All {
    Ensure-Runtime
    # Validate an existing state before any launch or cleanup can mutate it.
    $validatedState = @(Read-State)
    $records = @($validatedState)
    $startedRecords = @()
    try {
        $python = Join-Path $script:Workspace 'python-core\.venv\Scripts\python.exe'
        if (-not (Test-Path -LiteralPath $python)) { throw 'python-core/.venv is missing; install requirements-dev.txt first' }
        $pythonRoot = Join-Path $script:Workspace 'python-core'
        $existingPython = $validatedState | Where-Object Name -eq 'python' | Select-Object -First 1
        if ($existingPython -and (Test-OwnedProcess $existingPython)) {
            $pythonPort = [int]$existingPython.Port
            $pythonUrl = [string]$existingPython.Url
        } else {
            $pythonPort = Get-FreePort @(8000, 8001, 8002, 8003)
            if ($pythonPort -ne 8000) { Write-Warning "Port 8000 is occupied; using Python port $pythonPort" }
            $pythonUrl = "http://127.0.0.1:$pythonPort/api/health"
        }
        $record = Start-OwnedService 'python' $python @('-m', 'uvicorn', 'main:app', '--app-dir', $pythonRoot, '--host', '127.0.0.1', '--port', $pythonPort) $pythonRoot $pythonPort $pythonUrl
        if (-not $existingPython -or $existingPython.Id -ne $record.Id) { $startedRecords += $record }
        $records = @($records | Where-Object Name -ne 'python') + @($record); Write-State $records
        Wait-Ready $record 45
        $records[-1] = Resolve-ListeningRecord $record; Write-State $records

        $javaRoot = Join-Path $script:Workspace 'java-manager'
        $javaJar = Join-Path $javaRoot 'target\social-persona-manager-1.0.0-SNAPSHOT.jar'
        $existingJava = $validatedState | Where-Object Name -eq 'java' | Select-Object -First 1
        if (-not $existingJava -or -not (Test-OwnedProcess $existingJava)) {
            $maven = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
            if (-not $maven) { $maven = (Get-Command mvn -ErrorAction SilentlyContinue).Source }
            if (-not $maven) { throw 'Maven is missing from PATH' }
            & $maven '-f' (Join-Path $javaRoot 'pom.xml') 'package' '-DskipTests' '-B' '--no-transfer-progress'
            if ($LASTEXITCODE -ne 0) { throw 'Java package failed; inspect Maven output' }
        }
        if (-not (Test-Path -LiteralPath $javaJar)) { throw "Java jar was not created: $javaJar" }
        $java = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
        if (-not $java) { $java = (Get-Command java -ErrorAction Stop).Source }
        $record = Start-OwnedService 'java' $java @('-jar', $javaJar, "--python.core.url=$($pythonUrl -replace '/api/health$','')") $javaRoot 8080 'http://127.0.0.1:8080/api/health'
        if (-not $existingJava -or $existingJava.Id -ne $record.Id) { $startedRecords += $record }
        $records = @($records | Where-Object Name -ne 'java') + @($record); Write-State $records
        Wait-Ready $record 120
        $records[-1] = Resolve-ListeningRecord $record; Write-State $records

        $node = (Get-Command node.exe -ErrorAction SilentlyContinue).Source
        if (-not $node) { $node = (Get-Command node -ErrorAction Stop).Source }
        $frontendRoot = Join-Path $script:Workspace 'frontend'
        $vite = Join-Path $frontendRoot 'node_modules\vite\bin\vite.js'
        if (-not (Test-Path -LiteralPath $vite)) { throw 'frontend/node_modules is missing; run npm ci first' }
        $existingFrontend = $validatedState | Where-Object Name -eq 'frontend' | Select-Object -First 1
        if ($existingFrontend -and (Test-OwnedProcess $existingFrontend)) {
            $frontendPort = [int]$existingFrontend.Port
        } else {
            $frontendPort = Get-FreePort @(5173, 5174, 5175, 5176)
        }
        if ($frontendPort -ne 5173) { Write-Warning "Port 5173 is occupied; using frontend port $frontendPort" }
        $frontendUrl = "http://127.0.0.1:$frontendPort/"
        $record = Start-OwnedService 'frontend' $node @($vite, '--host', '127.0.0.1', '--port', $frontendPort, '--strictPort') $frontendRoot $frontendPort $frontendUrl
        if (-not $existingFrontend -or $existingFrontend.Id -ne $record.Id) { $startedRecords += $record }
        $records = @($records | Where-Object Name -ne 'frontend') + @($record); Write-State $records
        Wait-Ready $record 45
        $records[-1] = Resolve-ListeningRecord $record; Write-State $records
        Write-Host "Services ready: $frontendUrl (Python $($pythonUrl -replace '/api/health$',''), Java http://127.0.0.1:8080)"
    } catch {
        Stop-All -Records $startedRecords
        throw
    }
}

function Stop-All {
    param([object[]]$Records)
    $state = @(Read-State)
    $stopRecords = if ($PSBoundParameters.ContainsKey('Records')) { @($Records) } else { @($state) }
    $remaining = @($state | Where-Object { $stopRecords.Name -notcontains $_.Name })
    foreach ($record in $stopRecords) {
        $ids = @(Get-OwnedProcessIds $record)
        if ($ids.Count -eq 0) {
            foreach ($id in (Get-RecordIds $record)) {
                if (Get-Process -Id ([int]$id) -ErrorAction SilentlyContinue) {
                    Write-Warning "Refused to stop PID ${id}: ownership check failed"
                    if ($remaining.Name -notcontains $record.Name) { $remaining += $record }
                }
            }
            continue
        }
        foreach ($id in ($ids | Sort-Object -Descending)) {
            Stop-Process -Id ([int]$id) -Force -ErrorAction SilentlyContinue
            Write-Host "Stopped $($record.Name) ($id)"
        }
    }
    Write-State $remaining
}

function Show-Status {
    $rows = foreach ($record in (Read-State)) {
        [pscustomobject]@{
            Name = $record.Name
            Id = $record.Id
            Port = $record.Port
            Owned = (Test-OwnedProcess $record)
            Listening = (Test-ListeningPort ([int]$record.Port))
            Url = $record.Url
        }
    }
    if ($rows) { $rows | Format-Table -AutoSize } else { Write-Host 'No services recorded.' }
}

function Show-Url {
    $frontend = Read-State | Where-Object Name -eq 'frontend' | Select-Object -First 1
    if ($frontend -and $frontend.Url) { Write-Output $frontend.Url } else { Write-Output 'http://127.0.0.1:5173/' }
}

if ($Action -eq 'library') { return }
switch ($Action) {
    'start' { Start-All }
    'stop' { Stop-All }
    'status' { Show-Status }
    'url' { Show-Url }
}
