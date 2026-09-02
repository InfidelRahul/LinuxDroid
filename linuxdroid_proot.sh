#!/usr/bin/env bash
set -euo pipefail

# LinuxDroid PRoot source repair script.
# Safe-by-default: refuses dirty trees, creates a backup branch, applies only
# exact source transformations, then builds/tests the host target when possible.

SCRIPT_NAME="$(basename "$0")"
PROOT_DIR="${1:-${LINUXDROID_PROOT_DIR:-$(pwd)}}"
PROOT_REF="${PROOT_REF:-master}"
BACKUP_BRANCH="backup/pre-linuxdroid-state-machine-$(date -u +%Y%m%d-%H%M%S)"

cd "$PROOT_DIR"

die() { echo "[$SCRIPT_NAME] ERROR: $*" >&2; exit 1; }
info() { echo "[$SCRIPT_NAME] $*"; }

[[ -d .git && -f src/arch.h ]] || die "Not a LinuxDroid_proot checkout: $PROOT_DIR"
git diff --quiet && git diff --cached --quiet || die "Working tree is dirty. Commit/stash changes before running this script."

git fetch origin "$PROOT_REF"
CURRENT="$(git rev-parse HEAD)"
REMOTE="$(git rev-parse "origin/$PROOT_REF")"
info "Current: $CURRENT"
info "Origin/$PROOT_REF: $REMOTE"

# Do not silently discard local commits. A fast-forward is safe.
if [[ "$CURRENT" != "$REMOTE" ]]; then
    if git merge-base --is-ancestor "$CURRENT" "$REMOTE"; then
        git checkout "$PROOT_REF"
        git merge --ff-only "origin/$PROOT_REF"
    else
        die "Checkout has diverged from origin/$PROOT_REF. Resolve divergence manually; this script will not overwrite it."
    fi
fi

git branch "$BACKUP_BRANCH"
info "Backup branch created: $BACKUP_BRANCH"

python3 - "$PROOT_DIR" <<'PY'
from pathlib import Path
import re, sys

root = Path(sys.argv[1])

def read(path):
    p = root / path
    return p, p.read_text()

def write(p, old, new, label):
    if old == new:
        return
    p.write_text(new)
    print(f"patched {label}: {p.relative_to(root)}")

# ---------------------------------------------------------------------------
# 1. Android syscall avoider
# ---------------------------------------------------------------------------
p, s = read("src/arch.h")

if "#include <asm/unistd.h>" not in s:
    marker = '#include <linux/audit.h>   /* AUDIT_ARCH_*,  */\n'
    if marker not in s:
        raise SystemExit("arch.h: expected include marker not found")
    s = s.replace(marker, marker + '#if defined(__ANDROID__)\n#include <asm/unistd.h>\n#endif\n', 1)

old = "#define SYSCALL_AVOIDER ((word_t) -1)"
new = """#if defined(__ANDROID__)
#define SYSCALL_AVOIDER ((word_t) __NR_getpid)
#else
#define SYSCALL_AVOIDER ((word_t) -1)
#endif"""
if old in s:
    s = s.replace(old, new, 1)
elif new not in s:
    raise SystemExit("arch.h: SYSCALL_AVOIDER definition not found")
write(p, p.read_text(), s, "Android syscall avoider")

# ---------------------------------------------------------------------------
# 2. Restore the normal PRoot getcwd enter state machine.
#    PR_void remains the semantic operation; set_sysnum() now maps it to the
#    Android-safe avoider syscall.
# ---------------------------------------------------------------------------
p, s = read("src/syscall/enter.c")
pattern = re.compile(
    r'\tcase PR_getcwd: \{\n'
    r'.*?'
    r'\t\}\n\n'
    r'\tcase PR_fchdir:',
    re.S
)
replacement = """\tcase PR_getcwd:
\t\tset_sysnum(tracee, PR_void);
\t\tstatus = 0;
\t\tbreak;

\tcase PR_fchdir:"""
m = pattern.search(s)
if m:
    s = s[:m.start()] + replacement + s[m.end():]
elif re.search(r'\tcase PR_getcwd:\s*\n\s*set_sysnum\(tracee, PR_void\);', s) is None:
    raise SystemExit("enter.c: unexpected PR_getcwd implementation; refusing to rewrite")
write(p, p.read_text(), s, "upstream-style getcwd enter path")

# ---------------------------------------------------------------------------
# 3. Remove getcwd from the generic SIGSYS synthetic-emulation switch.
#    With the Android-safe avoider, getcwd follows the regular PRoot
#    enter/sysexit state machine.
# ---------------------------------------------------------------------------
p, s = read("src/tracee/event.c")

getcwd_block = re.compile(
    r'\n\tcase PR_getcwd: \{\n'
    r'.*?'
    r'\n\t\}\n\n\tcase PR_faccessat:',
    re.S
)
m = getcwd_block.search(s)
if m:
    s = s[:m.start()] + "\n\tcase PR_faccessat:" + s[m.end():]
else:
    # It may already be absent.
    if "case PR_getcwd:" in s and "emulate_seccomp_trapped_syscall" in s:
        # Don't guess at an altered implementation.
        raise SystemExit("event.c: PR_getcwd still exists but does not match expected synthetic block")

# Remove the blanket PR_prctl -> success fabrication.
s2 = s.replace("\n\tcase PR_prctl:\n\t\treturn 0;\n", "\n", 1)
if s2 == s and "case PR_prctl:\n\t\treturn 0;" in s:
    raise SystemExit("event.c: PR_prctl fabrication found in unexpected form")
s = s2

