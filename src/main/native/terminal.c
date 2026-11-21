/* Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT */
/* Owned POSIX implementation. See README.md in this directory for provenance. */
/* Darwin gates SIGWINCH behind _DARWIN_C_SOURCE even when the POSIX level is sufficient. */
#if defined(__APPLE__) && !defined(_DARWIN_C_SOURCE)
#define _DARWIN_C_SOURCE 1
#endif
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <string.h>
#include <sys/stat.h>
#include <stdlib.h>
#include <signal.h>
#include <stdatomic.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#ifdef __APPLE__
#include <sys/select.h>
#else
#include <poll.h>
#endif

#define BT_MAX_SUSPEND_BYTES 4096

struct bt_terminal {
    int input;
    int output;
    int raw;
    int released;
    struct termios original;
    struct sigaction previous_resize;
    struct sigaction previous_continue;
    struct sigaction previous_stop;
    unsigned char restore_bytes[BT_MAX_SUSPEND_BYTES];
    int restore_length;
    unsigned char resume_bytes[BT_MAX_SUSPEND_BYTES];
    int resume_length;
    int stop_pipe[2];
    pthread_t stop_thread;
    int stop_thread_started;
};

int bt_raw(struct bt_terminal *t);

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

/* External job control. The handler performs one write to a self-pipe and nothing else: write,
   atomic_load and errno access are async-signal-safe. The helper thread does the restoration and
   the stop, neither of which is safe to do in a signal handler. */
static struct bt_terminal *_Atomic stop_owner;
static _Atomic int stop_signal_fd = -1;
static _Atomic unsigned int stop_requested;
static _Atomic unsigned int resume_epoch;

static void stop_signal(int signal_number) {
    (void)signal_number;
    int saved = errno;
    if (atomic_exchange_explicit(&stop_requested, 1u, memory_order_relaxed) == 0u) {
        int descriptor = atomic_load_explicit(&stop_signal_fd, memory_order_relaxed);
        if (descriptor >= 0) {
            unsigned char byte = 1;
            ssize_t written;
            do { written = write(descriptor, &byte, 1); } while (written < 0 && errno == EINTR);
        }
    }
    errno = saved;
}

static int wait_ready(int descriptor, int timeout, int for_write);

static void write_bounded(int descriptor, const unsigned char *bytes, int length, int timeout) {
    int sent = 0;
    while (sent < length) {
        ssize_t count = write(descriptor, bytes + sent, (size_t)(length - sent));
        if (count > 0) { sent += (int)count; continue; }
        if (count < 0 && errno == EINTR) continue;
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (wait_ready(descriptor, timeout, 1) == 1) continue;
        }
        break;
    }
}

static void *stop_worker(void *argument) {
    struct bt_terminal *t = argument;
    unsigned char byte;
    while (read(t->stop_pipe[0], &byte, 1) == 1) {
        if (atomic_load_explicit(&stop_owner, memory_order_relaxed) == t && !t->released) {
            if (t->raw) {
                int status;
                do { status = tcsetattr(t->input, TCSANOW, &t->original); } while (status < 0 && errno == EINTR);
                if (status == 0) t->raw = 0;
            }
            write_bounded(t->output, t->restore_bytes, t->restore_length, 1000);
            unsigned int beforeContinue = atomic_load_explicit(&continue_epoch, memory_order_relaxed);
            if (kill(0, SIGSTOP) == 0) {
                /* kill returns before the stop is delivered. Wait for the SIGCONT notification so
                   no resume byte can be written while the shell still owns the terminal. */
                while (atomic_load_explicit(&continue_epoch, memory_order_relaxed) == beforeContinue) {
                    struct timespec idle = {0, 1 * 1000 * 1000};
                    (void)nanosleep(&idle, NULL);
                }
            }
            /* The kernel resumes this thread with the process group. Put the session back before
               the runtime repaints, then publish a resume epoch it can observe. */
            if (atomic_load_explicit(&stop_owner, memory_order_relaxed) == t && !t->released) {
                if (bt_raw(t) == 0) t->raw = 1;
                write_bounded(t->output, t->resume_bytes, t->resume_length, 1000);
            }
            atomic_fetch_add_explicit(&resume_epoch, 1u, memory_order_relaxed);
        }
        atomic_store_explicit(&stop_requested, 0u, memory_order_relaxed);
    }
    return NULL;
}

int bt_resume_epoch(void) {
    return (int)atomic_load_explicit(&resume_epoch, memory_order_relaxed);
}

