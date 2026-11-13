package net.minecraft.heavyspear.init;

import net.minecraft.heavyspear.HeavyspearMod;
import net.minecraft.heavyspear.item.HeavySpearItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class HeavyspearModItems {
    public static final DeferredRegister<Item> REGISTRY =
            DeferredRegister.create(ForgeRegistries.ITEMS, HeavyspearMod.MODID);

    public static final RegistryObject<Item> HEAVY_SPEAR = REGISTRY.register("heavy_spear",
            () -> new HeavySpearItem(new Item.Properties()
                    .durability(500)
                    .rarity(Rarity.EPIC)
                    .fireResistant()));
}
