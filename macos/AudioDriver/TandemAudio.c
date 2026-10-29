// Tandem's sound devices: an AudioServerPlugIn that coreaudiod loads from /Library/Audio/Plug-Ins/HAL.
//
// Four devices, two pairs joined by a loopback (ring.c):
//
//   Tandem Speaker            output, in the sound menu. Apps play to it.
//   Tandem Speaker Return     input, hidden. Tandem records it and sends it to the phone.
//   Tandem Microphone         input, in the sound menu. Apps record from it.
//   Tandem Microphone Feed    output, hidden. Tandem plays the phone's microphone into it.
//
// Why four and not two devices with an input and an output each (the usual loopback driver): a device with both is listed as an
// output and as an input, so "Tandem Speaker" would show up as a microphone in every call app. Hidden devices are left out of every
// list and can never be the default; Tandem finds its halves by UID, which is all a hidden device answers to.
//
// All four run on one clock from mach_absolute_time, anchored once when the plug-in starts, so a sample time is the same moment on
// both halves of a pair and the loopback needs no matching up. 48 kHz stereo float32, nothing else, so nothing is ever converted here.
//
// The volume and mute of Tandem Speaker are only kept here, not applied: Tandem reads them (OutputVolume.swift) and turns the phone
// up and down with them, as it does with every output.

#include <CoreAudio/AudioServerPlugIn.h>
#include <CoreFoundation/CoreFoundation.h>
#include <mach/mach_time.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <string.h>

#include "ring.h"

#define RATE 48000.0
#define CHANNELS 2u
// The HAL only needs to know how often the clock passes a zero point; one ring of a real card. Any steady value works.
#define PERIOD 16384u
#define MANUFACTURER CFSTR("Tandem")
#define MIN_DB (-64.0f)

enum {
    kPlugIn = kAudioObjectPlugInObject, // 1
    kSpeaker = 2, kSpeakerStream, kSpeakerVolume, kSpeakerMute,
    kSpeakerReturn = 6, kSpeakerReturnStream,
    kMicrophone = 8, kMicrophoneStream,
    kMicrophoneFeed = 10, kMicrophoneFeedStream,
};

typedef struct {
    AudioObjectID device, stream;
    CFStringRef name, uid;
    Boolean input; // the direction of its one stream
    Boolean hidden;
    TandemRing *ring;
    UInt32 running;
} Device;

static TandemRing gSpeakerRing, gMicrophoneRing;

static Device gDevices[] = {
    {kSpeaker, kSpeakerStream, CFSTR("Tandem Speaker"), CFSTR("nl.markmaaktmedia.tandem.audio.speaker"), false, false, &gSpeakerRing, 0},
    {kSpeakerReturn, kSpeakerReturnStream, CFSTR("Tandem Speaker Return"), CFSTR("nl.markmaaktmedia.tandem.audio.speaker.return"), true, true, &gSpeakerRing, 0},
    {kMicrophone, kMicrophoneStream, CFSTR("Tandem Microphone"), CFSTR("nl.markmaaktmedia.tandem.audio.microphone"), true, false, &gMicrophoneRing, 0},
    {kMicrophoneFeed, kMicrophoneFeedStream, CFSTR("Tandem Microphone Feed"), CFSTR("nl.markmaaktmedia.tandem.audio.microphone.feed"), false, true, &gMicrophoneRing, 0},
};
#define DEVICE_COUNT (sizeof(gDevices) / sizeof(gDevices[0]))

static pthread_mutex_t gLock = PTHREAD_MUTEX_INITIALIZER;
static AudioServerPlugInHostRef gHost;
static UInt64 gAnchor;
static Float64 gTicksPerFrame;
static Float32 gVolume = 1.0f;
static UInt32 gMuted;
static _Atomic UInt32 gRefCount = 1;

static Device *deviceFor(AudioObjectID object) {
    for (UInt32 i = 0; i < DEVICE_COUNT; i++) {
        if (gDevices[i].device == object) return &gDevices[i];
    }
    return NULL;
}

static Device *deviceForStream(AudioObjectID object) {
    for (UInt32 i = 0; i < DEVICE_COUNT; i++) {
        if (gDevices[i].stream == object) return &gDevices[i];
    }
    return NULL;
}

static AudioStreamBasicDescription format(void) {
    AudioStreamBasicDescription f = {0};
    f.mSampleRate = RATE;
    f.mFormatID = kAudioFormatLinearPCM;
    f.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagsNativeEndian | kAudioFormatFlagIsPacked;
    f.mBytesPerPacket = CHANNELS * sizeof(Float32);
    f.mFramesPerPacket = 1;
    f.mBytesPerFrame = CHANNELS * sizeof(Float32);
    f.mChannelsPerFrame = CHANNELS;
    f.mBitsPerChannel = 32;
    return f;
}

static Float32 scalarToDecibels(Float32 scalar) {
    if (scalar <= 0) return MIN_DB;
    // Squared, so the slider moves through the loudness the ear hears rather than through the signal.
    Float32 db = 20.0f * log10f(scalar * scalar);
    return db < MIN_DB ? MIN_DB : (db > 0 ? 0 : db);
}

