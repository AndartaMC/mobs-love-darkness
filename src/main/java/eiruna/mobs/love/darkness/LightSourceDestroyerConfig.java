package eiruna.mobs.love.darkness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class LightSourceDestroyerConfig {
    public String _comment_LightSourceSearchRadius =
            "How far away from mob to search for light sources. Min: 1 Max: 128 Default: 16. " +
            "Performance warning: search cost scales cubically with radius. " +
            "Radius 16 is negligible, 32 is minor, 64 is noticeable, 128 is severe unless " +
            "LightFixationEligibilityChancePerMobType values are kept very low (under 0.01). " +
            "Large radii are intentionally supported but require proportionally rare mob eligibility.";
    public int LightSourceSearchRadius = 16;
    public String _comment_LightSourceSearchRadiusVertical =
            "Vertical block radius to search for light sources. " +
            "Keeping this low prevents mobs targeting unreachable underground lights. " +
            "Min: 1 Max: 128 Default: 4";
    public int LightSourceSearchRadiusVertical = 4;
    public String _comment_LightSourceBreakTimeTicks =
            "How many ticks it takes to break a light source. Min: 1 Max: 1200 Default: 20";
    public int LightSourceBreakTimeTicks = 20;
    public String _comment_LightFixationChance =
            "Chance per search attempt that a mob with the light fixation goal will fixate on a nearby light source. " +
            "Note: searches occur at most once per second rather than every tick, so this is effectively " +
            "a chance-per-second rather than a chance-per-tick. " +
            "Min: 0.0 Max: 1.0 Default: 0.05";
    public float LightFixationChance = 0.05F;
    public String _comment_LightFixationSpeedMultiplier =
            "How movement speed should change while mob is moving toward a light source. Min: 0.1 Max: 10.0 Default: 0.85";
    public float LightFixationSpeedMultiplier = 0.85F;
    public String _comment_LightFixationCooldownTicks =
            "How long to wait after destroying a light source before targeting a new one. Min: 0 Max: 72000 Default: 200";
    public int LightFixationCooldownTicks = 200;
    public String _comment_LightFixationGoalPriority =
            "Goal priority for the light fixation behavior. Lower numbers take priority over higher numbers. Vanilla zombie attack goal is priority 2. Recommended values: 1 (high priority, overrides most behaviors) 3 (low priority, most other behaviors take precedence). Min: 1 Max: 10 Default: 3";
    public int LightFixationGoalPriority = 3;
    public String _comment_EnableParticles =
            "Should particle effects be used while the mob is targeting a light source? Allowed values: true/false Default: true";
    public boolean EnableParticles = true;
    public String _comment_EnableSounds =
            "Should sounds be played when the mob starts targeting and destroys a light source? Allowed values: true/false Default: true";
    public boolean EnableSounds = true;
    public String _comment_EnableLightFixationGlowEffect =
            "Should fixated mobs glow? Makes them easier to identify. Allowed values: true/false Default: true";
    public boolean EnableLightFixationGlowEffect = true;
    public String _comment_LightFixationMaxGoalTicks =
            "Maximum ticks a mob can spend on a single fixation attempt before giving up. " +
            "Increase for mobs with poor pathfinding like phantoms. Min: 100 Max: 72000 Default: 600";
    public int LightFixationMaxGoalTicks = 600;
    public String _comment_TargetableLightSources =
            "Which blocks are valid light sources to target? Provide an array of blocks including mod prefix. Default: [ \"minecraft:torch\", \"minecraft:wall_torch\", \"minecraft:lantern\", \"minecraft:soul_torch\", \"minecraft:soul_lantern\", \"minecraft:campfire\", \"minecraft:soul_campfire\" ] ";
    public List<String> TargetableLightSources = List.of(
            "minecraft:torch",
            "minecraft:wall_torch",
            "minecraft:lantern",
            "minecraft:soul_torch",
            "minecraft:soul_lantern",
            "minecraft:campfire",
            "minecraft:soul_campfire"
    );
    public String _comment_LightFixationEligibilityChancePerMobType =
            "Chance per mob type to receive the light fixation goal on spawn. " +
            "Performance warning: each mob with the goal searches for light sources periodically. " +
            "Keep total eligible mobs under ~10-20 for default settings. " +
            "If using large search radii, keep eligibility chances proportionally lower. " +
            "Format: { \"namespace:mob_id\": chance }";
    public Map<String, Float> LightFixationEligibilityChancePerMobType = new HashMap<>(Map.of(
            "minecraft:zombie", 0.05f,
            "minecraft:husk", 0.05f,
            "minecraft:zombie_villager", 0.05f,
            "minecraft:zombified_piglin", 0.05f,
            "minecraft:phantom", 0.75f
    ));

    // --- Derived sets, built once at load time ---
    public transient Set<Identifier> TargetableLightSourcesSet;
    public transient Map<Identifier, Float> LightFixationEligibilityChancePerMobTypeMap; // derived

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = MobsLoveDarkness.MOD_ID + ".json";

    // Called after Gson populates the fields
    public void init() {
        validate();
        TargetableLightSourcesSet = TargetableLightSources.stream()
                .map(Identifier::of)
                .collect(Collectors.toCollection(HashSet::new));
    }

    public static LightSourceDestroyerConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);

        LightSourceDestroyerConfig config;

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                config = GSON.fromJson(reader, LightSourceDestroyerConfig.class);
            } catch (IOException e) {
                MobsLoveDarkness.LOGGER.info("Failed to read config, using defaults: {}", e.getMessage());
                config = new LightSourceDestroyerConfig();
            }
        } else {
            config = new LightSourceDestroyerConfig();
            // Write defaults to disk so the user can edit them
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(config, writer);
            } catch (IOException e) {
                MobsLoveDarkness.LOGGER.info("Failed to write default config: {}", e.getMessage());
            }
        }

        config.init();
        return config;
    }

    private void validate() {
        LightSourceSearchRadius = clampInt("LightSourceSearchRadius", LightSourceSearchRadius, 1, 128);
        LightSourceSearchRadiusVertical = clampInt("LightSourceSearchRadiusVertical", LightSourceSearchRadiusVertical, 1, 128);
        LightSourceBreakTimeTicks = clampInt("LightSourceBreakTimeTicks", LightSourceBreakTimeTicks, 1, 1200);
        LightFixationCooldownTicks = clampInt("LightFixationCooldownTicks", LightFixationCooldownTicks, 0, 72000);
        LightFixationGoalPriority = clampInt("LightFixationGoalPriority", LightFixationGoalPriority, 1, 10);
        LightFixationMaxGoalTicks = clampInt("LightFixationMaxGoalTicks", LightFixationMaxGoalTicks, 100, 72000);
        LightFixationSpeedMultiplier = clampFloat("LightFixationSpeedMultiplier", LightFixationSpeedMultiplier, 0.1F, 10.0F);
        LightFixationChance = clampFloat("LightFixationChance", LightFixationChance, 0.0F, 1.0F);
        if (LightFixationEligibilityChancePerMobType == null ||
                LightFixationEligibilityChancePerMobType.isEmpty()) {
            MobsLoveDarkness.LOGGER.warn(
                    "Config: LightFixationEligibilityChancePerMobType is empty — no mobs will get the goal. Using defaults."
            );
            LightFixationEligibilityChancePerMobType = new HashMap<>(Map.of(
                    "minecraft:zombie", 0.05f,
                    "minecraft:husk", 0.05f,
                    "minecraft:zombie_villager", 0.05f,
                    "minecraft:zombified_piglin", 0.05f,
                    "minecraft:phantom", 0.75f
            ));
        }

        // Validate individual values and identifier format
        LightFixationEligibilityChancePerMobType.forEach((mobId, chance) -> {
            if (!mobId.contains(":") || mobId.split(":").length != 2) {
                MobsLoveDarkness.LOGGER.warn(
                        "Config: LightFixationEligibilityChancePerMobType contains '{}' " +
                                "which doesn't look like a valid identifier.", mobId
                );
            }
            if (chance < 0.0f || chance > 1.0f) {
                MobsLoveDarkness.LOGGER.warn(
                        "Config: LightFixationEligibilityChancePerMobType value for '{}' " +
                                "is {} which is out of range [0.0, 1.0], clamping.", mobId, chance
                );
                LightFixationEligibilityChancePerMobType.put(
                        mobId, Math.clamp(chance, 0.0f, 1.0f)
                );
            }
        });

        LightFixationEligibilityChancePerMobTypeMap = LightFixationEligibilityChancePerMobType
                .entrySet().stream()
                .collect(Collectors.toMap(
                        e -> Identifier.of(e.getKey()),
                        Map.Entry::getValue
                ));

        if (TargetableLightSources == null || TargetableLightSources.isEmpty()) {
            MobsLoveDarkness.LOGGER.warn("Config: TargetableLightSources is empty — mobs will have nothing to target. Using defaults.");
            TargetableLightSources = List.of(
                    "minecraft:torch",
                    "minecraft:wall_torch",
                    "minecraft:lantern",
                    "minecraft:soul_torch",
                    "minecraft:soul_lantern",
                    "minecraft:campfire",
                    "minecraft:soul_campfire"
            );
        }

        // Warn about any malformed identifier strings in either list
        validateIdentifierList("TargetableLightSources", TargetableLightSources);
    }

    private int clampInt(String fieldName, int value, int min, int max) {
        if (value < min || value > max) {
            MobsLoveDarkness.LOGGER.warn(
                    "Config: {} value {} is out of range [{}, {}], clamping to nearest valid value.",
                    fieldName, value, min, max
            );
            return Math.clamp(value, min, max);
        }
        return value;
    }

    private float clampFloat(String fieldName, float value, float min, float max) {
        if (value < min || value > max) {
            MobsLoveDarkness.LOGGER.warn(
                    "Config: {} value {} is out of range [{}, {}], clamping to nearest valid value.",
                    fieldName, value, min, max
            );
            return Math.clamp(value, min, max);
        }
        return value;
    }

    // Warns about entries that don't look like "namespace:path" but doesn't remove them,
    // since mods may use non-minecraft namespaces
    private void validateIdentifierList(String fieldName, List<String> list) {
        for (String entry : list) {
            if (entry == null || entry.isBlank()) {
                MobsLoveDarkness.LOGGER.warn("Config: {} contains a null or blank entry, it will be ignored.", fieldName);
                continue;
            }
            if (!entry.contains(":") || entry.split(":").length != 2) {
                MobsLoveDarkness.LOGGER.warn(
                        "Config: {} contains '{}' which doesn't look like a valid identifier (expected 'namespace:path').",
                        fieldName, entry
                );
            }
        }
    }
}
