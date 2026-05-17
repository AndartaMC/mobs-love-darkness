package eiruna.mobs.love.darkness;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.BirdNavigation;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Set;

public class DestroyLightSourceGoal extends Goal {
    private final MobEntity mob;
    private BlockPos targetLightSource;
    private int breakProgress;
    private final LightSourceDestroyerConfig config;
    private int fixationCooldown;
    private final String mobType;
    private boolean completedSuccessfully = false;
    private final World world;
    private final Set<BlockPos> unreachableTargets = new HashSet<BlockPos>();
    private int blacklistClearTimer = 0;
    private static final int BLACKLIST_CLEAR_INTERVAL = 20000;
    private int totalGoalTicks = 0;
    private final boolean isBirdNavigator;

    public DestroyLightSourceGoal(MobEntity mob, LightSourceDestroyerConfig config) {
        this.mob = mob;
        this.config = config;
        this.mobType = mob.getType().toString();
        this.world = mob.getEntityWorld();
        isBirdNavigator = mob.getNavigation() instanceof BirdNavigation;
        MobsLoveDarkness.LOGGER.debug("{} Initialized new DestroyLightSourceGoal", mobType);
    }

    @Override
    public boolean canStart() {
        if(fixationCooldown > 0){
            fixationCooldown--;
            return false;
        }

        blacklistClearTimer++;
        if (blacklistClearTimer >= BLACKLIST_CLEAR_INTERVAL) {
            blacklistClearTimer = 0;
            if (!unreachableTargets.isEmpty()) {
                unreachableTargets.clear();
                MobsLoveDarkness.LOGGER.debug("{} cleared unreachable targets blacklist.", mobType);
            }
        }

        if (mob.getRandom().nextFloat() > config.LightFixationChance) {
            return false;
        }

        targetLightSource = findNearbyLightSource();

        if (targetLightSource == null) {
            MobsLoveDarkness.LOGGER.debug("{} found no nearby light sources.", mobType);
            return false;
        }

        return true;
    }

    @Override
    public boolean shouldContinue() {
        boolean shouldContinue = isAllowedLightSource(targetLightSource);
        if (!shouldContinue && !completedSuccessfully) {
            MobsLoveDarkness.LOGGER.info("{} Cancelled block targeting.", mobType);
        }

        return shouldContinue;
    }

    @Override
    public void start() {
        super.start();
        breakProgress = 0;
        totalGoalTicks = 0;
        completedSuccessfully = false;
        if(config.EnableSounds){
            world.playSound(
                null, // null = broadcast to nearby players
                mob.getBlockPos(),
                SoundEvents.ENTITY_ZOMBIE_INFECT,
                SoundCategory.HOSTILE,
                4.0F, // volume
                0.5F  // pitch
            );
        }
        if(config.EnableLightFixationGlowEffect){
            mob.addStatusEffect(new StatusEffectInstance(
                StatusEffects.GLOWING,
                Integer.MAX_VALUE,
                0,
                true, // ambient (makes particles subtler if true)
                false
            ));
        }

        MobsLoveDarkness.LOGGER.info("{} started targeting light source at {} {} {}",
                mobType,
                targetLightSource.getX(), targetLightSource.getY(), targetLightSource.getZ()
        );
    }

