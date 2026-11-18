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
    int released;
    struct termios original;
    struct sigaction previous_resize;
};

/* Only a lock-free counter is touched in the signal handlers; no context can be freed under them. */
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "resize/continue notifications require lock-free unsigned int");
static _Atomic unsigned int resize_epoch;
static void resized(int signal_number) {
    (void)signal_number;
    atomic_fetch_add_explicit(&resize_epoch, 1u, memory_order_relaxed);
}
int bt_resize_epoch(void) {
    return (int)atomic_load_explicit(&resize_epoch, memory_order_relaxed);
}

/* SIGCONT is not a signal the JVM handles, so it can carry this service's notification: whichever
   thread the kernel delivers it to only bumps the counter, and no terminal state is touched there. */
static _Atomic unsigned int continue_epoch;
static void continued(int signal_number) {
    (void)signal_number;
    atomic_fetch_add_explicit(&continue_epoch, 1u, memory_order_relaxed);
}

/* ABI 3: opaque contexts; no platform-specific structures cross the Java boundary.
   ABI 2 replaced the deadline-bound bt_write with the single bounded attempt bt_write_some.
   ABI 3 added bt_release/bt_reacquire/bt_foreground/bt_suspend: the session can give the
   terminal back and take it again, and can hand its foreground process group to the job
   controller, without closing the context. */
int bt_abi(void) { return 3; }

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

/* Gives the terminal back: the input configuration bt_raw changed is restored, and reads and
   writes report that the terminal is not the session's until bt_reacquire. Idempotent, so a
   second release before a reacquire succeeds without touching the device again. */
int bt_release(struct bt_terminal *t) {
    if (t->released) return 0;
    if (t->raw) {
        int status;
        do { status = tcsetattr(t->input, TCSANOW, &t->original); } while (status < 0 && errno == EINTR);
        if (status < 0) return errno;
    }
    t->raw = 0;
    t->released = 1;
    return 0;
}

/* Takes the terminal back after bt_release, which is the only state that changes anything: an
   active terminal keeps its configuration and a second call does no work. */
int bt_reacquire(struct bt_terminal *t) {
    t->released = 0;
    return bt_raw(t);
}

/* 1 when this process group is the terminal's foreground group, 0 when it is not, -errno on
   failure. Job control only belongs to the foreground group: stopping a background group would
   leave nobody to continue it and would take input the shell is reading. A terminal that is not
   any process's controlling terminal cannot have a foreground group, which is the same answer as
   not being it. */
int bt_foreground(struct bt_terminal *t) {
    pid_t foreground = tcgetpgrp(t->input);
    if (foreground < 0) return errno == ENOTTY ? 0 : -errno;
    return foreground == getpgrp() ? 1 : 0;
}

/* Stops this process group until it is continued, and reports when it has been. The terminal must
   already be released: a session that keeps the terminal in its own configuration while stopped
   would leave a cooked-looking screen in raw mode for the shell that continues it. EPERM means no
   stop took place - the group is not the foreground, or it is orphaned and the kernel discarded the
   signal - while a resume is reported as success. */
int bt_suspend(struct bt_terminal *t) {
    if (!t->released) return EINVAL;
    int foreground = bt_foreground(t);
    if (foreground < 0) return -foreground;
    if (foreground == 0) return EPERM;
    struct sigaction action = {0};
    struct sigaction previous;
    action.sa_handler = continued;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGCONT, &action, &previous) < 0) return errno;
    unsigned int before = atomic_load_explicit(&continue_epoch, memory_order_relaxed);
    int error = 0;
    if (kill(0, SIGTSTP) < 0) {
        error = errno;
    } else {
        /* The group is stopped here; a shell's fg/bg, or anything else that continues it, resumes
           it and sends SIGCONT. This loop only ever runs while the group is running, so its bound is
           running time: a job stopped for an hour still continues without spending it.
           A stop signal to an orphaned process group is discarded by the kernel, so the loop also
           says how a suspend that cannot take effect is reported: running this long without a
           continue means there was nothing to wait for. */
        struct pollfd idle = {-1, 0, 0};
        for (int waited = 0; atomic_load_explicit(&continue_epoch, memory_order_relaxed) == before;
                waited += 10) {
            if (waited >= 400) { error = EPERM; break; }
            (void)poll(&idle, 0, 10);
        }
    }
    if (sigaction(SIGCONT, &previous, NULL) < 0 && error == 0) error = errno;
    return error;
}

/* read: positive byte count, zero timeout/interruption, -1 EOF, -(errno+2) error. A released
   terminal yields no input: the child process or the shell is reading it. */
int bt_read(struct bt_terminal *t, unsigned char *bytes, int capacity, int timeout) {
    if (capacity <= 0 || timeout < 0) return -(EINVAL + 2);
    if (t->released) return 0;
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

/* write_some: one bounded attempt. Returns the positive count of bytes the terminal accepted,
   0 when it accepted nothing within timeout milliseconds (or the wait was interrupted), or
   -errno when the terminal failed. The caller owns retry and stall policy: only it knows how
   much progress has been made and whether shutdown has begun. */
int bt_write_some(struct bt_terminal *t, const unsigned char *bytes, int length, int timeout) {
    if (length <= 0 || timeout < 0) return -EINVAL;
    /* A released terminal belongs to somebody else: writing a frame into it would corrupt
       whatever that process is drawing. */
    if (t->released) return -EBUSY;
    int polled = 0;
    for (;;) {
        ssize_t count = write(t->output, bytes, (size_t)length);
        if (count > 0) return (int)count;
        if (count == 0) return -EIO;
        if (errno == EINTR) continue;
        if (errno != EAGAIN && errno != EWOULDBLOCK) return -errno;
        /* Writable after a poll yet still full: report no progress rather than spin. */
        if (polled) return 0;
        struct pollfd descriptor = {t->output, POLLOUT, 0};
        int ready = poll(&descriptor, 1, timeout);
        if (ready < 0) return errno == EINTR ? 0 : -errno;
        if (ready == 0) return 0;
        if (descriptor.revents & POLLNVAL) return -EBADF;
        if (descriptor.revents & (POLLERR | POLLHUP)) return -EIO;
        polled = 1;
    }
}

int bt_size(struct bt_terminal *t, int *rows, int *columns) {
    struct winsize size;
    if (ioctl(t->output, TIOCGWINSZ, &size) < 0) return errno;
    *rows = size.ws_row;
    *columns = size.ws_col;
    return 0;
}

/* The caller serializes close with every other operation on this context. Closing is defined in
   every state: an active terminal is restored, and a released one is already in the user's own
   configuration, so only the descriptors and the context are given back. */
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
