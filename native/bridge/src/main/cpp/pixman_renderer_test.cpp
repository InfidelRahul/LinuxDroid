#include "android_presentation.h"

#include <android/hardware_buffer.h>
#include <pixman.h>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <iostream>

int main() {
    std::cout << "==========================================================" << std::endl;
    std::cout << "  LinuxDroid Phase 5: Pixman Software Renderer Tests" << std::endl;
    std::cout << "==========================================================" << std::endl;

    // Test 1: Pixman Version & Core Initialization
    std::cout << "[PixmanTest] Test 1: Verifying Pixman version and library load..." << std::endl;
    const char* version_str = pixman_version_string();
    int version_int = pixman_version();
    std::cout << "  Pixman version string: " << version_str << " (int: " << version_int << ")" << std::endl;
    assert(version_str != nullptr);
    assert(version_int >= 40000); // v0.40.0 or higher
    std::cout << "  [PASS] Pixman version verified: " << version_str << std::endl;

    // Test 2: AHardwareBuffer Allocation with CPU Access Flags
    std::cout << "[PixmanTest] Test 2: Allocating AHardwareBuffer with CPU_WRITE_OFTEN..." << std::endl;
    const uint32_t width = 1920;
    const uint32_t height = 1080;

    AHardwareBuffer_Desc desc{};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                 AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                 AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;

    AHardwareBuffer* buffer = nullptr;
    int err = AHardwareBuffer_allocate(&desc, &buffer);
    assert(err == 0);
    assert(buffer != nullptr);

    AHardwareBuffer_Desc actual_desc{};
    AHardwareBuffer_describe(buffer, &actual_desc);
    assert(actual_desc.width == width);
    assert(actual_desc.height == height);
    assert(actual_desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
    assert(actual_desc.stride >= width);
    std::cout << "  [PASS] AHardwareBuffer allocated: stride=" << actual_desc.stride
              << " px, format=" << actual_desc.format << std::endl;

    // Test 3: CPU Buffer Locking and Virtual Address Mapping
    std::cout << "[PixmanTest] Test 3: Locking buffer for CPU write..." << std::endl;
    void* mapped_pixels = nullptr;
    err = AHardwareBuffer_lock(buffer,
                               AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                               -1,
                               nullptr,
                               &mapped_pixels);
    assert(err == 0);
    assert(mapped_pixels != nullptr);
    std::cout << "  [PASS] Buffer locked at virtual address: " << mapped_pixels << std::endl;

    // Test 4: Creating Pixman Image Wrapping Mapped AHardwareBuffer Memory
    std::cout << "[PixmanTest] Test 4: Wrapping buffer in Pixman image with actual stride..." << std::endl;
    int stride_bytes = static_cast<int>(actual_desc.stride * 4);
    pixman_image_t* dest_image = pixman_image_create_bits(
        PIXMAN_a8b8g8r8,
        static_cast<int>(width),
        static_cast<int>(height),
        static_cast<uint32_t*>(mapped_pixels),
        stride_bytes
    );
    assert(dest_image != nullptr);
    assert(pixman_image_get_width(dest_image) == static_cast<int>(width));
    assert(pixman_image_get_height(dest_image) == static_cast<int>(height));
    assert(pixman_image_get_stride(dest_image) == stride_bytes);
    assert(pixman_image_get_format(dest_image) == PIXMAN_a8b8g8r8);
    std::cout << "  [PASS] Pixman image created with format PIXMAN_a8b8g8r8, stride="
              << stride_bytes << " bytes" << std::endl;

    // Test 5: Deterministic Scene Rendering
    std::cout << "[PixmanTest] Test 5: Rendering deterministic multi-region test scene..." << std::endl;

    // 5a. Fill background with Navy (#101828, opaque)
    pixman_color_t bg_color = { 0x1010, 0x1818, 0x2828, 0xffff };
    pixman_image_t* bg_fill = pixman_image_create_solid_fill(&bg_color);
    assert(bg_fill != nullptr);
    pixman_image_composite32(PIXMAN_OP_SRC, bg_fill, nullptr, dest_image,
                             0, 0, 0, 0, 0, 0, width, height);
    pixman_image_unref(bg_fill);

    // 5b. Top-left RED rectangle (100x100 at 0, 0)
    pixman_color_t red_color = { 0xffff, 0x0000, 0x0000, 0xffff };
    pixman_image_t* red_fill = pixman_image_create_solid_fill(&red_color);
    assert(red_fill != nullptr);
    pixman_image_composite32(PIXMAN_OP_SRC, red_fill, nullptr, dest_image,
                             0, 0, 0, 0, 0, 0, 100, 100);
    pixman_image_unref(red_fill);

    // 5c. Center GREEN rectangle (100x100 at 910, 490)
    pixman_color_t green_color = { 0x0000, 0xffff, 0x0000, 0xffff };
    pixman_image_t* green_fill = pixman_image_create_solid_fill(&green_color);
    assert(green_fill != nullptr);
    pixman_image_composite32(PIXMAN_OP_SRC, green_fill, nullptr, dest_image,
                             0, 0, 0, 0, 910, 490, 100, 100);
    pixman_image_unref(green_fill);

    // 5d. Bottom-right BLUE rectangle (100x100 at 1820, 980)
    pixman_color_t blue_color = { 0x0000, 0x0000, 0xffff, 0xffff };
    pixman_image_t* blue_fill = pixman_image_create_solid_fill(&blue_color);
    assert(blue_fill != nullptr);
    pixman_image_composite32(PIXMAN_OP_SRC, blue_fill, nullptr, dest_image,
                             0, 0, 0, 0, 1820, 980, 100, 100);
    pixman_image_unref(blue_fill);

    std::cout << "  [PASS] Composite operations executed successfully." << std::endl;

    // Test 6: Pixel Correctness Tests (Section 31)
    std::cout << "[PixmanTest] Test 6: Validating byte layout and pixel correctness at known offsets..." << std::endl;
    auto get_pixel = [&](uint32_t x, uint32_t y) -> const uint8_t* {
        auto* base = static_cast<const uint8_t*>(mapped_pixels);
        return base + (y * stride_bytes) + (x * 4);
    };

    // Verify Top-Left (0, 0) is RED: R=0xFF, G=0x00, B=0x00, A=0xFF
    const uint8_t* px_tl = get_pixel(50, 50);
    std::cout << "  Top-Left (50, 50): R=" << (int)px_tl[0] << " G=" << (int)px_tl[1]
              << " B=" << (int)px_tl[2] << " A=" << (int)px_tl[3] << std::endl;
    assert(px_tl[0] == 0xFF);
    assert(px_tl[1] == 0x00);
    assert(px_tl[2] == 0x00);
    assert(px_tl[3] == 0xFF);

    // Verify Center (960, 540) is GREEN: R=0x00, G=0xFF, B=0x00, A=0xFF
    const uint8_t* px_center = get_pixel(960, 540);
    std::cout << "  Center (960, 540): R=" << (int)px_center[0] << " G=" << (int)px_center[1]
              << " B=" << (int)px_center[2] << " A=" << (int)px_center[3] << std::endl;
    assert(px_center[0] == 0x00);
    assert(px_center[1] == 0xFF);
    assert(px_center[2] == 0x00);
    assert(px_center[3] == 0xFF);

    // Verify Bottom-Right (1870, 1030) is BLUE: R=0x00, G=0x00, B=0xFF, A=0xFF
    const uint8_t* px_br = get_pixel(1870, 1030);
    std::cout << "  Bottom-Right (1870, 1030): R=" << (int)px_br[0] << " G=" << (int)px_br[1]
              << " B=" << (int)px_br[2] << " A=" << (int)px_br[3] << std::endl;
    assert(px_br[0] == 0x00);
    assert(px_br[1] == 0x00);
    assert(px_br[2] == 0xFF);
    assert(px_br[3] == 0xFF);

    // Verify Background at (500, 500) is Navy (#101828)
    const uint8_t* px_bg = get_pixel(500, 500);
    std::cout << "  Background (500, 500): R=" << (int)px_bg[0] << " G=" << (int)px_bg[1]
              << " B=" << (int)px_bg[2] << " A=" << (int)px_bg[3] << std::endl;
    assert(px_bg[0] == 0x10);
    assert(px_bg[1] == 0x18);
    assert(px_bg[2] == 0x28);
    assert(px_bg[3] == 0xFF);

    std::cout << "  [PASS] All pixel locations matched expected R8G8B8A8 byte layout with zero color skew!" << std::endl;

    // Test 7: Scoped Lock / Unlock Lifetime & Teardown
    std::cout << "[PixmanTest] Test 7: Testing unbind, CPU unlock, and clean release..." << std::endl;
    pixman_image_unref(dest_image);
    dest_image = nullptr;

    int fence = -1;
    err = AHardwareBuffer_unlock(buffer, &fence);
    assert(err == 0);
    mapped_pixels = nullptr;

    AHardwareBuffer_release(buffer);
    buffer = nullptr;
    std::cout << "  [PASS] Buffer unlocked and destroyed without leaks." << std::endl;

    std::cout << "==========================================================" << std::endl;
    std::cout << "  All 7 Pixman software renderer tests PASSED successfully!" << std::endl;
    std::cout << "==========================================================" << std::endl;
    return 0;
}
