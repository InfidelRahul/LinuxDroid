#pragma once

#include <string>
#include <sys/stat.h>

namespace linuxdroid {

/** Returns true if the path exists (file or directory). */
bool pathExists(const std::string& path);

/** Returns true if the path is a directory. */
bool isDirectory(const std::string& path);

/** Returns true if the path is a regular file. */
bool isRegularFile(const std::string& path);

/** Returns file size in bytes, or -1 on error. */
long long getFileSize(const std::string& path);

/** Creates a directory and all parent directories. Returns true on success. */
bool mkdirp(const std::string& path, mode_t mode = 0755);

} // namespace linuxdroid
