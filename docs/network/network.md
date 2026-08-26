# LinuxDroid — Networking Subsystem

## 1. Rootless Networking Principle
The Linux userspace shares the host Android network stack without requiring root privileges or virtual network interfaces (`tun`/`tap`).

## 2. Dynamic DNS & Monitoring
- `NetworkManager` observes Android `ConnectivityManager` state changes.
- Injects public DNS servers (`8.8.8.8`, `1.1.1.1`) into `/etc/resolv.conf` upon environment bootstrap.
- Diagnostic ping and DNS resolution checks verify internet connectivity.
