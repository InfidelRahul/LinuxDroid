#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <functional>
#include <mutex>

namespace linuxdroid {

enum class DesktopLayoutMode {
    PHONE = 0,
    TABLET = 1,
    DESKTOP = 2
};

struct DisplayMetrics {
    int32_t width{1920};
    int32_t height{1080};
    int32_t scale{1};
    int32_t dpi{160};
    int32_t refresh_rate_mhz{60000};
    DesktopLayoutMode layout_mode{DesktopLayoutMode::DESKTOP};
};

struct ThemeTokens {
    uint32_t bg_gradient_top{0xFF0F172A};      // Deep slate navy
    uint32_t bg_gradient_bottom{0xFF1E293B};   // Slate dark blue
    uint32_t panel_bg{0xE61E293B};             // Glass slate panel
    uint32_t panel_border{0x4038BDF8};         // Subtle cyan highlight
    uint32_t accent_color{0xFF0284C7};         // Vibrant blue accent
    uint32_t text_primary{0xFFF8FAFC};         // Bright white
    uint32_t text_secondary{0xFF94A3B8};       // Muted silver
    uint32_t pill_active_bg{0xFF0369A1};       // Active window pill
    uint32_t pill_inactive_bg{0xFF334155};     // Inactive window pill
    uint32_t pill_hover_bg{0xFF475569};        // Hover state
    uint32_t launcher_bg{0xF20F172A};          // Translucent dark drawer
    int32_t panel_height{48};
};

struct SystemStatus {
    int32_t battery_percent{100};
    bool is_charging{false};
    bool network_connected{true};
    std::string network_name{"Wi-Fi"};
    std::string clock_str{"12:00:00"};
};

struct DesktopStateSnapshot {
    DisplayMetrics display;
    ThemeTokens theme;
    SystemStatus system;
    uint32_t active_workspace{0};
    uint64_t focused_window_id{0};
    bool launcher_visible{false};
};

class DesktopState {
public:
    static DesktopState& getInstance();

    // Snapshot query (thread-safe copy)
    DesktopStateSnapshot getSnapshot() const;

    // Display updates
    void setDisplayGeometry(int32_t width, int32_t height, int32_t scale, int32_t dpi = 160);
    DisplayMetrics getDisplayMetrics() const;

    // Workspace & Window Focus
    void setActiveWorkspace(uint32_t workspace);
    uint32_t getActiveWorkspace() const;

    void setFocusedWindow(uint64_t window_id);
    uint64_t getFocusedWindow() const;

    // Launcher UI State
    void setLauncherVisible(bool visible);
    bool isLauncherVisible() const;
    void toggleLauncher();

    // System Status updates
    void updateBattery(int32_t percent, bool charging);
    void updateNetwork(bool connected, const std::string& name);
    void updateClock(const std::string& clock_str);

    // Theme updates
    void setTheme(const ThemeTokens& theme);
    ThemeTokens getTheme() const;

    // Observer subscription
    using ChangeListener = std::function<void(const DesktopStateSnapshot&)>;
    void addChangeListener(ChangeListener listener);
    void clearChangeListeners();

    void reset();

private:
    DesktopState();
    ~DesktopState() = default;
    DesktopState(const DesktopState&) = delete;
    DesktopState& operator=(const DesktopState&) = delete;

    void notifyListenersLocked();

    mutable std::mutex mutex_;
    DesktopStateSnapshot snapshot_;
    std::vector<ChangeListener> listeners_;
};

} // namespace linuxdroid

