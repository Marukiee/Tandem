<#
Tries the Windows app the way a person does, on this machine, and fails when something is wrong: it installs, starts, shows its
windows, pairs with a second device, moves the clipboard and files both ways, survives an update and a crash, and goes away again.

    pwsh scripts/windows-smoke.ps1 -Setup dist-windows/Tandem-Windows-x64-setup.exe -Daemon dist-windows/tandemd-windows-x86_64.zip

The second device is tandemd on this same machine. The app is driven through the debugging port of its web view, which needs nothing
in the app itself and nothing installed. What it finds lands in -Out (summary.md, screenshots, logs, every text on every page).
It needs a desktop: run it in a logged-in session, not straight from SSH. It takes over the clipboard and the screen while it runs.
#>
param(
    [Parameter(Mandatory = $true)][string]$Setup,
    [Parameter(Mandatory = $true)][string]$Daemon,
    [string]$Out = "smoke",
    [switch]$KeepInstalled,
    [int]$DebugPort = 9222
)

$ErrorActionPreference = "Continue"
New-Item -ItemType Directory -Force $Out | Out-Null
$Out = (Resolve-Path $Out).Path
$Setup = (Resolve-Path $Setup).Path
$Daemon = (Resolve-Path $Daemon).Path
$walk = Join-Path $PSScriptRoot "windows-smoke-walk.js"
$summary = Join-Path $Out "summary.md"
Set-Content $summary "# Windows smoke test`n"
$started = Get-Date
$script:failures = New-Object System.Collections.ArrayList
$script:pageErrors = @{}

$installed = Join-Path $env:LOCALAPPDATA "Tandem"
$exe = Join-Path $installed "Tandem.exe"
$log = Join-Path $env:LOCALAPPDATA "nl.markmaaktmedia.tandem\logs\tandem.log"
$mainPage = "index\.html|tauri\.localhost/?$"

function Note([string]$text) {
    Write-Host $text
    Add-Content $summary $text
}

function Check([bool]$ok, [string]$what, [string]$detail = "") {
    $line = $(if ($ok) { "- ok: $what" } else { "- **FAILED: $what**" })
    if ($detail) { $line += " ($detail)" }
    Note $line
    if (-not $ok) { [void]$script:failures.Add($what) }
    return
}

# One part going wrong says nothing about the next, so a part that throws is a failure and the test goes on.
function Part([string]$title, [scriptblock]$body) {
    Note "`n## $title"
    try { & $body } catch { Check $false "$title stopped" "$($_.Exception.Message)" }
}

function WaitFor([scriptblock]$test, [int]$seconds) {
    $until = (Get-Date).AddSeconds($seconds)
    do {
        try {
            $value = & $test
            if ($value) { return $value }
        } catch { }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $until)
    return $null
}

function Shot([string]$name) {
    try {
        Add-Type -AssemblyName System.Windows.Forms, System.Drawing
        $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
        $bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
        $bitmap.Save((Join-Path $Out "$name.png"))
        $graphics.Dispose()
        $bitmap.Dispose()
    } catch { Write-Host "no picture of the screen for ${name}: $($_.Exception.Message)" }
}

function Memory([string]$label) {
    $own = (Get-Process -Name Tandem -ErrorAction SilentlyContinue | Measure-Object -Property WorkingSet64 -Sum).Sum / 1MB
    $views = @(Get-Process -Name msedgewebview2 -ErrorAction SilentlyContinue)
    $web = ($views | Measure-Object -Property WorkingSet64 -Sum).Sum / 1MB
    Note ("- memory, {0}: Tandem {1:N0} MB, {2} web view processes {3:N0} MB" -f $label, $own, $views.Count, $web)
    return $own
}

# ---- The log of the app ---------------------------------------------------------------------------------------------------------

function LogCount([string]$pattern) {
    if (-not (Test-Path $log)) { return 0 }
    return @(Select-String -Path $log -Pattern $pattern).Count
}

# The app starts a new log with every start, so what the run before wrote is looked at before that happens.
function SaveLog([string]$label) {
    if (-not (Test-Path $log)) { return }
    Copy-Item $log (Join-Path $Out "$label-tandem.log") -Force
    $bad = @(Select-String -Path $log -Pattern "panic:| ERROR " | ForEach-Object { $_.Line })
    Check ($bad.Count -eq 0) "no panic or error in the log of $label" ($bad | Select-Object -First 3 | Out-String).Trim()
}

function AppRuns { return @(Get-Process -Name Tandem -ErrorAction SilentlyContinue).Count -gt 0 }

