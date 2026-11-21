package net.minecraft.heavyspear.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 鞘翅加速 HUD。
 *
 * <p>不关心加速来源：只要滑翔中速度比上一 tick 明显增加（重矛突进、烟花、其它飞行模组都算），
 * 就把“加速窗口”重置为 {@code windowTicks}，在右上角显示剩余时间和当前速度。</p>
 *
 * <p>位置可调：滑翔/任意时刻按住 <b>Right Shift</b>，HUD 会跟随鼠标，拖动到想要的位置后
 * 松开 Right Shift 即保存到 {@code config/heavyspear-hud.properties}。
 * 按住 <b>Right Shift + Right Ctrl</b> 会立即重置回右上角。</p>
 */
@EventBusSubscriber(modid = "heavyspear", value = Dist.CLIENT)
public final class HeavyspearHud {
   private static final int EDIT_KEY = GLFW.GLFW_KEY_RIGHT_SHIFT;
   private static final int RESET_KEY = GLFW.GLFW_KEY_RIGHT_CONTROL;
   private static final int DEFAULT_OFFSET_X = 4;
   private static final int DEFAULT_OFFSET_Y = 4;
   private static final int DEFAULT_WINDOW_TICKS = 40;
   private static final double DEFAULT_ACCEL_THRESHOLD = 0.5D;

   private static boolean loaded;
   private static boolean enabled = true;
   private static boolean showSpeed;
   private static int offsetX = DEFAULT_OFFSET_X;
   private static int offsetY = DEFAULT_OFFSET_Y;
   private static int windowTicks = DEFAULT_WINDOW_TICKS;
   private static double accelThreshold = DEFAULT_ACCEL_THRESHOLD;

   private static int boostTicks;
   private static double currentSpeed;
   private static double previousSpeed;
   private static boolean wasGliding;
   private static boolean wasEditing;
   private static boolean wasResetting;
   private static boolean resetLocked;

   private HeavyspearHud() {
   }

   @SubscribeEvent
   public static void onClientTick(ClientTickEvent.Post event) {
      ensureLoaded();
      Minecraft mc = Minecraft.getInstance();
      if (mc == null) {
         return;
      }

      boolean editing = mc.screen == null && InputConstants.isKeyDown(mc.getWindow().getWindow(), EDIT_KEY);
      if (editing && !wasEditing) {
         // 拖动 HUD 需要真正的鼠标指针，先释放鼠标抓取。
         mc.mouseHandler.releaseMouse();
      } else if (!editing && wasEditing) {
         save();
         if (mc.screen == null) {
            mc.mouseHandler.grabMouse();
         }
      }
      wasEditing = editing;

      boolean resetting = editing && InputConstants.isKeyDown(mc.getWindow().getWindow(), RESET_KEY);
      if (resetting && !wasResetting) {
         // Right Shift + Right Ctrl：一键回到右上角，并锁住拖动直到松开 Right Shift。
         offsetX = DEFAULT_OFFSET_X;
         offsetY = DEFAULT_OFFSET_Y;
         resetLocked = true;
         save();
      }
      wasResetting = resetting;
      if (!editing) {
         resetLocked = false;
      }

      LocalPlayer player = mc.player;
      if (player == null) {
         boostTicks = 0;
         wasGliding = false;
         return;
      }

      double speed = player.getDeltaMovement().length() * 20.0D;
      if (!player.isFallFlying()) {
         boostTicks = 0;
         wasGliding = false;
         previousSpeed = speed;
         return;
      }

      if (!wasGliding) {
         // 刚进入滑翔：先记录基准速度，避免把“起跳”误判成一次加速。
         wasGliding = true;
         previousSpeed = speed;
         boostTicks = 0;
         return;
      }

      if (boostTicks > 0) {
         --boostTicks;
      }
      if (speed - previousSpeed > accelThreshold) {
         boostTicks = windowTicks;
      }

      currentSpeed = speed;
      previousSpeed = speed;
   }

   @SubscribeEvent
   public static void onRenderGui(RenderGuiEvent.Post event) {
      ensureLoaded();
      if (!enabled) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.player == null || mc.options.hideGui || mc.screen != null) {
         return;
      }

      boolean editing = mc.screen == null && InputConstants.isKeyDown(mc.getWindow().getWindow(), EDIT_KEY);
      if (!editing && boostTicks <= 0) {
         return;
      }

      Window window = mc.getWindow();
      int guiWidth = window.getGuiScaledWidth();
      int guiHeight = window.getGuiScaledHeight();
      int displayTicks = editing ? Math.max(boostTicks, windowTicks) : boostTicks;
      boolean resetting = editing && InputConstants.isKeyDown(window.getWindow(), RESET_KEY);

