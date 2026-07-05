# Changelog 1.3.2

## Fixes
- Fixed an `InvalidInjectionException` related to `VideoOptionsScreenMixin`. The `MinecraftClient` parameter was missing from the `@Inject` method signature in `potatofps$captureParent`, preventing PotatoFPS from correctly replacing the vanilla video settings.

## Git Commands
```bash
git add src/main/java/com/potatofps/mixin/VideoOptionsScreenMixin.java changelogs/CHANGELOG1.3.2.md
git commit -m "Fix InvalidInjectionException in VideoOptionsScreenMixin and add changelog 1.3.2"
```
