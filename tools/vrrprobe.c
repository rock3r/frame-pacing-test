// vrrprobe.c — Linux VRR capability + vblank cadence probe (libdrm).
//
// Reports, for every connected connector: the "vrr_capable" connector
// property, the driving CRTC's "VRR_ENABLED" property, and the current mode.
// Then measures vblank interval statistics on the active CRTC — run it once
// with an idle desktop and once with a variable-rate fullscreen load (see
// VRR-RUNBOOK.md) to see whether the vblank clock follows content rate.
//
// Build: gcc -O2 -o vrrprobe vrrprobe.c $(pkg-config --cflags --libs libdrm) -lm
// Usage: vrrprobe [/dev/dri/cardN] [samples]
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <fcntl.h>
#include <unistd.h>
#include <time.h>
#include <xf86drm.h>
#include <xf86drmMode.h>

static int readObjectProp(int fd, uint32_t objId, uint32_t objType,
                          const char *name, uint64_t *out)
{
    drmModeObjectProperties *props = drmModeObjectGetProperties(fd, objId, objType);
    if (!props) return 0;
    int found = 0;
    for (uint32_t i = 0; i < props->count_props && !found; i++) {
        drmModePropertyRes *p = drmModeGetProperty(fd, props->props[i]);
        if (!p) continue;
        if (strcmp(p->name, name) == 0) {
            *out = props->prop_values[i];
            found = 1;
        }
        drmModeFreeProperty(p);
    }
    drmModeFreeObjectProperties(props);
    return found;
}

int main(int argc, char **argv)
{
    const char *path = argc > 1 ? argv[1] : NULL;
    int samples = argc > 2 ? atoi(argv[2]) : 600;
    char detected[32];

    if (!path) {
        for (int i = 0; i < 16; i++) {
            snprintf(detected, sizeof detected, "/dev/dri/card%d", i);
            int fd = open(detected, O_RDWR | O_CLOEXEC);
            if (fd >= 0) { close(fd); path = detected; break; }
        }
        if (!path) { fprintf(stderr, "no /dev/dri/card* accessible\n"); return 1; }
    }

    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) { perror(path); return 1; }
    drmModeRes *res = drmModeGetResources(fd);
    if (!res) { fprintf(stderr, "no KMS resources on %s\n", path); return 1; }

    int activeCrtcIndex = -1;
    for (int i = 0; i < res->count_connectors; i++) {
        drmModeConnector *c = drmModeGetConnectorCurrent(fd, res->connectors[i]);
        if (!c) continue;
        if (c->connection == DRM_MODE_CONNECTED) {
            const char *tn = drmModeGetConnectorTypeName(c->connector_type);
            uint64_t vrrCapable = 0;
            int hasVrrCap = readObjectProp(fd, c->connector_id,
                    DRM_MODE_OBJECT_CONNECTOR, "vrr_capable", &vrrCapable);

            uint32_t crtcId = 0;
            if (c->encoder_id) {
                drmModeEncoder *e = drmModeGetEncoder(fd, c->encoder_id);
                if (e) { crtcId = e->crtc_id; drmModeFreeEncoder(e); }
            }
            uint64_t vrrEnabled = 0;
            int hasVrrEn = 0;
            char modeStr[64] = "no active mode";
            if (crtcId) {
                hasVrrEn = readObjectProp(fd, crtcId, DRM_MODE_OBJECT_CRTC,
                        "VRR_ENABLED", &vrrEnabled);
                drmModeCrtc *crtc = drmModeGetCrtc(fd, crtcId);
                if (crtc && crtc->mode_valid) {
                    double hz = (double)crtc->mode.clock * 1000.0
                            / ((double)crtc->mode.htotal * crtc->mode.vtotal);
                    snprintf(modeStr, sizeof modeStr, "%ux%u nominal %.3f Hz",
                             crtc->mode.hdisplay, crtc->mode.vdisplay, hz);
                    for (int k = 0; k < res->count_crtcs; k++)
                        if (res->crtcs[k] == crtcId) activeCrtcIndex = k;
                }
                if (crtc) drmModeFreeCrtc(crtc);
            }

            printf("%s-%d: %s\n", tn, c->connector_type_id, modeStr);
            printf("  vrr_capable = %s\n",
                   hasVrrCap ? (vrrCapable ? "YES" : "no") : "property absent (driver predates VRR or no support)");
            printf("  VRR_ENABLED = %s\n",
                   hasVrrEn ? (vrrEnabled ? "YES (compositor enabled VRR on this CRTC)" : "no")
                            : "property absent");
        }
        drmModeFreeConnector(c);
    }

    if (activeCrtcIndex < 0) {
        printf("no active CRTC — skipping cadence measurement\n");
        return 0;
    }

    unsigned int high = (activeCrtcIndex << DRM_VBLANK_HIGH_CRTC_SHIFT)
            & DRM_VBLANK_HIGH_CRTC_MASK;
    drmVBlank vbl;
    memset(&vbl, 0, sizeof vbl);
    vbl.request.type = (drmVBlankSeqType)(DRM_VBLANK_RELATIVE | high);
    vbl.request.sequence = 0;
    if (drmWaitVBlank(fd, &vbl) != 0) { perror("drmWaitVBlank"); return 1; }

    double *iv = calloc(samples, sizeof(double));
    double prev = vbl.reply.tval_sec + vbl.reply.tval_usec / 1e6;
    for (int i = 0; i < samples; i++) {
        memset(&vbl, 0, sizeof vbl);
        vbl.request.type = (drmVBlankSeqType)(DRM_VBLANK_RELATIVE | high);
        vbl.request.sequence = 1;
        if (drmWaitVBlank(fd, &vbl) != 0) { perror("drmWaitVBlank"); return 1; }
        double t = vbl.reply.tval_sec + vbl.reply.tval_usec / 1e6;
        iv[i] = (t - prev) * 1000.0;
        prev = t;
    }

    double sum = 0, min = 1e9, max = 0;
    for (int i = 0; i < samples; i++) { sum += iv[i]; if (iv[i] < min) min = iv[i]; if (iv[i] > max) max = iv[i]; }
    double mean = sum / samples, var = 0;
    for (int i = 0; i < samples; i++) var += (iv[i] - mean) * (iv[i] - mean);
    printf("vblank cadence: samples %d  mean %.3fms  sd %.3fms  min %.3fms  max %.3fms  -> %.2f Hz\n",
           samples, mean, sqrt(var / samples), min, max, 1000.0 / mean);
    printf("  (VRR active shows here as content-dependent intervals: min near the panel's\n"
           "   max-rate period, max stretching toward its min-rate period)\n");
    return 0;
}