    @Override
    public void tick() {
        if (config.EnableParticles && world instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(
                ParticleTypes.SOUL_FIRE_FLAME,
                mob.getX(), mob.getY() + mob.getHeight(), mob.getZ(),
                1,    // count
                0.1, 0.1, 0.1, // spread
                0.0   // speed
            );
        }

        totalGoalTicks++;
        if (totalGoalTicks >= config.LightFixationMaxGoalTicks) {
            MobsLoveDarkness.LOGGER.debug("{} took too long to break target, abandoning.", mobType);
            unreachableTargets.add(targetLightSource);
            completedSuccessfully = true;
            resetFixation();
            return;
        }

        double distance = mob.squaredDistanceTo(
                targetLightSource.getX() + 0.5,
                targetLightSource.getY() + 0.5,
                targetLightSource.getZ() + 0.5
        );

        // Real distance of 3 blocks → squaredDistance of 9
        // Real distance of 4 blocks → squaredDistance of 16
        double breakDistance = isBirdNavigator ? 16.0 : 9.0;
        if (distance > breakDistance) {
            if (breakProgress > 0) {
                if (!isBirdNavigator) {
                    breakProgress = 0;
                    MobsLoveDarkness.LOGGER.debug("{} was interrupted while breaking, re-navigating.", mobType);
                } else {
                    MobsLoveDarkness.LOGGER.debug("{} left break range, continuing to accumulate progress while re-navigating.", mobType);
                }
            }

            mob.getNavigation().startMovingTo(
                    targetLightSource.getX(),
                    targetLightSource.getY() + (isBirdNavigator ? 2 : 0),
                    targetLightSource.getZ(),
                    config.LightFixationSpeedMultiplier
            );

            // Helps diagnose mobs that never reach the target
            MobsLoveDarkness.LOGGER.debug(
                "{} moving toward light source at {} {} {}, squaredDistance: {}",
                mobType,
                targetLightSource.getX(), targetLightSource.getY(), targetLightSource.getZ(),
                String.format("%.1f", distance)
            );

            return;
        }

        breakProgress++;

        if (breakProgress % 8 == 0) {
            mob.swingHand(Hand.MAIN_HAND);
        }

        if (breakProgress == 1) {
            MobsLoveDarkness.LOGGER.debug(
                    "{} reached light source, beginning break sequence ({} ticks)",
                    mobType,
                    config.LightSourceBreakTimeTicks
            );
        }

        if (breakProgress >= config.LightSourceBreakTimeTicks) {
            BlockState state = world.getBlockState(targetLightSource);
            MobsLoveDarkness.LOGGER.debug("{} is breaking {}.", mobType, state.getBlock().getName());

            fixationCooldown = config.LightFixationCooldownTicks;

            // Guard: block may have been broken by something else
            if (!isAllowedLightSource(targetLightSource)) {
                MobsLoveDarkness.LOGGER.info("{} target light source no longer valid, breaking aborted, cooldown set to {}", mobType, fixationCooldown);
                resetFixation();
                return;
            }

            world.breakBlock(targetLightSource, false, mob);

            if(config.EnableSounds){
                world.playSound(
                    null, // null = broadcast to nearby players
                    mob.getBlockPos(),
                    SoundEvents.BLOCK_FIRE_EXTINGUISH,
                    SoundCategory.HOSTILE,
                    2.0F, // volume
                    0.5F  // pitch
                );
            }

            if (isAllowedLightSource(targetLightSource)) {
                // Block still there — likely protected, blacklist it
                MobsLoveDarkness.LOGGER.debug("{} failed to break light source, likely protected. Blacklisting.", mobType);
                unreachableTargets.add(targetLightSource);
                resetFixation();
                return;
            }

            completedSuccessfully = true;
            MobsLoveDarkness.LOGGER.info("{} broke light source, cooldown set to {}", mobType, fixationCooldown);
        }
    }

    @Override
    public void stop() {
        super.stop();
        if (targetLightSource != null) {
            // Only reset if tick() didn't already clean up
            resetFixation();
        }
        mob.removeStatusEffect(StatusEffects.GLOWING); // always ensure glow is removed
        MobsLoveDarkness.LOGGER.debug("{} DestroyLightSourceGoal stopped.", mobType);
    }

    private BlockPos findNearbyLightSource() {
        BlockPos mobPos = mob.getBlockPos();

        for (BlockPos pos : BlockPos.iterateOutwards(
                mobPos,
                config.LightSourceSearchRadius,
                config.LightSourceSearchRadius,
                config.LightSourceSearchRadius
        )) {
            if(isAllowedLightSource(pos) && !unreachableTargets.contains(pos)){
                return pos.toImmutable();
            }
        }

        return null;
    }

    private boolean isAllowedLightSource(BlockPos pos){
        if(pos == null){
            return false;
        }
        Block block = world
                .getBlockState(pos).getBlock();
        Identifier blockId = Registries.BLOCK.getId(block);
        return config.TargetableLightSourcesSet.contains(blockId.toString());
    }

    private void resetFixation(){
        targetLightSource = null;
        mob.removeStatusEffect(StatusEffects.GLOWING);
        breakProgress = 0;
        totalGoalTicks = 0;
    }
}
