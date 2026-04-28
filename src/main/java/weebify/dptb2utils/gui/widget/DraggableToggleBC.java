package weebify.dptb2utils.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import weebify.dptb2utils.DPTB2Utils;

public class DraggableToggleBC extends ClickableWidget {
    private boolean dragging = false;
    private int dragOffsetX, dragOffsetY;
    private final DPTB2Utils mod;

    public DraggableToggleBC(DPTB2Utils mod) {
        super(0, 0, 80, 12, Text.empty());
        this.mod = mod;
        updatePosition();
    }

    public void updatePosition() {
        MinecraftClient client = MinecraftClient.getInstance();
        this.setX((int) (client.getWindow().getScaledWidth() * mod.getFloatConfig("others.bc_x")));
        this.setY((int) (client.getWindow().getScaledHeight() * mod.getFloatConfig("others.bc_y")));
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        String status = mod.isToggleBc ? "§aON" : "§cOFF";
        context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                "§7ToggleBC: " + status, getX(), getY(), 0xFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isMouseOver(mouseX, mouseY) && button == 0) {
            this.dragging = true;
            this.dragOffsetX = (int) (mouseX - getX());
            this.dragOffsetY = (int) (mouseY - getY());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (this.dragging) {
            MinecraftClient client = MinecraftClient.getInstance();
            int sw = client.getWindow().getScaledWidth();
            int sh = client.getWindow().getScaledHeight();

            this.setX((int) (mouseX - dragOffsetX));
            this.setY((int) (mouseY - dragOffsetY));

            mod.setFloatConfig("others.bc_x", (float) getX() / sw);
            mod.setFloatConfig("others.bc_y", (float) getY() / sh);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.dragging = false;
        return true;
    }

    @Override
    protected void appendClickableNarrations(net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {}
}