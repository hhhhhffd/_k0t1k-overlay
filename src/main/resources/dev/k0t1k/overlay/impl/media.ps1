# Bridge between the overlay and Windows' system media controls (what Win+media keys drive: Spotify,
# browsers, players). Prints one line per change: "S<TAB>state<TAB>title<TAB>artist<TAB>app", or "N"
# when nothing plays. Reads commands from stdin: play, next, prev.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]
function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $task.Wait(-1) | Out-Null
    $task.Result
}
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]

# Console.In reads synchronously even through ReadLineAsync; a StreamReader over the raw stream does not.
$stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput())
$pending = $stdin.ReadLineAsync()
$last = ''
while ($true) {
    $session = $manager.GetCurrentSession()
    if ($pending.IsCompleted) {
        $command = $pending.Result
        if ($null -eq $command) { break }
        if ($session) {
            switch ($command.Trim()) {
                'play' { $null = $session.TryTogglePlayPauseAsync() }
                'next' { $null = $session.TrySkipNextAsync() }
                'prev' { $null = $session.TrySkipPreviousAsync() }
            }
        }
        $pending = $stdin.ReadLineAsync()
        Start-Sleep -Milliseconds 250
        continue
    }
    $line = 'N'
    if ($session) {
        try {
            $props = Await ($session.TryGetMediaPropertiesAsync()) $propsType
            $state = $session.GetPlaybackInfo().PlaybackStatus.ToString()
            $clean = { param($s) if ($s) { $s -replace "[`t`r`n]", ' ' } else { '' } }
            $line = "S`t$state`t$(& $clean $props.Title)`t$(& $clean $props.Artist)`t$(& $clean $session.SourceAppUserModelId)"
        } catch {
            $line = 'N'
        }
    }
    if ($line -ne $last) {
        [Console]::Out.WriteLine($line)
        [Console]::Out.Flush()
        $last = $line
    }
    Start-Sleep -Milliseconds 700
}