int bt_set_suspend_bytes(struct bt_terminal *t, const unsigned char *restore, int restore_length,
                         const unsigned char *resume, int resume_length) {
    if (t == NULL || restore_length < 0 || resume_length < 0 ||
        restore_length > BT_MAX_SUSPEND_BYTES || resume_length > BT_MAX_SUSPEND_BYTES ||
        (restore_length > 0 && restore == NULL) || (resume_length > 0 && resume == NULL)) return EINVAL;
    if (restore_length > 0) memcpy(t->restore_bytes, restore, (size_t)restore_length);
    if (resume_length > 0) memcpy(t->resume_bytes, resume, (size_t)resume_length);
    t->restore_length = restore_length;
    t->resume_length = resume_length;
    return 0;
}

/* Wait for a descriptor with platform-native readiness. Darwin's poll on /dev/tty is unreliable,
   so the Apple path uses select there; the Linux path keeps poll. Returns 1 ready, 0 timeout,
   and -1 with errno on failure. */
static int wait_ready(int descriptor, int timeout, int for_write) {
    if (descriptor < 0) { errno = EBADF; return -1; }
#ifdef __APPLE__
    struct timeval limit;
    limit.tv_sec = timeout / 1000;
    limit.tv_usec = (long)(timeout % 1000) * 1000;
    fd_set ready;
    FD_ZERO(&ready);
    FD_SET(descriptor, &ready);
    if (for_write) return select(descriptor + 1, NULL, &ready, NULL, &limit);
    return select(descriptor + 1, &ready, NULL, NULL, &limit);
#else
    struct pollfd item = {descriptor, (short)(for_write ? POLLOUT : POLLIN), 0};
    int count = poll(&item, 1, timeout);
    if (count > 0 && (item.revents & POLLNVAL)) { errno = EBADF; return -1; }
    return count;
#endif
}

/* ABI 4: opaque contexts; no platform-specific structures cross the Java boundary.
   ABI 2 replaced the deadline-bound bt_write with the single bounded attempt bt_write_some.
   ABI 3 added bt_release/bt_reacquire/bt_foreground/bt_suspend: the session can give the
   terminal back and take it again, and can hand its foreground process group to the job
   controller, without closing the context.
   ABI 4 reads input from the controlling terminal (/dev/tty) so redirected stdin is still
   available to the application, and uses select for /dev/tty readiness on macOS.
   ABI 5 added preregistered suspend/resume bytes and external SIGTSTP/SIGCONT handling. */
int bt_abi(void) { return 5; }