static Float32 decibelsToScalar(Float32 db) {
    if (db <= MIN_DB) return 0;
    if (db >= 0) return 1;
    return sqrtf(powf(10.0f, db / 20.0f));
}

static void changed(AudioObjectID object, AudioObjectPropertySelector selector, AudioObjectPropertyScope scope) {
    if (!gHost) return;
    AudioObjectPropertyAddress address = {selector, scope, kAudioObjectPropertyElementMain};
    gHost->PropertiesChanged(gHost, object, 1, &address);
}

// MARK: IUnknown

static HRESULT QueryInterface(void *driver, REFIID uuid, LPVOID *out);
static ULONG AddRef(void *driver);
static ULONG Release(void *driver);
static OSStatus Initialize(AudioServerPlugInDriverRef driver, AudioServerPlugInHostRef host);
static OSStatus CreateDevice(AudioServerPlugInDriverRef driver, CFDictionaryRef description, const AudioServerPlugInClientInfo *client, AudioObjectID *out);
static OSStatus DestroyDevice(AudioServerPlugInDriverRef driver, AudioObjectID device);
static OSStatus AddDeviceClient(AudioServerPlugInDriverRef driver, AudioObjectID device, const AudioServerPlugInClientInfo *client);
static OSStatus RemoveDeviceClient(AudioServerPlugInDriverRef driver, AudioObjectID device, const AudioServerPlugInClientInfo *client);
static OSStatus PerformDeviceConfigurationChange(AudioServerPlugInDriverRef driver, AudioObjectID device, UInt64 action, void *info);
static OSStatus AbortDeviceConfigurationChange(AudioServerPlugInDriverRef driver, AudioObjectID device, UInt64 action, void *info);
static Boolean HasProperty(AudioServerPlugInDriverRef driver, AudioObjectID object, pid_t pid, const AudioObjectPropertyAddress *address);
static OSStatus IsPropertySettable(AudioServerPlugInDriverRef driver, AudioObjectID object, pid_t pid, const AudioObjectPropertyAddress *address, Boolean *out);
static OSStatus GetPropertyDataSize(AudioServerPlugInDriverRef driver, AudioObjectID object, pid_t pid, const AudioObjectPropertyAddress *address, UInt32 qualifierSize, const void *qualifier, UInt32 *out);
static OSStatus GetPropertyData(AudioServerPlugInDriverRef driver, AudioObjectID object, pid_t pid, const AudioObjectPropertyAddress *address, UInt32 qualifierSize, const void *qualifier, UInt32 size, UInt32 *outSize, void *out);
static OSStatus SetPropertyData(AudioServerPlugInDriverRef driver, AudioObjectID object, pid_t pid, const AudioObjectPropertyAddress *address, UInt32 qualifierSize, const void *qualifier, UInt32 size, const void *data);
static OSStatus StartIO(AudioServerPlugInDriverRef driver, AudioObjectID device, UInt32 client);
static OSStatus StopIO(AudioServerPlugInDriverRef driver, AudioObjectID device, UInt32 client);
static OSStatus GetZeroTimeStamp(AudioServerPlugInDriverRef driver, AudioObjectID device, UInt32 client, Float64 *sampleTime, UInt64 *hostTime, UInt64 *seed);
static OSStatus WillDoIOOperation(AudioServerPlugInDriverRef driver, AudioObjectID device, UInt32 client, UInt32 operation, Boolean *willDo, Boolean *inPlace);
static OSStatus BeginIOOperation(AudioServerPlugInDriverRef driver, AudioObjectID device, UInt32 client, UInt32 operation, UInt32 frames, const AudioServerPlugInIOCycleInfo *cycle);
static OSStatus DoIOOperation(AudioServerPlugInDriverRef driver, AudioObjectID device, AudioObjectID stream, UInt32 client, UInt32 operation, UInt32 frames, const AudioServerPlugInIOCycleInfo *cycle, void *main, void *secondary);
static OSStatus EndIOOperation(AudioServerPlugInDriverRef driver, AudioObjectID device, UInt32 client, UInt32 operation, UInt32 frames, const AudioServerPlugInIOCycleInfo *cycle);

static AudioServerPlugInDriverInterface gInterface = {
    NULL, QueryInterface, AddRef, Release, Initialize, CreateDevice, DestroyDevice, AddDeviceClient, RemoveDeviceClient,
    PerformDeviceConfigurationChange, AbortDeviceConfigurationChange, HasProperty, IsPropertySettable, GetPropertyDataSize,
    GetPropertyData, SetPropertyData, StartIO, StopIO, GetZeroTimeStamp, WillDoIOOperation, BeginIOOperation, DoIOOperation,
    EndIOOperation,
};
static AudioServerPlugInDriverInterface *gInterfacePtr = &gInterface;
static AudioServerPlugInDriverRef gDriver = &gInterfacePtr;

// The entry point named in Info.plist (CFPlugInFactories).
__attribute__((visibility("default"))) void *TandemAudio_Create(CFAllocatorRef allocator, CFUUIDRef type) {
    (void)allocator;
    return CFEqual(type, kAudioServerPlugInTypeUUID) ? gDriver : NULL;
}

