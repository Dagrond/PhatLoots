package com.codisimus.plugins.phatloots.events;

import com.codisimus.plugins.phatloots.PhatLoot;
import com.codisimus.plugins.phatloots.loot.LootBundle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Called when a mob is killed and drops custom loot
 *
 * @author Codisimus
 */
public class MobDropLootEvent extends LootEvent {
    private static final HandlerList handlers = new HandlerList();

    private final LivingEntity mob;
    private final Player killer;
    private PhatLoot phatLoot;

    /**
     * Creates a new event with the given data
     *
     * @param mob The mob that was killed
     * @param killer The Player who killed the mob or null if the mob died of natural causes
     * @param lootBundle The bundle of loot dropped by the mob
     */
    public MobDropLootEvent(LivingEntity mob, Player killer, PhatLoot phatLoot, LootBundle lootBundle) {
        this.mob = mob;
        this.killer = killer;
        this.phatLoot = phatLoot;
        this.lootBundle = lootBundle;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    /**
     * Returns the mob that was killed
     *
     * @return The mob that was killed
     */
    public LivingEntity getMob() {
        return mob;
    }

    /**
     * Returns the Player who killed the mob
     *
     * @return The Player who killed the mob or null if the mob died of natural causes
     */
    public Player getKiller() {
        return killer;
    }

    /**
     * Returns the PhatLoot that provided the loot
     *
     * @return The PhatLoot that provided the loot
     */
    public PhatLoot getPhatLoot() {
        return phatLoot;
    }
}
