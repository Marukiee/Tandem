<#
Tries the Windows app the way a person does, on this machine, and fails when something is wrong: it installs, starts, shows its
windows, pairs with a second device, moves the clipboard and files both ways, shares its mouse in both roles, survives an update and
a crash, and goes away again.

    pwsh scripts/windows-smoke.ps1 -Setup dist-windows/Tandem-Windows-x64-setup.exe -Daemon dist-windows/tandemd-windows-x86_64.zip

The second device is tandemd on this same machine. The app is driven through the debugging port of its web view and asks for that port
when TANDEM_TEST_DEBUG_PORT is set; nothing gets installed for it. What it finds lands in -Out (summary.md, screenshots, logs, every
text on every page). It needs a desktop: run it in a logged-in session, not straight from SSH. It takes over the clipboard, the mouse,
the keyboard and the screen while it runs, so nobody can use the machine meanwhile.
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

# The hands of a person, as far as a script can be them. The hooks of the app ignore input that a program makes, unless it carries the
# mark that TEST_HANDS in winsys/src/imp.rs looks for. All in pixels of the screen, whatever the scale of the display.
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class SmokeHands {
    public struct Point { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)] struct MouseInput { public int dx, dy; public uint data, flags, time; public IntPtr extra; }
    [StructLayout(LayoutKind.Sequential)] struct KeyInput { public ushort vk, scan; public uint flags, time; public IntPtr extra; }
    [StructLayout(LayoutKind.Explicit)] struct Either { [FieldOffset(0)] public MouseInput mouse; [FieldOffset(0)] public KeyInput key; }
    [StructLayout(LayoutKind.Sequential)] struct Input { public uint type; public Either what; }
    [DllImport("user32.dll")] static extern uint SendInput(uint count, Input[] inputs, int size);
    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] static extern bool GetCursorPos(out Point point);
    [DllImport("user32.dll")] static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] static extern short GetAsyncKeyState(int key);
    static readonly IntPtr Mark = new IntPtr(0x54414E44);
    static SmokeHands() { SetProcessDPIAware(); }
    static void Send(Input input) { SendInput(1, new[] { input }, Marshal.SizeOf(typeof(Input))); }
    static void Mouse(int dx, int dy, uint flags) {
        var input = new Input { type = 0 };
        input.what.mouse = new MouseInput { dx = dx, dy = dy, flags = flags, extra = Mark };
        Send(input);
    }
    public static void Move(int dx, int dy) { Mouse(dx, dy, 1); }
    public static void Left(bool down) { Mouse(0, 0, down ? 2u : 4u); }
    public static uint Key(ushort vk, bool down) {
        var input = new Input { type = 1 };
        input.what.key = new KeyInput { vk = vk, flags = down ? 0u : 2u, extra = Mark };
        return SendInput(1, new[] { input }, Marshal.SizeOf(typeof(Input)));
    }
    public static void Put(int x, int y) { SetCursorPos(x, y); }
    public static Point At() { Point point; GetCursorPos(out point); return point; }
    public static int Width { get { return GetSystemMetrics(0); } }
    public static int Height { get { return GetSystemMetrics(1); } }
    public static bool LeftIsDown { get { return (GetAsyncKeyState(1) & 0x8000) != 0; } }
    public static bool IsDown(int key) { return (GetAsyncKeyState(key) & 0x8000) != 0; }
}
"@

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
        # In real pixels: what .NET says about the screen is scaled at 125% and cuts off the picture.
        $size = New-Object System.Drawing.Size ([SmokeHands]::Width), ([SmokeHands]::Height)
        $bitmap = New-Object System.Drawing.Bitmap $size.Width, $size.Height
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.CopyFromScreen([System.Drawing.Point]::Empty, [System.Drawing.Point]::Empty, $size)
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
                # The page went away while it was being asked: an answer of the debugger, with nothing of the page in it.
                if ($message.error) { throw "the debugger said: $($message.error.message)" }
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
    # A web view that has only just started answers before its page has the bridge to the app.
    return Evaluate $page "new Promise((ready) => { const look = () => (window.__TAURI__ ? ready() : setTimeout(look, 100)); look(); }).then(() => window.__TAURI__.core.invoke('$command', $json))"
}