static HRESULT QueryInterface(void *driver, REFIID uuid, LPVOID *out) {
    if (driver != gDriver || !out) return kAudioHardwareBadObjectError;
    CFUUIDRef asked = CFUUIDCreateFromUUIDBytes(NULL, uuid);
    if (!asked) return kAudioHardwareIllegalOperationError;
    Boolean known = CFEqual(asked, IUnknownUUID) || CFEqual(asked, kAudioServerPlugInDriverInterfaceUUID);
    CFRelease(asked);
    if (!known) return E_NOINTERFACE;
    atomic_fetch_add(&gRefCount, 1);
    *out = gDriver;
    return S_OK;
}

static ULONG AddRef(void *driver) {
    if (driver != gDriver) return 0;
    return atomic_fetch_add(&gRefCount, 1) + 1;
}

static ULONG Release(void *driver) {
    if (driver != gDriver) return 0;
    UInt32 now = atomic_load(&gRefCount);
    // The driver is static and lives as long as the process; the count only must not go below zero.
    while (now > 0 && !atomic_compare_exchange_weak(&gRefCount, &now, now - 1)) {}
    return now > 0 ? now - 1 : 0;
}

// MARK: Life

static OSStatus Initialize(AudioServerPlugInDriverRef driver, AudioServerPlugInHostRef host) {
    if (driver != gDriver) return kAudioHardwareBadObjectError;
    gHost = host;
    mach_timebase_info_data_t timebase;
    mach_timebase_info(&timebase);
    Float64 ticksPerSecond = 1e9 * (Float64)timebase.denom / (Float64)timebase.numer;
    gTicksPerFrame = ticksPerSecond / RATE;
    gAnchor = mach_absolute_time();
    tandem_ring_reset(&gSpeakerRing);
    tandem_ring_reset(&gMicrophoneRing);
    return noErr;
}

static OSStatus CreateDevice(AudioServerPlugInDriverRef d, CFDictionaryRef desc, const AudioServerPlugInClientInfo *c, AudioObjectID *o) {
    (void)d; (void)desc; (void)c; (void)o;
    return kAudioHardwareUnsupportedOperationError;
}

static OSStatus DestroyDevice(AudioServerPlugInDriverRef d, AudioObjectID device) {
    (void)d; (void)device;
    return kAudioHardwareUnsupportedOperationError;
}

static OSStatus AddDeviceClient(AudioServerPlugInDriverRef d, AudioObjectID device, const AudioServerPlugInClientInfo *c) {
    (void)c;
    return d == gDriver && deviceFor(device) ? noErr : kAudioHardwareBadObjectError;
}

static OSStatus RemoveDeviceClient(AudioServerPlugInDriverRef d, AudioObjectID device, const AudioServerPlugInClientInfo *c) {
    (void)c;
    return d == gDriver && deviceFor(device) ? noErr : kAudioHardwareBadObjectError;
}

static OSStatus PerformDeviceConfigurationChange(AudioServerPlugInDriverRef d, AudioObjectID device, UInt64 action, void *info) {
    (void)d; (void)device; (void)action; (void)info;
    return noErr;
}

static OSStatus AbortDeviceConfigurationChange(AudioServerPlugInDriverRef d, AudioObjectID device, UInt64 action, void *info) {
    (void)d; (void)device; (void)action; (void)info;
    return noErr;
}

// MARK: Properties

// Writes one value of type T into `out` when it fits, and says how much it took.
#define ANSWER(T, value)                                                                \
    do {                                                                                \
        if (size < sizeof(T)) return kAudioHardwareBadPropertySizeError;                \
        *(T *)out = (value);                                                            \
        *outSize = sizeof(T);                                                           \
        return noErr;                                                                   \
    } while (0)

// A list of object IDs, as many as fit.
static OSStatus answerIDs(const AudioObjectID *ids, UInt32 count, UInt32 size, UInt32 *outSize, void *out) {
    UInt32 fit = size / sizeof(AudioObjectID);
    if (fit > count) fit = count;
    memcpy(out, ids, fit * sizeof(AudioObjectID));
    *outSize = fit * sizeof(AudioObjectID);
    return noErr;
}

static Boolean scopeFits(const Device *device, AudioObjectPropertyScope scope) {
    if (scope == kAudioObjectPropertyScopeGlobal) return true;
    return device->input ? scope == kAudioObjectPropertyScopeInput : scope == kAudioObjectPropertyScopeOutput;
}

// The streams and controls of a device that belong to `scope`, in `ids`; how many.
static UInt32 ownedBy(const Device *device, AudioObjectPropertyScope scope, AudioObjectID ids[3], Boolean controlsOnly) {
    UInt32 n = 0;
    if (!scopeFits(device, scope)) return 0;
    if (!controlsOnly) ids[n++] = device->stream;
    if (device->device == kSpeaker) {
        ids[n++] = kSpeakerVolume;
        ids[n++] = kSpeakerMute;
    }
    return n;
}

