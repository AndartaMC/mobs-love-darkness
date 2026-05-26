package eiruna.mobs.love.darkness;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.api.SyntaxError;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class LightSourceDestroyerConfig {
    @Comment("""
            
            How far away from mob to search for light sources.
            Performance warning: search cost scales cubically with radius.
            Radius 16 is negligible, 32 is minor, 64 is noticeable, 128 is severe unless
            LightFixationEligibilityChancePerMobType values are kept very low (under 0.01).
            Large radii are intentionally supported but require proportionally rare mob eligibility.
            Min: 1 Max: 128 Default: 16
            """)
    public int LightSourceSearchRadius = 16;

    @Comment("""
            
            Vertical block radius to search for light sources.
            Keeping this low prevents mobs targeting unreachable underground lights.
            Min: 1 Max: 128 Default: 4
            """)
    public int LightSourceSearchRadiusVertical = 4;

    @Comment("""
            
            How many ticks it takes to break a light source.
            Must be smaller than LightFixationMaxGoalTicks.
            Min: 1 Max: 1200 Default: 20
            """)
    public int LightSourceBreakTimeTicks = 20;

    @Comment("""
            
            Chance per search attempt that a mob with the light fixation goal will fixate on a nearby light source.
            Note: searches occur at most once per second rather than every tick, so this is effectively
            a chance-per-second rather than a chance-per-tick.
            Min: 0.0 Max: 1.0 Default: 0.05
            """)
    public double LightFixationChance = 0.05;

    @Comment("""
            
            How movement speed should change while mob is moving toward a light source.
            Min: 0.1 Max: 10.0 Default: 0.85
            """)
    public double LightFixationSpeedMultiplier = 0.85;

    @Comment("""
            
            How long to wait after destroying a light source before targeting a new one.
            Min: 0 Max: 72000 Default: 200
            """)
    public int LightFixationCooldownTicks = 200;

    @Comment("""
            
            Goal priority for the light fixation behavior. Lower numbers take priority over higher numbers.
            Vanilla zombie attack goal is priority 2.
            Recommended values: 1 (high priority, overrides most behaviors)
                                3 (low priority, most other behaviors take precedence).
            Min: 1 Max: 10 Default: 3
            """)
    public int LightFixationGoalPriority = 3;

    @Comment("""
            
            Should particle effects be used while the mob is targeting a light source?
            Allowed values: true/false Default: true
            """)
    public boolean EnableParticles = true;

    @Comment("""
            
            Should sounds be played when the mob starts targeting and destroys a light source?
            Allowed values: true/false Default: true
            """)
    public boolean EnableSounds = true;

    @Comment("""
            
            Should fixated mobs glow? Makes them easier to identify.
            Allowed values: true/false Default: true
            """)
    public boolean EnableLightFixationGlowEffect = true;

    @Comment("""
            
            Maximum ticks a mob can spend on a single fixation attempt before giving up.
            Increase for mobs with poor pathfinding like phantoms.
            Must be greater than LightSourceBreakTimeTicks.
            Min: 100 Max: 72000 Default: 600
            """)
    public int LightFixationMaxGoalTicks = 600;

    @Comment("""
            
            Which blocks are valid light sources to target?
            Provide an array of blocks including mod prefix.
            Default:
            [
                "minecraft:torch",
                "minecraft:wall_torch",
                "minecraft:lantern",
                "minecraft:soul_torch",
                "minecraft:soul_lantern",
                "minecraft:campfire",
                "minecraft:soul_campfire"
            ]
            """)
    public List<String> TargetableLightSources = new ArrayList<>(List.of(
            "minecraft:torch",
            "minecraft:wall_torch",
            "minecraft:lantern",
            "minecraft:soul_torch",
            "minecraft:soul_lantern",
            "minecraft:campfire",
            "minecraft:soul_campfire"
    ));

    @Comment("""
            
            Chance per mob type to receive the light fixation goal on spawn.
            Performance warning: each mob with the goal searches for light sources periodically.
            Keep total eligible mobs under ~10-20 for default settings.
            If using large search radii, keep eligibility chances proportionally lower.
            Format: { "namespace:mob_id": chance }
            """)
    public Map<String, Double> LightFixationEligibilityChancePerMobType = new LinkedHashMap<>(Map.of(
            "minecraft:zombie", 0.05,
            "minecraft:husk", 0.05,
            "minecraft:zombie_villager", 0.05,
            "minecraft:zombified_piglin", 0.05,
            "minecraft:phantom", 0.75
    ));

    // --- Derived sets, built once at load time ---
    public transient Set<Identifier> TargetableLightSourcesSet;
    public transient Map<Identifier, Double> LightFixationEligibilityChancePerMobTypeMap; // derived

    private static final String CONFIG_FILE = MobsLoveDarkness.MOD_ID + ".json5";

    private LightSourceDestroyerConfig(){}

    public static LightSourceDestroyerConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);

        LightSourceDestroyerConfig config;
        Jankson jankson = Jankson.builder().build();

        if (Files.exists(path)) {
            try {
                JsonObject obj = jankson.load(path.toFile());
                config = jankson.fromJson(obj, LightSourceDestroyerConfig.class);
            } catch (IOException | SyntaxError e) {
                MobsLoveDarkness.LOGGER.warn("Failed to read config, using defaults: {}", e.getMessage());
                config = new LightSourceDestroyerConfig();
            }
        } else {
            config = new LightSourceDestroyerConfig();
        }

        // Validate first so corrected values are what gets written to disk
        config.init();

        try {
            JsonObject json = (JsonObject) jankson.toJson(config);
            applyComments(json);
            Files.writeString(path, json.toJson(true, true));
        } catch (IOException e) {
            MobsLoveDarkness.LOGGER.warn("Failed to write config: {}", e.getMessage());
        }

        return config;
    }

    private static void applyComments(JsonObject json){
        for (Field field : LightSourceDestroyerConfig.class.getFields()) {
            Comment comment = field.getAnnotation(Comment.class);

            if (comment != null) {
                json.setComment(field.getName(), comment.value());
            }
        }
    }

    private void init() {
        validate();
        TargetableLightSourcesSet = TargetableLightSources.stream()
                .map(Identifier::of)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private void validate() {
        LightSourceSearchRadius = clampInt("LightSourceSearchRadius", LightSourceSearchRadius, 1, 128);
        LightSourceSearchRadiusVertical = clampInt("LightSourceSearchRadiusVertical", LightSourceSearchRadiusVertical, 1, 128);
        LightSourceBreakTimeTicks = clampInt("LightSourceBreakTimeTicks", LightSourceBreakTimeTicks, 1, 1200);
        LightFixationCooldownTicks = clampInt("LightFixationCooldownTicks", LightFixationCooldownTicks, 0, 72000);
        LightFixationGoalPriority = clampInt("LightFixationGoalPriority", LightFixationGoalPriority, 1, 10);
        LightFixationMaxGoalTicks = clampInt("LightFixationMaxGoalTicks", LightFixationMaxGoalTicks, 100, 72000);
        LightFixationSpeedMultiplier = clampDouble("LightFixationSpeedMultiplier", LightFixationSpeedMultiplier, 0.1F, 10.0F);
        LightFixationChance = clampDouble("LightFixationChance", LightFixationChance, 0.0F, 1.0F);
        if (LightFixationEligibilityChancePerMobType == null ||
                LightFixationEligibilityChancePerMobType.isEmpty()) {
            MobsLoveDarkness.LOGGER.warn(
                    "Config: LightFixationEligibilityChancePerMobType is empty — no mobs will get the goal. Using defaults."
            );
            LightFixationEligibilityChancePerMobType = new HashMap<>(Map.of(
                    "minecraft:zombie", 0.05,
                    "minecraft:husk", 0.05,
                    "minecraft:zombie_villager", 0.05,
                    "minecraft:zombified_piglin", 0.05,
                    "minecraft:phantom", 0.75
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

        if (LightFixationMaxGoalTicks <= LightSourceBreakTimeTicks) {
            int corrected = LightSourceBreakTimeTicks * 3;
            MobsLoveDarkness.LOGGER.warn(
                    "Config: LightFixationMaxGoalTicks ({}) must be greater than LightSourceBreakTimeTicks ({}). " +
                            "Setting LightFixationMaxGoalTicks to {} (3x break time).",
                    LightFixationMaxGoalTicks, LightSourceBreakTimeTicks, corrected
            );
            LightFixationMaxGoalTicks = corrected;
        }

        // Rough heuristic: at default speed a mob covers ~4 blocks/second
        // squaredRadius / 16 gives approximate seconds needed to cross the search radius
        double approximateTravelTicks = (LightSourceSearchRadius * LightSourceSearchRadius) / (16.0 * LightFixationSpeedMultiplier);
        if (LightFixationMaxGoalTicks < approximateTravelTicks) {
            MobsLoveDarkness.LOGGER.warn(
                    "Config: LightFixationMaxGoalTicks ({}) may be too low for LightSourceSearchRadius ({}). " +
                            "Mobs targeting distant light sources may time out before reaching them.",
                    LightFixationMaxGoalTicks, LightSourceSearchRadius
            );
        }

        if (LightFixationChance == 0.0) {
            MobsLoveDarkness.LOGGER.warn(
                    "Config: LightFixationChance is 0.0 — mobs will never fixate on light sources " +
                            "even if they are eligible. Set to a value above 0.0 to enable fixation behavior."
            );
        }
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

    private double clampDouble(String fieldName, double value, double min, double max) {
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

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Comment {
        String value();
    }
}