# Whether the app really answers: a frozen one still counts as responding to Windows.
function AppAnswers {
    try {
        $page = FindPage $mainPage 10
        if (-not $page) { $page = FindPage "panel\.html" 5 }
        return (Evaluate $page "window.__TAURI__.core.invoke('get_state').then(() => true)" 15) -eq $true
    } catch { return $false }
}

# Every page in both languages: the words are kept for reading, and what is plainly wrong fails the test.
function WalkPages([string]$pattern, [string]$label) {
    $page = FindPage $pattern
    if (-not $page) {
        Check $false "$label has a web view to look at"
        return
    }
    $report = Evaluate $page (Get-Content $walk -Raw -Encoding UTF8)
    # Nothing piped into a loop still goes round once, which would read as a finding with nothing in it.
    if (-not $report) {
        Check $false "$label could be looked at" "the page gave no report"
        return
    }
    $text = ($report.steps | ForEach-Object { "## [$($_.lang)] $($_.where)`n$($_.text)`n" }) -join "`n"
    Add-Content (Join-Path $Out "ui-text.txt") "# $label`n`n$text" -Encoding UTF8
    $lines = @($report.findings | Where-Object { $_.what } | ForEach-Object { "[$($_.lang)] $($_.where): $($_.what): $($_.excerpt)" })
    foreach ($line in $lines) { Write-Host $line }
    Add-Content (Join-Path $Out "ui-findings.txt") $lines -Encoding UTF8
    Check ($lines.Count -eq 0) "nothing wrong with the words on $($report.steps.Count) views of $label" ($lines | Select-Object -First 3 | Out-String).Trim()
    foreach ($mac in @($report.macWords | Where-Object { $_.line })) { Add-Content (Join-Path $Out "ui-mac-words.txt") "[$($mac.lang)] $($mac.where): $($mac.line)" -Encoding UTF8 }
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

function StartHelper([string[]]$more = @()) {
    # Its screen is as big as this one, so the whole edge of this one leads to it.
    $arguments = @("--data-dir", "`"$helperData`"", "--download-dir", "`"$helperFiles`"", "--name", "SmokeHelper", "--port", "47901", 
        "--pretend-cap", "pointer.ping", "--pretend-pointer-screen", "$([SmokeHands]::Width)x$([SmokeHands]::Height)") + $more + @("run")
    $script:helper = Start-Process $tandemd -PassThru -NoNewWindow -RedirectStandardOutput $helperOut -RedirectStandardError "$helperOut.err" -ArgumentList $arguments
}

function HelperSaid([string]$pattern) {
    return (Test-Path $helperOut) -and (Select-String -Path $helperOut -Pattern $pattern -Quiet)
}

function HelperCount([string]$pattern) {
    if (-not (Test-Path $helperOut)) { return 0 }
    return @(Select-String -Path $helperOut -Pattern $pattern).Count
}

function Say([string[]]$what) {
    $said = Helper (@("pointer", $script:appId) + $what) 20
    if (-not $said.ok) { throw "tandemd pointer $($what -join ' '): $($said.text)" }
}

function DeviceOnline([string]$id) {
    $state = App "get_state"
    return @($state.devices | Where-Object { $_.id -eq $id -and $_.online }).Count -gt 0
}

$script:appId = $null
$script:helperId = $null

# ---- Install and start --------------------------------------------------------------------------------------------------------

$env:TANDEM_TEST_DEBUG_PORT = "$DebugPort"
$env:TANDEM_TEST_HANDS = "1"
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
    # A run that stopped halfway leaves its second device behind on a machine that keeps the app.
    foreach ($old in @($state.devices | Where-Object { $_.name -eq "SmokeHelper" })) { [void](App "remove_device" @{ id = $old.id }) }
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
    StartHelper
    Check ([bool](WaitFor { Test-Path (Join-Path $helperData "tandemd.port") } 30)) "the second device runs"
    $offer = App "create_pairing"
    Check ([bool]$offer.uri) "the app gives a pairing link with a code" "code of $("$($offer.code)".Length) digits"
    $paired = Helper @("pair", $offer.uri)
    Check $paired.ok "the second device pairs with the link" $paired.text
    $script:helperId = ((Helper @("whoami")).text -split "\s+")[0]
    Check ([bool](WaitFor { DeviceOnline $script:helperId } 30)) "the app shows the second device as connected"
    Shot "3-paired"
}

