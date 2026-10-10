#ifndef TANDEM_VIRTUAL_DISPLAY_H
#define TANDEM_VIRTUAL_DISPLAY_H

#include <CoreGraphics/CoreGraphics.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Whether this system has the way to add a display that is only made of software (CoreGraphics keeps it, and offers no public header for it).
int TandemVirtualDisplayAvailable(void);

/// Adds a display of `width` by `height` pixels (`hiDPI` shows it with the sharpness of a Retina screen, at half the size in points) and gives
/// a handle to it, or NULL when the system would not. The display is gone when the handle is destroyed.
void *TandemVirtualDisplayCreate(const char *name, int width, int height, int hiDPI, int refresh, unsigned int serial, CGDirectDisplayID *displayID);

void TandemVirtualDisplayDestroy(void *handle);

#ifdef __cplusplus
}
#endif

#endif
