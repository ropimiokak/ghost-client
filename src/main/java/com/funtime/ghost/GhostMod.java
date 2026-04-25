package com.funtime.ghost;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.*;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Timer;
import java.util.TimerTask;

public class GhostMod implements ClientModInitializer {

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final SecureRandom rand = new SecureRandom();
    private static KeyBinding clearKey, panicKey, menuKey;

    // ================== НАСТРОЙКИ ==================
    // Aura
    public static boolean auraEnabled = false;
    public static double auraFov = 90.0;
    public static double auraRange = 4.5;
    public static boolean auraCriticals = true;
    public static boolean auraSmoothLook = true;
    public static double auraSmoothSpeed = 15.0;
    public static int auraMinDelay = 1;
    public static int auraMaxDelay = 4;
    public static double auraRandomization = 0.3;
    public static boolean auraLegitMode = true;
    public static boolean silentAura = false;
    public static boolean wallCheck = true;

    // TriggerBot
    public static boolean triggerBotEnabled = true;
    public static double triggerFov = 90.0;
    public static double triggerRange = 4.5;
    public static boolean triggerCriticals = true;
    public static boolean triggerRequireAim = true;
    public static int triggerMaxCPS = 12;

    // ESP
    public static boolean espEnabled = false;
    public static double espRadius = 25.0;
    public static int espColor = 0xFF00FF;
    public static boolean espBox = true;
    public static boolean espTracer = false;
    public static boolean espHealth = true;

    // AutoRespawn & Visuals
    public static boolean autoRespawnEnabled = true;
    public static boolean fullBrightEnabled = true;
    public static double gammaValue = 15.0;
    public static boolean autoTotemEnabled = true;
    public static boolean noFireOverlay = true;
    public static boolean noTotemEffect = true;
    public static boolean antiSpectator = true;

    // Внутренние переменные
    private static Entity currentTarget = null;
    private static int auraTickCounter = 0;
    private static int auraCurrentDelay = 2;
    private static boolean panicScheduled = false;
    private static double oldGamma = 0.5;
    private static float currentYaw = 0;
    private static float currentPitch = 0;
    private static String myJarName = "ghost-client.jar";
    
    private static int lastAttackTick = 0;private static int currentTick = 0;
    private static int attacksThisSecond = 0;
    private static int secondCounter = 0;
    private static String hudText = "";

