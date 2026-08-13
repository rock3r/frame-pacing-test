// Per-output vblank probe for the FramePacing v2 Windows backend.
//
// DwmGetCompositionTimingInfo carries one composition cadence for the whole desktop
// (the fastest connected display), so it cannot pace a window on a slower display.
// IDXGIOutput::WaitForVBlank is per-output and is the candidate replacement. It has a
// long-standing reputation for busy-waiting on some drivers, which would be fatal for a
// power-saving feature -- so this measures the waiting thread's CPU time as carefully as
// it measures the cadence.
//
// Prints, per attached output: the device name (which is what FramePacingWin hashes for
// its display id), the mode reported by EnumDisplaySettings, the observed vblank
// interval, and the CPU cost of waiting.

#include <windows.h>
#include <dxgi.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <vector>

#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "user32.lib") // EnumDisplaySettingsW

static double qpcFreq;

static double nowSec() {
    LARGE_INTEGER t;
    QueryPerformanceCounter(&t);
    return (double)t.QuadPart / qpcFreq;
}

static double threadCpuSec() {
    FILETIME created, exited, kernel, user;
    if (!GetThreadTimes(GetCurrentThread(), &created, &exited, &kernel, &user)) return 0.0;
    ULARGE_INTEGER k, u;
    k.LowPart = kernel.dwLowDateTime;
    k.HighPart = kernel.dwHighDateTime;
    u.LowPart = user.dwLowDateTime;
    u.HighPart = user.dwHighDateTime;
    return (double)(k.QuadPart + u.QuadPart) / 1e7; // 100ns units
}

static void probeOutput(IDXGIOutput* output, int samples) {
    DXGI_OUTPUT_DESC od;
    output->GetDesc(&od);

    DEVMODEW dm;
    ZeroMemory(&dm, sizeof(dm));
    dm.dmSize = sizeof(dm);
    DWORD hz = 0;
    if (EnumDisplaySettingsW(od.DeviceName, ENUM_CURRENT_SETTINGS, &dm)) hz = dm.dmDisplayFrequency;

    printf("  output %S  attached=%d  rect=(%ld,%ld)-(%ld,%ld)  mode=%lux%lu@%luHz\n",
           od.DeviceName, (int)od.AttachedToDesktop,
           od.DesktopCoordinates.left, od.DesktopCoordinates.top,
           od.DesktopCoordinates.right, od.DesktopCoordinates.bottom,
           dm.dmPelsWidth, dm.dmPelsHeight, hz);

    if (!od.AttachedToDesktop) {
        printf("    (not attached to the desktop, skipped)\n");
        return;
    }

    // Sync to a boundary first so the first interval is not a partial period.
    if (FAILED(output->WaitForVBlank())) {
        printf("    WaitForVBlank failed on the warm-up call\n");
        return;
    }

    std::vector<double> intervals;
    intervals.reserve(samples);

    const double cpu0 = threadCpuSec();
    const double wall0 = nowSec();
    double prev = wall0;
    int failed = 0;

    for (int i = 0; i < samples; i++) {
        const HRESULT hr = output->WaitForVBlank();
        const double t = nowSec();
        if (FAILED(hr)) failed++;
        intervals.push_back((t - prev) * 1000.0);
        prev = t;
    }

    const double wall = nowSec() - wall0;
    const double cpu = threadCpuSec() - cpu0;

    double sum = 0.0, mx = 0.0;
    for (size_t i = 0; i < intervals.size(); i++) {
        sum += intervals[i];
        if (intervals[i] > mx) mx = intervals[i];
    }
    const double mean = sum / intervals.size();

    double var = 0.0;
    for (size_t i = 0; i < intervals.size(); i++) {
        const double d = intervals[i] - mean;
        var += d * d;
    }
    const double sd = sqrt(var / intervals.size());

    printf("    interval: n=%d  mean=%.3fms  sd=%.3fms  max=%.3fms  expected=%.3fms  failed=%d\n",
           (int)intervals.size(), mean, sd, mx, hz ? 1000.0 / hz : 0.0, failed);
    printf("    observed rate: %.2f Hz\n", mean > 0.0 ? 1000.0 / mean : 0.0);
    printf("    thread CPU: %.3fs over %.3fs wall = %.1f%% of one core%s\n",
           cpu, wall, 100.0 * cpu / wall,
           (cpu / wall) > 0.10 ? "   <-- BUSY-WAIT" : "");
}

int main(int argc, char** argv) {
    const int samples = (argc > 1) ? atoi(argv[1]) : 300;

    LARGE_INTEGER f;
    QueryPerformanceFrequency(&f);
    qpcFreq = (double)f.QuadPart;

    IDXGIFactory1* factory = NULL;
    if (FAILED(CreateDXGIFactory1(__uuidof(IDXGIFactory1), (void**)&factory))) {
        printf("CreateDXGIFactory1 failed\n");
        return 1;
    }

    IDXGIAdapter1* adapter = NULL;
    for (UINT ai = 0; factory->EnumAdapters1(ai, &adapter) != DXGI_ERROR_NOT_FOUND; ai++) {
        DXGI_ADAPTER_DESC1 ad;
        adapter->GetDesc1(&ad);
        printf("adapter[%u]: %S\n", ai, ad.Description);

        IDXGIOutput* output = NULL;
        for (UINT oi = 0; adapter->EnumOutputs(oi, &output) != DXGI_ERROR_NOT_FOUND; oi++) {
            probeOutput(output, samples);
            output->Release();
        }
        adapter->Release();
    }

    factory->Release();
    return 0;
}
