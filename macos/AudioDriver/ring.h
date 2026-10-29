// The loopback between the two halves of a Tandem sound device: what one side writes at a sample time, the other reads at the
// same sample time. Both halves run on one clock (see TandemAudio.c), so a sample time means the same moment on either side.
//
// It is indexed by sample time, not by a read and a write position: two apps that record the same device both read, and neither
// may take the sound away from the other. A write horizon marks where the writer got to. Past it there is only silence, so a
// writer that stops never leaves the last third of a second going round and round.

#ifndef TANDEM_RING_H
#define TANDEM_RING_H

#include <stdatomic.h>
#include <stdint.h>

#define TANDEM_RING_FRAMES 65536u // a power of two, about 1.4 s at 48 kHz: well past what any IO buffer asks for
#define TANDEM_RING_CHANNELS 2u

typedef struct {
    float samples[TANDEM_RING_FRAMES * TANDEM_RING_CHANNELS];
    // One past the last frame written, as a sample time. Frames in [horizon - TANDEM_RING_FRAMES, horizon) are sound, all else silence.
    _Atomic int64_t horizon;
} TandemRing;

void tandem_ring_reset(TandemRing *ring);

// `frames` interleaved stereo frames from `time` on. A write that starts past the horizon clears the gap first, so what was left
// there from an earlier round is not taken for new sound.
void tandem_ring_write(TandemRing *ring, int64_t time, const float *frames, uint32_t count);

// `count` interleaved stereo frames from `time` on; silence for what was never written or has been written over since.
void tandem_ring_read(const TandemRing *ring, int64_t time, float *out, uint32_t count);

#endif
