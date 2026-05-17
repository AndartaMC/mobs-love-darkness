package eiruna.mobs.love.darkness;

import eiruna.mobs.love.darkness.mixin.MobEntityAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

public class ModEntityEvents {
    public static void register(LightSourceDestroyerConfig config) {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof MobEntity mob)) return;

            Identifier entityId = Registries.ENTITY_TYPE.getId(entity.getType());
            String mobType = mob.getType().toString();
            MobsLoveDarkness.LOGGER.debug("Loaded entity of type {}", mobType);

            if (!config.LightFixationEligibilityChancePerMobType.containsKey(entityId.toString())) return;
            MobsLoveDarkness.LOGGER.debug("{} is eligible for DestroyLightSourceGoal.", mobType);

            if (mob.getRandom().nextFloat() > config.LightFixationEligibilityChancePerMobType.get(entityId.toString())) return;

            ((MobEntityAccessor) mob).getGoalSelector().add(
                    config.LightFixationGoalPriority, // priority — lower = higher priority
                    new DestroyLightSourceGoal(mob, config)
            );
            MobsLoveDarkness.LOGGER.info("{} got DestroyLightSourceGoal.", mobType);
        });
    }
}