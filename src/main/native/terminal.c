/* Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT */
/* Owned POSIX implementation. See README.md in this directory for provenance. */
/* Darwin gates SIGWINCH behind _DARWIN_C_SOURCE even when the POSIX level is sufficient. */
#if defined(__APPLE__) && !defined(_DARWIN_C_SOURCE)
#define _DARWIN_C_SOURCE 1
#endif
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <limits.h>
#include <sys/stat.h>
#include <time.h>
#include <stdlib.h>
#include <signal.h>
#include <stdatomic.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

struct bt_terminal {
    int input;
    int output;
    int raw;
    struct termios original;
    struct sigaction previous_resize;
};

/* Only a lock-free counter is touched in the signal handler; no context can be freed under it. */
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "resize notifications require lock-free unsigned int");
static _Atomic unsigned int resize_epoch;
static void resized(int signal_number) {
    (void)signal_number;
    atomic_fetch_add_explicit(&resize_epoch, 1u, memory_order_relaxed);
}
int bt_resize_epoch(void) {
    return (int)atomic_load_explicit(&resize_epoch, memory_order_relaxed);
}

/* ABI 1: opaque contexts; no platform-specific structures cross the Java boundary. */
int bt_abi(void) { return 1; }

int bt_open(int input, int output, void **result) {
    *result = NULL;
    if (!isatty(input) || !isatty(output)) return ENOTTY;
    struct bt_terminal *t = calloc(1, sizeof(*t));
    if (t == NULL) return ENOMEM;
    t->input = fcntl(input, F_DUPFD_CLOEXEC, 3);
    if (t->input < 0) { int error = errno; free(t); return error; }
    /* dup shares file status flags. Reopen the same terminal so nonblocking output does
       not change stdout (or a shell/other owner sharing its open file description). */
    char path[PATH_MAX];
    int named = ttyname_r(output, path, sizeof(path));
    if (named != 0) { close(t->input); free(t); return named; }
    t->output = open(path, O_WRONLY | O_NOCTTY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    if (t->output < 0) {
        int error = errno; close(t->input); free(t); return error;
    }
    struct stat original_output, reopened_output;
    if (fstat(output, &original_output) < 0 || fstat(t->output, &reopened_output) < 0) {
        int error = errno; close(t->output); close(t->input); free(t); return error;
    }
    if (!S_ISCHR(reopened_output.st_mode) || original_output.st_dev != reopened_output.st_dev ||
        original_output.st_ino != reopened_output.st_ino || original_output.st_rdev != reopened_output.st_rdev) {
        close(t->output); close(t->input); free(t); return ENOTTY;
    }
    if (tcgetattr(t->input, &t->original) < 0) {
        int error = errno; close(t->output); close(t->input); free(t); return error;
    }
    struct sigaction action = {0};
    action.sa_handler = resized;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGWINCH, &action, &t->previous_resize) < 0) {
        int error = errno; close(t->output); close(t->input); free(t); return error;
    }
    *result = t;
    return 0;
}

int bt_raw(struct bt_terminal *t) {
    if (t->raw) return 0;
    struct termios mode = t->original;
    mode.c_iflag &= (tcflag_t)~(IGNBRK | BRKINT | PARMRK | ISTRIP | INLCR | IGNCR | ICRNL | IXON);
    mode.c_oflag &= (tcflag_t)~OPOST;
    mode.c_lflag &= (tcflag_t)~(ECHO | ECHONL | ICANON | ISIG | IEXTEN);
    mode.c_cflag = (mode.c_cflag & (tcflag_t)~(CSIZE | PARENB)) | CS8;
    mode.c_cc[VMIN] = 1;
    mode.c_cc[VTIME] = 0;
    int status;
    do { status = tcsetattr(t->input, TCSANOW, &mode); } while (status < 0 && errno == EINTR);
    if (status < 0) return errno;
    t->raw = 1;
    return 0;
}

/* read: positive byte count, zero timeout/interruption, -1 EOF, -(errno+2) error. */
int bt_read(struct bt_terminal *t, unsigned char *bytes, int capacity, int timeout) {
    if (capacity <= 0 || timeout < 0) return -(EINVAL + 2);
    struct pollfd descriptor = {t->input, POLLIN, 0};
    int ready = poll(&descriptor, 1, timeout);
    if (ready == 0 || (ready < 0 && errno == EINTR)) return 0;
    if (ready < 0) return -(errno + 2);
    if (descriptor.revents & POLLNVAL) return -(EBADF + 2);
    /* POLLHUP may accompany unread data. Drain that data before reporting EOF. */
    if (!(descriptor.revents & (POLLIN | POLLHUP | POLLERR))) return 0;
    ssize_t count = read(t->input, bytes, (size_t)capacity);
    if (count > 0) return (int)count;
    if (count == 0) return -1;
    if (errno == EINTR || errno == EAGAIN) return 0;
    /* Linux PTY masters may report EIO after their peer disappears. */
    if (errno == EIO && (descriptor.revents & POLLHUP)) return -1;
    return -(errno + 2);
}

/* One call has a one-second total budget, including short writes and interruptions. */
int bt_write(struct bt_terminal *t, const unsigned char *bytes, int length) {
    if (length < 0) return EINVAL;
    if (length == 0) return 0;
    struct timespec start, now;
    if (clock_gettime(CLOCK_MONOTONIC, &start) < 0) return errno;
    int offset = 0;
    while (offset < length) {
        if (clock_gettime(CLOCK_MONOTONIC, &now) < 0) return errno;
        long long elapsed = (long long)(now.tv_sec - start.tv_sec) * 1000000000LL +
            now.tv_nsec - start.tv_nsec;
        long long remaining = 1000000000LL - elapsed;
        if (remaining <= 0) return ETIMEDOUT;
        ssize_t count = write(t->output, bytes + offset, (size_t)(length - offset));
        if (count > 0) { offset += (int)count; continue; }
        if (count < 0 && errno == EINTR) continue;
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd descriptor = {t->output, POLLOUT, 0};
            int ready = poll(&descriptor, 1, (int)((remaining + 999999LL) / 1000000LL));
            if (ready < 0 && errno != EINTR) return errno;
            if (ready > 0 && (descriptor.revents & POLLNVAL)) return EBADF;
            if (ready > 0 && (descriptor.revents & (POLLERR | POLLHUP))) return EIO;
            continue;
        }
        return count == 0 ? EIO : errno;
    }
    return 0;
}

int bt_size(struct bt_terminal *t, int *rows, int *columns) {
    struct winsize size;
    if (ioctl(t->output, TIOCGWINSZ, &size) < 0) return errno;
    *rows = size.ws_row;
    *columns = size.ws_col;
    return 0;
}

/* The caller serializes close with every other operation on this context. */
int bt_close(struct bt_terminal *t) {
    int error = 0;
    struct sigaction current;
    if (sigaction(SIGWINCH, NULL, &current) < 0) error = errno;
    else if (current.sa_handler == resized && sigaction(SIGWINCH, &t->previous_resize, NULL) < 0) error = errno;
    if (t->raw) {
        int status;
        do { status = tcsetattr(t->input, TCSANOW, &t->original); } while (status < 0 && errno == EINTR);
        if (status < 0 && error == 0) error = errno;
    }
    /* Do not retry close(EINTR): a descriptor may already have been released/reused. */
    if (close(t->input) < 0 && error == 0) error = errno;
    if (close(t->output) < 0 && error == 0) error = errno;
    free(t);
    return error;
}
