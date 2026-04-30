# Flash / Флэш

A premium NFC file-sharing Android application with physics-based animations, liquid glass rendering, and seamless cross-device transfers.

## Overview

**Flash** is a high-performance file-sharing app designed for modern Android devices. It leverages NFC (Near Field Communication) for a zero-click handshake, enabling two devices to instantly pair and transfer files via local Wi-Fi streaming. The app features state-of-the-art visual design with liquid glass effects, Perlin noise-driven blob morphing, and smooth 120Hz-optimized animations.

### Key Features

- **Zero-Click NFC Handshake**: Tap two devices together to pair and initiate transfer
- **Liquid Glass Core**: Animated blob with glass refraction effects, distortion, and dynamic morphing
- **Orbiting Photos**: Selected photos orbit around the core with golden-angle spacing
- **Physics-Based Animations**: Spring-based entry/exit, easing curves, and smooth transitions
- **Batch File Transfer**: Send multiple photos in parallel with aggregated progress
- **Synchronized Receiver Experience**: Received photos materialize, orbit, then settle into gallery
- **Full Localization**: English and Russian language support
- **120Hz Optimized**: Smooth animations and responsive UI for high-refresh displays

### Tech Stack

| Layer | Technology |
|---|---|
| **Language** | Kotlin 2.0.21 |
| **UI Framework** | Jetpack Compose (BOM 2025.04.01) |
| **Material Design** | Material3 1.4.0-alpha11 (Expressive Motion) |
| **Glass Effects** | `io.github.kyant0:backdrop:2.0.0-alpha03` |
| **Networking** | Ktor CIO (Server 3.1.2, Client 3.1.2) |
| **Image Loading** | Coil 2.7.0 |
| **Persistence** | DataStore Preferences 1.1.2 |
| **Min SDK** | 26 · **Target SDK** | 35 |
| **Gradle** | AGP 8.5.2 |

---

## To-Do

(To be populated)

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