# Remove generic EINVAL -> flags=0 fallback from synthetic faccessat handling.
old = """\t\t\tstatus = faccessat(AT_FDCWD, host_path, mode, flags);
\t\t\tif (status < 0 && errno == EINVAL) {
\t\t\t\tstatus = faccessat(AT_FDCWD, host_path, mode, 0);
\t\t\t}
"""
new = """\t\t\tstatus = faccessat(AT_FDCWD, host_path, mode, flags);
"""
if old in s:
    s = s.replace(old, new, 1)
elif "errno == EINVAL" in s and "faccessat(AT_FDCWD, host_path" in s:
    raise SystemExit("event.c: faccessat fallback found in unexpected form")
write(p, p.read_text(), s, "remove synthetic getcwd/prctl/faccessat fallbacks")

# ---------------------------------------------------------------------------
# 4. Remove global Android heap-tagging disablement if it exists.
#    PRoot must handle tagged tracee addresses at the host-kernel boundary;
#    disabling Bionic's allocator globally is not a substitute for that.
# ---------------------------------------------------------------------------
candidates = list((root / "src").rglob("*.c"))
heap_pattern = re.compile(
    r'\n#if defined\(__ANDROID__\) && defined\(__aarch64__\)\n'
    r'\s*# ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL\n'
    r'\s*#  define M_BIONIC_SET_HEAP_TAGGING_LEVEL -204\n'
    r'\s*# endif\n'
    r'\s*# ifndef M_HEAP_TAGGING_LEVEL_NONE\n'
    r'\s*#  define M_HEAP_TAGGING_LEVEL_NONE 0\n'
    r'\s*# endif\n'
    r'\s*/\* (?:On Android 11-16 \(API 30-36\), configure Bionic heap allocator to return untagged pointers|Ensure child heap allocations are untagged prior to execvp) \*/\n'
    r'\s*extern int mallopt\(int param, int value\) __attribute__\(\(weak\)\);\n'
    r'\s*if \(mallopt != NULL\)\n'
    r'\s*mallopt\(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE\);\n'
    r'\s*#endif\n',
    re.S
)
for p in candidates:
    text = p.read_text()
    updated, n = heap_pattern.subn("\n", text)
    if n:
        p.write_text(updated)
        print(f"patched heap-tagging disable: {p.relative_to(root)} ({n} block(s))")

# ---------------------------------------------------------------------------
# 5. Repair the dangerous parts of the latest link2symlink recovery commit.
#    Do not ignore unlink/readlink errors. Normal libc failures must become
#    negative errno values; ignored failures can corrupt the emulation state.
# ---------------------------------------------------------------------------
p, s = read("src/extension/link2symlink/link2symlink.c")

# These unlinks were added as pre-cleanups even though rename() can replace
# the destination. They also hide filesystem failures.
for line in [
    "\t\t(void) unlink(final);\n",
    "\t\t(void) unlink(intermediate);\n",
    "\t\t(void) unlink(new_final);\n",
]:
    s = s.replace(line, "", 1)

# Restore error propagation for readlink/lstat in decrement_link_count.
s = s.replace(
    """\tsize = my_readlink(original, intermediate);
\tif (size < 0)
\t\treturn 0;
""",
    """\tsize = my_readlink(original, intermediate);
\tif (size < 0)
\t\treturn size;
""",
    1
)
s = s.replace(
    """\tsize = my_readlink(intermediate, final);
\tif (size < 0)
\t\treturn 0;
""",
    """\tsize = my_readlink(intermediate, final);
\tif (size < 0)
\t\treturn size;
""",
    1
)

# Last-link deletion must propagate errors rather than silently claiming
# success.
old = """\t\t/* If it is the last, delete the intermediate and final */
\t\t(void) unlink(intermediate);
\t\t(void) unlink(final);
"""
new = """\t\t/* If it is the last, delete the intermediate and final */
\t\tstatus = unlink(intermediate);
\t\tif (status < 0)
\t\t\treturn -errno;
\t\tstatus = unlink(final);
\t\tif (status < 0)
\t\t\treturn -errno;
"""
if old in s:
    s = s.replace(old, new, 1)

# Keep the ARM64 symlinkat transformation introduced by e45dcf, but verify
# that it remains present.
required = "set_sysnum(tracee, PR_symlinkat);"
if required not in s:
    raise SystemExit("link2symlink.c: ARM64 symlinkat conversion is missing")
write(p, p.read_text(), s, "link2symlink error-state cleanup")

print("source transformation complete")
PY

info "Running source sanity checks..."
grep -n "SYSCALL_AVOIDER" src/arch.h
grep -n -A4 -B2 "case PR_getcwd" src/syscall/enter.c
if grep -Pzo 'case PR_prctl:\s*return 0;' src/tracee/event.c; then
    die "blanket PR_prctl success fabrication remains"
fi
if grep -R -n "errno == EINVAL" src/tracee/event.c | grep -q faccessat; then
    die "faccessat EINVAL flag-stripping fallback remains"
fi
grep -n "PR_symlinkat" src/extension/link2symlink/link2symlink.c

info "Building host PRoot..."
if command -v make >/dev/null 2>&1; then
    make clean
    make proot loader
    if [[ -x ./build/host/proot ]]; then
        ./build/host/proot --version || true
    fi
else
    info "make not available; source changes were applied but build was skipped."
fi

info "Running host tests when available..."
if [[ -x ./build/host/proot && -d tests ]]; then
    make test
fi

cat <<EOF

PRoot source repair complete.

Backup branch:
  $BACKUP_BRANCH

Important:
  This script deliberately does NOT fabricate prctl/capability results,
  does NOT disable Android heap tagging, and does NOT rewrite apt/dpkg.

The Android runtime still needs device-side validation for:
  getcwd
  link/linkat
  rename/unlink
  dpkg --configure -a
  apt install nano

If dpkg still reports EPERM, capture PROOT_LOG_FILE around the exact
status/status-old transaction before making another semantic change.
EOF