static Boolean plugInHas(AudioObjectPropertySelector selector) {
    switch (selector) {
    case kAudioObjectPropertyBaseClass: case kAudioObjectPropertyClass: case kAudioObjectPropertyOwner:
    case kAudioObjectPropertyManufacturer: case kAudioObjectPropertyOwnedObjects: case kAudioPlugInPropertyDeviceList:
    case kAudioPlugInPropertyTranslateUIDToDevice: case kAudioPlugInPropertyResourceBundle:
        return true;
    }
    return false;
}

static Boolean deviceHas(AudioObjectPropertySelector selector) {
    switch (selector) {
    case kAudioObjectPropertyBaseClass: case kAudioObjectPropertyClass: case kAudioObjectPropertyOwner:
    case kAudioObjectPropertyName: case kAudioObjectPropertyManufacturer: case kAudioObjectPropertyOwnedObjects:
    case kAudioDevicePropertyDeviceUID: case kAudioDevicePropertyModelUID: case kAudioDevicePropertyTransportType:
    case kAudioDevicePropertyRelatedDevices: case kAudioDevicePropertyClockDomain: case kAudioDevicePropertyDeviceIsAlive:
    case kAudioDevicePropertyDeviceIsRunning: case kAudioDevicePropertyDeviceCanBeDefaultDevice:
    case kAudioDevicePropertyDeviceCanBeDefaultSystemDevice: case kAudioDevicePropertyLatency: case kAudioDevicePropertyStreams:
    case kAudioObjectPropertyControlList: case kAudioDevicePropertySafetyOffset: case kAudioDevicePropertyNominalSampleRate:
    case kAudioDevicePropertyAvailableNominalSampleRates: case kAudioDevicePropertyIsHidden:
    case kAudioDevicePropertyPreferredChannelsForStereo: case kAudioDevicePropertyPreferredChannelLayout:
    case kAudioDevicePropertyZeroTimeStampPeriod:
        return true;
    }
    return false;
}

static Boolean streamHas(AudioObjectPropertySelector selector) {
    switch (selector) {
    case kAudioObjectPropertyBaseClass: case kAudioObjectPropertyClass: case kAudioObjectPropertyOwner:
    case kAudioObjectPropertyOwnedObjects: case kAudioObjectPropertyName: case kAudioStreamPropertyIsActive:
    case kAudioStreamPropertyDirection: case kAudioStreamPropertyTerminalType: case kAudioStreamPropertyStartingChannel:
    case kAudioStreamPropertyLatency: case kAudioStreamPropertyVirtualFormat: case kAudioStreamPropertyPhysicalFormat:
    case kAudioStreamPropertyAvailableVirtualFormats: case kAudioStreamPropertyAvailablePhysicalFormats:
        return true;
    }
    return false;
}

static Boolean controlHas(AudioObjectID object, AudioObjectPropertySelector selector) {
    switch (selector) {
    case kAudioObjectPropertyBaseClass: case kAudioObjectPropertyClass: case kAudioObjectPropertyOwner:
    case kAudioObjectPropertyOwnedObjects: case kAudioControlPropertyScope: case kAudioControlPropertyElement:
        return true;
    case kAudioLevelControlPropertyScalarValue: case kAudioLevelControlPropertyDecibelValue:
    case kAudioLevelControlPropertyDecibelRange: case kAudioLevelControlPropertyConvertScalarToDecibels:
    case kAudioLevelControlPropertyConvertDecibelsToScalar:
        return object == kSpeakerVolume;
    case kAudioBooleanControlPropertyValue:
        return object == kSpeakerMute;
    }
    return false;
}

static Boolean HasProperty(AudioServerPlugInDriverRef driver, AudioObjectID object, pid_t pid, const AudioObjectPropertyAddress *address) {
    (void)pid;
    if (driver != gDriver || !address) return false;
    if (object == kPlugIn) return plugInHas(address->mSelector);
    if (deviceFor(object)) return deviceHas(address->mSelector);
    if (deviceForStream(object)) return streamHas(address->mSelector);
    if (object == kSpeakerVolume || object == kSpeakerMute) return controlHas(object, address->mSelector);
    return false;
}

static OSStatus IsPropertySettable(AudioServerPlugInDriverRef driver, AudioObjectID object, pid_t pid, const AudioObjectPropertyAddress *address, Boolean *out) {
    if (!HasProperty(driver, object, pid, address)) return kAudioHardwareUnknownPropertyError;
    AudioObjectPropertySelector s = address->mSelector;
    // The rate and the format are fixed; they say settable so the HAL may set them to what they already are.
    *out = (deviceFor(object) && s == kAudioDevicePropertyNominalSampleRate)
        || (deviceForStream(object) && (s == kAudioStreamPropertyVirtualFormat || s == kAudioStreamPropertyPhysicalFormat))
        || (object == kSpeakerVolume && (s == kAudioLevelControlPropertyScalarValue || s == kAudioLevelControlPropertyDecibelValue))
        || (object == kSpeakerMute && s == kAudioBooleanControlPropertyValue);
    return noErr;
}