function AppResponds {
    $processes = @(Get-Process -Name Tandem -ErrorAction SilentlyContinue)
    return $processes.Count -gt 0 -and @($processes | Where-Object { -not $_.Responding }).Count -eq 0
}

# ---- The web view, through its debugging port --------------------------------------------------------------------------------

function FindPage([string]$pattern, [int]$seconds = 45) {
    return WaitFor {
        $pages = Invoke-RestMethod "http://127.0.0.1:$DebugPort/json/list" -TimeoutSec 3
        $pages | Where-Object { $_.type -eq "page" -and $_.url -match $pattern } | Select-Object -First 1
    } $seconds
}

function Evaluate($page, [string]$expression, [int]$seconds = 120) {
    $socket = New-Object System.Net.WebSockets.ClientWebSocket
    $cancel = New-Object System.Threading.CancellationTokenSource ([TimeSpan]::FromSeconds($seconds))
    try {
        $socket.ConnectAsync([Uri]$page.webSocketDebuggerUrl, $cancel.Token).GetAwaiter().GetResult()
        # Turning the runtime on also hands over what the page said in the console before anyone was listening.
        $requests = @(
            @{ id = 1; method = "Runtime.enable" },
            @{ id = 2; method = "Runtime.evaluate"; params = @{ expression = $expression; awaitPromise = $true; returnByValue = $true } }
        )
        foreach ($request in $requests) {
            $bytes = [Text.Encoding]::UTF8.GetBytes(($request | ConvertTo-Json -Compress -Depth 5))
            $socket.SendAsync([ArraySegment[byte]]::new($bytes), [Net.WebSockets.WebSocketMessageType]::Text, $true, $cancel.Token).GetAwaiter().GetResult()
        }
        $buffer = New-Object byte[] 65536
        while ($true) {
            $stream = New-Object System.IO.MemoryStream
            do {
                $got = $socket.ReceiveAsync([ArraySegment[byte]]::new($buffer), $cancel.Token).GetAwaiter().GetResult()
                $stream.Write($buffer, 0, $got.Count)
            } while (-not $got.EndOfMessage)
            $message = [Text.Encoding]::UTF8.GetString($stream.ToArray()) | ConvertFrom-Json
            if ($message.method -eq "Runtime.exceptionThrown") {
                $details = $message.params.exceptionDetails
                $text = $(if ($details.exception.description) { $details.exception.description } else { $details.text })
                $script:pageErrors[[string]$text] = $page.url
            } elseif ($message.method -eq "Runtime.consoleAPICalled" -and $message.params.type -eq "error") {
                $text = ($message.params.args | ForEach-Object { if ($_.value) { $_.value } else { $_.description } }) -join " "
                $script:pageErrors[[string]$text] = $page.url
            } elseif ($message.id -eq 2) {
                $details = $message.result.exceptionDetails
                if ($details) {
                    $why = $(if ($details.exception.value) { $details.exception.value } elseif ($details.exception.description) { $details.exception.description } else { $details.text })
                    throw "the page said: $why"
                }
                return $message.result.result.value
            }
        }
    } finally {
        $socket.Dispose()
        $cancel.Dispose()
    }
}

function App([string]$command, $arguments = @{}) {
    $page = FindPage $mainPage
    if (-not $page) { throw "the main window has no web view to ask" }
    $json = $arguments | ConvertTo-Json -Compress -Depth 6
    return Evaluate $page "window.__TAURI__.core.invoke('$command', $json)"
}

# Every page in both languages: the words are kept for reading, and what is plainly wrong fails the test.
function WalkPages([string]$pattern, [string]$label) {
    $page = FindPage $pattern
    if (-not $page) {
        Check $false "$label has a web view to look at"
        return
    }
    $report = Evaluate $page (Get-Content $walk -Raw -Encoding UTF8)
    $text = ($report.steps | ForEach-Object { "## [$($_.lang)] $($_.where)`n$($_.text)`n" }) -join "`n"
    Add-Content (Join-Path $Out "ui-text.txt") "# $label`n`n$text" -Encoding UTF8
    $lines = @($report.findings | ForEach-Object { "[$($_.lang)] $($_.where): $($_.what): $($_.excerpt)" })
    foreach ($line in $lines) { Write-Host $line }
    Add-Content (Join-Path $Out "ui-findings.txt") $lines -Encoding UTF8
    Check ($lines.Count -eq 0) "nothing wrong with the words on $($report.steps.Count) views of $label" ($lines | Select-Object -First 3 | Out-String).Trim()
    foreach ($mac in @($report.macWords)) { Add-Content (Join-Path $Out "ui-mac-words.txt") "[$($mac.lang)] $($mac.where): $($mac.line)" -Encoding UTF8 }
}

