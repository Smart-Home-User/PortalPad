// PortalPad — Phase 1 physical-mouse capture (native).
//
// Runs INSIDE the privileged process (Shizuku shell uid, or root) because only
// that process can open /dev/input/eventN. Responsibilities:
//   1) open the device read-write
//   2) optionally EVIOCGRAB it (exclusive grab → the framework stops seeing it,
//      so the phone's own system pointer doesn't also move). Whether this
//      succeeds under the shell domain is THE Phase-1 question.
//   3) poll+read input_event structs, accumulate REL_X/REL_Y (+wheel/buttons),
//      and on each SYN_REPORT write a fixed 16-byte little-endian record
//      {int32 dx, int32 dy, int32 buttons, int32 wheel} to the pipe fd the
//      app reads. Parsing in native avoids 32/64-bit struct-size guesswork.
//
// All functions are JNI entry points for com.portalpad.app.service.NativeMouse.

#include <jni.h>
#include <android/log.h>

#include <linux/input.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdint.h>

#define TAG "PortalPadMouseNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// Single capture session at a time (Phase 1). The read loop polls this between
// reads so stopMouseCapture() can break it cleanly without closing the fd out
// from under an in-flight read().
static volatile int g_stop = 0;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_portalpad_app_service_NativeMouse_nativeOpen(JNIEnv* env, jobject, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return -EINVAL;
    // Read-only: we only read events and (optionally) EVIOCGRAB — we never write
    // to the evdev node (injection goes through the framework). Samsung's SELinux
    // policy denies the shell domain write access to /dev/input/*, so O_RDWR gets
    // EACCES; O_RDONLY is both sufficient and what `getevent` uses successfully.
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    int err = errno;
    env->ReleaseStringUTFChars(jpath, path);
    if (fd < 0) {
        LOGW("open failed errno=%d (%s)", err, strerror(err));
        return -err;
    }
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_portalpad_app_service_NativeMouse_nativeGrab(JNIEnv*, jobject, jint fd) {
    if (ioctl(fd, EVIOCGRAB, 1) != 0) {
        int err = errno;
        LOGW("EVIOCGRAB failed errno=%d (%s)", err, strerror(err));
        return -err;
    }
    LOGI("EVIOCGRAB succeeded on fd=%d", fd);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_portalpad_app_service_NativeMouse_nativeUngrabClose(JNIEnv*, jobject, jint fd) {
    if (fd >= 0) {
        ioctl(fd, EVIOCGRAB, 0);
        close(fd);
    }
}

JNIEXPORT void JNICALL
Java_com_portalpad_app_service_NativeMouse_nativeStop(JNIEnv*, jobject) {
    g_stop = 1;
}

// Write one tagged 20-byte little-endian record {int32 type, a, b, c, d} to the
// pipe. type 0 = mouse {dx, dy, buttons, wheel}; type 1 = key {linuxKeycode,
// down, 0, 0}. Returns false if the reader closed the pipe (EPIPE) so the caller
// can end the loop cleanly. The mouse PAYLOAD is byte-identical to before — only
// a leading type tag is prepended — so the existing mouse path is unaffected.
static bool writeRecord(int writeFd, const int32_t rec[5]) {
    const char* p = (const char*) rec;
    size_t left = sizeof(int32_t) * 5;
    while (left > 0) {
        ssize_t w = write(writeFd, p, left);
        if (w < 0) {
            if (errno == EINTR) continue;
            LOGW("pipe write failed errno=%d — ending loop", errno);
            return false;
        }
        p += w;
        left -= (size_t) w;
    }
    return true;
}

// Blocking. Returns a reason code: 0 = stopped on request, negative = errno of
// the failure that ended the loop (e.g. device unplugged, pipe reader closed).
JNIEXPORT jint JNICALL
Java_com_portalpad_app_service_NativeMouse_nativeRunLoop(JNIEnv*, jobject, jint fd, jint writeFd) {
    g_stop = 0;

    int32_t dx = 0, dy = 0, wheel = 0;
    int32_t buttons = 0;       // bit0 left, bit1 right, bit2 middle
    int32_t lastButtons = 0;
    bool pending = false;      // any REL/button change since last SYN

    struct input_event ev[64];
    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;

    while (!g_stop) {
        int pr = poll(&pfd, 1, 200);   // 200ms so g_stop is checked ~5x/sec
        if (pr < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        if (pr == 0) continue;          // timeout → re-check g_stop
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return -EIO;

        ssize_t n = read(fd, ev, sizeof(ev));
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            return -errno;              // device gone
        }
        if (n == 0) continue;
        size_t count = (size_t) n / sizeof(struct input_event);

        for (size_t i = 0; i < count; i++) {
            const struct input_event& e = ev[i];
            if (e.type == EV_REL) {
                if (e.code == REL_X)      { dx += e.value; pending = true; }
                else if (e.code == REL_Y) { dy += e.value; pending = true; }
                else if (e.code == REL_WHEEL) { wheel += e.value; pending = true; }
            } else if (e.type == EV_KEY) {
                int bit = -1;
                if (e.code == BTN_LEFT)        bit = 0;
                else if (e.code == BTN_RIGHT)  bit = 1;
                else if (e.code == BTN_MIDDLE) bit = 2;
                if (bit >= 0) {
                    if (e.value) buttons |= (1 << bit);
                    else         buttons &= ~(1 << bit);
                    pending = true;
                } else if (e.value == 0 || e.value == 1 || e.value == 2) {
                    // A non-mouse-button key on the same (combo) device — i.e. a
                    // KEYBOARD key. It's already in this grabbed stream; forward it
                    // immediately as a tagged key record so the app can map it to an
                    // Android keycode and inject it on the external display.
                    // value: 1=down, 2=autorepeat (treated as down), 0=up.
                    int32_t krec[5] = { 1, (int32_t) e.code, (e.value != 0) ? 1 : 0, 0, 0 };
                    if (!writeRecord(writeFd, krec)) return 0;
                }
            } else if (e.type == EV_SYN && e.code == SYN_REPORT) {
                if (pending && (dx || dy || wheel || buttons != lastButtons)) {
                    // type 0 = mouse; payload {dx,dy,buttons,wheel} unchanged.
                    int32_t rec[5] = { 0, dx, dy, buttons, wheel };
                    if (!writeRecord(writeFd, rec)) return 0;
                }
                lastButtons = buttons;
                dx = dy = wheel = 0;
                pending = false;
            }
        }
    }
    return 0;
}

} // extern "C"
