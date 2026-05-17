package eiruna.mobs.love.darkness;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MobsLoveDarkness implements ModInitializer {
	public static final String MOD_ID = "mobs-love-darkness";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.debug("Hello from " + MOD_ID + ".");
		LOGGER.debug("Initializing config file.");
		LightSourceDestroyerConfig config = LightSourceDestroyerConfig.load();
		LOGGER.debug("Configs loaded. Registering mob events.");
		ModEntityEvents.register(config);
		LOGGER.info("Initialization completed.");
	}
}