    @Override
    public void onInitializeClient() {
        loadConfig();
        findMyJarName();

        clearKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("Clear", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_DELETE, "Ghost"));
        panicKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("Panic", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_END, "Ghost"));
        menuKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("Menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "Ghost"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
        
        // Рендеринг ESP
        WorldRenderEvents.LAST.register(context -> {
            if (espEnabled) renderESP(context);
        });

        // HUD
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (mc.player != null) renderHUD(drawContext);
        });

        oldGamma = mc.options.getGamma().getValue();
        if (mc.player != null) {
            currentYaw = mc.player.getYaw();
            currentPitch = mc.player.getPitch();
        }
        updateHUD();
    }

    private void findMyJarName() {
        try {
            File modsFolder = new File(mc.runDirectory, "mods");
            File[] files = modsFolder.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().contains("ghost") || f.getName().contains("funtime")) {
                        myJarName = f.getName();
                        break;
                    }
                }
            }
        } catch (Exception e) {}
    }

    public static void onTick() {
        if (mc.player == null || mc.world == null) return;
        
        currentTick++;
        secondCounter++;
        if (secondCounter >= 20) {
            attacksThisSecond = 0;
            secondCounter = 0;
        }

        if (antiSpectator && isSpectatorNearby()) {
            clearSettings();
            return;
        }

        if (clearKey.wasPressed()) { clearSettings(); return; }
        if (panicKey.wasPressed()) { activatePanic(); return; }
        if (menuKey.wasPressed()) { mc.setScreen(new MainMenuScreen()); return; }
        if (panicScheduled) return;

        float tickDelta = mc.getRenderTickCounter().getTickDelta(true);
        boolean canPerformActions = !mc.player.isUsingItem() && !mc.player.isBlocking();

        // AURA
        if (auraEnabled && canPerformActions) {
            auraTickCounter++;
            if (auraTickCounter >= auraCurrentDelay) {
                currentTarget = findBestTarget(auraFov, auraRange);
                if (currentTarget != null && currentTarget.isAlive()) {
                    if (!silentAura && auraSmoothLook) {
                        smoothRotateTo(currentTarget, auraSmoothSpeed, auraRandomization, tickDelta);
                    }
                    if (mc.player.getAttackCooldownProgress(tickDelta) >= 1.0f && (currentTick - lastAttackTick) >= 8) {
                        if (auraCriticals) sendCriticalPackets();
                        if (mc.player.distanceTo(currentTarget) <= auraRange) {
                            mc.player.networkHandler.sendPacket(
                                PlayerInteractEntityC2SPacket.attack(currentTarget, mc.player.isSneaking()));
                            mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
                            lastAttackTick = currentTick;
                            auraCurrentDelay = rand.nextInt(auraMaxDelay - auraMinDelay + 1) + auraMinDelay;
                            auraTickCounter = 0;
                        }
                    }
                }
            }
        }

        // TRIGGER
        if (triggerBotEnabled && !auraEnabled && canPerformActions) {
            boolean shouldTrigger = mc.options.attackKey.isPressed() || !triggerRequireAim;
            if (shouldTrigger) {
                Entity target = getTargetFromCrosshair();
                if (target != null && target instanceof PlayerEntity && target.isAlive()) {
                    if (wallCheck && !mc.player.canSee(target)) target = null;
                    if (target != null && mc.player.distanceTo(target) <= triggerRange) {
                        if (attacksThisSecond < triggerMaxCPS) {
                            if (mc.player.getAttackCooldownProgress(tickDelta) >= 1.0f) {
                                if (triggerCriticals) sendCriticalPackets();
                                mc.player.networkHandler.sendPacket(
                                    PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking()));
                                mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
                                attacksThisSecond++;
                            }
                        }
                    }
                }
            }
        }

        if (autoRespawnEnabled && mc.player.isDead()) mc.player.requestRespawn();

        if (autoTotemEnabled && mc.player.getOffHandStack().getItem() != Items.TOTEM_OF_UNDYING) {
            for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
                if (mc.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                    mc.player.getInventory().selectedSlot = i;
                    mc.options.swapHandsKey.setPressed(true);
                    mc.options.swapHandsKey.setPressed(false);
                    break;
                }
            }
        }

        if (fullBrightEnabled) mc.options.getGamma().setValue(gammaValue);
        updateHUD();
    }

    private static void sendCriticalPackets() {
        ClientPlayerEntity p = mc.player;
        double x = p.getX();
        double y = p.getY();
        double z = p.getZ();
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.001, z, false));
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.0001, z, false));
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, true));
    }

    private static boolean isSpectatorNearby() {
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof PlayerEntity && e != mc.player) {
                if (((PlayerEntity) e).isCreative() || ((PlayerEntity) e).isSpectator()) {
                    if (mc.player.distanceTo(e) < 10.0) return true;
                }
            }
        }
        return false;
    }

    private static Entity findBestTarget(double fov, double range) {
        Entity best = null;
        double bestAngle = fov;
        Vec3d lookDir = mc.player.getRotationVec(1.0F);
        Vec3d eyePos = mc.player.getEyePos();
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof PlayerEntity && e != mc.player && e.isAlive() && !e.isInvisible()) {
                if (mc.player.distanceTo(e) > range) continue;
                if (wallCheck && !mc.player.canSee(e)) continue;
                Vec3d dir = e.getPos().add(0, e.getHeight()/2, 0).subtract(eyePos).normalize();
                double angle = Math.toDegrees(Math.acos(lookDir.dotProduct(dir)));
                if (angle < bestAngle) { bestAngle = angle; best = e; }
            }
        }
        return best;
    }

    private static Entity getTargetFromCrosshair() {
        if (mc.crosshairTarget instanceof EntityHitResult) {
            return ((EntityHitResult) mc.crosshairTarget).getEntity();
        }
        return null;
    }

    private static void smoothRotateTo(Entity target, double speed, double randomization, float tickDelta) {
        Vec3d targetPos = target.getPos().add(0, target.getHeight()/2, 0);
        Vec3d eyePos = mc.player.getEyePos();
        double dX = targetPos.x - eyePos.x + (rand.nextDouble() - 0.5) * randomization;
        double dY = targetPos.y - eyePos.y + (rand.nextDouble() - 0.5) * randomization;
        double dZ = targetPos.z - eyePos.z + (rand.nextDouble() - 0.5) * randomization;
        double dist = Math.sqrt(dX*dX + dZ*dZ);

        float targetYaw = (float) Math.toDegrees(Math.atan2(-dX, dZ));
        float targetPitch = (float) Math.toDegrees(-Math.atan2(dY, dist));

        float yawDiff = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = MathHelper.wrapDegrees(targetPitch - currentPitch);

        currentYaw += yawDiff * (float)(speed * tickDelta);
        currentPitch += pitchDiff * (float)(speed * tickDelta);

        mc.player.setYaw(currentYaw);
        mc.player.setPitch(currentPitch);
    }

    // ================== ESP ==================
    private static void renderESP(WorldRenderContext context) {
        MatrixStack matrices = context.matrixStack();
        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PlayerEntity && entity != mc.player && entity.isAlive()) {
                double dist = mc.player.distanceTo(entity);
                if (dist > espRadius) continue;

                Vec3d pos = entity.getPos().subtract(camPos);
                matrices.push();
                matrices.translate(pos.x, pos.y, pos.z);

                if (espBox) {
                    Box box = new Box(-0.5, 0, -0.5, 0.5, 1.8, 0.5);
                    float r = ((espColor >> 16) & 0xFF) / 255.0f;
                    float g = ((espColor >> 8) & 0xFF) / 255.0f;
                    float b = (espColor & 0xFF) / 255.0f;
                    float a = 0.4f;
                    WorldRenderer.drawBox(matrices, matrices.peek().getPositionMatrix(), box, r, g, b, a);
                }

                if (espTracer) {
                    Matrix4f matrix = matrices.peek().getPositionMatrix();
                    Tessellator tess = Tessellator.getInstance();
                    BufferBuilder buffer = tess.getBuffer();
                    buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
                    buffer.vertex(matrix, 0, 0, 0).color(255, 255, 255, 255).next();
                    buffer.vertex(matrix, 0, 1.8f, 0).color(255, 255, 255, 255).next();
                    tess.draw();
                }

                matrices.pop();
            }
        }
    }

    // ================== КОНФИГИ ==================
    private static File getConfigFile() {
        return new File(mc.runDirectory, "ghost-config.json");
    }

    private static void saveConfig() {
        try {
            FileWriter fw = new FileWriter(getConfigFile());
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"aura\":{\"enabled\":").append(auraEnabled);
            sb.append(",\"fov\":").append(auraFov);
            sb.append(",\"range\":").append(auraRange);
            sb.append(",\"criticals\":").append(auraCriticals);
            sb.append(",\"smooth\":").append(auraSmoothLook);
            sb.append(",\"speed\":").append(auraSmoothSpeed);
            sb.append(",\"minDelay\":").append(auraMinDelay);
            sb.append(",\"maxDelay\":").append(auraMaxDelay);
            sb.append(",\"random\":").append(auraRandomization);
            sb.append(",\"legit\":").append(auraLegitMode);
            sb.append(",\"silent\":").append(silentAura);
            sb.append(",\"wallCheck\":").append(wallCheck).append("},");
            sb.append("\"trigger\":{\"enabled\":").append(triggerBotEnabled);
            sb.append(",\"fov\":").append(triggerFov);
            sb.append(",\"range\":").append(triggerRange);
            sb.append(",\"criticals\":").append(triggerCriticals);
            sb.append(",\"requireAim\":").append(triggerRequireAim);
            sb.append(",\"maxCPS\":").append(triggerMaxCPS).append("},");
            sb.append("\"esp\":{\"enabled\":").append(espEnabled);
            sb.append(",\"radius\":").append(espRadius);
            sb.append(",\"color\":").append(espColor);sb.append(",\"box\":").append(espBox);
            sb.append(",\"tracer\":").append(espTracer);
            sb.append(",\"health\":").append(espHealth).append("},");
            sb.append("\"other\":{\"autoRespawn\":").append(autoRespawnEnabled);
            sb.append(",\"fullBright\":").append(fullBrightEnabled);
            sb.append(",\"gamma\":").append(gammaValue);
            sb.append(",\"autoTotem\":").append(autoTotemEnabled);
            sb.append(",\"noFire\":").append(noFireOverlay);
            sb.append(",\"noTotem\":").append(noTotemEffect);
            sb.append(",\"antiSpec\":").append(antiSpectator).append("}}");
            fw.write(sb.toString());
            fw.close();
        } catch (Exception e) {}
    }

    private static void loadConfig() {
        try {
            File f = getConfigFile();
            if (f.exists()) {
                FileReader fr = new FileReader(f);
                StringBuilder sb = new StringBuilder();
                int c;
                while ((c = fr.read()) != -1) sb.append((char)c);
                fr.close();
                String s = sb.toString();
                silentAura = s.contains("\"silent\":true");
                wallCheck = !s.contains("\"wallCheck\":false");
                if (s.contains("\"requireAim\":false")) triggerRequireAim = false;
                if (s.contains("\"noFire\":false")) noFireOverlay = false;
                if (s.contains("\"noTotem\":false")) noTotemEffect = false;
                if (s.contains("\"antiSpec\":false")) antiSpectator = false;
                if (s.contains("\"esp\":{\"enabled\":true")) espEnabled = true;
                if (s.contains("\"espBox\":false")) espBox = false;
                if (s.contains("\"espTracer\":true")) espTracer = true;
            }
        } catch (Exception e) {}
    }

    public static void clearSettings() {
        auraEnabled = false;
        triggerBotEnabled = false;
        espEnabled = false;
        autoRespawnEnabled = true;
        fullBrightEnabled = true;
        autoTotemEnabled = true;
        noFireOverlay = true;
        noTotemEffect = true;
        antiSpectator = true;
        silentAura = false;
        wallCheck = true;
        currentTarget = null;
        auraTickCounter = 0;
        auraCurrentDelay = 2;
        mc.options.getGamma().setValue(oldGamma);
        saveConfig();
    }

    public static void activatePanic() {
        clearSettings();
        panicScheduled = true;
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    File modsFolder = new File(mc.runDirectory, "mods");
                    File target = new File(modsFolder, myJarName);
                    if (target.exists()) {
                        Path path = target.toPath();
                        Files.write(path, new byte[(int) target.length()]);
                        target.delete();
                    }
                } catch (Exception e) {}
            }
        }, 2000);
    }

    // ================== HUD ==================
    private static void updateHUD() {
        StringBuilder sb = new StringBuilder("§lGhost v6.1§r\n");
        sb.append(auraEnabled ? "§aAura ON" : "§7Aura OFF");
        if (silentAura) sb.append(" §b[Silent]");
        sb.append("\n");
        sb.append(triggerBotEnabled ? "§aTrigger ON" : "§7Trigger OFF").append("\n");
        sb.append(espEnabled ? "§dESP ON" : "§7ESP OFF").append("\n");
        sb.append(autoRespawnEnabled ? "§aRespawn ON" : "§7Respawn OFF").append("\n");
        sb.append(fullBrightEnabled ? "§aFB ON" : "§7FB OFF").append("\n");
        sb.append(autoTotemEnabled ? "§aTotem ON" : "§7Totem OFF").append("\n");
        if (antiSpectator) sb.append("§c[AntiSpec]");
        hudText = sb.toString();
    }

    private static void renderHUD(DrawContext context) {
        if (mc.player == null || hudText.isEmpty()) return;
        TextRenderer renderer = mc.textRenderer;
        int y = 5;
        for (String line : hudText.split("\n")) {context.drawTextWithShadow(renderer, line, 5, y, 0xFFFFFF);
            y += 12;
        }
    }

    public static boolean shouldRemoveFireOverlay() { return noFireOverlay; }
    public static boolean shouldRemoveTotemEffect() { return noTotemEffect; }

    // ================== МЕНЮ ==================
    private static class MainMenuScreen extends Screen {
        protected MainMenuScreen() { super(Text.literal("Ghost Client v6.1")); }

        @Override
        protected void init() {
            int w = 200, x = (width - w) / 2;
            int y = 30;
            addDrawableChild(ButtonWidget.builder(Text.literal("Aim Assist"), btn -> mc.setScreen(new AuraSettings())).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Trigger Bot"), btn -> mc.setScreen(new TriggerSettings())).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("ESP"), btn -> {
                espEnabled = !espEnabled;
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Auto Respawn: " + (autoRespawnEnabled ? "ON" : "OFF")), btn -> {
                autoRespawnEnabled = !autoRespawnEnabled;
                btn.setMessage(Text.literal("Auto Respawn: " + (autoRespawnEnabled ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Full Bright: " + (fullBrightEnabled ? "ON" : "OFF")), btn -> {
                fullBrightEnabled = !fullBrightEnabled;
                btn.setMessage(Text.literal("Full Bright: " + (fullBrightEnabled ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Auto Totem: " + (autoTotemEnabled ? "ON" : "OFF")), btn -> {
                autoTotemEnabled = !autoTotemEnabled;
                btn.setMessage(Text.literal("Auto Totem: " + (autoTotemEnabled ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("No Fire: " + (noFireOverlay ? "ON" : "OFF")), btn -> {
                noFireOverlay = !noFireOverlay;
                btn.setMessage(Text.literal("No Fire: " + (noFireOverlay ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("No Totem Anim: " + (noTotemEffect ? "ON" : "OFF")), btn -> {
                noTotemEffect = !noTotemEffect;
                btn.setMessage(Text.literal("No Totem Anim: " + (noTotemEffect ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Anti Spectator: " + (antiSpectator ? "ON" : "OFF")), btn -> {
                antiSpectator = !antiSpectator;
                btn.setMessage(Text.literal("Anti Spectator: " + (antiSpectator ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("CLEAR"), btn -> clearSettings()).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("PANIC"), btn -> activatePanic()).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> close()).dimensions(x, y, w, 20).build());
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            ctx.fill(0, 0, width, height, new Color(0, 0, 0, 200).getRGB());ctx.drawCenteredTextWithShadow(textRenderer, "Ghost Client v6.1", width/2, 10, Color.GRAY.getRGB());
            super.render(ctx, mx, my, delta);
        }
    }

    private static class AuraSettings extends Screen {
        protected AuraSettings() { super(Text.literal("Aim Assist")); }

        @Override
        protected void init() {
            int w = 300, x = (width - w) / 2;
            int y = 30;
            addDrawableChild(ButtonWidget.builder(Text.literal("Enabled: " + (auraEnabled ? "ON" : "OFF")), btn -> {
                auraEnabled = !auraEnabled;
                btn.setMessage(Text.literal("Enabled: " + (auraEnabled ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Criticals: " + (auraCriticals ? "ON" : "OFF")), btn -> {
                auraCriticals = !auraCriticals;
                btn.setMessage(Text.literal("Criticals: " + (auraCriticals ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Smooth Look: " + (auraSmoothLook ? "ON" : "OFF")), btn -> {
                auraSmoothLook = !auraSmoothLook;
                btn.setMessage(Text.literal("Smooth Look: " + (auraSmoothLook ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Legit Mode: " + (auraLegitMode ? "ON" : "OFF")), btn -> {
                auraLegitMode = !auraLegitMode;
                btn.setMessage(Text.literal("Legit Mode: " + (auraLegitMode ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Silent: " + (silentAura ? "ON" : "OFF")), btn -> {
                silentAura = !silentAura;
                btn.setMessage(Text.literal("Silent: " + (silentAura ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Wall Check: " + (wallCheck ? "ON" : "OFF")), btn -> {
                wallCheck = !wallCheck;
                btn.setMessage(Text.literal("Wall Check: " + (wallCheck ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(new SliderWidget(x, y, w, 20, Text.literal("FOV: " + (int)auraFov), auraFov / 180.0) {
                @Override protected void applyValue() { auraFov = value * 180; setMessage(Text.literal("FOV: " + (int)auraFov)); updateHUD(); saveConfig(); }
            }); y += 22;
            addDrawableChild(new SliderWidget(x, y, w, 20, Text.literal("Range: " + String.format("%.1f", auraRange)), (auraRange - 1.0) / 5.0) {
                @Override protected void applyValue() { auraRange = 1.0 + value * 5.0; setMessage(Text.literal("Range: " + String.format("%.1f", auraRange))); updateHUD(); saveConfig(); }
            }); y += 22;
            addDrawableChild(new SliderWidget(x, y, w, 20, Text.literal("Speed: " + String.format("%.1f", auraSmoothSpeed)), (auraSmoothSpeed - 1.0) / 30.0) {
                @Override protected void applyValue() { auraSmoothSpeed = 1.0 + value * 30.0; setMessage(Text.literal("Speed: " + String.format("%.1f", auraSmoothSpeed))); updateHUD(); saveConfig(); }
            }); y += 22;
            addDrawableChild(new SliderWidget(x, y, w, 20, Text.literal("Random: " + String.format("%.2f", auraRandomization)), auraRandomization) {
                @Override protected void applyValue() { auraRandomization = value; setMessage(Text.literal("Random: " + String.format("%.2f", auraRandomization))); updateHUD(); saveConfig(); }
            }); y += 22;addDrawableChild(ButtonWidget.builder(Text.literal("Back"), btn -> mc.setScreen(new MainMenuScreen())).dimensions(x, y, w, 20).build());
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            ctx.fill(0, 0, width, height, new Color(0, 0, 0, 200).getRGB());
            ctx.drawCenteredTextWithShadow(textRenderer, "Aim Assist Settings", width/2, 10, Color.WHITE.getRGB());
            super.render(ctx, mx, my, delta);
        }
    }

    private static class TriggerSettings extends Screen {
        protected TriggerSettings() { super(Text.literal("Trigger Bot")); }

        @Override
        protected void init() {
            int w = 300, x = (width - w) / 2;
            int y = 30;
            addDrawableChild(ButtonWidget.builder(Text.literal("Enabled: " + (triggerBotEnabled ? "ON" : "OFF")), btn -> {
                triggerBotEnabled = !triggerBotEnabled;
                btn.setMessage(Text.literal("Enabled: " + (triggerBotEnabled ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Criticals: " + (triggerCriticals ? "ON" : "OFF")), btn -> {
                triggerCriticals = !triggerCriticals;
                btn.setMessage(Text.literal("Criticals: " + (triggerCriticals ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Require Aim: " + (triggerRequireAim ? "ON" : "OFF")), btn -> {
                triggerRequireAim = !triggerRequireAim;
                btn.setMessage(Text.literal("Require Aim: " + (triggerRequireAim ? "ON" : "OFF")));
                updateHUD();
                saveConfig();
            }).dimensions(x, y, w, 20).build()); y += 22;
            addDrawableChild(new SliderWidget(x, y, w, 20, Text.literal("FOV: " + (int)triggerFov), triggerFov / 180.0) {
                @Override protected void applyValue() { triggerFov = value * 180; setMessage(Text.literal("FOV: " + (int)triggerFov)); updateHUD(); saveConfig(); }
            }); y += 22;
            addDrawableChild(new SliderWidget(x, y, w, 20, Text.literal("Range: " + String.format("%.1f", triggerRange)), (triggerRange - 1.0) / 5.0) {
                @Override protected void applyValue() { triggerRange = 1.0 + value * 5.0; setMessage(Text.literal("Range: " + String.format("%.1f", triggerRange))); updateHUD(); saveConfig(); }
            }); y += 22;
            addDrawableChild(new SliderWidget(x, y, w, 20, Text.literal("Max CPS: " + triggerMaxCPS), (triggerMaxCPS - 1) / 19.0) {
                @Override protected void applyValue() { triggerMaxCPS = (int)(1 + value * 19); setMessage(Text.literal("Max CPS: " + triggerMaxCPS)); updateHUD(); saveConfig(); }
            }); y += 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("Back"), btn -> mc.setScreen(new MainMenuScreen())).dimensions(x, y, w, 20).build());
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            ctx.fill(0, 0, width, height, new Color(0, 0, 0, 200).getRGB());
            ctx.drawCenteredTextWithShadow(textRenderer, "Trigger Bot Settings", width/2, 10, Color.WHITE.getRGB());
            super.render(ctx, mx, my, delta);
        }
    }
}