static OSStatus GetPropertyDataSize(AudioServerPlugInDriverRef driver, AudioObjectID object, pid_t pid, const AudioObjectPropertyAddress *address, UInt32 qualifierSize, const void *qualifier, UInt32 *out) {
    // Asking the data with a buffer that fits everything gives its size; every answer here is small.
    union {
        AudioObjectID ids[8];
        AudioStreamRangedDescription format;
        AudioChannelLayout layout;
        CFStringRef string;
        Float64 rate;
        AudioValueRange range;
        UInt32 numbers[2];
    } scratch;
    UInt32 size = 0;
    OSStatus status = GetPropertyData(driver, object, pid, address, qualifierSize, qualifier, sizeof(scratch), &size, &scratch);
    if (status != noErr) return status;
    // A string answer is a retained object, given back here since only the size was wanted.
    AudioObjectPropertySelector s = address->mSelector;
    if (s == kAudioObjectPropertyName || s == kAudioObjectPropertyManufacturer || s == kAudioDevicePropertyDeviceUID
        || s == kAudioDevicePropertyModelUID || s == kAudioPlugInPropertyResourceBundle) {
        if (scratch.string) CFRelease(scratch.string);
    }
    *out = size;
    return noErr;
}

static OSStatus plugInData(const AudioObjectPropertyAddress *address, UInt32 qualifierSize, const void *qualifier, UInt32 size, UInt32 *outSize, void *out) {
    switch (address->mSelector) {
    case kAudioObjectPropertyBaseClass: ANSWER(AudioClassID, kAudioObjectClassID);
    case kAudioObjectPropertyClass: ANSWER(AudioClassID, kAudioPlugInClassID);
    case kAudioObjectPropertyOwner: ANSWER(AudioObjectID, kAudioObjectUnknown);
    case kAudioObjectPropertyManufacturer: ANSWER(CFStringRef, (CFStringRef)CFRetain(MANUFACTURER));
    case kAudioPlugInPropertyResourceBundle: ANSWER(CFStringRef, (CFStringRef)CFRetain(CFSTR("")));
    case kAudioObjectPropertyOwnedObjects:
    case kAudioPlugInPropertyDeviceList: {
        AudioObjectID ids[DEVICE_COUNT];
        for (UInt32 i = 0; i < DEVICE_COUNT; i++) ids[i] = gDevices[i].device;
        return answerIDs(ids, DEVICE_COUNT, size, outSize, out);
    }
    case kAudioPlugInPropertyTranslateUIDToDevice: {
        if (qualifierSize < sizeof(CFStringRef) || !qualifier) return kAudioHardwareBadPropertySizeError;
        CFStringRef uid = *(const CFStringRef *)qualifier;
        AudioObjectID found = kAudioObjectUnknown;
        for (UInt32 i = 0; uid && i < DEVICE_COUNT; i++) {
            if (CFEqual(uid, gDevices[i].uid)) found = gDevices[i].device;
        }
        ANSWER(AudioObjectID, found);
    }
    }
    return kAudioHardwareUnknownPropertyError;
}

