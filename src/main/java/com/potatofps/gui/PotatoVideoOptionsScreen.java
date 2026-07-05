package com.potatofps.gui;

import com.potatofps.PotatoFPS;
import com.potatofps.config.PotatoConfig;
import com.potatofps.render.ResolutionScaler;
import com.potatofps.threading.ThreadPoolManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * PotatoVideoOptionsScreen — Replaces vanilla Video Settings entirely.
 *
 * Contains all vanilla video options (Render Distance, Max FPS, VSync, etc.)
 * plus all PotatoFPS optimisation settings, organized into 5 colour-coded tabs.
 *
 * Visual design: dark navy gradient, glowing tab accents, live FPS header.
 */
public class PotatoVideoOptionsScreen extends Screen {

    // ── Tab constants ──────────────────────────────────────────────────────────
    private static final int TAB_VIDEO       = 0;
    private static final int TAB_DISPLAY     = 1;
    private static final int TAB_PERFORMANCE = 2;
    private static final int TAB_POTATO      = 3;
    private static final int TAB_MEMORY      = 4;
    private static final int TAB_COUNT       = 5;

    /** ARGB accent colour per tab — used for underline glow + section tints. */
    private static final int[] TAB_COLORS = {
        0xFF4A90D9,   // Video       — steel blue
        0xFF9B59B6,   // Display     — purple
        0xFFE67E22,   // Performance — orange
        0xFFF1C40F,   // Potato      — gold
        0xFF27AE60,   // Memory      — green
    };

    private static final String[] TAB_LABELS = {
        "Video",
        "Display",
        "Performance",
        "Potato \u2605",   // ★
        "Memory",
    };

    // ── Layout ─────────────────────────────────────────────────────────────────
    private static final int HEADER_H  = 38;
    private static final int TAB_BAR_H = 26;
    private static final int FOOTER_H  = 34;
    private static final int OPT_W     = 200;
    private static final int OPT_H     = 20;
    private static final int OPT_GAP_X = 4;
    private static final int OPT_GAP_Y = 4;

    // ── State ──────────────────────────────────────────────────────────────────
    private int currentTab = TAB_VIDEO;
    private final Screen parent;
    private final GameOptions gameOptions;
    /** Working copy — only committed to disk on Done/close. */
    private final PotatoConfig cfg;

    @SuppressWarnings("unchecked")
    private final List<ClickableWidget>[] tabWidgets = new List[TAB_COUNT];
    private final ButtonWidget[] tabButtons = new ButtonWidget[TAB_COUNT];

    // ── Constructor ────────────────────────────────────────────────────────────

    public PotatoVideoOptionsScreen(Screen parent, GameOptions gameOptions) {
        super(Text.literal("Video Settings"));
        this.parent      = parent;
        this.gameOptions = gameOptions;
        this.cfg         = deepCopy(PotatoFPS.CONFIG.getConfig());
        for (int i = 0; i < TAB_COUNT; i++) {
            tabWidgets[i] = new ArrayList<>();
        }
    }

