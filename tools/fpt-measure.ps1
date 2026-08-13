# fpt-measure4.ps1 -mode <on|off> [-window WxH] [-seconds N]
#
# Adds the contamination guards the Linux script has:
#   - kills EVERY leaked harness instance first (the old version killed only the one
#     PID it found, so instances accumulated across runs and stole CPU)
#   - refuses to measure unless the GPU and CPU are actually idle
#   - re-checks afterwards, because load appearing mid-run is invisible to a
#     start-only check and silently depresses the unpaced figure
param(
  [Parameter(Mandatory=$true)][ValidateSet('on','off')][string]$mode,
  [string]$window = '1600x1000',
  [int]$seconds = 20
)

function Kill-Harness {
  $procs = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'MainKt' }
  foreach ($q in $procs) { Stop-Process -Id $q.ProcessId -Force -ErrorAction SilentlyContinue }
  if ($procs) { Start-Sleep -Seconds 3 }
  return ($procs | Measure-Object).Count
}

function Get-Idle {
  $w = [double](((nvidia-smi --query-gpu=power.draw --format=csv,noheader,nounits) -split "`n")[0].Trim())
  $c = [double]((Get-CimInstance Win32_Processor | Select-Object -First 1).LoadPercentage)
  return @{ gpu = $w; cpu = $c }
}

$killed = Kill-Harness
if ($killed -gt 0) { Write-Output "  cleaned up $killed leaked harness instance(s) before starting" }

$pre = Get-Idle
if ($pre.gpu -gt 15.0 -or $pre.cpu -gt 15.0) {
  Write-Output ("  ABORT: machine not idle - GPU {0:N2} W, CPU {1:N0}% (want <15 W, <15%)" -f $pre.gpu, $pre.cpu)
  Write-Output "  measurements would be corrupted. Top CPU consumers:"
  Get-Process | Sort-Object CPU -Descending | Select-Object -First 5 Name,CPU |
    Format-Table -AutoSize | Out-String | ForEach-Object { $_ -split "`n" } | ForEach-Object { "    $_" }
  exit 1
}
Write-Output ("  idle check: GPU {0:N2} W, CPU {1:N0}% - quiet, proceeding" -f $pre.gpu, $pre.cpu)

$jbr = 'C:\src\jbr-frame-pacing\build\windows-x86_64-server-release\images\jdk'
$app = 'C:\src\frame-pacing-test\build\install\frame-pacing-test\bin\frame-pacing-test.bat'
$log = "C:\src\fpt-$mode.log"
$bat = "C:\src\fpt-run-$mode.bat"

@"
@echo off
set JAVA_HOME=$jbr
set FRAME_PACING_TEST_OPTS=-Dpacing=$mode -Dwindow=$window
call $app > $log 2>&1
"@ | Set-Content -Path $bat -Encoding ASCII
Remove-Item $log -ErrorAction SilentlyContinue

schtasks /run /tn "fpt-$mode" | Out-Null
Start-Sleep -Seconds 12

$all = @(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'MainKt' })
if ($all.Count -eq 0) {
  Write-Output "  NO PROCESS - harness failed to start. Log tail:"
  Get-Content $log -Tail 10 -ErrorAction SilentlyContinue | ForEach-Object { "    $_" }
  exit 1
}
if ($all.Count -gt 1) { Write-Output "  WARNING: $($all.Count) harness instances running - results will be contaminated" }
$id = $all[0].ProcessId; $proc = Get-Process -Id $id; $cpu0 = $proc.CPU; $t0 = Get-Date

$w=@(); $c=@(); $u=@()
for ($i = 0; $i -lt $seconds; $i++) {
  $row = (nvidia-smi --query-gpu=power.draw,clocks.gr,utilization.gpu --format=csv,noheader,nounits) -split "`n" | Select-Object -First 1
  $f = $row -split ',' | ForEach-Object { $_.Trim() }
  if ($f.Count -ge 3) { $w += [double]$f[0]; $c += [double]$f[1]; $u += [double]$f[2] }
  Start-Sleep -Seconds 1
}
$proc.Refresh()
$cpuPct = (($proc.CPU - $cpu0) / ((Get-Date) - $t0).TotalSeconds) * 100

$r = Get-Content $log -ErrorAction SilentlyContinue |
     Select-String -Pattern 'renders/sec:\s*(\d+)' |
     ForEach-Object { [int]$_.Matches[0].Groups[1].Value }
$rAvg = if ($r.Count -gt 3) { ($r[3..($r.Count-1)] | Measure-Object -Average).Average } else { 0 }

$label = if ($mode -eq 'on') { "PACED   win=$window" } else { "UNPACED win=$window" }
Write-Output ("  {0,-24} GPU={1,5:N1}% clk={2,5:N0} MHz  GPUw={3,6:N2} W  cpu1core={4,5:N1}%  renders/sec={5,6:N1}" -f `
  $label, ($u|Measure-Object -Average).Average, ($c|Measure-Object -Average).Average,
  ($w|Measure-Object -Average).Average, $cpuPct, $rAvg)

Get-Content $log -ErrorAction SilentlyContinue |
  Select-String -Pattern 'FramePacing:' | Select-Object -First 1 |
  ForEach-Object { "      $($_.Line)" }

[void](Kill-Harness)
Start-Sleep -Seconds 5
$post = Get-Idle
if ($post.gpu -gt 15.0 -or $post.cpu -gt 15.0) {
  Write-Output ("  *** SUSPECT RUN: after finishing, GPU {0:N2} W / CPU {1:N0}% - something loaded the machine mid-run" -f $post.gpu, $post.cpu)
} else {
  Write-Output ("  idle after run: GPU {0:N2} W, CPU {1:N0}% - clean" -f $post.gpu, $post.cpu)
}
