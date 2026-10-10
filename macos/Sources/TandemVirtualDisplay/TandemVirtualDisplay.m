#import "TandemVirtualDisplay.h"

#import <Foundation/Foundation.h>
#import <dispatch/dispatch.h>

// What CoreGraphics has for a display made of software. There is no public header: these are the declarations of what the class has,
// and the system looks the class up by name when it runs. Nothing here is called unless TandemVirtualDisplayAvailable says it is there.
@interface CGVirtualDisplayMode : NSObject
- (instancetype)initWithWidth:(unsigned int)width height:(unsigned int)height refreshRate:(double)refreshRate;
@end

@interface CGVirtualDisplaySettings : NSObject
@property(nonatomic, retain) NSArray<CGVirtualDisplayMode *> *modes;
@property(nonatomic) unsigned int hiDPI;
@end

@interface CGVirtualDisplayDescriptor : NSObject
@property(nonatomic, retain) dispatch_queue_t queue;
@property(nonatomic, retain) NSString *name;
@property(nonatomic) unsigned int maxPixelsWide;
@property(nonatomic) unsigned int maxPixelsHigh;
@property(nonatomic) CGSize sizeInMillimeters;
@property(nonatomic) unsigned int productID;
@property(nonatomic) unsigned int vendorID;
@property(nonatomic) unsigned int serialNum;
@end

@interface CGVirtualDisplay : NSObject
@property(readonly, nonatomic) CGDirectDisplayID displayID;
- (instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor *)descriptor;
- (BOOL)applySettings:(CGVirtualDisplaySettings *)settings;
@end

int TandemVirtualDisplayAvailable(void) {
    return NSClassFromString(@"CGVirtualDisplay") != nil && NSClassFromString(@"CGVirtualDisplayDescriptor") != nil &&
           NSClassFromString(@"CGVirtualDisplaySettings") != nil && NSClassFromString(@"CGVirtualDisplayMode") != nil;
}

void *TandemVirtualDisplayCreate(const char *name, int width, int height, int hiDPI, int refresh, unsigned int serial, CGDirectDisplayID *displayID) {
    if (!TandemVirtualDisplayAvailable() || width < 64 || height < 64) return NULL;
    @autoreleasepool {
        CGVirtualDisplayDescriptor *descriptor = [[NSClassFromString(@"CGVirtualDisplayDescriptor") alloc] init];
        descriptor.queue = dispatch_get_main_queue();
        descriptor.name = [NSString stringWithUTF8String:name ? name : "Tandem"];
        descriptor.maxPixelsWide = (unsigned int)width;
        descriptor.maxPixelsHigh = (unsigned int)height;
        // About the size of a laptop panel at this many pixels, which is what the system works the points per inch out of.
        descriptor.sizeInMillimeters = CGSizeMake(width * 25.4 / 160.0, height * 25.4 / 160.0);
        descriptor.productID = 0x7A4D;
        descriptor.vendorID = 0x7A4E;
        descriptor.serialNum = serial;

        CGVirtualDisplay *display = [[NSClassFromString(@"CGVirtualDisplay") alloc] initWithDescriptor:descriptor];
        if (display == nil) return NULL;

        CGVirtualDisplaySettings *settings = [[NSClassFromString(@"CGVirtualDisplaySettings") alloc] init];
        settings.hiDPI = hiDPI ? 1 : 0;
        // In pixels: a display that shows hiDPI is described by the size of its points.
        unsigned int modeWidth = hiDPI ? (unsigned int)width / 2 : (unsigned int)width;
        unsigned int modeHeight = hiDPI ? (unsigned int)height / 2 : (unsigned int)height;
        CGVirtualDisplayMode *mode = [[NSClassFromString(@"CGVirtualDisplayMode") alloc] initWithWidth:modeWidth height:modeHeight refreshRate:(double)(refresh > 0 ? refresh : 60)];
        settings.modes = @[ mode ];
        if (![display applySettings:settings]) return NULL;
        if (displayID != NULL) *displayID = display.displayID;
        return (void *)CFBridgingRetain(display);
    }
}

void TandemVirtualDisplayDestroy(void *handle) {
    if (handle == NULL) return;
    // Letting go of the last reference is what takes the display away.
    CFBridgingRelease(handle);
}
