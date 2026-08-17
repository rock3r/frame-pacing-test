// vrrpresent.cpp — Windows VRR load generator + present cadence reporter.
//
// Borderless fullscreen flip-model D3D11 presenter, paced at a target frame
// rate with DXGI_PRESENT_ALLOW_TEARING. On a VRR display (G-Sync/FreeSync
// enabled in the driver) presents inside the panel's VRR range drive the
// refresh directly — run tools/vbprobe alongside this and the per-output
// WaitForVBlank cadence should track the target rate rather than the fixed
// maximum. On a fixed-rate display the vblank cadence stays at the mode's
// refresh regardless of this app.
//
// Also prints whether the system supports tearing at all
// (IDXGIFactory5::CheckFeatureSupport ALLOW_TEARING) — a prerequisite for VRR.
//
// Build (VS dev prompt):
//   cl /O2 /EHsc vrrpresent.cpp /link d3d11.lib dxgi.lib user32.lib
// Usage: vrrpresent [targetFps] [seconds]
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_5.h>
#include <stdio.h>
#include <math.h>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "user32.lib")

static LRESULT CALLBACK wndProc(HWND h, UINT m, WPARAM w, LPARAM l)
{
    if (m == WM_DESTROY) { PostQuitMessage(0); return 0; }
    if (m == WM_KEYDOWN && w == VK_ESCAPE) { DestroyWindow(h); return 0; }
    return DefWindowProcW(h, m, w, l);
}

int main(int argc, char **argv)
{
    double targetFps = argc > 1 ? atof(argv[1]) : 48.0;
    double seconds = argc > 2 ? atof(argv[2]) : 20.0;

    IDXGIFactory5 *factory5 = nullptr;
    BOOL tearing = FALSE;
    if (SUCCEEDED(CreateDXGIFactory1(__uuidof(IDXGIFactory5), (void **)&factory5))) {
        factory5->CheckFeatureSupport(DXGI_FEATURE_PRESENT_ALLOW_TEARING,
                                      &tearing, sizeof(tearing));
    }
    printf("DXGI_FEATURE_PRESENT_ALLOW_TEARING: %s\n", tearing ? "supported" : "NOT supported");
    if (!tearing) {
        printf("  (no tearing support means no VRR; check Windows/driver versions)\n");
    }

    WNDCLASSW wc = {};
    wc.lpfnWndProc = wndProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = L"vrrpresent";
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    RegisterClassW(&wc);

    int w = GetSystemMetrics(SM_CXSCREEN);
    int h = GetSystemMetrics(SM_CYSCREEN);
    HWND hwnd = CreateWindowExW(0, wc.lpszClassName, L"vrrpresent", WS_POPUP,
                                0, 0, w, h, nullptr, nullptr, wc.hInstance, nullptr);
    ShowWindow(hwnd, SW_SHOW);

    D3D_FEATURE_LEVEL fl;
    ID3D11Device *dev = nullptr;
    ID3D11DeviceContext *ctx = nullptr;
    if (FAILED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0,
                                 nullptr, 0, D3D11_SDK_VERSION, &dev, &fl, &ctx))) {
        fprintf(stderr, "D3D11CreateDevice failed\n");
        return 1;
    }

    DXGI_SWAP_CHAIN_DESC1 sd = {};
    sd.Width = w;
    sd.Height = h;
    sd.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    sd.SampleDesc.Count = 1;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.BufferCount = 2;
    sd.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    sd.Flags = tearing ? DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING : 0;

    IDXGISwapChain1 *swap = nullptr;
    if (FAILED(factory5->CreateSwapChainForHwnd(dev, hwnd, &sd, nullptr, nullptr, &swap))) {
        fprintf(stderr, "CreateSwapChainForHwnd failed\n");
        return 1;
    }

    LARGE_INTEGER freq, start, now, last;
    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&start);
    last = start;

    HANDLE timer = CreateWaitableTimerExW(nullptr, nullptr,
            CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
    double periodQpc = freq.QuadPart / targetFps;
    double nextDue = start.QuadPart + periodQpc;

    double sum = 0, sumSq = 0, minIv = 1e18, maxIv = 0;
    long frames = 0;
    UINT presentFlags = tearing ? DXGI_PRESENT_ALLOW_TEARING : 0;

    printf("presenting at target %.1f fps for %.0f s (Esc to quit)...\n", targetFps, seconds);
    for (;;) {
        MSG msg;
        while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
            if (msg.message == WM_QUIT) goto done;
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }

        QueryPerformanceCounter(&now);
        if ((now.QuadPart - start.QuadPart) / (double)freq.QuadPart > seconds) break;

        // Pace to the target rate with a high-resolution timer.
        if (now.QuadPart < nextDue) {
            LARGE_INTEGER due;
            due.QuadPart = -(LONGLONG)((nextDue - now.QuadPart) * 10000000.0 / freq.QuadPart);
            if (due.QuadPart < 0) {
                SetWaitableTimer(timer, &due, 0, nullptr, nullptr, FALSE);
                WaitForSingleObject(timer, INFINITE);
            }
        }
        QueryPerformanceCounter(&now);
        nextDue += periodQpc;
        if (nextDue < now.QuadPart) nextDue = now.QuadPart + periodQpc;

        ID3D11Texture2D *bb = nullptr;
        if (SUCCEEDED(swap->GetBuffer(0, __uuidof(ID3D11Texture2D), (void **)&bb))) {
            ID3D11RenderTargetView *rtv = nullptr;
            if (SUCCEEDED(dev->CreateRenderTargetView(bb, nullptr, &rtv))) {
                float shade = (frames & 1) ? 0.25f : 0.30f;
                float col[4] = { shade, shade, shade, 1.0f };
                ctx->ClearRenderTargetView(rtv, col);
                rtv->Release();
            }
            bb->Release();
        }
        swap->Present(0, presentFlags);

        QueryPerformanceCounter(&now);
        double iv = (now.QuadPart - last.QuadPart) * 1000.0 / freq.QuadPart;
        last = now;
        if (frames++ > 0) { // first interval covers setup
            sum += iv; sumSq += iv * iv;
            if (iv < minIv) minIv = iv;
            if (iv > maxIv) maxIv = iv;
        }
    }
done:
    if (frames > 1) {
        long n = frames - 1;
        double mean = sum / n;
        double sd = sqrt(sumSq / n - mean * mean);
        printf("present cadence: %ld frames  mean %.3fms  sd %.3fms  min %.3fms  max %.3fms  -> %.2f fps\n",
               n, mean, sd, minIv, maxIv, 1000.0 / mean);
        printf("now compare against vbprobe's per-output WaitForVBlank cadence, run alongside.\n");
    }
    return 0;
}