static OSStatus deviceData(const Device *device, const AudioObjectPropertyAddress *address, UInt32 size, UInt32 *outSize, void *out) {
    AudioObjectPropertyScope scope = address->mScope;
    switch (address->mSelector) {
    case kAudioObjectPropertyBaseClass: ANSWER(AudioClassID, kAudioObjectClassID);
    case kAudioObjectPropertyClass: ANSWER(AudioClassID, kAudioDeviceClassID);
    case kAudioObjectPropertyOwner: ANSWER(AudioObjectID, kPlugIn);
    case kAudioObjectPropertyName: ANSWER(CFStringRef, (CFStringRef)CFRetain(device->name));
    case kAudioObjectPropertyManufacturer: ANSWER(CFStringRef, (CFStringRef)CFRetain(MANUFACTURER));
    case kAudioDevicePropertyDeviceUID: ANSWER(CFStringRef, (CFStringRef)CFRetain(device->uid));
    case kAudioDevicePropertyModelUID: ANSWER(CFStringRef, (CFStringRef)CFRetain(CFSTR("nl.markmaaktmedia.tandem.audio.model")));
    case kAudioDevicePropertyTransportType: ANSWER(UInt32, kAudioDeviceTransportTypeVirtual);
    case kAudioDevicePropertyRelatedDevices: return answerIDs(&device->device, 1, size, outSize, out);
    case kAudioDevicePropertyClockDomain: ANSWER(UInt32, 0);
    case kAudioDevicePropertyDeviceIsAlive: ANSWER(UInt32, 1);
    case kAudioDevicePropertyDeviceIsRunning: {
        pthread_mutex_lock(&gLock);
        UInt32 running = device->running > 0;
        pthread_mutex_unlock(&gLock);
        ANSWER(UInt32, running);
    }
    // Only in the direction of its stream, and never a hidden half: Tandem Speaker is no microphone, Tandem Microphone no speaker.
    case kAudioDevicePropertyDeviceCanBeDefaultDevice:
        ANSWER(UInt32, !device->hidden && scope != kAudioObjectPropertyScopeGlobal && scopeFits(device, scope));
    // System sounds (alerts) stay on the Mac's own speakers.
    case kAudioDevicePropertyDeviceCanBeDefaultSystemDevice: ANSWER(UInt32, 0);
    case kAudioDevicePropertyLatency: ANSWER(UInt32, 0);
    case kAudioDevicePropertySafetyOffset: ANSWER(UInt32, 0);
    case kAudioDevicePropertyZeroTimeStampPeriod: ANSWER(UInt32, PERIOD);
    case kAudioDevicePropertyIsHidden: ANSWER(UInt32, device->hidden);
    case kAudioDevicePropertyNominalSampleRate: ANSWER(Float64, RATE);
    case kAudioDevicePropertyAvailableNominalSampleRates: {
        AudioValueRange range = {RATE, RATE};
        ANSWER(AudioValueRange, range);
    }
    case kAudioDevicePropertyStreams:
    case kAudioObjectPropertyOwnedObjects:
    case kAudioObjectPropertyControlList: {
        AudioObjectID ids[3];
        Boolean controls = address->mSelector == kAudioObjectPropertyControlList;
        Boolean streams = address->mSelector == kAudioDevicePropertyStreams;
        UInt32 n = ownedBy(device, controls ? kAudioObjectPropertyScopeGlobal : scope, ids, controls);
        if (streams && n > 1) n = 1;
        return answerIDs(ids, n, size, outSize, out);
    }
    case kAudioDevicePropertyPreferredChannelsForStereo: {
        if (size < 2 * sizeof(UInt32)) return kAudioHardwareBadPropertySizeError;
        ((UInt32 *)out)[0] = 1;
        ((UInt32 *)out)[1] = 2;
        *outSize = 2 * sizeof(UInt32);
        return noErr;
    }
    case kAudioDevicePropertyPreferredChannelLayout: {
        UInt32 need = (UInt32)(offsetof(AudioChannelLayout, mChannelDescriptions) + CHANNELS * sizeof(AudioChannelDescription));
        if (size < need) return kAudioHardwareBadPropertySizeError;
        AudioChannelLayout *layout = out;
        memset(layout, 0, need);
        layout->mChannelLayoutTag = kAudioChannelLayoutTag_UseChannelDescriptions;
        layout->mNumberChannelDescriptions = CHANNELS;
        layout->mChannelDescriptions[0].mChannelLabel = kAudioChannelLabel_Left;
        layout->mChannelDescriptions[1].mChannelLabel = kAudioChannelLabel_Right;
        *outSize = need;
        return noErr;
    }
    }
    return kAudioHardwareUnknownPropertyError;
}

static OSStatus streamData(const Device *device, const AudioObjectPropertyAddress *address, UInt32 size, UInt32 *outSize, void *out) {
    switch (address->mSelector) {
    case kAudioObjectPropertyBaseClass: ANSWER(AudioClassID, kAudioObjectClassID);
    case kAudioObjectPropertyClass: ANSWER(AudioClassID, kAudioStreamClassID);
    case kAudioObjectPropertyOwner: ANSWER(AudioObjectID, device->device);
    case kAudioObjectPropertyOwnedObjects: *outSize = 0; return noErr;
    case kAudioObjectPropertyName: ANSWER(CFStringRef, (CFStringRef)CFRetain(device->name));
    case kAudioStreamPropertyIsActive: ANSWER(UInt32, 1);
    case kAudioStreamPropertyDirection: ANSWER(UInt32, device->input ? 1 : 0);
    case kAudioStreamPropertyTerminalType:
        ANSWER(UInt32, device->input ? kAudioStreamTerminalTypeMicrophone : kAudioStreamTerminalTypeSpeaker);
    case kAudioStreamPropertyStartingChannel: ANSWER(UInt32, 1);
    case kAudioStreamPropertyLatency: ANSWER(UInt32, 0);
    case kAudioStreamPropertyVirtualFormat:
    case kAudioStreamPropertyPhysicalFormat: ANSWER(AudioStreamBasicDescription, format());
    case kAudioStreamPropertyAvailableVirtualFormats:
    case kAudioStreamPropertyAvailablePhysicalFormats: {
        AudioStreamRangedDescription ranged = {format(), {RATE, RATE}};
        ANSWER(AudioStreamRangedDescription, ranged);
    }
    }
    return kAudioHardwareUnknownPropertyError;
}