int bt_open(int input, int output, void **result) {
    *result = NULL;
    if (!isatty(output)) return ENOTTY;
    struct bt_terminal *t = calloc(1, sizeof(*t));
    if (t == NULL) return ENOMEM;
    /* Prefer the controlling terminal. A supplied input descriptor that is itself a terminal is a
       fallback for hosts (and test harnesses) with no controlling terminal; redirected input is
       never used, so the application can still read its piped data. */
    t->input = open("/dev/tty", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (t->input < 0 && isatty(input)) t->input = fcntl(input, F_DUPFD_CLOEXEC, 3);
    if (t->input < 0) { int error = errno; free(t); return error == ENXIO ? ENXIO : ENOTTY; }
    if (!isatty(t->input)) {
        close(t->input); free(t); return ENOTTY;
    }
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
    if (pipe(t->stop_pipe) < 0) {
        int error = errno;
        sigaction(SIGWINCH, &t->previous_resize, NULL);
        close(t->output); close(t->input); free(t); return error;
    }
    for (int end = 0; end < 2; end++) {
        (void)fcntl(t->stop_pipe[end], F_SETFD, FD_CLOEXEC);
    }
    int write_flags = fcntl(t->stop_pipe[1], F_GETFL);
    if (write_flags >= 0) (void)fcntl(t->stop_pipe[1], F_SETFL, write_flags | O_NONBLOCK);
    struct sigaction continue_action = {0};
    continue_action.sa_handler = continued;
    continue_action.sa_flags = SA_RESTART;
    sigemptyset(&continue_action.sa_mask);
    if (sigaction(SIGCONT, &continue_action, &t->previous_continue) < 0) {
        int error = errno;
        sigaction(SIGWINCH, &t->previous_resize, NULL);
        close(t->stop_pipe[0]); close(t->stop_pipe[1]);
        close(t->output); close(t->input); free(t); return error;
    }
    struct sigaction stop_action = {0};
    stop_action.sa_handler = stop_signal;
    stop_action.sa_flags = SA_RESTART;
    sigemptyset(&stop_action.sa_mask);
    atomic_store_explicit(&stop_signal_fd, t->stop_pipe[1], memory_order_relaxed);
    atomic_store_explicit(&stop_owner, t, memory_order_relaxed);
    if (sigaction(SIGTSTP, &stop_action, &t->previous_stop) < 0) {
        int error = errno;
        atomic_store_explicit(&stop_signal_fd, -1, memory_order_relaxed);
        atomic_store_explicit(&stop_owner, NULL, memory_order_relaxed);
        sigaction(SIGCONT, &t->previous_continue, NULL);
        sigaction(SIGWINCH, &t->previous_resize, NULL);
        close(t->stop_pipe[0]); close(t->stop_pipe[1]);
        close(t->output); close(t->input); free(t); return error;
    }
    int started = pthread_create(&t->stop_thread, NULL, stop_worker, t);
    if (started != 0) {
        sigaction(SIGTSTP, &t->previous_stop, NULL);
        sigaction(SIGCONT, &t->previous_continue, NULL);
        atomic_store_explicit(&stop_signal_fd, -1, memory_order_relaxed);
        atomic_store_explicit(&stop_owner, NULL, memory_order_relaxed);
        sigaction(SIGWINCH, &t->previous_resize, NULL);
        close(t->stop_pipe[0]); close(t->stop_pipe[1]);
        close(t->output); close(t->input); free(t); return started;
    }
    t->stop_thread_started = 1;
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
    /* An explicit suspend needs the ordinary stop disposition: our external-stop handler would
       otherwise consume SIGTSTP and only wake the helper thread. Restore the saved action for the
       duration of this stop, then put ours back after SIGCONT. */
    struct sigaction during_stop;
    if (sigaction(SIGTSTP, &t->previous_stop, &during_stop) < 0) {
        sigaction(SIGCONT, &previous, NULL);
        return errno;
    }
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
        for (int waited = 0; atomic_load_explicit(&continue_epoch, memory_order_relaxed) == before;
                waited += 10) {
            if (waited >= 400) { error = EPERM; break; }
            struct timespec idle = {0, 10 * 1000 * 1000};
            (void)nanosleep(&idle, NULL);
        }
    }
    if (sigaction(SIGTSTP, &during_stop, NULL) < 0 && error == 0) error = errno;
    if (sigaction(SIGCONT, &previous, NULL) < 0 && error == 0) error = errno;
    return error;
}

/* read: positive byte count, zero timeout/interruption, -1 EOF, -(errno+2) error. A released
   terminal yields no input: the child process or the shell is reading it. */
int bt_read(struct bt_terminal *t, unsigned char *bytes, int capacity, int timeout) {
    if (capacity <= 0 || timeout < 0) return -(EINVAL + 2);
    if (t->released) return 0;
    int ready = wait_ready(t->input, timeout, 0);
    if (ready == 0 || (ready < 0 && errno == EINTR)) return 0;
    if (ready < 0) return -(errno + 2);
    ssize_t count = read(t->input, bytes, (size_t)capacity);
    if (count > 0) return (int)count;
    if (count == 0) return -1;
    if (errno == EINTR || errno == EAGAIN) return 0;
    /* A PTY master reports EIO after its peer disappears; select reports no hangup reason. */
    if (errno == EIO) return -1;
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
    int waited = 0;
    for (;;) {
        ssize_t count = write(t->output, bytes, (size_t)length);
        if (count > 0) return (int)count;
        if (count == 0) return -EIO;
        if (errno == EINTR) continue;
        if (errno != EAGAIN && errno != EWOULDBLOCK) return -errno;
        /* Writable after a readiness wait yet still full: report no progress rather than spin. */
        if (waited) return 0;
        int ready = wait_ready(t->output, timeout, 1);
        if (ready < 0) return errno == EINTR ? 0 : -errno;
        if (ready == 0) return 0;
        waited = 1;
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
    if (sigaction(SIGTSTP, NULL, &current) < 0) error = error == 0 ? errno : error;
    else if (current.sa_handler == stop_signal && sigaction(SIGTSTP, &t->previous_stop, NULL) < 0)
        error = error == 0 ? errno : error;
    if (sigaction(SIGCONT, NULL, &current) < 0) error = error == 0 ? errno : error;
    else if (current.sa_handler == continued && sigaction(SIGCONT, &t->previous_continue, NULL) < 0)
        error = error == 0 ? errno : error;
    atomic_store_explicit(&stop_signal_fd, -1, memory_order_relaxed);
    atomic_store_explicit(&stop_owner, NULL, memory_order_relaxed);
    if (t->stop_thread_started) {
        close(t->stop_pipe[1]);
        pthread_join(t->stop_thread, NULL);
        close(t->stop_pipe[0]);
    }
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
