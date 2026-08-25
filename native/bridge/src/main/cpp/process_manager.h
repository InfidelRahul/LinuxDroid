#pragma once

#include <string>
#include <sys/types.h>

namespace linuxdroid {

/** Returns true if a process with [pid] exists. */
bool processExists(pid_t pid);

/** Returns the process name from /proc/<pid>/comm. */
std::string getProcessName(pid_t pid);

/** Returns the RSS memory usage in KB from /proc/<pid>/status. */
long getProcessRssKb(pid_t pid);

} // namespace linuxdroid
