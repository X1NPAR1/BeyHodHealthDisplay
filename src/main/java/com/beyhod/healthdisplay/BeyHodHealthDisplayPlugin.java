package com.beyhod.healthdisplay;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@SuppressWarnings({"deprecation", "removal"})
public final class BeyHodHealthDisplayPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String PREFIX = "§8[§c§lBeyHodHP§8] §r";
    private static final String HIDDEN_NAMETAG_TEAM = "bhhd_hide_names";


    private final Map<UUID, DisplayEntry> displays = new HashMap<>();
    private final Map<UUID, Long> combatUntilTick = new HashMap<>();
    private final Set<UUID> disabledPlayers = new HashSet<>();
    private final DecimalFormat oneDecimal = new DecimalFormat("0.#");

    private NamespacedKey displayMarkerKey;
    private NamespacedKey hiddenOriginalNameKey;
    private NamespacedKey hiddenOriginalVisibleKey;
    private NamespacedKey hiddenNameActiveKey;
    private long tickCounter = 0L;
    private int taskId = -1;

    private Plugin bossPlugin;
    private Field bossStateField;
    private Field bossEntityField;
    private Field bossHpField;
    private Field bossMaxHpField;
    private long nextBossReflectionTryTick = 0L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        displayMarkerKey = new NamespacedKey(this, "health_display_marker");
        hiddenOriginalNameKey = new NamespacedKey(this, "hidden_original_custom_name");
        hiddenOriginalVisibleKey = new NamespacedKey(this, "hidden_original_custom_name_visible");
        hiddenNameActiveKey = new NamespacedKey(this, "hidden_name_active");

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("healthdisplay") != null) getCommand("healthdisplay").setExecutor(this);
        if (getCommand("hdadmin") != null) getCommand("hdadmin").setExecutor(this);

        restoreHiddenNamesFromPreviousSession();
        cleanupExistingDisplays();
        startTask();
        getLogger().info("BeyHodHealthDisplay aktif. TextDisplay can gostergesi calisiyor.");
    }

    @Override
    public void onDisable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        if (getConfig().getBoolean("performance.cleanup-on-disable", true)) {
            removeAllDisplays();
            cleanupExistingDisplays();
        }
    }

    private void startTask() {
        int interval = updateIntervalTicks();
        long startupDelay = Math.max(1L, getConfig().getLong("performance.startup-delay-ticks", 1L));
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            tickCounter += interval;
            if (!getConfig().getBoolean("general.enabled", true)) {
                removeAllDisplays();
                return;
            }
            updateDisplays();
        }, startupDelay, interval);
    }

    private int updateIntervalTicks() {
        int configured = Math.max(1, getConfig().getInt("performance.update-interval-ticks", 1));
        if (getConfig().getBoolean("performance.fast-mode", true)) {
            return 1;
        }
        return configured;
    }

    private void restartTask() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
        startTask();
    }

    private void updateDisplays() {
        Set<LivingEntity> wanted = collectWantedEntities();
        int maxDisplays = Math.max(10, getConfig().getInt("performance.max-displays-total", 500));

        if (wanted.size() > maxDisplays) {
            wanted = wanted.stream()
                    .sorted(Comparator.comparingDouble(this::nearestPlayerDistanceSquared))
                    .limit(maxDisplays)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        for (LivingEntity entity : wanted) {
            if (!isValidTarget(entity)) continue;
            DisplayEntry entry = displays.get(entity.getUniqueId());
            if (entry == null || entry.display == null || !entry.display.isValid()) {
                entry = createDisplay(entity);
                if (entry == null) continue;
                displays.put(entity.getUniqueId(), entry);
            }
            updateSingleDisplay(entity, entry);
        }

        Set<UUID> wantedIds = wanted.stream().map(Entity::getUniqueId).collect(Collectors.toSet());
        List<UUID> remove = new ArrayList<>();
        for (Map.Entry<UUID, DisplayEntry> mapEntry : displays.entrySet()) {
            UUID uuid = mapEntry.getKey();
            DisplayEntry entry = mapEntry.getValue();
            if (!wantedIds.contains(uuid) || entry.target == null || !entry.target.isValid() || entry.target.isDead()) {
                removeDisplay(entry);
                remove.add(uuid);
            }
        }
        for (UUID uuid : remove) displays.remove(uuid);

        combatUntilTick.entrySet().removeIf(e -> e.getValue() < tickCounter);

        if (shouldRefreshNametagTeams()) {
            syncHiddenNametagTeams();
        }
    }

    private Set<LivingEntity> collectWantedEntities() {
        DisplayMode mode = currentMode();
        double scanRadius = getConfig().getDouble("performance.scan-radius", 48.0);
        boolean showNearby = getConfig().getBoolean("visibility.show-nearby-entities", true);
        Set<LivingEntity> result = new LinkedHashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (disabledPlayers.contains(player.getUniqueId())) continue;

            if (showNearby || mode == DisplayMode.ALWAYS) {
                collectAroundPlayer(player, scanRadius, result);
            }

            if (!showNearby) {
                if (mode == DisplayMode.LOOK) {
                    getLookTarget(player).ifPresent(result::add);
                } else if (mode == DisplayMode.COMBAT) {
                    collectCombatEntitiesNear(player, scanRadius, result);
                } else if (mode == DisplayMode.COMBAT_LOOK) {
                    getLookTarget(player).ifPresent(result::add);
                    collectCombatEntitiesNear(player, scanRadius, result);
                }
            } else {
                collectCombatEntitiesNear(player, scanRadius, result);
            }
        }
        return result;
    }

    private void collectAroundPlayer(Player player, double radius, Set<LivingEntity> result) {
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity living && isValidTarget(living) && canPlayerSeeEntity(player, living)) {
                result.add(living);
            }
        }
        if (getConfig().getBoolean("players.show-self", false) && isValidTarget(player)) result.add(player);
    }

    private void collectCombatEntitiesNear(Player player, double radius, Set<LivingEntity> result) {
        double radiusSq = radius * radius;
        for (UUID uuid : combatUntilTick.keySet()) {
            Entity entity = findEntity(uuid);
            if (!(entity instanceof LivingEntity living)) continue;
            if (!isValidTarget(living)) continue;
            if (!living.getWorld().equals(player.getWorld())) continue;
            if (living.getLocation().distanceSquared(player.getLocation()) > radiusSq) continue;
            if (canPlayerSeeEntity(player, living)) result.add(living);
        }
    }

    private Optional<LivingEntity> getLookTarget(Player player) {
        double distance = getConfig().getDouble("look.max-distance", 32.0);
        double raySize = getConfig().getDouble("look.ray-size", 0.75);
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult ray = player.getWorld().rayTraceEntities(eye, direction, distance, raySize, entity -> {
            if (!(entity instanceof LivingEntity living)) return false;
            if (entity.getUniqueId().equals(player.getUniqueId()) && !getConfig().getBoolean("players.show-self", false)) return false;
            return isValidTarget(living) && canPlayerSeeEntity(player, living);
        });
        if (ray == null || !(ray.getHitEntity() instanceof LivingEntity living)) return Optional.empty();
        return Optional.of(living);
    }

    private boolean canPlayerSeeEntity(Player player, LivingEntity entity) {
        if (disabledPlayers.contains(player.getUniqueId())) return false;
        if (!entity.getWorld().equals(player.getWorld())) return false;
        if (entity instanceof Player target) {
            if (!getConfig().getBoolean("players.enabled", true)) return false;
            if (target.getUniqueId().equals(player.getUniqueId())) return getConfig().getBoolean("players.show-self", false);
            if (!getConfig().getBoolean("players.show-others", true)) return false;
        }
        return true;
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (entity == null || !entity.isValid() || entity.isDead()) return false;
        if (entity instanceof ArmorStand && !getConfig().getBoolean("visibility.show-armor-stands", false)) return false;
        if (entity instanceof Player) return getConfig().getBoolean("visibility.show-players", true) && getConfig().getBoolean("players.enabled", true);
        if (entity instanceof Villager && !getConfig().getBoolean("visibility.show-villagers", true)) return false;
        if (entity.isInvisible() && !getConfig().getBoolean("visibility.show-invisible-entities", false)) return false;
        if (getConfig().getBoolean("visibility.show-named-only", false) && !hasVisibleOrStoredCustomName(entity)) return false;

        List<String> ignored = getConfig().getStringList("visibility.ignored-entity-types");
        if (ignored.stream().anyMatch(s -> s.equalsIgnoreCase(entity.getType().name()))) return false;

        boolean hostile = entity instanceof Monster;
        if (hostile && !getConfig().getBoolean("visibility.show-hostile-mobs", true)) return false;
        if (!hostile && !(entity instanceof Player) && !(entity instanceof Villager) && !getConfig().getBoolean("visibility.show-passive-mobs", true)) return false;
        return true;
    }

    private DisplayEntry createDisplay(LivingEntity entity) {
        try {
            Location loc = displayLocation(entity);
            TextDisplay display = (TextDisplay) entity.getWorld().spawnEntity(loc, EntityType.TEXT_DISPLAY);
            display.getPersistentDataContainer().set(displayMarkerKey, PersistentDataType.BYTE, (byte) 1);
            display.setPersistent(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setBillboard(resolveBillboard());
            display.setShadowed(getConfig().getBoolean("display.shadowed", true));
            display.setSeeThrough(getConfig().getBoolean("display.see-through", false));
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            applyTextDisplayBackground(display);
            applySmoothDisplaySettings(display);

            HiddenNameState hiddenNameState = hideVanillaCustomNameIfNeeded(entity);
            return new DisplayEntry(entity, display, hiddenNameState);
        } catch (Throwable t) {
            getLogger().warning("TextDisplay olusturulamadi: " + t.getMessage());
            return null;
        }
    }

    private Display.Billboard resolveBillboard() {
        String value = getConfig().getString("display.billboard", "CENTER");
        try { return Display.Billboard.valueOf(value.toUpperCase(Locale.ENGLISH)); }
        catch (Exception ignored) { return Display.Billboard.CENTER; }
    }

    private HiddenNameState hideVanillaCustomNameIfNeeded(LivingEntity entity) {
        if (!getConfig().getBoolean("display.hide-vanilla-custom-name-while-displayed", true)) return null;
        if (entity instanceof Player && !getConfig().getBoolean("display.hide-player-nametags", false)) return null;

        String customName = readStoredOrCurrentCustomName(entity);
        boolean hasCustomName = customName != null && !customName.isBlank();
        boolean shouldHidePlayers = entity instanceof Player && getConfig().getBoolean("display.hide-player-nametags", false);
        if (!hasCustomName && !shouldHidePlayers) return null;

        HiddenNameState state = new HiddenNameState(customName, entity.isCustomNameVisible());
        try {
            entity.setCustomNameVisible(false);
            storeHiddenNameState(entity, state);
            if (hasCustomName && getConfig().getBoolean("display.clear-custom-name-to-block-crosshair-tag", true)) {
                entity.setCustomName(null);
                state.customNameCleared = true;
            }
            addEntityToHiddenNametagTeams(entity);
        } catch (Throwable ignored) {
        }
        return state;
    }

    private void keepVanillaCustomNameHidden(DisplayEntry entry) {
        if (entry == null || entry.target == null || !entry.target.isValid() || entry.target.isDead()) return;
        if (entry.hiddenNameState == null) return;
        if (!getConfig().getBoolean("display.hide-vanilla-custom-name-while-displayed", true)) return;
        if (entry.target instanceof Player && !getConfig().getBoolean("display.hide-player-nametags", false)) return;

        try {
            String current = entry.target.getCustomName();
            if (current != null && !current.isBlank()) {
                if (entry.hiddenNameState.originalCustomName == null || !current.equals(entry.hiddenNameState.originalCustomName)) {
                    entry.hiddenNameState.originalCustomName = current;
                    storeHiddenNameState(entry.target, entry.hiddenNameState);
                }
                if (getConfig().getBoolean("display.clear-custom-name-to-block-crosshair-tag", true)) {
                    entry.target.setCustomName(null);
                    entry.hiddenNameState.customNameCleared = true;
                }
            }
            entry.target.setCustomNameVisible(false);
        } catch (Throwable ignored) {
        }
    }

    private void restoreVanillaCustomName(DisplayEntry entry) {
        if (entry == null || entry.target == null) return;
        restoreVanillaCustomName(entry.target, entry.hiddenNameState);
        removeEntityFromHiddenNametagTeams(entry.target);
    }

    private void restoreVanillaCustomName(LivingEntity entity, HiddenNameState state) {
        if (entity == null || !entity.isValid() || entity.isDead()) return;
        if (entity instanceof Player && !getConfig().getBoolean("display.hide-player-nametags", false)) return;

        try {
            HiddenNameState resolved = state != null ? state : readHiddenNameState(entity);
            if (resolved != null && resolved.originalCustomName != null && !resolved.originalCustomName.isBlank()) {
                entity.setCustomName(resolved.originalCustomName);
            }
            if (resolved != null && resolved.originalCustomNameVisible != null) {
                entity.setCustomNameVisible(resolved.originalCustomNameVisible);
            }
            clearHiddenNameState(entity);
        } catch (Throwable ignored) {
        }
    }

    private boolean hasVisibleOrStoredCustomName(LivingEntity entity) {
        String name = readStoredOrCurrentCustomName(entity);
        return name != null && !name.isBlank();
    }

    private String readStoredOrCurrentCustomName(LivingEntity entity) {
        if (entity == null) return null;
        try {
            String stored = entity.getPersistentDataContainer().get(hiddenOriginalNameKey, PersistentDataType.STRING);
            if (stored != null && !stored.isBlank()) return stored;
        } catch (Throwable ignored) {
        }
        return entity.getCustomName();
    }

    private void storeHiddenNameState(LivingEntity entity, HiddenNameState state) {
        if (entity == null || state == null) return;
        try {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            pdc.set(hiddenNameActiveKey, PersistentDataType.BYTE, (byte) 1);
            if (state.originalCustomName != null && !state.originalCustomName.isBlank()) {
                pdc.set(hiddenOriginalNameKey, PersistentDataType.STRING, state.originalCustomName);
            }
            pdc.set(hiddenOriginalVisibleKey, PersistentDataType.BYTE, (byte) (Boolean.TRUE.equals(state.originalCustomNameVisible) ? 1 : 0));
        } catch (Throwable ignored) {
        }
    }

    private HiddenNameState readHiddenNameState(LivingEntity entity) {
        if (entity == null) return null;
        try {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            Byte active = pdc.get(hiddenNameActiveKey, PersistentDataType.BYTE);
            if (active == null || active != (byte) 1) return null;
            String name = pdc.get(hiddenOriginalNameKey, PersistentDataType.STRING);
            Byte visible = pdc.get(hiddenOriginalVisibleKey, PersistentDataType.BYTE);
            return new HiddenNameState(name, visible != null && visible == (byte) 1);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void clearHiddenNameState(LivingEntity entity) {
        if (entity == null) return;
        try {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            pdc.remove(hiddenNameActiveKey);
            pdc.remove(hiddenOriginalNameKey);
            pdc.remove(hiddenOriginalVisibleKey);
        } catch (Throwable ignored) {
        }
    }

    private void restoreHiddenNamesFromPreviousSession() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                HiddenNameState state = readHiddenNameState(entity);
                if (state == null) continue;
                restoreVanillaCustomName(entity, state);
            }
        }
    }

    private void syncHiddenNametagTeams() {
        for (DisplayEntry entry : displays.values()) {
            if (entry == null || entry.target == null || !entry.target.isValid() || entry.target.isDead()) continue;
            if (entry.hiddenNameState == null) continue;
            addEntityToHiddenNametagTeams(entry.target);
        }
    }

    private void addEntityToHiddenNametagTeams(Entity entity) {
        if (entity == null) return;
        if (!getConfig().getBoolean("display.hide-vanilla-nametag-with-scoreboard-team", true)) return;
        String entry = nametagTeamEntry(entity);
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) addEntryToHiddenTeam(manager.getMainScoreboard(), entry);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            addEntryToHiddenTeam(viewer.getScoreboard(), entry);
        }
    }

    private void removeEntityFromHiddenNametagTeams(Entity entity) {
        if (entity == null) return;
        String entry = nametagTeamEntry(entity);
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) removeEntryFromHiddenTeam(manager.getMainScoreboard(), entry);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            removeEntryFromHiddenTeam(viewer.getScoreboard(), entry);
        }
    }

    private String nametagTeamEntry(Entity entity) {
        if (entity instanceof Player player) return player.getName();
        return entity.getUniqueId().toString();
    }

    private void addEntryToHiddenTeam(Scoreboard scoreboard, String entry) {
        if (scoreboard == null || entry == null || entry.isBlank()) return;
        try {
            Team team = scoreboard.getTeam(HIDDEN_NAMETAG_TEAM);
            if (team == null) team = scoreboard.registerNewTeam(HIDDEN_NAMETAG_TEAM);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            if (!team.hasEntry(entry)) team.addEntry(entry);
        } catch (Throwable ignored) {
        }
    }

    private void removeEntryFromHiddenTeam(Scoreboard scoreboard, String entry) {
        if (scoreboard == null || entry == null || entry.isBlank()) return;
        try {
            Team team = scoreboard.getTeam(HIDDEN_NAMETAG_TEAM);
            if (team != null && team.hasEntry(entry)) team.removeEntry(entry);
        } catch (Throwable ignored) {
        }
    }

    private void forceRefreshAllDisplayVisibility() {
        for (DisplayEntry entry : displays.values()) {
            if (entry == null || entry.display == null || !entry.display.isValid()) continue;
            updateViewerVisibility(entry.display);
            entry.lastVisibilityRefreshTick = tickCounter;
        }
    }

    private void applyTextDisplayBackground(TextDisplay display) {
        // TextDisplay varsayılan arka planı bazı sürümlerde koyu/opak görünebiliyor.
        // Bu ayarlar yazının arkasını tamamen şeffaf yapar, yazı opak kalır.
        boolean forceTransparent = getConfig().getBoolean("display.force-transparent-background", true);
        if (forceTransparent || getConfig().getBoolean("display.transparent-background", true)) {
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        } else {
            display.setDefaultBackground(false);
            display.setBackgroundColor(readBackgroundColor());
        }
        display.setTextOpacity(readTextOpacity());
    }

    private Color readBackgroundColor() {
        String raw = getConfig().getString("display.background-color", "#66000000");
        if (raw == null || raw.isBlank()) return Color.fromARGB(102, 0, 0, 0);
        raw = raw.trim();
        try {
            if (raw.startsWith("#")) raw = raw.substring(1);
            if (raw.length() == 6) {
                int rgb = Integer.parseInt(raw, 16);
                int alpha = Math.max(0, Math.min(255, getConfig().getInt("display.background-alpha", 102)));
                return Color.fromARGB(alpha, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            }
            if (raw.length() == 8) {
                long argb = Long.parseLong(raw, 16);
                return Color.fromARGB((int) ((argb >> 24) & 0xFF), (int) ((argb >> 16) & 0xFF), (int) ((argb >> 8) & 0xFF), (int) (argb & 0xFF));
            }
        } catch (Throwable ignored) {
        }
        return Color.fromARGB(102, 0, 0, 0);
    }

    private byte readTextOpacity() {
        int opacity = Math.max(0, Math.min(255, getConfig().getInt("display.text-opacity", 255)));
        return (byte) opacity;
    }

    private void applySmoothDisplaySettings(TextDisplay display) {
        if (!getConfig().getBoolean("display.smooth-movement", true)) return;
        int interpolationDuration = getConfig().getInt("display.interpolation-duration", 2);
        int teleportDuration = getConfig().getInt("display.teleport-duration", 2);
        if (getConfig().getBoolean("display.force-minimum-smoothing", true)) {
            interpolationDuration = Math.max(2, interpolationDuration);
            teleportDuration = Math.max(2, teleportDuration);
        }
        invokeDisplayInt(display, "setInterpolationDelay", getConfig().getInt("display.interpolation-delay", 0));
        invokeDisplayInt(display, "setInterpolationDuration", interpolationDuration);
        invokeDisplayInt(display, "setTeleportDuration", teleportDuration);
    }

    private void invokeDisplayInt(TextDisplay display, String methodName, int value) {
        try {
            Method method = display.getClass().getMethod(methodName, int.class);
            method.invoke(display, value);
        } catch (Throwable ignored) {
        }
    }

    private void updateSingleDisplay(LivingEntity entity, DisplayEntry entry) {
        HealthData health = getHealthData(entity);
        if (health.maxHealth <= 0) return;

        Location loc = displayLocation(entity);
        if (!entry.display.getWorld().equals(entity.getWorld())) {
            removeDisplay(entry);
            displays.remove(entity.getUniqueId());
            return;
        }
        applySmoothDisplaySettings(entry.display);
        keepVanillaCustomNameHidden(entry);
        applyTextDisplayBackground(entry.display);
        entry.display.teleport(loc);

        String text = buildDisplayText(entity, health, entry);
        if (!text.equals(entry.lastText)) {
            entry.display.setText(text);
            entry.lastText = text;
        }
        if (shouldRefreshDisplayVisibility(entry)) {
            updateViewerVisibility(entry.display);
            entry.lastVisibilityRefreshTick = tickCounter;
        }
    }

    private boolean shouldRefreshDisplayVisibility(DisplayEntry entry) {
        long interval = Math.max(1L, getConfig().getLong("performance.visibility-refresh-interval-ticks", 10L));
        return entry.lastVisibilityRefreshTick < 0 || tickCounter - entry.lastVisibilityRefreshTick >= interval;
    }

    private boolean shouldRefreshNametagTeams() {
        long interval = Math.max(1L, getConfig().getLong("performance.nametag-team-refresh-interval-ticks", 10L));
        return tickCounter % interval == 0;
    }

    private void updateViewerVisibility(Entity display) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (disabledPlayers.contains(player.getUniqueId())) {
                player.hideEntity(this, display);
            } else {
                player.showEntity(this, display);
            }
        }
    }

    private Location displayLocation(LivingEntity entity) {
        // TextDisplay konumu metnin merkezine göre hesaplandığı için iki satırlı yazılarda
        // düşük offset yazının kafaya/gövdeye fazla yakın görünmesine neden olur.
        double offset = getConfig().getDouble("display.height-offset", 1.05);
        return entity.getLocation().add(0, entity.getHeight() + offset, 0);
    }

    private String buildDisplayText(LivingEntity entity, HealthData health, DisplayEntry entry) {
        String name = entityName(entity, entry);
        String nameLine = color(getConfig().getString("format.name-line", "&f&l%name%")).replace("%name%", name);
        String healthLine = color(getConfig().getString("format.health-line", "&cCan: %bar% &e%health%&7/&f%max_health% &eCan"));
        healthLine = healthLine
                .replace("%bar%", healthBar(health.currentHealth, health.maxHealth))
                .replace("%health%", formatHealth(health.currentHealth))
                .replace("%max_health%", formatHealth(health.maxHealth))
                .replace("%percent%", String.valueOf((int) Math.round(100.0 * health.currentHealth / health.maxHealth)));
        return nameLine + "\n" + healthLine;
    }

    private String healthBar(double health, double max) {
        int length = Math.max(1, getConfig().getInt("format.heart-bar-length", 10));
        double ratio = max <= 0 ? 0 : Math.max(0.0, Math.min(1.0, health / max));
        int filled = (int) Math.ceil(ratio * length);
        if (health <= 0 && !getConfig().getBoolean("format.show-zero-health", false)) filled = 0;
        String filledChar = getConfig().getString("format.heart-filled", "♥");
        String emptyChar = getConfig().getString("format.heart-empty", "♡");
        String filledColor = colorForRatio(ratio);
        String emptyColor = color(getConfig().getString("colors.empty", "&7"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(i < filled ? filledColor : emptyColor).append(i < filled ? filledChar : emptyChar);
        }
        return sb.toString();
    }

    private String colorForRatio(double ratio) {
        if (ratio >= 0.75) return color(getConfig().getString("colors.high", "&a"));
        if (ratio >= 0.50) return color(getConfig().getString("colors.medium", "&e"));
        if (ratio >= 0.25) return color(getConfig().getString("colors.low", "&6"));
        return color(getConfig().getString("colors.critical", "&c"));
    }

    private String formatHealth(double value) {
        if (getConfig().getBoolean("format.round-health", true)) {
            return String.valueOf((int) Math.ceil(Math.max(0, value)));
        }
        return oneDecimal.format(Math.max(0, value));
    }

    private String entityName(LivingEntity entity, DisplayEntry entry) {
        if (entity instanceof Player player) return player.getName();
        String custom = null;
        if (entry != null && entry.hiddenNameState != null) custom = entry.hiddenNameState.originalCustomName;
        if (custom == null || custom.isBlank()) custom = readStoredOrCurrentCustomName(entity);
        if (custom != null && !custom.isBlank()) return color(custom);
        if (getConfig().getBoolean("mob-names.use-turkish-names", true)) {
            String configured = getConfig().getString("mob-names.names." + entity.getType().name());
            if (configured != null && !configured.isBlank()) return color(configured);
        }
        String raw = entity.getType().name().toLowerCase(Locale.ENGLISH).replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split(" ")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private HealthData getHealthData(LivingEntity entity) {
        if (getConfig().getBoolean("boss-integration.enabled", true)) {
            Optional<HealthData> bossHealth = readBeyHodBossHealth(entity);
            if (bossHealth.isPresent()) return bossHealth.get();

            Optional<HealthData> pdcHealth = readPdcHealth(entity);
            if (pdcHealth.isPresent()) return pdcHealth.get();

            Optional<HealthData> metadataHealth = readMetadataHealth(entity);
            if (metadataHealth.isPresent()) return metadataHealth.get();
        }

        double current = Math.max(0, entity.getHealth());
        double max = 20.0;
        Attribute attr = attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH");
        if (attr != null) {
            AttributeInstance instance = entity.getAttribute(attr);
            if (instance != null) max = Math.max(1.0, instance.getValue());
        }
        return new HealthData(current, max);
    }

    private Optional<HealthData> readBeyHodBossHealth(LivingEntity entity) {
        if (!getConfig().getBoolean("boss-integration.beyhodboss-support", true)) return Optional.empty();
        if (tickCounter >= nextBossReflectionTryTick && (bossPlugin == null || !bossPlugin.isEnabled())) {
            setupBossReflection();
            nextBossReflectionTryTick = tickCounter + 100L;
        }
        if (bossPlugin == null || bossStateField == null) return Optional.empty();
        try {
            Object state = bossStateField.get(bossPlugin);
            if (state == null) return Optional.empty();
            if (bossEntityField == null || bossHpField == null || bossMaxHpField == null) {
                bossEntityField = findField(state.getClass(), "entity");
                bossHpField = findField(state.getClass(), "hp");
                bossMaxHpField = findField(state.getClass(), "maxHp", "maxHP", "maxHealth");
            }
            if (bossEntityField == null || bossHpField == null || bossMaxHpField == null) return Optional.empty();
            Object bossEntity = bossEntityField.get(state);
            if (!(bossEntity instanceof Entity be)) return Optional.empty();
            if (!be.getUniqueId().equals(entity.getUniqueId())) return Optional.empty();
            double hp = ((Number) bossHpField.get(state)).doubleValue();
            double max = ((Number) bossMaxHpField.get(state)).doubleValue();
            if (max <= 0) return Optional.empty();
            return Optional.of(new HealthData(Math.max(0, hp), max));
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private void setupBossReflection() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("BeyHodBoss");
            if (plugin == null || !plugin.isEnabled()) return;
            bossPlugin = plugin;
            bossStateField = findField(plugin.getClass(), "state");
            bossEntityField = null;
            bossHpField = null;
            bossMaxHpField = null;
        } catch (Throwable ignored) {
            bossPlugin = null;
            bossStateField = null;
        }
    }

    private Field findField(Class<?> type, String... names) {
        for (String name : names) {
            Class<?> current = type;
            while (current != null) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                }
            }
        }
        return null;
    }

    private Optional<HealthData> readPdcHealth(LivingEntity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        List<String> currentKeys = getConfig().getStringList("boss-integration.pdc-health-keys.current");
        List<String> maxKeys = getConfig().getStringList("boss-integration.pdc-health-keys.max");
        Double current = readFirstNumber(pdc, currentKeys);
        Double max = readFirstNumber(pdc, maxKeys);
        if (current == null || max == null || max <= 0) return Optional.empty();
        return Optional.of(new HealthData(Math.max(0, current), max));
    }

    private Double readFirstNumber(PersistentDataContainer pdc, List<String> keyStrings) {
        for (String keyString : keyStrings) {
            NamespacedKey key = NamespacedKey.fromString(keyString);
            if (key == null) continue;
            Double d = pdc.get(key, PersistentDataType.DOUBLE);
            if (d != null) return d;
            Integer i = pdc.get(key, PersistentDataType.INTEGER);
            if (i != null) return i.doubleValue();
            Long l = pdc.get(key, PersistentDataType.LONG);
            if (l != null) return l.doubleValue();
            Float f = pdc.get(key, PersistentDataType.FLOAT);
            if (f != null) return f.doubleValue();
            String s = pdc.get(key, PersistentDataType.STRING);
            if (s != null) {
                try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private Optional<HealthData> readMetadataHealth(LivingEntity entity) {
        Double hp = readMetadataNumber(entity, "bhboss_hp", "hp", "custom_hp");
        Double max = readMetadataNumber(entity, "bhboss_maxhp", "maxhp", "custom_maxhp");
        if (hp == null || max == null || max <= 0) return Optional.empty();
        return Optional.of(new HealthData(hp, max));
    }

    private Double readMetadataNumber(Entity entity, String... keys) {
        for (String key : keys) {
            if (!entity.hasMetadata(key)) continue;
            for (MetadataValue value : entity.getMetadata(key)) {
                try { return value.asDouble(); } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private Attribute attribute(String... names) {
        for (String name : names) {
            try { return Attribute.valueOf(name); } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) return;
        markCombat(living);
        if (getConfig().getBoolean("performance.instant-on-damage", true)) {
            showNowNextTick(living);
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && getConfig().getBoolean("combat.mark-attacker-too", true)) {
            Entity damager = unwrapDamager(byEntity.getDamager());
            if (damager instanceof LivingEntity attacker) {
                markCombat(attacker);
                if (getConfig().getBoolean("performance.instant-on-damage", true)) {
                    showNowNextTick(attacker);
                }
            }
        }
    }

    private void showNowNextTick(LivingEntity entity) {
        if (entity == null || !entity.isValid() || entity.isDead()) return;
        Bukkit.getScheduler().runTask(this, () -> {
            if (!getConfig().getBoolean("general.enabled", true)) return;
            if (!isValidTarget(entity) || entity.isDead() || !entity.isValid()) return;
            DisplayEntry entry = displays.get(entity.getUniqueId());
            if (entry == null || entry.display == null || !entry.display.isValid()) {
                entry = createDisplay(entity);
                if (entry == null) return;
                displays.put(entity.getUniqueId(), entry);
            }
            updateSingleDisplay(entity, entry);
        });
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        combatUntilTick.remove(uuid);
        DisplayEntry entry = displays.remove(uuid);
        if (entry != null) removeDisplay(entry);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, this::syncHiddenNametagTeams, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        disabledPlayers.remove(event.getPlayer().getUniqueId());
    }

    private Entity unwrapDamager(Entity damager) {
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Entity entity) return entity;
        }
        return damager;
    }

    private void markCombat(LivingEntity entity) {
        long duration = Math.max(20, getConfig().getLong("combat.display-duration-ticks", 100));
        combatUntilTick.put(entity.getUniqueId(), tickCounter + duration);
    }

    private Entity findEntity(UUID uuid) {
        return Bukkit.getEntity(uuid);
    }

    private double nearestPlayerDistanceSquared(Entity entity) {
        double best = Double.MAX_VALUE;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(entity.getWorld())) continue;
            best = Math.min(best, player.getLocation().distanceSquared(entity.getLocation()));
        }
        return best;
    }

    private void removeDisplay(DisplayEntry entry) {
        restoreVanillaCustomName(entry);
        if (entry != null && entry.display != null && entry.display.isValid()) entry.display.remove();
    }

    private void removeAllDisplays() {
        for (DisplayEntry entry : displays.values()) removeDisplay(entry);
        displays.clear();
    }

    private void cleanupExistingDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
                Byte marker = entity.getPersistentDataContainer().get(displayMarkerKey, PersistentDataType.BYTE);
                if (marker != null && marker == (byte) 1) entity.remove();
            }
        }
    }

    private DisplayMode currentMode() {
        String raw = getConfig().getString("general.default-mode", "COMBAT_LOOK");
        try { return DisplayMode.valueOf(raw.toUpperCase(Locale.ENGLISH)); }
        catch (Exception ignored) { return DisplayMode.COMBAT_LOOK; }
    }

    private String color(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ENGLISH);
        boolean adminCommand = cmd.equals("hdadmin");
        if (adminCommand && !sender.hasPermission("beyhodhealth.admin")) {
            sender.sendMessage(PREFIX + "§cBu komut için yetkin yok.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, adminCommand);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ENGLISH);
        if (sub.equals("toggle")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(PREFIX + "§cBu komutu sadece oyuncular kullanabilir.");
                return true;
            }
            if (disabledPlayers.remove(player.getUniqueId())) {
                sender.sendMessage(PREFIX + "§aCan göstergesi açıldı.");
            } else {
                disabledPlayers.add(player.getUniqueId());
                sender.sendMessage(PREFIX + "§cCan göstergesi kapatıldı.");
            }
            forceRefreshAllDisplayVisibility();
            return true;
        }

        if (sub.equals("mode")) {
            if (!sender.hasPermission("beyhodhealth.admin")) {
                sender.sendMessage(PREFIX + "§cBu komut için yetkin yok.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(PREFIX + "§eKullanım: §f/hd mode <always|look|combat|combat_look>");
                return true;
            }
            String value = args[1].toUpperCase(Locale.ENGLISH);
            if (value.equals("COMBATLOOK")) value = "COMBAT_LOOK";
            try {
                DisplayMode.valueOf(value);
                getConfig().set("general.default-mode", value);
                saveConfig();
                removeAllDisplays();
                sender.sendMessage(PREFIX + "§aMod değiştirildi: §f" + value);
            } catch (Exception ex) {
                sender.sendMessage(PREFIX + "§cGeçersiz mod. §7always, look, combat, combat_look");
            }
            return true;
        }

        if (sub.equals("reload")) {
            if (!sender.hasPermission("beyhodhealth.admin")) {
                sender.sendMessage(PREFIX + "§cBu komut için yetkin yok.");
                return true;
            }
            reloadConfig();
            removeAllDisplays();
            cleanupExistingDisplays();
            restartTask();
            sender.sendMessage(PREFIX + "§aConfig yeniden yüklendi.");
            return true;
        }

        if (sub.equals("cleanup")) {
            if (!sender.hasPermission("beyhodhealth.admin")) {
                sender.sendMessage(PREFIX + "§cBu komut için yetkin yok.");
                return true;
            }
            removeAllDisplays();
            cleanupExistingDisplays();
            sender.sendMessage(PREFIX + "§aTüm health display entityleri temizlendi.");
            return true;
        }

        if (sub.equals("debug")) {
            sender.sendMessage(PREFIX + "§7Aktif display: §f" + displays.size());
            sender.sendMessage(PREFIX + "§7Combat cache: §f" + combatUntilTick.size());
            sender.sendMessage(PREFIX + "§7Mod: §f" + currentMode().name());
            sender.sendMessage(PREFIX + "§7Update interval: §f" + updateIntervalTicks() + " tick");
            sender.sendMessage(PREFIX + "§7Yakındaki tüm canlılar: §f" + getConfig().getBoolean("visibility.show-nearby-entities", true));
            sender.sendMessage(PREFIX + "§7Scan radius: §f" + getConfig().getDouble("performance.scan-radius", 48.0));
            sender.sendMessage(PREFIX + "§7Vanilla nametag gizleme: §f" + getConfig().getBoolean("display.hide-vanilla-custom-name-while-displayed", true));
            sender.sendMessage(PREFIX + "§7BeyHodBoss hook: §f" + (bossPlugin != null && bossPlugin.isEnabled()));
            return true;
        }

        sendHelp(sender, adminCommand);
        return true;
    }

    private void sendHelp(CommandSender sender, boolean admin) {
        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage("§c§lBeyHodHealthDisplay §7Komutları");
        sender.sendMessage("§e/hd toggle §7- Kendi can göstergeni aç/kapat.");
        sender.sendMessage("§e/hd debug §7- Aktif gösterge durumunu gör.");
        if (sender.hasPermission("beyhodhealth.admin") || admin) {
            sender.sendMessage("§e/hd mode <always|look|combat|combat_look> §7- Gösterim modu.");
            sender.sendMessage("§e/hd reload §7- Configi yeniler.");
            sender.sendMessage("§e/hd cleanup §7- Takılı display entitylerini temizler.");
            sender.sendMessage("§e/hdadmin reload/cleanup/debug §7- Admin alias.");
        }
        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(Arrays.asList("toggle", "debug", "help"));
            if (sender.hasPermission("beyhodhealth.admin")) base.addAll(Arrays.asList("mode", "reload", "cleanup"));
            return filter(base, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode") && sender.hasPermission("beyhodhealth.admin")) {
            return filter(Arrays.asList("always", "look", "combat", "combat_look"), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ENGLISH);
        return values.stream().filter(v -> v.toLowerCase(Locale.ENGLISH).startsWith(p)).collect(Collectors.toList());
    }

    private enum DisplayMode {
        ALWAYS,
        LOOK,
        COMBAT,
        COMBAT_LOOK
    }

    private static final class DisplayEntry {
        final LivingEntity target;
        final TextDisplay display;
        final HiddenNameState hiddenNameState;
        String lastText = "";
        long lastVisibilityRefreshTick = -1L;

        DisplayEntry(LivingEntity target, TextDisplay display, HiddenNameState hiddenNameState) {
            this.target = target;
            this.display = display;
            this.hiddenNameState = hiddenNameState;
        }
    }

    private static final class HiddenNameState {
        String originalCustomName;
        final Boolean originalCustomNameVisible;
        boolean customNameCleared;

        HiddenNameState(String originalCustomName, Boolean originalCustomNameVisible) {
            this.originalCustomName = originalCustomName;
            this.originalCustomNameVisible = originalCustomNameVisible;
        }
    }

    private static final class HealthData {
        final double currentHealth;
        final double maxHealth;

        HealthData(double currentHealth, double maxHealth) {
            this.currentHealth = currentHealth;
            this.maxHealth = maxHealth;
        }
    }
}