static OSStatus controlData(AudioObjectID object, const AudioObjectPropertyAddress *address, UInt32 qualifierSize, const void *qualifier, UInt32 size, UInt32 *outSize, void *out) {
    (void)qualifierSize; (void)qualifier;
    Boolean volume = object == kSpeakerVolume;
    switch (address->mSelector) {
    case kAudioObjectPropertyBaseClass: ANSWER(AudioClassID, volume ? kAudioLevelControlClassID : kAudioBooleanControlClassID);
    case kAudioObjectPropertyClass: ANSWER(AudioClassID, volume ? kAudioVolumeControlClassID : kAudioMuteControlClassID);
    case kAudioObjectPropertyOwner: ANSWER(AudioObjectID, kSpeaker);
    case kAudioObjectPropertyOwnedObjects: *outSize = 0; return noErr;
    case kAudioControlPropertyScope: ANSWER(AudioObjectPropertyScope, kAudioObjectPropertyScopeOutput);
    case kAudioControlPropertyElement: ANSWER(AudioObjectPropertyElement, kAudioObjectPropertyElementMain);
    }
    if (volume) {
        switch (address->mSelector) {
        case kAudioLevelControlPropertyScalarValue: {
            pthread_mutex_lock(&gLock);
            Float32 value = gVolume;
            pthread_mutex_unlock(&gLock);
            ANSWER(Float32, value);
        }
        case kAudioLevelControlPropertyDecibelValue: {
            pthread_mutex_lock(&gLock);
            Float32 value = scalarToDecibels(gVolume);
            pthread_mutex_unlock(&gLock);
            ANSWER(Float32, value);
        }
        case kAudioLevelControlPropertyDecibelRange: {
            AudioValueRange range = {MIN_DB, 0};
            ANSWER(AudioValueRange, range);
        }
        // These two convert the value passed in, in place.
        case kAudioLevelControlPropertyConvertScalarToDecibels:
            if (size < sizeof(Float32)) return kAudioHardwareBadPropertySizeError;
            *(Float32 *)out = scalarToDecibels(*(Float32 *)out);
            *outSize = sizeof(Float32);
            return noErr;
        case kAudioLevelControlPropertyConvertDecibelsToScalar:
            if (size < sizeof(Float32)) return kAudioHardwareBadPropertySizeError;
            *(Float32 *)out = decibelsToScalar(*(Float32 *)out);
            *outSize = sizeof(Float32);
            return noErr;
        }
    } else if (address->mSelector == kAudioBooleanControlPropertyValue) {
        pthread_mutex_lock(&gLock);
        UInt32 value = gMuted;
        pthread_mutex_unlock(&gLock);
        ANSWER(UInt32, value);
    }
    return kAudioHardwareUnknownPropertyError;
}

static OSStatus GetPropertyData(AudioServerPlugInDriverRef driver, AudioObjectID object, pid_t pid, const AudioObjectPropertyAddress *address, UInt32 qualifierSize, const void *qualifier, UInt32 size, UInt32 *outSize, void *out) {
    (void)pid;
    if (driver != gDriver) return kAudioHardwareBadObjectError;
    if (!address || !outSize || !out) return kAudioHardwareIllegalOperationError;
    if (object == kPlugIn) return plugInData(address, qualifierSize, qualifier, size, outSize, out);
    Device *device = deviceFor(object);
    if (device) return deviceData(device, address, size, outSize, out);
    device = deviceForStream(object);
    if (device) return streamData(device, address, size, outSize, out);
    if (object == kSpeakerVolume || object == kSpeakerMute) return controlData(object, address, qualifierSize, qualifier, size, outSize, out);
    return kAudioHardwareBadObjectError;
}

static OSStatus SetPropertyData(AudioServerPlugInDriverRef driver, AudioObjectID object, pid_t pid, const AudioObjectPropertyAddress *address, UInt32 qualifierSize, const void *qualifier, UInt32 size, const void *data) {
    (void)pid; (void)qualifierSize; (void)qualifier;
    if (driver != gDriver) return kAudioHardwareBadObjectError;
    if (!address || !data) return kAudioHardwareIllegalOperationError;
    AudioObjectPropertySelector s = address->mSelector;
    if (deviceFor(object) && s == kAudioDevicePropertyNominalSampleRate) {
        if (size < sizeof(Float64)) return kAudioHardwareBadPropertySizeError;
        return *(const Float64 *)data == RATE ? noErr : kAudioDeviceUnsupportedFormatError;
    }
    if (deviceForStream(object) && (s == kAudioStreamPropertyVirtualFormat || s == kAudioStreamPropertyPhysicalFormat)) {
        if (size < sizeof(AudioStreamBasicDescription)) return kAudioHardwareBadPropertySizeError;
        const AudioStreamBasicDescription *asked = data;
        AudioStreamBasicDescription ours = format();
        Boolean same = asked->mSampleRate == ours.mSampleRate && asked->mFormatID == ours.mFormatID
            && asked->mChannelsPerFrame == ours.mChannelsPerFrame && asked->mBitsPerChannel == ours.mBitsPerChannel
            && (asked->mFormatFlags & kAudioFormatFlagIsFloat);
        return same ? noErr : kAudioDeviceUnsupportedFormatError;
    }
    if (object == kSpeakerVolume && (s == kAudioLevelControlPropertyScalarValue || s == kAudioLevelControlPropertyDecibelValue)) {
        if (size < sizeof(Float32)) return kAudioHardwareBadPropertySizeError;
        Float32 value = *(const Float32 *)data;
        if (s == kAudioLevelControlPropertyDecibelValue) value = decibelsToScalar(value);
        value = value < 0 ? 0 : (value > 1 ? 1 : value);
        pthread_mutex_lock(&gLock);
        Boolean differs = value != gVolume;
        gVolume = value;
        pthread_mutex_unlock(&gLock);
        if (differs) {
            changed(kSpeakerVolume, kAudioLevelControlPropertyScalarValue, kAudioObjectPropertyScopeGlobal);
            changed(kSpeakerVolume, kAudioLevelControlPropertyDecibelValue, kAudioObjectPropertyScopeGlobal);
        }
        return noErr;
    }
    if (object == kSpeakerMute && s == kAudioBooleanControlPropertyValue) {
        if (size < sizeof(UInt32)) return kAudioHardwareBadPropertySizeError;
        UInt32 value = *(const UInt32 *)data != 0;
        pthread_mutex_lock(&gLock);
        Boolean differs = value != gMuted;
        gMuted = value;
        pthread_mutex_unlock(&gLock);
        if (differs) changed(kSpeakerMute, kAudioBooleanControlPropertyValue, kAudioObjectPropertyScopeGlobal);
        return noErr;
    }
    return HasProperty(driver, object, pid, address) ? kAudioHardwareUnsupportedOperationError : kAudioHardwareUnknownPropertyError;
}

