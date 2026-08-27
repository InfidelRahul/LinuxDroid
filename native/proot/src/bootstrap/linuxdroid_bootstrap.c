/*
 * LinuxDroid - Standalone Guest Bootstrap Helper
 *
 * Responsibilities:
 * 1. Early userspace preparation (runtime directories, standard paths, identity)
 * 2. Validate environment sanity (/bin/sh, /proc, /dev, /tmp)
 * 3. Handoff to normal Linux userspace (BOOTSTRAP_USERSPACE, BOOTSTRAP_DIRECT_EXEC, BOOTSTRAP_NATIVE_INIT)
 * 4. Exits via execvp() — NEVER acts as a permanent init/supervisor.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <errno.h>

#define DEFAULT_PATH "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

static void ensure_directory(const char *path, mode_t mode) {
    struct stat st;
    if (stat(path, &st) != 0) {
        mkdir(path, mode);
    }
}

static const char *resolve_shell(void) {
    const char *candidates[] = {
        "/bin/bash",
        "/usr/bin/bash",
        "/bin/sh",
        "/usr/bin/sh",
        NULL
    };
    for (int i = 0; candidates[i] != NULL; i++) {
        if (access(candidates[i], X_OK) == 0) {
            return candidates[i];
        }
    }
    return "/bin/sh";
}

int main(int argc, char *argv[]) {
    // 1. Prepare runtime directory structure
    ensure_directory("/tmp", 01777);
    ensure_directory("/run", 0755);
    ensure_directory("/var/run", 0755);
    ensure_directory("/dev/shm", 01777);
    ensure_directory("/dev/pts", 0755);

    // 2. Set up standard environment variables
    if (getenv("PATH") == NULL) {
        setenv("PATH", DEFAULT_PATH, 1);
    }
    if (getenv("TERM") == NULL) {
        setenv("TERM", "xterm-256color", 1);
    }
    if (getenv("LANG") == NULL) {
        setenv("LANG", "C.UTF-8", 1);
    }
    if (getenv("TMPDIR") == NULL) {
        setenv("TMPDIR", "/tmp", 1);
    }

    const char *policy = getenv("LINUXDROID_BOOTSTRAP_POLICY");
    if (policy == NULL) {
        policy = "BOOTSTRAP_USERSPACE";
    }

    // 3. Select handoff mode and execute
    if (strcmp(policy, "BOOTSTRAP_DIRECT_EXEC") == 0 && argc > 1) {
        execvp(argv[1], &argv[1]);
        fprintf(stderr, "linuxdroid-bootstrap: failed to direct exec '%s': %s\n", argv[1], strerror(errno));
        return 127;
    }

    if (strcmp(policy, "BOOTSTRAP_NATIVE_INIT") == 0) {
        const char *init_candidates[] = {
            "/sbin/init",
            "/lib/systemd/systemd",
            "/sbin/openrc-init",
            NULL
        };
        for (int i = 0; init_candidates[i] != NULL; i++) {
            if (access(init_candidates[i], X_OK) == 0) {
                char *init_args[] = { (char *)init_candidates[i], NULL };
                execvp(init_candidates[i], init_args);
                fprintf(stderr, "linuxdroid-bootstrap: failed to exec init '%s': %s\n", init_candidates[i], strerror(errno));
                break;
            }
        }
        // If native init failed or not found, fall through to userspace shell
    }

    // 4. Default: BOOTSTRAP_USERSPACE (handoff to user shell)
    if (argc > 1) {
        execvp(argv[1], &argv[1]);
        fprintf(stderr, "linuxdroid-bootstrap: failed to exec '%s': %s\n", argv[1], strerror(errno));
        return 127;
    }

    const char *shell = resolve_shell();
    char *shell_args[] = { (char *)shell, "-l", NULL };
    execvp(shell, shell_args);

    fprintf(stderr, "linuxdroid-bootstrap: failed to exec shell '%s': %s\n", shell, strerror(errno));
    return 127;
}
