param(
    [string]$device = "",       # e.g. \\.\DISPLAY1 ; empty = primary
    [int]$width = 0,
    [int]$height = 0,
    [int]$refresh = 0
)
# Windows counterpart to apply-displays. Must run in an interactive session:
# EnumDisplaySettings returns 0x0 from session 0, so this is useless over ssh
# and has to be launched through schtasks /it like the harness itself.
Add-Type @"
using System;
using System.Runtime.InteropServices;
[StructLayout(LayoutKind.Sequential, CharSet=CharSet.Ansi)]
public struct DEVMODE {
  [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string dmDeviceName;
  public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
  public int dmFields;
  public int dmPositionX, dmPositionY; public int dmDisplayOrientation, dmDisplayFixedOutput;
  public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
  [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string dmFormName;
  public short dmLogPixels; public int dmBitsPerPel, dmPelsWidth, dmPelsHeight;
  public int dmDisplayFlags, dmDisplayFrequency;
  public int dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
}
public class Disp {
  [DllImport("user32.dll", CharSet=CharSet.Ansi)]
  public static extern bool EnumDisplaySettings(string dev, int mode, ref DEVMODE dm);
  [DllImport("user32.dll", CharSet=CharSet.Ansi)]
  public static extern int ChangeDisplaySettingsEx(string dev, ref DEVMODE dm, IntPtr hwnd, int flags, IntPtr param);
  [DllImport("user32.dll", CharSet=CharSet.Ansi)]
  public static extern bool EnumDisplayDevices(string dev, uint num, ref DISPLAY_DEVICE d, uint flags);
}
[StructLayout(LayoutKind.Sequential, CharSet=CharSet.Ansi)]
public struct DISPLAY_DEVICE {
  public int cb;
  [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string DeviceName;
  [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string DeviceString;
  public int StateFlags;
  [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string DeviceID;
  [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string DeviceKey;
}
"@

if (-not $device) {
    $d = New-Object DISPLAY_DEVICE; $d.cb = [Runtime.InteropServices.Marshal]::SizeOf($d)
    for ($i = 0; [Disp]::EnumDisplayDevices($null, $i, [ref]$d, 0); $i++) {
        if ($d.StateFlags -band 0x4) { $device = $d.DeviceName; break }   # PRIMARY
        $d.cb = [Runtime.InteropServices.Marshal]::SizeOf($d)
    }
}

$dm = New-Object DEVMODE
$dm.dmSize = [int16][Runtime.InteropServices.Marshal]::SizeOf($dm)
if (-not [Disp]::EnumDisplaySettings($device, -1, [ref]$dm)) { Write-Output "cannot read current mode for $device"; exit 1 }
Write-Output "current: $device $($dm.dmPelsWidth)x$($dm.dmPelsHeight)@$($dm.dmDisplayFrequency)Hz"

if ($width)   { $dm.dmPelsWidth = $width }
if ($height)  { $dm.dmPelsHeight = $height }
if ($refresh) { $dm.dmDisplayFrequency = $refresh }
# PELSWIDTH|PELSHEIGHT|DISPLAYFREQUENCY|BITSPERPEL
$dm.dmFields = 0x80000 -bor 0x100000 -bor 0x400000 -bor 0x40000

$r = [Disp]::ChangeDisplaySettingsEx($device, [ref]$dm, [IntPtr]::Zero, 0x00000001, [IntPtr]::Zero)  # UPDATEREGISTRY
switch ($r) {
    0 { Write-Output "applied: $($dm.dmPelsWidth)x$($dm.dmPelsHeight)@$($dm.dmDisplayFrequency)Hz" }
    1 { Write-Output "applied, restart required" }
    default { Write-Output "ChangeDisplaySettingsEx failed: $r" }
}
Start-Sleep -Seconds 3
$v = New-Object DEVMODE; $v.dmSize = [int16][Runtime.InteropServices.Marshal]::SizeOf($v)
[void][Disp]::EnumDisplaySettings($device, -1, [ref]$v)
Write-Output "verified: $($v.dmPelsWidth)x$($v.dmPelsHeight)@$($v.dmDisplayFrequency)Hz"