// MARK: IO

static OSStatus StartIO(AudioServerPlugInDriverRef driver, AudioObjectID object, UInt32 client) {
    (void)client;
    Device *device = deviceFor(object);
    if (driver != gDriver || !device) return kAudioHardwareBadObjectError;
    pthread_mutex_lock(&gLock);
    // The HAL tells its clients itself that the device runs; this count is only for kAudioDevicePropertyDeviceIsRunning.
    device->running++;
    pthread_mutex_unlock(&gLock);
    return noErr;
}

static OSStatus StopIO(AudioServerPlugInDriverRef driver, AudioObjectID object, UInt32 client) {
    (void)client;
    Device *device = deviceFor(object);
    if (driver != gDriver || !device) return kAudioHardwareBadObjectError;
    pthread_mutex_lock(&gLock);
    if (device->running > 0) device->running--;
    pthread_mutex_unlock(&gLock);
    return noErr;
}

// The last zero point of the shared clock at or before now. Worked out from the time alone, so every device agrees and it never
// goes back.
static OSStatus GetZeroTimeStamp(AudioServerPlugInDriverRef driver, AudioObjectID object, UInt32 client, Float64 *sampleTime, UInt64 *hostTime, UInt64 *seed) {
    (void)client;
    if (driver != gDriver || !deviceFor(object)) return kAudioHardwareBadObjectError;
    Float64 ticksPerPeriod = gTicksPerFrame * PERIOD;
    UInt64 now = mach_absolute_time();
    UInt64 periods = now > gAnchor ? (UInt64)((Float64)(now - gAnchor) / ticksPerPeriod) : 0;
    *sampleTime = (Float64)periods * PERIOD;
    *hostTime = gAnchor + (UInt64)((Float64)periods * ticksPerPeriod);
    *seed = 1;
    return noErr;
}

static OSStatus WillDoIOOperation(AudioServerPlugInDriverRef driver, AudioObjectID object, UInt32 client, UInt32 operation, Boolean *willDo, Boolean *inPlace) {
    (void)client;
    Device *device = deviceFor(object);
    if (driver != gDriver || !device) return kAudioHardwareBadObjectError;
    *willDo = device->input ? operation == kAudioServerPlugInIOOperationReadInput : operation == kAudioServerPlugInIOOperationWriteMix;
    *inPlace = true;
    return noErr;
}

static OSStatus BeginIOOperation(AudioServerPlugInDriverRef driver, AudioObjectID object, UInt32 client, UInt32 operation, UInt32 frames, const AudioServerPlugInIOCycleInfo *cycle) {
    (void)client; (void)operation; (void)frames; (void)cycle;
    return driver == gDriver && deviceFor(object) ? noErr : kAudioHardwareBadObjectError;
}

static OSStatus DoIOOperation(AudioServerPlugInDriverRef driver, AudioObjectID object, AudioObjectID stream, UInt32 client, UInt32 operation, UInt32 frames, const AudioServerPlugInIOCycleInfo *cycle, void *main, void *secondary) {
    (void)client; (void)secondary;
    Device *device = deviceFor(object);
    if (driver != gDriver || !device || stream != device->stream || !cycle || !main) return kAudioHardwareBadObjectError;
    if (operation == kAudioServerPlugInIOOperationReadInput && device->input) {
        tandem_ring_read(device->ring, (int64_t)cycle->mInputTime.mSampleTime, main, frames);
    } else if (operation == kAudioServerPlugInIOOperationWriteMix && !device->input) {
        tandem_ring_write(device->ring, (int64_t)cycle->mOutputTime.mSampleTime, main, frames);
    }
    return noErr;
}

static OSStatus EndIOOperation(AudioServerPlugInDriverRef driver, AudioObjectID object, UInt32 client, UInt32 operation, UInt32 frames, const AudioServerPlugInIOCycleInfo *cycle) {
    (void)client; (void)operation; (void)frames; (void)cycle;
    return driver == gDriver && deviceFor(object) ? noErr : kAudioHardwareBadObjectError;
}
