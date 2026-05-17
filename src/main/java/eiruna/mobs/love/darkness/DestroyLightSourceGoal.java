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
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.rule.GameRules;

import java.util.EnumSet;
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
    public static final int FIXATION_GLOW_AMPLIFIER = 42; //glow amplifier used for identifying the glow effect for cleanup purposes
    private int pathRefreshTimer = 0;
    private static final int PATH_REFRESH_INTERVAL = 20; // recalculate path once per second
    private long nextSearchTick = 0;
    private static final int SEARCH_INTERVAL_TICKS = 20; // search at most once per second

    public DestroyLightSourceGoal(MobEntity mob, LightSourceDestroyerConfig config) {
        this.mob = mob;
        this.config = config;
        this.mobType = mob.getType().toString();
        this.world = mob.getEntityWorld();
        isBirdNavigator = mob.getNavigation() instanceof BirdNavigation;

        if (!isBirdNavigator) {
            this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
        } else {
            this.setControls(EnumSet.of(Goal.Control.LOOK)); // only block looking for flyers
        }

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

        if(!shouldDoANewSearch()) return false;

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
        playTargetSelectedSound();
        addGlowingEffect();

        MobsLoveDarkness.LOGGER.info("{} started targeting light source at {} {} {}",
                mobType,
                targetLightSource.getX(), targetLightSource.getY(), targetLightSource.getZ()
        );
    }

    @Override
    public void tick() {
        if(handleTargetBreakingExpiration()) return;
        addParticleEffect();

        double squaredDistance = mob.squaredDistanceTo(
                targetLightSource.getX() + 0.5,
                targetLightSource.getY() + 0.5,
                targetLightSource.getZ() + 0.5
        );

        // Real distance of 3 blocks → squaredDistance of 9
        // Real distance of 4 blocks → squaredDistance of 16
        double squaredBreakDistance = isBirdNavigator ? 16.0 : 9.0;
        boolean isTooFarAwayFromTargetToBreakIt = squaredDistance > squaredBreakDistance;
        if (isTooFarAwayFromTargetToBreakIt) {
            moveTowardTargetIfApplicable(squaredDistance);
            return;
        }

        attackTarget();

        if (breakProgress >= config.LightSourceBreakTimeTicks) {
           destroyTarget();
        }
    }

    @Override
    public void stop() {
        super.stop();
        if (targetLightSource != null) {
            // Only reset if tick() didn't already clean up
            resetFixation();
        }
        removeFixationGlow();
        MobsLoveDarkness.LOGGER.debug("{} DestroyLightSourceGoal stopped.", mobType);
    }

    private BlockPos findNearbyLightSource() {
        BlockPos mobPos = mob.getBlockPos();

        for (BlockPos pos : BlockPos.iterateOutwards(
                mobPos,
                config.LightSourceSearchRadius,
                config.LightSourceSearchRadiusVertical,
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
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        if (!config.TargetableLightSourcesSet.contains(Registries.BLOCK.getId(block))) return false;

        // If the block has a LIT property, only target it when lit
        if (state.contains(Properties.LIT)) {
            return state.get(Properties.LIT);
        }

        return true;
    }

    private void resetFixation(){
        targetLightSource = null;
        removeFixationGlow();
        breakProgress = 0;
        totalGoalTicks = 0;
        pathRefreshTimer = 0;
    }

    private boolean hasFixationGlow() {
        StatusEffectInstance effect = mob.getStatusEffect(StatusEffects.GLOWING);
        return effect != null && effect.getAmplifier() == FIXATION_GLOW_AMPLIFIER;
    }

    private void removeFixationGlow() {
        if (hasFixationGlow()) {
            mob.removeStatusEffect(StatusEffects.GLOWING);
        }
    }

    private void addParticleEffect(){
        if (config.EnableParticles && world instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(
                    ParticleTypes.SOUL_FIRE_FLAME,
                    mob.getX(), mob.getY() + mob.getHeight(), mob.getZ(),
                    1,    // count
                    0.1, 0.1, 0.1, // spread
                    0.0   // speed
            );
        }
    }

    private boolean handleTargetBreakingExpiration(){
        totalGoalTicks++;
        if (totalGoalTicks >= config.LightFixationMaxGoalTicks) {
            MobsLoveDarkness.LOGGER.debug("{} took too long to break target, abandoning.", mobType);
            unreachableTargets.add(targetLightSource);
            resetFixation();
            completedSuccessfully = true;
            return true;
        }
        return false;
    }

    private void resetBreakProgressForGroundMobs(){
        if (breakProgress > 0) {
            if (!isBirdNavigator) {
                breakProgress = 0;
                MobsLoveDarkness.LOGGER.debug("{} was interrupted while breaking, re-navigating.", mobType);
            } else {
                MobsLoveDarkness.LOGGER.debug("{} left break range, continuing to accumulate progress while re-navigating.", mobType);
            }
        }
    }

    private void moveTowardTarget(double squaredDistance){
        mob.getNavigation().startMovingTo(
            targetLightSource.getX(),
            targetLightSource.getY() + (isBirdNavigator ? 2 : 0),
            targetLightSource.getZ(),
            config.LightFixationSpeedMultiplier
        );
        MobsLoveDarkness.LOGGER.debug(
            "{} recalculating path to light source at {} {} {}, squaredDistance: {}",
            mobType,
            targetLightSource.getX(), targetLightSource.getY(), targetLightSource.getZ(),
            String.format("%.1f", squaredDistance)
        );
    }

    private void moveTowardTargetIfApplicable(double squaredDistance){
        pathRefreshTimer++;
        if (pathRefreshTimer >= PATH_REFRESH_INTERVAL || mob.getNavigation().isIdle()) {
            pathRefreshTimer = 0;
            resetBreakProgressForGroundMobs();
            moveTowardTarget(squaredDistance);
        }
    }

    private void attackTarget(){
        mob.getLookControl().lookAt(
                targetLightSource.getX() + 0.5,
                targetLightSource.getY() + 0.5,
                targetLightSource.getZ() + 0.5
        );

        breakProgress++;

        if (breakProgress % 8 == 0) {
            mob.swingHand(Hand.MAIN_HAND);
        }

        if (breakProgress == 1) {
            mob.getNavigation().stop();
            MobsLoveDarkness.LOGGER.debug(
                    "{} reached light source, beginning break sequence ({} ticks)",
                    mobType,
                    config.LightSourceBreakTimeTicks
            );
        }
    }

    private void destroyTarget(){
        BlockState state = world.getBlockState(targetLightSource);
        MobsLoveDarkness.LOGGER.debug("{} is breaking {}.", mobType, state.getBlock().getName());

        fixationCooldown = config.LightFixationCooldownTicks;

        // Guard: block may have been broken by something else
        if (!isAllowedLightSource(targetLightSource)) {
            MobsLoveDarkness.LOGGER.info("{} target light source no longer valid, breaking aborted, cooldown set to {}", mobType, fixationCooldown);
            resetFixation();
            return;
        }

        if(world instanceof ServerWorld serverWorld){
            if (!serverWorld.getGameRules().getValue(GameRules.DO_MOB_GRIEFING)) {
                MobsLoveDarkness.LOGGER.debug("{} mob griefing disabled, aborting break.", mobType);
                resetFixation();
                return;
            }
        }

        world.breakBlock(targetLightSource, false, mob);

        playTargetBrokenSound();

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

    private void playTargetBrokenSound(){
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
    }

    private void playTargetSelectedSound(){
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
    }

    private void addGlowingEffect(){
        if(config.EnableLightFixationGlowEffect){
            mob.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.GLOWING,
                    Integer.MAX_VALUE,
                    FIXATION_GLOW_AMPLIFIER,
                    true, // ambient (makes particles subtler if true)
                    false
            ));
        }
    }

    private boolean shouldDoANewSearch(){
        long currentTick = world.getTime();
        if (currentTick < nextSearchTick) {
            return false;
        }
        nextSearchTick = currentTick + SEARCH_INTERVAL_TICKS;
        return true;
    }
}
