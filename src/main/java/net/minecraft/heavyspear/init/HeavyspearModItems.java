/*
 *    MCreator note: This file will be REGENERATED on each build.
 */
package net.minecraft.heavyspear.init;

import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredItem;

import net.minecraft.heavyspear.item.HeavySpearItem;
import net.minecraft.heavyspear.HeavyspearMod;

public class HeavyspearModItems {
	public static final DeferredRegister.Items REGISTRY = DeferredRegister.createItems(HeavyspearMod.MODID);
	public static final DeferredItem<Item> HEAVY_SPEAR;
    static {
        HEAVY_SPEAR = REGISTRY.register("heavy_spear",
                id -> new HeavySpearItem(new Item.Properties()
                        .durability(500)
                        .rarity(Rarity.EPIC)
                        .fireResistant()));
    }
	// Start of user code block custom items
	// End of user code block custom items

}