package net.minecraft.heavyspear.init;

import net.minecraft.heavyspear.HeavyspearMod;
import net.minecraft.heavyspear.item.HeavySpearItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class HeavyspearModItems {
    public static final DeferredRegister.Items REGISTRY = DeferredRegister.createItems(HeavyspearMod.MODID);

    public static final DeferredItem<Item> HEAVY_SPEAR = REGISTRY.registerItem(
            "heavy_spear",
            HeavySpearItem::new);
}
