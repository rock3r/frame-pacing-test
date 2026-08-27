param(
    [int]$seconds = 20,
    [string]$window = "1600x1000",
    [string]$display = "",
    [double]$idleMax = 0
)
# Windows counterpart to jbr-experiment-skiko: drives SkikoDirectMainKt against
# the mavenLocal skiko snapshot, so the pacing under test is skiko's own
# (skiko.swing.frame.pacing) rather than the app-level RepaintManager pacer.
#
# GUI apps cannot run from an ssh session (session 0 has no desktop), so the
# harness is launched through a scheduled task with /it. Scheduled tasks do not
# inherit environment set over ssh, which is why the launcher .bat is rewritten
# on each run rather than parameterised.

$JBR = "C:\src\jbr-frame-pacing\build\windows-x86_64-server-release\images\jdk"
$LIB = "C:\src\frame-pacing-test\build\install\frame-pacing-test\lib"
$M2  = "$env:USERPROFILE\.m2\repository\org\jetbrains\skiko"
$SNAP = "$M2\skiko-awt\0.0.0-SNAPSHOT\skiko-awt-0.0.0-SNAPSHOT.jar;$M2\skiko-awt-runtime-windows-x64\0.0.0-SNAPSHOT\skiko-awt-runtime-windows-x64-0.0.0-SNAPSHOT.jar"

foreach ($p in @("$JBR\bin\java.exe", $LIB)) {
    if (-not (Test-Path $p)) { Write-Output "missing: $p"; exit 1 }
}
foreach ($j in $SNAP -split ';') {
    if (-not (Test-Path $j)) { Write-Output "missing skiko snapshot: $j"; exit 1 }
}

function Kill-Harness {
    # Match the java process, not the launcher: survivors steal GPU from later
    # runs, and every match must go, not just the first PID found.
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*SkikoDirectMainKt*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Sample-Gpu([string]$label, [int]$secs) {
    $w = @(); $c = @(); $u = @()
    $end = (Get-Date).AddSeconds($secs)
    while ((Get-Date) -lt $end) {
        $r = (nvidia-smi --query-gpu=power.draw,clocks.gr,utilization.gpu --format=csv,noheader,nounits 2>$null | Select-Object -First 1)
        if ($r) { $p = $r -split ',\s*'; $w += [double]$p[0]; $c += [double]$p[1]; $u += [double]$p[2] }
        Start-Sleep -Milliseconds 500
    }
    if ($w.Count -eq 0) { Write-Output "$label  (nvidia-smi returned nothing)"; return $null }
    $avg = { param($a) ($a | Measure-Object -Average).Average }
    "{0,-26} GPU={1,5:N1}% clk={2,4:N0} MHz  GPUw={3,6:N2} W" -f $label, (& $avg $u), (& $avg $c), (& $avg $w)
    return (& $avg $w)
}

# --- idle guard. A failed nvidia-smi read must not look like a quiet machine.
$pre = Sample-Gpu "preflight" 5
if ($null -eq $pre) { Write-Output "ABORT: no GPU telemetry"; exit 1 }
Write-Output $pre
if ($idleMax -gt 0 -and $pre -gt $idleMax) {
    Write-Output "ABORT: GPU drawing $([math]::Round($pre,2)) W before the run (ceiling $idleMax W)"
    exit 1
}

Write-Output "=== Skiko frame pacing (skiko.swing.frame.pacing): unpaced vs paced ==="
Write-Output "  host: $env:COMPUTERNAME   window: $window$(if($display){"   screen: $display"})"
Write-Output ""

function Run-Mode([string]$pacing, [string]$label) {
    Kill-Harness; Start-Sleep -Seconds 1
    $log = "C:\src\fpt-skiko-$pacing.log"
    Remove-Item $log -ErrorAction SilentlyContinue
    $disp = if ($display) { "-Ddisplay=$display" } else { "" }
    @"
@echo off
"$JBR\bin\java.exe" -cp "$SNAP;$LIB\*" -Dskiko.swing.frame.pacing=$pacing -Dwindow=$window $disp SkikoDirectMainKt > $log 2>&1
"@ | Set-Content -Encoding ASCII C:\src\fpt-skiko-run.bat

    schtasks /create /tn fpt-skiko /tr C:\src\fpt-skiko-run.bat /ru $env:USERNAME /it /f /sc once /st 00:00 | Out-Null
    schtasks /run /tn fpt-skiko | Out-Null
    Start-Sleep -Seconds 10
    $alive = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*SkikoDirectMainKt*' }
    if (-not $alive) {
        Write-Output "  ${label}: app exited early --"
        if (Test-Path $log) { Get-Content $log -TotalCount 6 | ForEach-Object { "      $_" } }
        return
    }
    Write-Output (Sample-Gpu "$label win=$window" $seconds)
    if (Test-Path $log) {
        $v = Get-Content $log | Select-String 'renders/sec' | ForEach-Object { [double](($_ -split ':\s*')[-1]) }
        if ($v.Count -gt 3) {
            $avg = ($v[3..($v.Count-1)] | Measure-Object -Average).Average
            "{0,-26}   renders/sec avg = {1:N1}  (n={2})" -f "", $avg, ($v.Count - 3)
        }
        Get-Content $log | Select-String 'window-on|fullscreen:' | ForEach-Object { "      $_" }
    }
    Kill-Harness; Start-Sleep -Seconds 5
}

Run-Mode "false" "UNPACED"
Write-Output ""
Run-Mode "true"  "PACED  "
Write-Output ""
$post = Sample-Gpu "postflight" 5
Write-Output $post
if ($idleMax -gt 0 -and $post -gt $idleMax) {
    Write-Output "  *** SUSPECT RUN: GPU at $([math]::Round($post,2)) W after the run (ceiling $idleMax W)"
}
