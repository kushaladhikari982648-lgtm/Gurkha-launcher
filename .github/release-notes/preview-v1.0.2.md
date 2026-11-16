# Gurkha Launcher 1.0.2

## Community, Help, Bug Reports & Feature Requests
- **Official Discord Server**: [https://discord.gg/7FaUJf4R9](https://discord.gg/7FaUJf4R9)
  - Join our Discord if you need help, found any bugs to report, or want to ask us to add new features!

## Highlights in 1.0.2
- **Fixed Discord Rich Presence (`Playing Gurkha Launcher`, Application ID `1571705435713437726`)**:
  - **Gateway v10 Desktop Client Identification**: Fixed Discord Gateway `IDENTIFY` (`op: 2`) to use `"os": "Windows"`, `"browser": "Discord Client"`, `"device": "Discord Client"`, `"capabilities": 4093`, and `"created_at"` so Discord's Gateway accepts and displays `type: 0` (*Playing Gurkha Launcher*) Rich Presence activities on user profiles instead of filtering them as mobile-only sessions.
  - **Large `READY` Dispatch Fast-Path & Periodic Presence Refresh**: Added fast regex header extraction for multi-megabyte `READY` Gateway packets, automatic reconnection on socket drops, and periodic `op: 3` presence refresh on every heartbeat cycle.
  - **4-Layer Automatic Discord Login Session Capture**: Added Desktop Chrome User-Agent (preventing Discord's mobile "Download the App" redirect), native `WebViewClient.shouldInterceptRequest` header inspection, `@JavascriptInterface` (`GurkhaDiscordBridge`), `Storage.prototype.setItem`/`getItem` hooks, and `/auth/login` response hooks so signing in with your Discord account automatically connects Rich Presence immediately.
  - **Cross-Process `:launcher` ↔ `:game` RPC Persistence & Handoff**: Added atomic disk synchronization (`gurkha_discord_rpc.json`) and clean WebSocket handoff when launching `MainActivity` so Rich Presence transitions seamlessly from *"In Gurkha Launcher Menu"* to *"Playing Minecraft <version>"*.
- **Includes All 1.0.1 Improvements**:
  - No-scroll Launch button and compact `31dp` left navigation rail.
  - Default landscape mode with switchable portrait mode.
  - Full `VulkanMod` (`net.vulkanmod`) compatibility across all Minecraft versions (1.13–1.21.11 and 26.x).