# ---- The second device -----------------------------------------------------------------------------------------------------------

$work = Join-Path $Out "work"
$helperData = Join-Path $work "data"
$helperFiles = Join-Path $work "received"
$helperOut = Join-Path $Out "helper.log"
$tandemd = Join-Path $work "daemon\tandemd.exe"
$script:helper = $null

function Helper([string[]]$arguments, [int]$seconds = 60) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $tandemd
    $info.Arguments = (@("--data-dir", $helperData) + $arguments | ForEach-Object { '"' + $_ + '"' }) -join " "
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($info)
    $output = $process.StandardOutput.ReadToEndAsync()
    $errors = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($seconds * 1000)) {
        $process.Kill()
        return [pscustomobject]@{ ok = $false; text = "no answer in $seconds seconds" }
    }
    return [pscustomobject]@{ ok = ($process.ExitCode -eq 0); text = ($output.Result + $errors.Result).Trim() }
}

function HelperSaid([string]$pattern) {
    return (Test-Path $helperOut) -and (Select-String -Path $helperOut -Pattern $pattern -Quiet)
}

function DeviceOnline([string]$id) {
    $state = App "get_state"
    return @($state.devices | Where-Object { $_.id -eq $id -and $_.online }).Count -gt 0
}

$script:appId = $null
$script:helperId = $null

# ---- Install and start --------------------------------------------------------------------------------------------------------

# The port is asked for from outside, so the build that is tested is the build people get.
$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = "--remote-debugging-port=$DebugPort"
# The panel goes away a few seconds after it closes instead of a few minutes, so that can be seen here too.
$env:TANDEM_PANEL_KEPT_SECONDS = "6"

Part "Install" {
    $process = Start-Process $Setup -ArgumentList "/S" -Wait -PassThru
    Check ($process.ExitCode -eq 0) "the installer ends well" "exit code $($process.ExitCode)"
    Check (Test-Path $exe) "the installer puts Tandem.exe in place" $exe
}

if (-not (Test-Path $exe)) {
    Note "`nNothing to try without the app."
    exit 1
}

Part "First start" {
    # The console of a runner is out of the way, so the window of the app is what is in the picture.
    if ($env:CI) { (New-Object -ComObject Shell.Application).MinimizeAll() }
    if (Test-Path $log) { Move-Item $log (Join-Path $Out "before-tandem.log") -Force }
    Start-Process $exe
    # The app says in its log what it has done. A cold start of the web view can take long on a new machine.
    Check ([bool](WaitFor { (LogCount "the engine runs") -gt 0 } 90)) "the engine starts"
    Check ([bool](WaitFor { (LogCount "the main window shows") -gt 0 } 120)) "the main window shows"
    Start-Sleep -Seconds 4
    Shot "1-main"
    Check (AppResponds) "the app responds"
    [void](Memory "window open")
    $state = App "get_state"
    Check ($state.ready -and -not $state.error) "the window sees a running engine" "$($state.error)"
    $script:appId = $state.self.id
    Note "- version $($state.version), running as $($state.self.name) on port $($state.self.port), language $($state.systemLanguage)"
    Note "- Windows $([Environment]::OSVersion.Version) $env:PROCESSOR_ARCHITECTURE"
}

Part "Every page, nothing paired yet" {
    WalkPages $mainPage "the main window with no devices"
    Shot "2-settings"
}

# ---- A second device ------------------------------------------------------------------------------------------------------------

Part "Pairing" {
    New-Item -ItemType Directory -Force $helperData, $helperFiles | Out-Null
    Expand-Archive $Daemon (Join-Path $work "daemon") -Force
    $script:helper = Start-Process $tandemd -PassThru -NoNewWindow -RedirectStandardOutput $helperOut -RedirectStandardError "$helperOut.err" `
        -ArgumentList "--data-dir", "`"$helperData`"", "--download-dir", "`"$helperFiles`"", "--name", "SmokeHelper", "--port", "47901", "run"
    Check ([bool](WaitFor { Test-Path (Join-Path $helperData "tandemd.port") } 30)) "the second device runs"
    $offer = App "create_pairing"
    Check ([bool]$offer.uri) "the app gives a pairing link with a code" "code of $("$($offer.code)".Length) digits"
    $paired = Helper @("pair", $offer.uri)
    Check $paired.ok "the second device pairs with the link" $paired.text
    $script:helperId = ($paired.text -replace "^paired with\s*", "").Trim()
    Check ([bool](WaitFor { DeviceOnline $script:helperId } 30)) "the app shows the second device as connected"
    Shot "3-paired"
}

