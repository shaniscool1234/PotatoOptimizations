# Changelog - Version 1.3.1

## [1.3.1] - 2026-07-05

### Fixed
- **Startup Crash / Mixin Apply Failure**: Fixed a critical crash on startup caused by a parameter signature mismatch in `VideoOptionsScreenMixin.java`.
  - Specifically, corrected the `@Inject` target method constructor parameters for `VideoOptionsScreenMixin#potatofps$captureParent` from:
    ```java
    private void potatofps$captureParent(Screen parent, net.minecraft.client.MinecraftClient client, GameOptions options, CallbackInfo ci)
    ```
    to:
    ```java
    private void potatofps$captureParent(Screen parent, GameOptions options, CallbackInfo ci)
    ```
    This matches the constructor of `VideoOptionsScreen` in Minecraft 1.21.1 (`VideoOptionsScreen(Screen parent, GameOptions options)`), ensuring the Mixin transformation completes successfully.
