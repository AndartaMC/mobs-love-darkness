package eiruna.mobs.love.darkness;

import eiruna.mobs.love.darkness.mixin.MobEntityAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.world.rule.GameRules;

public class ModEntityEvents {
    public static void register(LightSourceDestroyerConfig config) {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof MobEntity mob)) return;
            if (!world.getGameRules().getValue(GameRules.DO_MOB_GRIEFING)) return;

            Identifier entityId = Registries.ENTITY_TYPE.getId(entity.getType());
            String mobType = mob.getType().toString();
            MobsLoveDarkness.LOGGER.debug("Loaded entity of type {}", mobType);

            StatusEffectInstance glow = mob.getStatusEffect(StatusEffects.GLOWING);
            if (glow != null && glow.getAmplifier() == DestroyLightSourceGoal.FIXATION_GLOW_AMPLIFIER) {
                mob.removeStatusEffect(StatusEffects.GLOWING);
                MobsLoveDarkness.LOGGER.debug("{} removed leftover fixation glow on load.", mobType);
            }

            if (!config.LightFixationEligibilityChancePerMobTypeMap.containsKey(entityId)) return;
            MobsLoveDarkness.LOGGER.debug("{} is eligible for DestroyLightSourceGoal.", mobType);

            if (mob.getRandom().nextFloat() > config.LightFixationEligibilityChancePerMobTypeMap.get(entityId)) return;

            ((MobEntityAccessor) mob).getGoalSelector().add(
                    config.LightFixationGoalPriority, // priority — lower = higher priority
                    new DestroyLightSourceGoal(mob, config)
            );
            MobsLoveDarkness.LOGGER.info("{} got DestroyLightSourceGoal.", mobType);
        });
    }
}