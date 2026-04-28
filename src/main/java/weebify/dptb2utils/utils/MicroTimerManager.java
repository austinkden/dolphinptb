package weebify.dptb2utils.utils;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.Formatting;
import weebify.dptb2utils.DPTB2Utils;
import weebify.dptb2utils.gui.screen.MicroTimerConfigScreen;

public class MicroTimerManager {
    public static int microTimer = -1;
    public static String lastEvent = "§lNAH";
    public static final String prefix = "§lLast: ";
    public static String[] eventsList = {
            "§4§lMAYHEM",
            "§7§lDISABLED",
            "§c§lIMMUNITY",
            "§a§lJUMP",
            "§b§lICE"
    };

    public static Text tickToTime(int ticks) {
        if (ticks < 0) {
            return Text.of("N/A");
        }

        int seconds = ticks / 20;
        int minutes = seconds / 60;
        seconds %= 60;
        int hours = minutes / 60;
        minutes %= 60;

        String timeString = hours > 0 ? String.format("%02d:%02d:%02d", hours, minutes, seconds) : String.format("%02d:%02d", minutes, seconds);
        MutableText timeText = Text.literal(timeString);

        // Color formatting for the timer
        if (ticks >= 6000) return timeText.formatted(Formatting.RED);
        else if (ticks >= 5100) return timeText.formatted(Formatting.GOLD);
        else if (ticks >= 4200) return timeText.formatted(Formatting.YELLOW);
        return timeText;
    }

    public static void setTime(int minutes, int seconds) {
        // Convert minutes and seconds to total ticks
        int totalSeconds = (minutes * 60) + seconds;
        microTimer = totalSeconds * 20;
    }

    public static void initialize() {
        ClientTickEvents.START_CLIENT_TICK.register((mc) -> {
            if (DPTB2Utils.getInstance().isInDPTB2) {
                if (MicroTimerManager.microTimer >= 0) {
                    MicroTimerManager.microTimer += 1;

                    // --- NEW SOUND LOGIC ---
                    // 4 minutes 50 seconds = 290 seconds
                    // 290 seconds * 20 ticks = 5800 ticks
                    if (MicroTimerManager.microTimer == 5800) {
                        if (mc.player != null) {
                            mc.player.playSound(
                                    net.minecraft.sound.SoundEvents.ENTITY_BLAZE_DEATH,
                                    1.0f, // Volume (1.0 is full)
                                    1.0f  // Pitch (1.0 is normal)
                            );
                        }
                    }
                    // -----------------------

                    // 5-Minute Remover:
                    if (MicroTimerManager.microTimer >= 6020) {
                        MicroTimerManager.microTimer -= 6000;
                    }
                }
            }
        });

        HudRenderCallback.EVENT.register(MicroTimerManager::renderMicroTimer);
    }

    private static void renderMicroTimer(DrawContext drawContext, RenderTickCounter renderTickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        DPTB2Utils mod = DPTB2Utils.getInstance();

        if (mod.isInDPTB2 && mod.getBoolConfig("microTimer.enabled") && !(mc.currentScreen instanceof MicroTimerConfigScreen)) {
            int width = mc.getWindow().getScaledWidth();
            int height = mc.getWindow().getScaledHeight();
            int posX = (int)(mod.getFloatConfig("microTimer.posX")*width);
            int posY = (int)(mod.getFloatConfig("microTimer.posY")*height);

            Text text = MicroTimerManager.tickToTime(MicroTimerManager.microTimer);
            int widgetWidth = Math.max(mc.textRenderer.getWidth(text), mc.textRenderer.getWidth(Text.of(prefix + lastEvent)));

            if (mod.getBoolConfig("microTimer.renderBackground")) {
                drawContext.fill(
                        posX,
                        posY,
                        posX + widgetWidth + 8,
                        posY + 21 + mc.textRenderer.fontHeight,
                        0x63000000
                );
            }

            drawContext.drawText(
                    mc.textRenderer, text,
                    posX + 4,
                    posY + 4,
                    Colors.WHITE,
                    mod.getBoolConfig("microTimer.textShadow")
            );
            drawContext.drawText(
                    mc.textRenderer, prefix + lastEvent,
                    posX + 4,
                    posY + 4 + mc.textRenderer.fontHeight + 3,
                    Colors.WHITE,
                    mod.getBoolConfig("microTimer.textShadow")
            );
        }
    }
}