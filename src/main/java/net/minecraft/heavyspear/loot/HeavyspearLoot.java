package net.minecraft.heavyspear.loot;

import net.minecraft.heavyspear.HeavyspearMod;
import net.minecraft.heavyspear.init.HeavyspearModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraftforge.event.LootTableLoadEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 末地城/末地船宝藏箱低概率掉落重矛。
 *
 * 末地船和末地城的宝藏箱共用 minecraft:chests/end_city_treasure，
 * 因此这里按战利品表注入会同时影响两者。
 */
@Mod.EventBusSubscriber(modid = HeavyspearMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HeavyspearLoot {
    private static final ResourceLocation END_CITY_TREASURE = BuiltInLootTables.END_CITY_TREASURE;
    private static final String POOL_NAME = "heavyspear_end_ship_bonus";

    private HeavyspearLoot() {
    }

    @SubscribeEvent
    public static void onLootTableLoad(LootTableLoadEvent event) {
        if (!END_CITY_TREASURE.equals(event.getName())) {
            return;
        }
        // 避免 /reload 时重复添加同一个 pool。
        if (event.getTable().getPool(POOL_NAME) != null) {
            return;
        }

        // 空权重 99 + 重矛权重 1 = 每箱约 1%。
        event.getTable().addPool(LootPool.lootPool()
                .name(POOL_NAME)
                .setRolls(ConstantValue.exactly(1.0F))
                .add(EmptyLootItem.emptyItem().setWeight(99))
                .add(LootItem.lootTableItem(HeavyspearModItems.HEAVY_SPEAR.get()).setWeight(1))
                .build());
    }
}
