package weebify.dptb2utils.gui.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import weebify.dptb2utils.DPTB2Utils;
import weebify.dptb2utils.gui.widget.DraggableToggleBC;

public class ToggleBCConfigScreen extends Screen {
    private final Screen parent;
    private final DPTB2Utils mod;

    public ToggleBCConfigScreen(Screen parent, DPTB2Utils mod) {
        super(Text.of("ToggleBC Configuration"));
        this.parent = parent;
        this.mod = mod;
    }

    @Override
    protected void init() {
        // The Toggle Button
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(String.format("HUD Visibility: %s", mod.getBoolConfig("others.isToggleBc") ? "VISIBLE" : "HIDDEN")),
                (btn) -> {
                    // This ONLY toggles if the HUD shows up in-game
                    boolean hudVisible = mod.toggleBoolConfig("others.isToggleBc");
                    mod.saveSettings();
                    btn.setMessage(Text.of(String.format("HUD Visibility: %s", hudVisible ? "VISIBLE" : "HIDDEN")));
                }).dimensions(this.width / 2 - 75, 40, 150, 20).build());
        // The Done Button
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), (btn) -> {
            this.client.setScreen(this.parent);
        }).dimensions(this.width / 2 - 75, this.height - 40, 150, 20).build());

        // The Draggable Widget
        this.addDrawableChild(new DraggableToggleBC(this.mod));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);
    }

    @Override
    public void close() {
        mod.saveSettings();
        this.client.setScreen(this.parent);
    }
}