package net.minecraft.heavyspear;

import net.minecraft.heavyspear.init.HeavyspearModItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(HeavyspearMod.MODID)
public class HeavyspearMod {
    public static final String MODID = "heavyspear";

    public HeavyspearMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        HeavyspearModItems.REGISTRY.register(modEventBus);
        modEventBus.addListener(this::addCreative);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(new ItemStack(HeavyspearModItems.HEAVY_SPEAR.get()));
        }
    }
}