$pairedWell = $script:helperId -and (DeviceOnline $script:helperId)

Part "Every page, with a device" {
    if ($pairedWell) { WalkPages $mainPage "the main window with a computer paired" }
}

if ($pairedWell) {
    Part "Clipboard" {
        $word = "smoke-" + [Guid]::NewGuid().ToString("N")
        $sent = Helper @("clip", $script:appId, $word)
        Check $sent.ok "the second device sends its clipboard" $sent.text
        Check ([bool](WaitFor { (Get-Clipboard -Raw) -match $word } 20)) "it lands on the clipboard of Windows"

        $word = "smoke-" + [Guid]::NewGuid().ToString("N")
        Set-Clipboard $word
        Check ([bool](WaitFor { HelperSaid $word } 20)) "what is copied in Windows reaches the second device"
    }

    Part "Files" {
        # A space and a letter outside ASCII: the kind of name people have and programs trip over.
        $name = "smoke " + [char]0xEB + " file.bin"
        $source = Join-Path $work $name
        $bytes = New-Object byte[] (8MB)
        (New-Object Random).NextBytes($bytes)
        [IO.File]::WriteAllBytes($source, $bytes)
        $hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash

        $state = App "get_state"
        $sent = Helper @("send", $script:appId, $source)
        Check $sent.ok "the second device offers a file" $sent.text
        $landed = WaitFor {
            $state = App "get_state"
            Get-ChildItem -LiteralPath $state.downloadDir -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -eq $name -and (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash -eq $hash } | Select-Object -First 1
        } 60
        Check ([bool]$landed) "the file lands in the download folder, whole" "$($state.downloadDir)"
        if ($landed) { Remove-Item -LiteralPath $landed.FullName -Force }

        [void](App "send_paths" @{ ids = @($script:helperId); paths = @($source) })
        $came = WaitFor {
            Get-ChildItem -LiteralPath $helperFiles -File -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -eq $name -and (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash -eq $hash } | Select-Object -First 1
        } 60
        Check ([bool]$came) "a file sent from the app reaches the second device, whole"
    }
}

# ---- The windows --------------------------------------------------------------------------------------------------------------

Part "Windows open and close" {
    $handles = @(Get-Process -Name Tandem -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 })
    Check ($handles.Count -gt 0) "the app has a window to close"
    foreach ($process in $handles) { [void]$process.CloseMainWindow() }
    Start-Sleep -Seconds 10
    Check (AppRuns) "closing the window leaves the app running in the tray"
    $own = Memory "window closed"
    Check ($own -lt 200) "the app holds little with its windows closed" ("{0:N0} MB" -f $own)
    # Only here: on a machine of a person other programs have web views too.
    if ($env:CI) { Check (@(Get-Process -Name msedgewebview2 -ErrorAction SilentlyContinue).Count -eq 0) "closing the window gives the web view back" }

    $before = LogCount "the panel opens"
    Start-Process $exe -ArgumentList "--panel"
    Check ([bool](WaitFor { (LogCount "the panel opens") -gt $before } 90)) "a second start with --panel opens the panel"
    Start-Sleep -Seconds 2
    Shot "4-panel"
    Check (@(Get-Process -Name Tandem -ErrorAction SilentlyContinue).Count -eq 1) "there is still one Tandem"
    [void](Memory "panel open")
    WalkPages "panel\.html" "the panel"

    $before = LogCount "the main window shows"
    Start-Process $exe
    Check ([bool](WaitFor { (LogCount "the main window shows") -gt $before } 60)) "a second start brings the main window back"
    Check (AppResponds) "the app responds"
}

# ---- Updates, crashes, the end --------------------------------------------------------------------------------------------------

