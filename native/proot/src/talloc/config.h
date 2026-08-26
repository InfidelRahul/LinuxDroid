#ifndef CONFIG_H
#define CONFIG_H

#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <strings.h>
#include <limits.h>
#include <stdarg.h>
#include <errno.h>
#include <unistd.h>
#include <sys/types.h>

#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 1
#define TALLOC_BUILD_VERSION_RELEASE 14
#define HAVE_CONFIG_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_BOOL 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_USECONDS_T 1
#define HAVE_USLEEP 1
#define HAVE_MEMMOVE 1
#define HAVE_STRNDUP 1
#define HAVE_STRNLEN 1
#define HAVE_VA_COPY 1
#define HAVE_C99_VSNPRINTF 1
#define HAVE_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_VASPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define HAVE_PTHREAD_H 1

#ifndef MIN
#define MIN(a,b) ((a)<(b)?(a):(b))
#endif
#ifndef MAX
#define MAX(a,b) ((a)>(b)?(a):(b))
#endif

#endif