      String seconds = String.format(Locale.ROOT, "%.1f", displayTicks / 20.0D);
      String text = Component.translatable("hud.heavyspear.elytra_accel", seconds).getString();
      if (showSpeed) {
         String speed = String.format(Locale.ROOT, "%.1f", currentSpeed);
         text = text + Component.translatable("hud.heavyspear.speed", speed).getString();
      }
      int textWidth = mc.font.width(text);

      if (editing && !resetLocked && !resetting) {
         double mouseX = mc.mouseHandler.xpos() * (double)guiWidth / (double)window.getScreenWidth();
         double mouseY = mc.mouseHandler.ypos() * (double)guiHeight / (double)window.getScreenHeight();
         offsetX = (int)Math.round(guiWidth - mouseX);
         offsetY = (int)Math.round(mouseY);
         offsetX = clamp(offsetX, 0, Math.max(0, guiWidth - textWidth));
         offsetY = clamp(offsetY, 0, Math.max(0, guiHeight - 12));
      }

      offsetX = clamp(offsetX, 0, Math.max(0, guiWidth - textWidth));
      offsetY = clamp(offsetY, 0, Math.max(0, guiHeight - 12));
      int drawX = guiWidth - offsetX - textWidth;
      int drawY = offsetY;
      GuiGraphics graphics = event.getGuiGraphics();
      graphics.fill(drawX - 3, drawY - 2, drawX + textWidth + 3, drawY + 10, 0x90000000);
      graphics.drawString(mc.font, text, drawX, drawY, 0xFFFFFFFF, true);

      if (editing) {
         String hintKey = resetLocked || resetting ? "hud.heavyspear.reset_hint" : "hud.heavyspear.edit_hint";
         String hint = Component.translatable(hintKey).getString();
         int hintWidth = mc.font.width(hint);
         graphics.drawString(mc.font, hint, (guiWidth - hintWidth) / 2, 20, 0xFFFFE080, true);
      }
   }

   private static int clamp(int value, int min, int max) {
      if (value < min) {
         return min;
      }
      return value > max ? max : value;
   }

   private static void ensureLoaded() {
      if (loaded) {
         return;
      }
      loaded = true;

      Path path = configPath();
      if (!Files.isRegularFile(path)) {
         save();
         return;
      }

      Properties props = new Properties();
      try (InputStream in = Files.newInputStream(path)) {
         props.load(in);
      } catch (IOException ignored) {
         return;
      }

      enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
      showSpeed = Boolean.parseBoolean(props.getProperty("showSpeed", "false"));
      offsetX = parseInt(props.getProperty("offsetX"), 4);
      offsetY = parseInt(props.getProperty("offsetY"), 4);
      windowTicks = Math.max(10, Math.min(200, parseInt(props.getProperty("windowTicks"), DEFAULT_WINDOW_TICKS)));
      accelThreshold = parseDouble(props.getProperty("accelThreshold"), DEFAULT_ACCEL_THRESHOLD);
      if (accelThreshold <= 0.0D) {
         accelThreshold = DEFAULT_ACCEL_THRESHOLD;
      }
      offsetX = Math.max(0, offsetX);
      offsetY = Math.max(0, offsetY);
   }

   private static void save() {
      Path path = configPath();
      try {
         Path parent = path.getParent();
         if (parent != null) {
            Files.createDirectories(parent);
         }
         Properties props = new Properties();
         props.setProperty("enabled", Boolean.toString(enabled));
         props.setProperty("showSpeed", Boolean.toString(showSpeed));
         props.setProperty("offsetX", Integer.toString(offsetX));
         props.setProperty("offsetY", Integer.toString(offsetY));
         props.setProperty("windowTicks", Integer.toString(windowTicks));
         props.setProperty("accelThreshold", Double.toString(accelThreshold));
         try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "Heavyspear elytra acceleration HUD");
         }
      } catch (IOException ignored) {
         // HUD 只是装饰，配置写失败不影响游戏。
      }
   }

   private static Path configPath() {
      return FMLPaths.CONFIGDIR.get().resolve("heavyspear-hud.properties");
   }

   private static int parseInt(String value, int fallback) {
      if (value == null) {
         return fallback;
      }
      try {
         return Integer.parseInt(value.trim());
      } catch (NumberFormatException ignored) {
         return fallback;
      }
   }

   private static double parseDouble(String value, double fallback) {
      if (value == null) {
         return fallback;
      }
      try {
         return Double.parseDouble(value.trim());
      } catch (NumberFormatException ignored) {
         return fallback;
      }
   }
}
