package com.github.dayhuha1333art.justcullingmod;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

@Mod(modid = JustCulling.MODID, name = "JustCulling", version = "1.0",
        clientSideOnly = true, acceptableRemoteVersions = "*")
public class JustCulling {

    public static final String MODID = "justculling";

    private static final double MIN_CULL_DISTANCE = 32.0D;
    private static final double MIN_CULL_DISTANCE_SQ = MIN_CULL_DISTANCE * MIN_CULL_DISTANCE;
    private static final float FOV_MULTIPLIER = 1.2F;
    private static final long RAYCAST_CACHE_NS = 200_000_000L;
    private static final long RAYCAST_MAX_AGE_NS = 5_000_000_000L;
    private static final int CLEANUP_INTERVAL_TICKS = 100;

    private static final Logger LOGGER = LogManager.getLogger("JustCulling");

    private static final Map<Entity, Long> raycastTimeNs = new WeakHashMap<>();
    private static final Map<Entity, Boolean> raycastResult = new WeakHashMap<>();

    private static boolean optifinePresent = false;
    private static int tickCounter = 0;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        optifinePresent = Loader.isModLoaded("optifine")
                || Loader.isModLoaded("OptiFine")
                || Loader.isModLoaded("Optifine");
        if (optifinePresent) {
            LOGGER.warn("[JustCulling] OptiFine detected, culling disabled.");
        }
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public void onRenderLivingPre(RenderLivingEvent.Pre event) {
        if (optifinePresent) return;
        if (event.entity == null) return;
        if (event.entity instanceof EntityPlayer) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        if (event.entity == mc.thePlayer) return;
        if (event.entity == mc.getRenderViewEntity()) return;
        if (event.entity.isDead) return;
        if (event.entity.isInvisible()) return;

        if (!isCurrentlyVisible(event.entity)) {
            event.setCanceled(true);
        }
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (optifinePresent) return;

        tickCounter++;
        if (tickCounter % CLEANUP_INTERVAL_TICKS != 0) return;

        long now = System.nanoTime();
        Iterator<Map.Entry<Entity, Long>> it = raycastTimeNs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Entity, Long> entry = it.next();
            Entity e = entry.getKey();
            Long t = entry.getValue();
            if (e == null || t == null || now - t > RAYCAST_MAX_AGE_NS) {
                it.remove();
                if (e != null) raycastResult.remove(e);
            }
        }
    }

    static boolean isCurrentlyVisible(Entity entity) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return true;

        Entity view = mc.getRenderViewEntity();
        if (view == null) view = mc.thePlayer;

        float partial = 1.0F;

        double camX = view.prevPosX + (view.posX - view.prevPosX) * partial;
        double camY = view.prevPosY + (view.posY - view.prevPosY) * partial + view.getEyeHeight();
        double camZ = view.prevPosZ + (view.posZ - view.prevPosZ) * partial;

        float camYaw = view.rotationYaw;
        float camPitch = view.rotationPitch;

        double ex = entity.prevPosX + (entity.posX - entity.prevPosX) * partial;
        double ey = entity.prevPosY + (entity.posY - entity.prevPosY) * partial + entity.height * 0.5D;
        double ez = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partial;

        double dx = ex - camX;
        double dy = ey - camY;
        double dz = ez - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq <= MIN_CULL_DISTANCE_SQ) return true;

        double dist = Math.sqrt(distSq);
        if (dist < 0.0001D) return true;

        float halfFovRad = (float) Math.toRadians(mc.gameSettings.fovSetting * 0.5F * FOV_MULTIPLIER);
        double cosHalfFov = Math.cos(halfFovRad);

        double yawRad = Math.toRadians(camYaw);
        double pitchRad = Math.toRadians(camPitch);
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

        double dot = (dx * lookX + dy * lookY + dz * lookZ) / dist;
        if (dot < cosHalfFov) return false;

        if (dist <= 16.0D) return true;

        long now = System.nanoTime();
        Long last = raycastTimeNs.get(entity);
        if (last != null && now - last < RAYCAST_CACHE_NS) {
            Boolean cached = raycastResult.get(entity);
            if (cached != null) return cached;
        }

        Vec3 camVec = new Vec3(camX, camY, camZ);
        Vec3 targetVec = new Vec3(ex, ey, ez);
        boolean visible = true;
        try {
            MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(camVec, targetVec, false, true, false);
            if (mop != null && mop.hitVec != null) {
                double hitDist = mop.hitVec.distanceTo(camVec);
                if (hitDist < dist - 0.5D) {
                    visible = false;
                }
            }
        } catch (Throwable t) {
            visible = true;
        }

        raycastTimeNs.put(entity, now);
        raycastResult.put(entity, visible);
        return visible;
    }
}
