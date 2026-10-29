#include "ring.h"

#include <string.h>

static uint32_t slot(int64_t time) { return (uint32_t)((uint64_t)time & (TANDEM_RING_FRAMES - 1)); }

// Frames [time, time + count) of the ring as at most two runs, since the ring wraps.
static void copy_in(TandemRing *ring, int64_t time, const float *frames, uint32_t count) {
    uint32_t start = slot(time);
    uint32_t first = TANDEM_RING_FRAMES - start < count ? TANDEM_RING_FRAMES - start : count;
    float *base = ring->samples;
    if (frames) {
        memcpy(base + start * TANDEM_RING_CHANNELS, frames, first * TANDEM_RING_CHANNELS * sizeof(float));
        memcpy(base, frames + first * TANDEM_RING_CHANNELS, (count - first) * TANDEM_RING_CHANNELS * sizeof(float));
    } else {
        memset(base + start * TANDEM_RING_CHANNELS, 0, first * TANDEM_RING_CHANNELS * sizeof(float));
        memset(base, 0, (count - first) * TANDEM_RING_CHANNELS * sizeof(float));
    }
}

void tandem_ring_reset(TandemRing *ring) {
    memset(ring->samples, 0, sizeof(ring->samples));
    atomic_store_explicit(&ring->horizon, INT64_MIN / 2, memory_order_release);
}

void tandem_ring_write(TandemRing *ring, int64_t time, const float *frames, uint32_t count) {
    if (count == 0) return;
    // More than the ring holds: only the end of it can be kept.
    if (count > TANDEM_RING_FRAMES) {
        frames += (size_t)(count - TANDEM_RING_FRAMES) * TANDEM_RING_CHANNELS;
        time += count - TANDEM_RING_FRAMES;
        count = TANDEM_RING_FRAMES;
    }
    int64_t horizon = atomic_load_explicit(&ring->horizon, memory_order_acquire);
    if (time > horizon) {
        int64_t gap = time - horizon;
        if (gap > (int64_t)TANDEM_RING_FRAMES) gap = TANDEM_RING_FRAMES;
        copy_in(ring, time - gap, NULL, (uint32_t)gap);
    }
    copy_in(ring, time, frames, count);
    int64_t end = time + count;
    if (end > horizon) atomic_store_explicit(&ring->horizon, end, memory_order_release);
}

void tandem_ring_read(const TandemRing *ring, int64_t time, float *out, uint32_t count) {
    int64_t horizon = atomic_load_explicit(&ring->horizon, memory_order_acquire);
    int64_t oldest = horizon - (int64_t)TANDEM_RING_FRAMES;
    for (uint32_t i = 0; i < count; i++) {
        int64_t t = time + i;
        float *dst = out + (size_t)i * TANDEM_RING_CHANNELS;
        if (t >= horizon || t < oldest) {
            dst[0] = 0;
            dst[1] = 0;
        } else {
            const float *src = ring->samples + slot(t) * TANDEM_RING_CHANNELS;
            dst[0] = src[0];
            dst[1] = src[1];
        }
    }
}