Part "An update over the running app" {
    SaveLog "first start"
    $before = @(Get-Process -Name Tandem -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
    # What update.rs starts: it knows the app runs, takes it away, and starts it again when it is done.
    $process = Start-Process $Setup -ArgumentList "/P", "/R", "/UPDATE" -PassThru
    Check ($process.WaitForExit(180000) -and $process.ExitCode -eq 0) "the installer replaces the running app" "exit code $($process.ExitCode)"
    $again = WaitFor { @(Get-Process -Name Tandem -ErrorAction SilentlyContinue | Where-Object { $before -notcontains $_.Id }).Count -gt 0 } 60
    Check ([bool]$again) "the app starts again by itself"
    Check ([bool](WaitFor { (LogCount "the engine runs") -gt 0 } 60)) "the engine starts after the update"
    $state = App "get_state"
    Check ($state.self.id -eq $script:appId) "it is still the same device"
    if ($pairedWell) { Check ([bool](WaitFor { DeviceOnline $script:helperId } 60)) "the second device connects again" }
    Shot "5-updated"
}

Part "A hard stop, then the screen and the encoder" {
    SaveLog "after the update"
    Stop-Process -Name Tandem -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    # Measures taking the screen and encoding it the way showing the screen to another device does, and says so in the log.
    $env:TANDEM_BENCH_HOST = "1"
    $shots = Join-Path $work "shots"
    $env:TANDEM_DEBUG_SHOT = $shots
    Start-Process $exe
    Remove-Item Env:\TANDEM_BENCH_HOST, Env:\TANDEM_DEBUG_SHOT
    Check ([bool](WaitFor { (LogCount "the engine runs") -gt 0 } 60)) "the engine starts after a hard stop"
    $state = App "get_state"
    Check ($state.self.id -eq $script:appId) "it is still the same device after a hard stop"
    if ($pairedWell) { Check ([bool](WaitFor { DeviceOnline $script:helperId } 60)) "the second device connects again after a hard stop" }
    Check ([bool](WaitFor { Test-Path (Join-Path $shots "screen.png") } 60)) "the screen can be taken"
    Check ([bool](WaitFor { (LogCount "bench host: BGRx") -gt 0 } 120)) "the bench of the screen ends"
    $bench = @(Select-String -Path $log -Pattern "bench host:" | ForEach-Object { $_.Line -replace "^.*bench host:", "" })
    foreach ($line in $bench) { Note "- bench:$line" }
    Check (-not (@($bench) -match "could not|did not start|not encoded \(|first picture was not")) "the encoder gives pictures"
    Check (-not (@($bench) -match " 0 not encoded") -or (@($bench) -match "[1-9]\d* new pictures")) "the screen gives new pictures"
    Copy-Item (Join-Path $shots "screen.png") (Join-Path $Out "6-taken-by-the-app.png") -ErrorAction SilentlyContinue
}

Part "Removing a device, and quitting" {
    if ($pairedWell) {
        [void](App "remove_device" @{ id = $script:helperId })
        Check ([bool](WaitFor { @((App "get_state").devices | Where-Object { $_.id -eq $script:helperId }).Count -eq 0 } 20)) "a removed device is gone from the app"
    }
    try { [void](App "quit_app") } catch { }
    Check ([bool](WaitFor { -not (AppRuns) } 30)) "Quit ends the app"
    SaveLog "the last run"
}

if ($script:helper) { Stop-Process -Id $script:helper.Id -Force -ErrorAction SilentlyContinue }

if (-not $KeepInstalled) {
    Part "Uninstall" {
        $uninstall = Join-Path $installed "uninstall.exe"
        Check (Test-Path $uninstall) "there is an uninstaller"
        Start-Process $uninstall -ArgumentList "/S" -Wait
        Check ([bool](WaitFor { -not (Test-Path $exe) } 60)) "the uninstaller removes Tandem.exe"
        $run = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -ErrorAction SilentlyContinue
        Check (-not ($run.PSObject.Properties.Name -match "Tandem")) "nothing is left that starts Tandem with Windows"
    }
}

# ---- What Windows and the pages saw meanwhile --------------------------------------------------------------------------------------

Part "What went wrong out of sight" {
    $crashes = @(Get-WinEvent -FilterHashtable @{ LogName = "Application"; ProviderName = "Application Error"; StartTime = $started } -ErrorAction SilentlyContinue |
        Where-Object { $_.Message -match "Tandem|tandemd|msedgewebview2" } | ForEach-Object { ($_.Message -split "`n")[0..2] -join " " })
    Check ($crashes.Count -eq 0) "Windows saw no crash of Tandem or its web view" ($crashes | Select-Object -First 2 | Out-String).Trim()
    $errors = @($script:pageErrors.GetEnumerator() | ForEach-Object { "$($_.Value): $($_.Key)" })
    Add-Content (Join-Path $Out "page-errors.txt") $errors -Encoding UTF8
    Check ($errors.Count -eq 0) "the pages threw no errors" ($errors | Select-Object -First 3 | Out-String).Trim()
}

Remove-Item (Join-Path $work "daemon") -Recurse -Force -ErrorAction SilentlyContinue

if ($env:GITHUB_STEP_SUMMARY) { Get-Content $summary | Add-Content $env:GITHUB_STEP_SUMMARY }

Note "`n$($script:failures.Count) failed, after $([int]((Get-Date) - $started).TotalSeconds) seconds."
foreach ($failure in $script:failures) { Write-Host "FAILED: $failure" }
if ($script:failures.Count -gt 0) { exit 1 }
exit 0