$pairedWell = $script:helperId -and (DeviceOnline $script:helperId)

if (-not $pairedWell) {
    # What was never tried must not look like what went well.
    foreach ($what in "the pages with a device", "the clipboard", "files", "the shared mouse", "removing a device") { Check $false "$what could not be tried without a second device" }
}

Part "Every page, with a device" {
    if ($pairedWell) { WalkPages $mainPage "the main window with a computer paired" }
}

$sendTo = Join-Path $env:APPDATA "Microsoft\Windows\SendTo"
$helperLink = Join-Path $sendTo "SmokeHelper (Tandem).lnk"

if ($pairedWell) {
    Part "Send to in Explorer" {
        Check ([bool](WaitFor { Test-Path $helperLink } 20)) "a paired device gets a link in Send to"
    }
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

        # The way a password manager copies: the settings promise this stays here. The copy before this one shows the way itself works.
        $word = "smoke-secret-" + [Guid]::NewGuid().ToString("N")
        Add-Type -AssemblyName System.Windows.Forms
        $data = New-Object System.Windows.Forms.DataObject
        $data.SetText($word)
        $data.SetData("ExcludeClipboardContentFromMonitorProcessing", (New-Object IO.MemoryStream (, [byte[]](0, 0, 0, 0))))
        [System.Windows.Forms.Clipboard]::SetDataObject($data, $true)
        Start-Sleep -Seconds 6
        Check ((Get-Clipboard -Raw) -match $word) "a copy marked as secret is on the clipboard"
        Check (-not (HelperSaid $word)) "a copy marked as secret does not reach the second device"
        $history = (App "clip_history") | ConvertTo-Json -Depth 6 -Compress
        Check ($history -notmatch $word) "a copy marked as secret stays out of the history"
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

    # A window in the middle of the screen, to see what clicks and keys do. In a process of its own: a window that looks at its messages only
    # now and then is soon taken for hung, and Windows then puts a ghost in its place that swallows the input.
    $windowState = Join-Path $work "window.txt"
    $windowScript = Join-Path $work "window.ps1"
    Set-Content $windowScript -Encoding UTF8 @'
param([string]$state)
Add-Type -Name Dpi -Namespace Smoke -MemberDefinition '[DllImport("user32.dll")] public static extern bool SetProcessDPIAware();'
[void][Smoke.Dpi]::SetProcessDPIAware()
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$script:clicks = 0
$screen = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$form = New-Object System.Windows.Forms.Form
$form.Text = "Tandem smoke test"
$form.TopMost = $true
$form.StartPosition = "Manual"
$form.ClientSize = New-Object System.Drawing.Size 600, 300
$form.Location = New-Object System.Drawing.Point ([int]($screen.Width / 2) - 300), ([int]($screen.Height / 2) - 150)
$box = New-Object System.Windows.Forms.TextBox
$box.Multiline = $true
$box.Dock = "Fill"
function Save { [IO.File]::WriteAllText($state, "$($script:clicks)`n$($box.Text)", [Text.Encoding]::UTF8) }
$box.Add_MouseDown({ $script:clicks++; Save })
$box.Add_TextChanged({ Save })
$form.Add_Shown({ Save })
$form.Controls.Add($box)
[System.Windows.Forms.Application]::Run($form)
'@
    # Not hidden: that would hide the first window it shows, which is this one.
    $testWindow = Start-Process powershell.exe -PassThru -NoNewWindow -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$windowScript`"", "`"$windowState`""
    [void](WaitFor { Test-Path $windowState } 30)
    function WindowClicks {
        try { return [int]([IO.File]::ReadAllLines($windowState)[0]) } catch { return -1 }
    }
    function WindowText {
        try { return ([IO.File]::ReadAllLines($windowState, [Text.Encoding]::UTF8) | Select-Object -Skip 1) -join "" } catch { return "?" }
    }
    # The strip that catches a drag at the edge is there only while another computer has the pointer.
    function EdgeStrip { return [bool](FindPage "edge\.html" 1) }
    $middle = @([int]([SmokeHands]::Width / 2), [int]([SmokeHands]::Height / 2))

    Part "Shared mouse, another computer has the pointer here" {
        [void](App "set_settings" @{ patch = @{ remoteInput = $false } })
        $left = HelperCount "Leave \{"
        Say @("enter", "right", "0.5")
        Check ([bool](WaitFor { (HelperCount "Leave \{") -gt $left } 10)) "without permission the pointer is handed straight back"

        [void](App "set_settings" @{ patch = @{ remoteInput = $true } })
        [SmokeHands]::Put($middle[0], $middle[1])
        $sizes = HelperCount "Size \{"
        Say @("enter", "right", "0.5")
        Check ([bool](WaitFor { [SmokeHands]::At().X -le 1 } 10)) "the pointer comes in at the side where the other computer stands" "at $([SmokeHands]::At().X),$([SmokeHands]::At().Y)"
        Check ([Math]::Abs([SmokeHands]::At().Y - $middle[1]) -le 3) "halfway down, as it left the other screen" "at $([SmokeHands]::At().Y) of $([SmokeHands]::Height)"
        Check ([bool](WaitFor { (HelperCount "Size \{") -gt $sizes } 10)) "this PC says how big its screen is"

        $pinger = Start-Process powershell.exe -WindowStyle Hidden -PassThru -ArgumentList "-NoProfile", "-Command", `
            "while (`$true) { & '$tandemd' --data-dir '$helperData' pointer $($script:appId) ping *> `$null; Start-Sleep -Milliseconds 300 }"
        try {
            # The pointer takes its speed from Windows, so it is steered by looking where it went.
            $there = $false
            for ($i = 0; $i -lt 10 -and -not $there; $i++) {
                $at = [SmokeHands]::At()
                $dx = $middle[0] - $at.X
                $dy = $middle[1] - $at.Y
                if ([Math]::Abs($dx) -le 100 -and [Math]::Abs($dy) -le 100) { $there = $true } else { Say @("move", "$dx", "$dy"); Start-Sleep -Milliseconds 200 }
            }
            Check $there "moves of the other computer move the pointer" "at $([SmokeHands]::At().X),$([SmokeHands]::At().Y), wanted $($middle -join ',')"

            Say @("button", "0", "down")
            Say @("button", "0", "up")
            Check ([bool](WaitFor { (WindowClicks) -gt 0 } 10)) "a click of the other computer lands"
            Check (EdgeStrip) "the edge is ready to catch a drag"
            Say @("key", "0", "down", "0")
            Say @("key", "0", "up", "0")
            Check ([bool](WaitFor { (WindowText) -ceq "a" } 10)) "a key of the other computer gives its letter" "the box says '$(WindowText)'"
            Say @("key", "0", "down", "1")
            Say @("key", "0", "up", "1")
            Check ([bool](WaitFor { (WindowText) -ceq "aA" } 10)) "with shift held on the other computer it is a capital" "the box says '$(WindowText)'"
            $word = "h" + [char]0xEB + "llo"
            Say @("text", $word)
            Check ([bool](WaitFor { (WindowText) -ceq "aA$word" } 10)) "text of the other computer arrives whole" "the box says '$(WindowText)'"
            Shot "7-shared-mouse"

            $left = HelperCount "Leave \{"
            Say @("move", "-30000", "0")
            Check ([bool](WaitFor { (HelperCount "Leave \{") -gt $left } 10)) "running into the edge it came in by sends the pointer back"
            Check ([bool](WaitFor { -not (EdgeStrip) } 10)) "the strip at the edge goes with it"
            # The pings above keep coming: from a computer that does not have the pointer here, as far as this PC knows.
            $left = HelperCount "Leave \{"
            Check ([bool](WaitFor { (HelperCount "Leave \{") -gt $left } 10)) "a computer that thinks it still has the pointer is told it does not"
            Say @("enter", "right", "0.5")
            Start-Sleep -Milliseconds 800
            Say @("button", "0", "down")
            Check ([bool](WaitFor { [SmokeHands]::LeftIsDown } 10)) "a button can be held down from the other computer"
        } finally {
            Stop-Process -Id $pinger.Id -Force -ErrorAction SilentlyContinue
        }
        # What a closed lid on the other computer looks like: the worst thing it could leave behind is a button that stays down here.
        Check ([bool](WaitFor { -not [SmokeHands]::LeftIsDown } 8)) "when the other computer goes silent, nothing stays held down"
        Check ([bool](WaitFor { -not (EdgeStrip) } 10)) "and the strip at the edge is gone"
    }

    Part "Shared mouse, this PC has the mouse" {
        [void](App "set_settings" @{ patch = @{ pointerShare = $true } })
        [void](App "arrange_place" @{ id = $script:helperId; x = [SmokeHands]::Width; y = 0; width = [SmokeHands]::Width; height = [SmokeHands]::Height; snap = 40 })

        function Cross {
            $before = HelperCount "Enter \{"
            [SmokeHands]::Put(([SmokeHands]::Width - 30), $middle[1])
            for ($i = 0; $i -lt 60; $i++) {
                [SmokeHands]::Move(4, 0)
                Start-Sleep -Milliseconds 30
            }
            return [bool](WaitFor { (HelperCount "Enter \{") -gt $before } 5)
        }
        function BackAtTheEdge { return [SmokeHands]::At().X -ge ([SmokeHands]::Width - 3) }

        Check (Cross) "the pointer goes over at the edge where the other screen is"
        Start-Sleep -Milliseconds 500
        $at = [SmokeHands]::At()
        Check ([Math]::Abs($at.X - $middle[0]) -le 3 -and [Math]::Abs($at.Y - $middle[1]) -le 3) "while it is away the real pointer stays put" "at $($at.X),$($at.Y)"

        $moves = HelperCount "move -?\d"
        [SmokeHands]::Move(30, 12)
        Check ([bool](WaitFor { (HelperCount "move -?\d") -gt $moves } 5)) "moves reach the other computer"

        $clicks = WindowClicks
        [SmokeHands]::Left($true)
        [SmokeHands]::Left($false)
        Check ([bool](WaitFor { HelperSaid "Button \{ button: 0, down: false" } 5)) "a click reaches the other computer"
        $text = WindowText
        $sent = "$([SmokeHands]::Key(0x41, $true)) $([SmokeHands]::Key(0x41, $false))"
        Check ([bool](WaitFor { HelperSaid "Key \{ code: 0, down: false" } 5)) "a key reaches the other computer" "Windows took $sent"
        Shot "8-this-pc-has-the-mouse"
        Start-Sleep -Seconds 1
        Check ((WindowClicks) -eq $clicks -and (WindowText) -ceq $text) "and neither does anything on this PC" "clicks $clicks to $(WindowClicks), box '$text' to '$(WindowText)'"

        Say @("leave", "0.5")
        Check ([bool](WaitFor { BackAtTheEdge } 5)) "when the other computer sends it back, the pointer is at the edge again" "at $([SmokeHands]::At().X)"
        [SmokeHands]::Put(200, 200)
        [SmokeHands]::Move(20, 0)
        Start-Sleep -Milliseconds 200
        Check ([SmokeHands]::At().X -gt 200) "and the mouse works on this PC again" "at $([SmokeHands]::At().X)"

        # Held here before the pointer left: its release has to reach this PC too, or Shift stays down here.
        [void][SmokeHands]::Key(0x10, $true)
        Check (Cross) "it goes over with Shift held"
        [void][SmokeHands]::Key(0x10, $false)
        Start-Sleep -Milliseconds 300
        Check (-not [SmokeHands]::IsDown(0x10)) "Shift that went down here also comes up here"
        Say @("leave", "0.5")
        [void](WaitFor { BackAtTheEdge } 5)

        # With a button held the edge is a wall: a drag on this PC stays on this PC.
        $entered = HelperCount "Enter \{"
        [SmokeHands]::Put(([SmokeHands]::Width - 30), $middle[1])
        [SmokeHands]::Left($true)
        for ($i = 0; $i -lt 60; $i++) {
            [SmokeHands]::Move(4, 0)
            Start-Sleep -Milliseconds 30
        }
        Start-Sleep -Milliseconds 500
        Check ((HelperCount "Enter \{") -eq $entered) "with a button held the pointer stays on this PC"
        [SmokeHands]::Left($false)

        Check (Cross) "it goes over a second time"
        $released = HelperCount "Release"
        foreach ($key in 0x11, 0x12, 0x10) { [void][SmokeHands]::Key($key, $true) }
        [void][SmokeHands]::Key(0x1B, $true)
        [void][SmokeHands]::Key(0x1B, $false)
        foreach ($key in 0x10, 0x12, 0x11) { [void][SmokeHands]::Key($key, $false) }
        Check ([bool](WaitFor { (HelperCount "Release") -gt $released } 5)) "Ctrl+Alt+Shift+Esc takes it back and says so"
        Check ([bool](WaitFor { BackAtTheEdge } 5)) "the pointer is where it left" "at $([SmokeHands]::At().X)"

        Check (Cross) "it goes over a third time"
        Start-Sleep -Seconds 1
        Stop-Process -Id $script:helper.Id -Force -ErrorAction SilentlyContinue
        Check ([bool](WaitFor { BackAtTheEdge } 8)) "when the other computer goes away, the pointer comes home by itself" "at $([SmokeHands]::At().X)"

        Copy-Item $helperOut (Join-Path $Out "helper-mouse.log") -Force
        # One that says how it is: when it says no, the edge is a wall and the settings say why.
        StartHelper @("--pretend-cap", "pointer.ready")
        Check ([bool](WaitFor { DeviceOnline $script:helperId } 30)) "the other computer connects again"
        function SaysNo { return @((App "arrange_state").devices | Where-Object { $_.id -eq $script:helperId -and $_.notReady }).Count -gt 0 }
        [void](WaitFor { @((App "get_state").devices | Where-Object { $_.id -eq $script:helperId -and $_.caps -contains "pointer.ready" }).Count -gt 0 } 15)
        Say @("release")
        Check ([bool](WaitFor { SaysNo } 10)) "a computer that says it cannot take the pointer: the settings say so"
        $entered = HelperCount "Enter \{"
        [SmokeHands]::Put(([SmokeHands]::Width - 30), $middle[1])
        for ($i = 0; $i -lt 60; $i++) {
            [SmokeHands]::Move(4, 0)
            Start-Sleep -Milliseconds 30
        }
        Start-Sleep -Milliseconds 500
        Check ((HelperCount "Enter \{") -eq $entered) "and the edge towards it is a wall"
        Say @("size", "$([SmokeHands]::Width)", "$([SmokeHands]::Height)")
        Check ([bool](WaitFor { -not (SaysNo) } 10)) "when it says it can again, the settings say so too"
        Check (Cross) "and the pointer goes over"
        Say @("leave", "0.5")
        [void](WaitFor { BackAtTheEdge } 5)
        [void](App "arrange_remove" @{ id = $script:helperId })
        [void](App "set_settings" @{ patch = @{ remoteInput = $false } })
    }
    Stop-Process -Id $testWindow.Id -Force -ErrorAction SilentlyContinue
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
    # First the words: the panel goes when it loses focus, and on a machine in use that can be any moment.
    WalkPages "panel\.html" "the panel"
    Shot "4-panel"
    Check (@(Get-Process -Name Tandem -ErrorAction SilentlyContinue).Count -eq 1) "there is still one Tandem"
    [void](Memory "panel open")

    # What Open Tandem in the panel does. A plain command that makes a window freezes the whole app, and the process still counts as responding.
    $before = LogCount "the main window shows"
    $panel = FindPage "panel\.html" 10
    if ($panel) {
        try { [void](Evaluate $panel "window.__TAURI__.core.invoke('show_main', {})" 20) } catch { Write-Host "show_main: $_" }
        Check ([bool](WaitFor { (LogCount "the main window shows") -gt $before } 60)) "the panel opens the closed main window"
        Check (AppAnswers) "and the app still answers"
    } else {
        Write-Host "the panel went away by itself, so it could not be asked"
    }
    foreach ($process in @(Get-Process -Name Tandem -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 })) { [void]$process.CloseMainWindow() }
    Start-Sleep -Seconds 3

    $before = LogCount "the main window shows"
    Start-Process $exe
    Check ([bool](WaitFor { (LogCount "the main window shows") -gt $before } 60)) "a second start brings the main window back"
    Check (AppResponds) "the app responds"

    # The login window of Terminal, which froze the app in the same way.
    try { [void](Evaluate (FindPage $mainPage) "window.__TAURI__.core.invoke('ssh_open', { id: 'smoke', name: 'Smoke', address: '127.0.0.1' })" 20) } catch { Write-Host "ssh_open: $_" }
    $login = FindPage "ssh\.html" 20
    Check ([bool]$login) "Terminal opens its login window"
    Check (AppAnswers) "and the app still answers"
    if ($login) {
        Start-Sleep -Seconds 2
        Check ([bool](Evaluate $login "document.body.innerText.trim().length > 0" 15)) "the login window has something to say"
        try { [void](Evaluate $login "window.__TAURI__.window.getCurrentWindow().close()" 5) } catch {}
    }
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
    # The installer can start the app by way of the shell, without the debugging port this script asked for, so this looks from outside.
    Check ((LogCount "engine started id=$($script:appId)") -gt 0) "it is still the same device"
    if ($pairedWell) { Check ([bool](WaitFor { (Helper @("devices")).text -match "\bonline\b" } 60)) "the second device connects again" }
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
        Check ([bool](WaitFor { -not (Test-Path $helperLink) } 20)) "a removed device loses its link in Send to"
    }
    # Quit itself sits in the menu of the icon, out of reach here. With closing to the tray off, closing the window goes the same way.
    [void](App "set_settings" @{ patch = @{ closeToTray = $false } })
    @(Get-Process -Name Tandem) | ForEach-Object { [void]$_.CloseMainWindow() }
    Check ([bool](WaitFor { -not (AppRuns) } 30)) "Quit ends the app"
    SaveLog "the last run"
    if ($KeepInstalled) {
        # The machine goes on being used, with the choice the way it was.
        Start-Process $exe
        [void](WaitFor { (LogCount "the engine runs") -gt 0 } 60)
        [void](App "set_settings" @{ patch = @{ closeToTray = $true } })
        Stop-Process -Name Tandem -Force -ErrorAction SilentlyContinue
    }
}

if ($script:helper) { Stop-Process -Id $script:helper.Id -Force -ErrorAction SilentlyContinue }

if (-not $KeepInstalled) {
    Part "Uninstall" {
        $uninstall = Join-Path $installed "uninstall.exe"
        Check (Test-Path $uninstall) "there is an uninstaller"
        # By now the device has been removed, and with it its link. A person uninstalls with their devices still there.
        Set-Content $helperLink ""
        Start-Process $uninstall -ArgumentList "/S" -Wait
        Check ([bool](WaitFor { -not (Test-Path $exe) } 60)) "the uninstaller removes Tandem.exe"
        Check (@(Get-ChildItem $sendTo -Filter "* (Tandem).lnk").Count -eq 0) "the uninstaller leaves no links in Send to"
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