    // ── Screen init ────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        buildTabButtons();
        buildAllTabs();
        buildFooterButtons();
        switchTab(currentTab);
    }

    // ── Tab bar ────────────────────────────────────────────────────────────────

    private void buildTabButtons() {
        int totalTabW = this.width;
        int tabW      = totalTabW / TAB_COUNT;

        for (int i = 0; i < TAB_COUNT; i++) {
            final int idx = i;
            int x = i * tabW;
            // Last tab gets remaining pixels to fill exactly
            int w = (i == TAB_COUNT - 1) ? totalTabW - x : tabW;

            tabButtons[i] = ButtonWidget.builder(
                    Text.literal(TAB_LABELS[i]),
                    btn -> switchTab(idx))
                    .dimensions(x, HEADER_H, w, TAB_BAR_H)
                    .build();
            addDrawableChild(tabButtons[i]);
        }
    }

    // ── Footer buttons ─────────────────────────────────────────────────────────

    private void buildFooterButtons() {
        int footerY  = this.height - FOOTER_H + 7;
        int centerX  = this.width / 2;
        int halfW    = OPT_W / 2 + OPT_GAP_X / 2;

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Done"),
                btn -> closeSave())
                .dimensions(centerX - halfW - OPT_W / 2, footerY, OPT_W, OPT_H)
                .build());

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Reset to Defaults"),
                btn -> resetToDefaults())
                .dimensions(centerX + halfW - OPT_W / 2, footerY, OPT_W, OPT_H)
                .build());
    }

    // ── Tab builders ───────────────────────────────────────────────────────────

    private void buildAllTabs() {
        buildVideoTab();
        buildDisplayTab();
        buildPerformanceTab();
        buildPotatoTab();
        buildMemoryTab();
    }

    /** VIDEO — all vanilla video options. */
    private void buildVideoTab() {
        List<ClickableWidget> w = tabWidgets[TAB_VIDEO];
        int lx = leftCol(), rx = rightCol();
        int y  = contentStartY();

        // Row 1  (viewDistance = the Yarn 1.21.1 field name for render distance)
        w.add(gameOptions.viewDistance.createWidget(gameOptions, lx, y, OPT_W));
        w.add(gameOptions.simulationDistance.createWidget(gameOptions, rx, y, OPT_W));
        y += OPT_H + OPT_GAP_Y;

        // Row 2
        w.add(gameOptions.maxFps.createWidget(gameOptions, lx, y, OPT_W));
        w.add(gameOptions.enableVsync.createWidget(gameOptions, rx, y, OPT_W));
        y += OPT_H + OPT_GAP_Y;

        // Row 3
        w.add(gameOptions.graphicsMode.createWidget(gameOptions, lx, y, OPT_W));
        w.add(gameOptions.ao.createWidget(gameOptions, rx, y, OPT_W));
        y += OPT_H + OPT_GAP_Y;

        // Row 4
        w.add(gameOptions.cloudRenderMode.createWidget(gameOptions, lx, y, OPT_W));
        w.add(gameOptions.entityShadows.createWidget(gameOptions, rx, y, OPT_W));
        y += OPT_H + OPT_GAP_Y;

        // Row 5  (getParticles/getBobView are public getters in 1.21.1)
        w.add(gameOptions.getParticles().createWidget(gameOptions, lx, y, OPT_W));
        w.add(gameOptions.getBobView().createWidget(gameOptions, rx, y, OPT_W));
        y += OPT_H + OPT_GAP_Y;

        // Row 6  (getGamma/biomeBlendRadius — gamma exposed via getter)
        w.add(gameOptions.getGamma().createWidget(gameOptions, lx, y, OPT_W));
        w.add(gameOptions.biomeBlendRadius.createWidget(gameOptions, rx, y, OPT_W));

        registerTabWidgets(w);
    }

    /** DISPLAY — screen resolution, GUI, fullscreen + PotatoFPS render scale. */
    private void buildDisplayTab() {
        List<ClickableWidget> w = tabWidgets[TAB_DISPLAY];
        int lx = leftCol(), rx = rightCol();
        int y  = contentStartY();

        // getGuiScale/getFullscreen are public getters in 1.21.1
        w.add(gameOptions.getGuiScale().createWidget(gameOptions, lx, y, OPT_W));
        w.add(gameOptions.getFullscreen().createWidget(gameOptions, rx, y, OPT_W));
        y += OPT_H + OPT_GAP_Y;

        w.add(gameOptions.attackIndicator.createWidget(gameOptions, lx, y, OPT_W));
        y += OPT_H + OPT_GAP_Y;

        // Divider gap for section heading drawn in render()
        y += 14;

        // PotatoFPS: Render Scale
        float rsInitial = cfg.renderScale;
        w.add(new SliderWidget(lx, y, OPT_W, OPT_H,
                Text.literal("Render Scale: " + pct(rsInitial)),
                norm(rsInitial, 0.25f, 1.0f)) {
            @Override protected void updateMessage() {
                setMessage(Text.literal("Render Scale: " + pct(denorm(this.value, 0.25f, 1.0f))));
            }
            @Override protected void applyValue() {
                cfg.renderScale = snap(denorm((float) this.value, 0.25f, 1.0f), 0.05f);
            }
        });
        w.add(tooltip(cycleButton(rx, y, "Upscale Filter",
                () -> cfg.upscaleFilter.displayName,
                () -> {
                    PotatoConfig.UpscaleFilter[] f = PotatoConfig.UpscaleFilter.values();
                    cfg.upscaleFilter = f[(cfg.upscaleFilter.ordinal() + 1) % f.length];
                }),
                "How the scaled 3D world is upscaled.\n"
                        + "Bilinear = smooth (recommended).\n"
                        + "Nearest = pixelated, zero cost."));

        registerTabWidgets(w);
    }

    /** PERFORMANCE — threading, entity culling, animation. */
    private void buildPerformanceTab() {
        List<ClickableWidget> w = tabWidgets[TAB_PERFORMANCE];
        int lx = leftCol(), rx = rightCol();
        int y  = contentStartY();

        // Chunk threads — apply deferred to closeSave() to avoid blocking render thread
        w.add(intSlider(lx, y, "Chunk Threads",
                cfg.chunkBuilderThreads, 1, 4,
                v -> cfg.chunkBuilderThreads = v,
                v -> v + (v == 1 ? " thread" : " threads"),
                "Mesh-building workers.\ni5-3470S: 2 recommended.\nApplied on Done."));
        // Entity cull distance
        w.add(intSlider(rx, y, "Entity Render Dist.",
                cfg.entityCullDistance, 16, 128,
                v -> cfg.entityCullDistance = v,
                v -> v + " blocks",
                "Max entity render distance.\nVanilla ~96 blocks."));
        y += OPT_H + OPT_GAP_Y;

        // Entity AI throttle
        w.add(intSlider(lx, y, "AI Throttle Dist.",
                cfg.entityAiThrottleDistance, 16, 64,
                v -> cfg.entityAiThrottleDistance = v,
                v -> v + " blocks",
                "Reduce AI frequency beyond\nthis distance. Saves CPU."));
        // Animation rate
        w.add(tooltip(cycleButton(rx, y, "Animation Rate",
                () -> cfg.animationTickRate.displayName,
                () -> {
                    PotatoConfig.AnimationRate[] r = PotatoConfig.AnimationRate.values();
                    cfg.animationTickRate = r[(cfg.animationTickRate.ordinal() + 1) % r.length];
                }),
                "Water/lava/fire animation speed.\nHalf = save 1-2 FPS, barely visible."));

        registerTabWidgets(w);
    }

    /** POTATO ★ — render-pipeline optimisations unique to this mod. */
    private void buildPotatoTab() {
        List<ClickableWidget> w = tabWidgets[TAB_POTATO];
        int lx = leftCol(), rx = rightCol();
        int y  = contentStartY();

        w.add(toggle(lx, y, "Fog Culling",
                () -> cfg.fogCulling, v -> cfg.fogCulling = v,
                "Skip geometry hidden behind fog.\nSaves 1-5 FPS."));
        w.add(toggle(rx, y, "Frustum Culling+",
                () -> cfg.aggressiveFrustumCulling, v -> cfg.aggressiveFrustumCulling = v,
                "Skip off-screen block entities.\nSaves 1-3 FPS."));
        y += OPT_H + OPT_GAP_Y;

        w.add(toggle(lx, y, "Batched Rendering",
                () -> cfg.batchedRendering, v -> cfg.batchedRendering = v,
                "Combine chunk geometry into larger\ndraw calls. Huge win on Intel HD."));
        w.add(toggle(rx, y, "Vertex Compression",
                () -> cfg.vertexCompression, v -> cfg.vertexCompression = v,
                "Halves vertex buffer size.\nSaves DDR3 bandwidth."));
        y += OPT_H + OPT_GAP_Y;

        w.add(toggle(lx, y, "Fast Smooth Lighting",
                () -> cfg.fastSmoothLighting, v -> cfg.fastSmoothLighting = v,
                "Zero-allocation AO calculator.\nEliminates GC stutter. +3-8 FPS."));
        // Particle multiplier
        float pmInitial = cfg.particleMultiplier;
        w.add(new SliderWidget(rx, y, OPT_W, OPT_H,
                Text.literal("Particle Limit: " + pct(pmInitial)),
                norm(pmInitial, 0f, 1f)) {
            @Override protected void updateMessage() {
                setMessage(Text.literal("Particle Limit: " + pct(denorm(this.value, 0f, 1f))));
            }
            @Override protected void applyValue() {
                cfg.particleMultiplier = snap(denorm((float) this.value, 0f, 1f), 0.05f);
            }
        });

        registerTabWidgets(w);
    }

    /** MEMORY — object pools, caches, JVM pressure. */
    private void buildMemoryTab() {
        List<ClickableWidget> w = tabWidgets[TAB_MEMORY];
        int lx = leftCol(), rx = rightCol();
        int y  = contentStartY();

        w.add(toggle(lx, y, "Aggressive GC",
                () -> cfg.aggressiveMemoryManagement, v -> cfg.aggressiveMemoryManagement = v,
                "Call GC on dimension changes.\nPrevents texture thrashing on 8GB DDR3."));
        w.add(toggle(rx, y, "Biome Color Cache",
                () -> cfg.fastBiomeBlending, v -> cfg.fastBiomeBlending = v,
                "Cache grass/water colour results.\nSaves 2-4 FPS near biome borders."));
        y += OPT_H + OPT_GAP_Y;

        w.add(intSlider(lx, y, "Object Pool Size",
                cfg.objectPoolSize, 64, 512,
                v -> cfg.objectPoolSize = v,
                v -> v + " objects",
                "Pre-allocated pool. Larger = less GC.\n256 = ~128 MB overhead (safe for 8GB)."));

        registerTabWidgets(w);
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        // 1. Vanilla base (dim overlay in-game, panorama on main menu)
        renderBackground(ctx, mx, my, delta);

        // 2. Header gradient bar  (dark navy)
        ctx.fillGradient(0, 0, this.width, HEADER_H, 0xFF16112B, 0xFF0E0B1E);

        // 3. Tab bar background
        ctx.fill(0, HEADER_H, this.width, HEADER_H + TAB_BAR_H, 0xBB0A0819);

        // 4. Content area tint
        int cy = HEADER_H + TAB_BAR_H;
        ctx.fillGradient(0, cy, this.width, this.height - FOOTER_H, 0xCC080814, 0xCC050510);

        // 5. Footer bar
        ctx.fill(0, this.height - FOOTER_H, this.width, this.height, 0xFF0A0819);

        // 6. Active tab glow accent — 3px bar at bottom of tab row
        if (tabButtons[currentTab] != null) {
            ButtonWidget active = tabButtons[currentTab];
            int col = TAB_COLORS[currentTab];
            int barY = HEADER_H + TAB_BAR_H - 3;
            ctx.fill(active.getX(), barY, active.getX() + active.getWidth(), barY + 3, col);
            // softer outer glow pixel
            ctx.fill(active.getX() + 1, barY - 1, active.getX() + active.getWidth() - 1, barY,
                    (col & 0x00FFFFFF) | 0x66000000);
        }

        // 7. Header text + live FPS
        String title = "PotatoFPS   Video Settings";
        ctx.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, 8, 0xFFFFFFFF);

        String subtitle = tabSubtitles[currentTab];
        ctx.drawCenteredTextWithShadow(textRenderer, subtitle, this.width / 2, 20, 0xAABBBBFF);

        if (client != null) {
            int fps = client.getCurrentFps();
            int fpsColor = fps >= 60 ? 0xFF55FF55 : fps >= 30 ? 0xFFFFAA00 : 0xFFFF5555;
            ctx.drawTextWithShadow(textRenderer, "FPS: " + fps, this.width - 52, 14, fpsColor);
        }

        // 8. Section dividers for Display tab
        if (currentTab == TAB_DISPLAY) {
            int divY = contentStartY() + (OPT_H + OPT_GAP_Y) * 2 + 4;
            ctx.fill(leftCol(), divY, rightCol() + OPT_W, divY + 1, 0x44FFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                    "PotatoFPS Resolution", leftCol(), divY + 3, TAB_COLORS[TAB_DISPLAY]);
        }

        // 9. Widget card backgrounds (subtle highlight per row)
        for (ClickableWidget wid : tabWidgets[currentTab]) {
            if (wid.visible) {
                ctx.fill(wid.getX() - 1, wid.getY() - 1,
                         wid.getX() + wid.getWidth() + 1, wid.getY() + OPT_H + 1,
                         0x18FFFFFF);
            }
        }

        // 10. Widgets + tooltips
        super.render(ctx, mx, my, delta);
    }

    private static final String[] tabSubtitles = {
        "Render distance, framerate, graphics quality",
        "Screen resolution, GUI scale, render scale",
        "Threading, entity culling, animation speed",
        "Potato optimisations for old hardware \u2605",
        "Memory pools, caches, GC pressure",
    };

    // ── Tab switching ──────────────────────────────────────────────────────────

    private void switchTab(int idx) {
        for (int i = 0; i < TAB_COUNT; i++) {
            for (ClickableWidget w : tabWidgets[i]) w.visible = false;
        }
        currentTab = idx;
        for (ClickableWidget w : tabWidgets[currentTab]) w.visible = true;
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    private void closeSave() {
        applyPotatoConfig();
        // Apply thread pool resize here (not on slider tick) to avoid blocking render thread
        ThreadPoolManager.initialize(cfg.chunkBuilderThreads);
        PotatoFPS.CONFIG.save();
        ResolutionScaler.cleanup();
        gameOptions.write();
        if (client != null) client.setScreen(parent);
    }

    private void resetToDefaults() {
        PotatoFPS.CONFIG.resetToDefaults();
        clearChildren();
        init();
    }

    @Override
    public void close() {
        closeSave();
    }

    private void applyPotatoConfig() {
        PotatoConfig real = PotatoFPS.CONFIG.getConfig();
        real.renderScale              = cfg.renderScale;
        real.upscaleFilter            = cfg.upscaleFilter;
        real.fogCulling               = cfg.fogCulling;
        real.aggressiveFrustumCulling = cfg.aggressiveFrustumCulling;
        real.batchedRendering         = cfg.batchedRendering;
        real.vertexCompression        = cfg.vertexCompression;
        real.fastSmoothLighting       = cfg.fastSmoothLighting;
        real.particleMultiplier       = cfg.particleMultiplier;
        real.chunkBuilderThreads      = cfg.chunkBuilderThreads;
        real.animationTickRate        = cfg.animationTickRate;
        real.entityAiThrottleDistance = cfg.entityAiThrottleDistance;
        real.entityCullDistance       = cfg.entityCullDistance;
        real.aggressiveMemoryManagement = cfg.aggressiveMemoryManagement;
        real.fastBiomeBlending        = cfg.fastBiomeBlending;
        real.objectPoolSize           = cfg.objectPoolSize;
    }

    // ── Widget helpers ─────────────────────────────────────────────────────────

    private void registerTabWidgets(List<ClickableWidget> widgets) {
        for (ClickableWidget w : widgets) addDrawableChild(w);
    }

    /** A stateless ON/OFF toggle button backed by a supplier+consumer. */
    private ButtonWidget toggle(int x, int y, String label,
                                java.util.function.BooleanSupplier getter,
                                java.util.function.Consumer<Boolean> setter,
                                String tipText) {
        ButtonWidget[] ref = new ButtonWidget[1];
        ref[0] = ButtonWidget.builder(
                Text.literal(label + ": " + onOff(getter.getAsBoolean())),
                btn -> {
                    boolean cur = btn.getMessage().getString().endsWith("ON");
                    setter.accept(!cur);
                    btn.setMessage(Text.literal(label + ": " + onOff(!cur)));
                })
                .dimensions(x, y, OPT_W, OPT_H)
                .tooltip(Tooltip.of(Text.literal(tipText)))
                .build();
        return ref[0];
    }

    /** A cycle button whose label updates from a supplier on each click. */
    private ButtonWidget cycleButton(int x, int y, String label,
                                     java.util.function.Supplier<String> getValue,
                                     Runnable cycle) {
        ButtonWidget[] ref = new ButtonWidget[1];
        ref[0] = ButtonWidget.builder(
                Text.literal(label + ": " + getValue.get()),
                btn -> {
                    cycle.run();
                    btn.setMessage(Text.literal(label + ": " + getValue.get()));
                })
                .dimensions(x, y, OPT_W, OPT_H)
                .build();
        return ref[0];
    }

    /** Attach a tooltip to any button. */
    private ButtonWidget tooltip(ButtonWidget btn, String text) {
        btn.setTooltip(Tooltip.of(Text.literal(text)));
        return btn;
    }

    /** Integer slider with label, range, live consumer, formatter, and tooltip. */
    private SliderWidget intSlider(int x, int y, String label, int initial, int min, int max,
                                   java.util.function.Consumer<Integer> onChange,
                                   java.util.function.IntFunction<String> fmt,
                                   String tipText) {
        double norm = (double)(initial - min) / (max - min);
        return new SliderWidget(x, y, OPT_W, OPT_H,
                Text.literal(label + ": " + fmt.apply(initial)), norm) {
            @Override protected void updateMessage() {
                int v = min + (int)Math.round(this.value * (max - min));
                setMessage(Text.literal(label + ": " + fmt.apply(v)));
            }
            @Override protected void applyValue() {
                int v = min + (int)Math.round(this.value * (max - min));
                onChange.accept(v);
            }
        };
    }

    // ── Layout helpers ─────────────────────────────────────────────────────────

    private int contentStartY() { return HEADER_H + TAB_BAR_H + 12; }
    private int leftCol()       { return this.width / 2 - OPT_W - OPT_GAP_X / 2; }
    private int rightCol()      { return this.width / 2 + OPT_GAP_X / 2; }

    // ── Maths helpers ──────────────────────────────────────────────────────────

    private static double norm(float v, float min, float max) { return (v - min) / (max - min); }
    private static float denorm(double n, float min, float max) { return (float)(min + n * (max - min)); }
    /** Round to nearest step. */
    private static float snap(float v, float step) { return Math.round(v / step) * step; }
    private static String pct(float v) { return String.format("%.0f%%", v * 100); }
    private static String onOff(boolean b) { return b ? "ON" : "OFF"; }

    // ── Config deep-copy ───────────────────────────────────────────────────────

    private static PotatoConfig deepCopy(PotatoConfig src) {
        PotatoConfig d = new PotatoConfig();
        d.renderScale              = src.renderScale;
        d.upscaleFilter            = src.upscaleFilter;
        d.fogCulling               = src.fogCulling;
        d.aggressiveFrustumCulling = src.aggressiveFrustumCulling;
        d.batchedRendering         = src.batchedRendering;
        d.vertexCompression        = src.vertexCompression;
        d.fastSmoothLighting       = src.fastSmoothLighting;
        d.particleMultiplier       = src.particleMultiplier;
        d.chunkBuilderThreads      = src.chunkBuilderThreads;
        d.animationTickRate        = src.animationTickRate;
        d.entityAiThrottleDistance = src.entityAiThrottleDistance;
        d.entityCullDistance       = src.entityCullDistance;
        d.aggressiveMemoryManagement = src.aggressiveMemoryManagement;
        d.fastBiomeBlending        = src.fastBiomeBlending;
        d.objectPoolSize           = src.objectPoolSize;
        return d;
    }
}
