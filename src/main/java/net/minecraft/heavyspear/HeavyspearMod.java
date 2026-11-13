package net.minecraft.heavyspear;

import net.minecraft.heavyspear.init.HeavyspearModItems;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Mod(HeavyspearMod.MODID)
public class HeavyspearMod {
    public static final String MODID = "heavyspear";

    public HeavyspearMod(IEventBus modEventBus) {
        HeavyspearModItems.REGISTRY.register(modEventBus);
        modEventBus.addListener(this::buildCreativeTab);
    }

    private void buildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.insertAfter(
                    new ItemStack(Items.NETHERITE_SPEAR),
                    new ItemStack(HeavyspearModItems.HEAVY_SPEAR.get()),
                    CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS
            );
        }
    }
}
