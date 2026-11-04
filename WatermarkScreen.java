package gcse.visuals;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Watermark settings: 2 pages. The watermark is visible live behind this screen. */
public class WatermarkScreen extends Screen {
    static final String[] MODES = {"Радуга", "Один цвет", "Градиент"};
    static final String[] CORNERS = {"Слева сверху", "Справа сверху", "Слева снизу", "Справа снизу", "По центру сверху"};
    int page = 0;

    WatermarkScreen() { super(Text.literal("GCSE Watermark")); }

    static String onoff(boolean v) { return v ? "ВКЛ" : "ВЫКЛ"; }

    ButtonWidget btn(int x, int y, int w, Supplier<String> label, Runnable action) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(label.get()), b -> {
            action.run();
            b.setMessage(Text.literal(label.get()));
        }).dimensions(x, y, w, 20).build());
    }

    void toggle(int x, int y, String name, Supplier<Boolean> get, Runnable flip) {
        btn(x, y, 100, () -> name + ": " + onoff(get.get()), flip);
    }

    void stepper(int y, Supplier<String> label, Runnable dec, Runnable inc) {
        int cx = width / 2;
        ButtonWidget mid = addDrawableChild(ButtonWidget.builder(Text.literal(label.get()), b -> { }).dimensions(cx - 70, y, 140, 20).build());
        mid.active = false;
        addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> { dec.run(); mid.setMessage(Text.literal(label.get())); })
                .dimensions(cx - 102, y, 30, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> { inc.run(); mid.setMessage(Text.literal(label.get())); })
                .dimensions(cx + 72, y, 30, 20).build());
    }

    TextFieldWidget hexField(int x, int y, int w, int value, IntConsumer set) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 20, Text.literal("hex"));
        f.setMaxLength(7);
        f.setText(String.format("#%06X", value));
        f.setChangedListener(s -> {
            try {
                String h = s.startsWith("#") ? s.substring(1) : s;
                if (h.length() == 6) set.accept(Integer.parseInt(h, 16));
            } catch (Exception ignored) { }
        });
        return addDrawableChild(f);
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int L = cx - 102, R = cx + 2;
        int y = height / 2 - 96;

        if (page == 0) {
            TextFieldWidget tf = new TextFieldWidget(textRenderer, L, y, 204, 20, Text.literal("Текст"));
            tf.setMaxLength(48);
            tf.setText(Watermark.text);
            tf.setChangedListener(s -> Watermark.text = s);
            addDrawableChild(tf);
            y += 24;
            btn(L, y, 204, () -> "Цвет: " + MODES[Watermark.colorMode], () -> Watermark.colorMode = (Watermark.colorMode + 1) % 3);
            y += 24;
            hexField(L, y, 100, Watermark.color1, v -> Watermark.color1 = v);
            hexField(R, y, 100, Watermark.color2, v -> Watermark.color2 = v);
            y += 24;
            toggle(L, y, "Жирный", () -> Watermark.bold, () -> Watermark.bold = !Watermark.bold);
            toggle(R, y, "Тень", () -> Watermark.shadow, () -> Watermark.shadow = !Watermark.shadow);
            y += 24;
            toggle(L, y, "Фон", () -> Watermark.bg, () -> Watermark.bg = !Watermark.bg);
            toggle(R, y, "Линия", () -> Watermark.accent, () -> Watermark.accent = !Watermark.accent);
            y += 24;
            stepper(y, () -> "Прозрачность фона: " + Math.round(Watermark.bgAlpha * 100f / 255f) + "%",
                    () -> Watermark.bgAlpha = Math.max(0, Watermark.bgAlpha - 16),
                    () -> Watermark.bgAlpha = Math.min(255, Watermark.bgAlpha + 16));
            y += 24;
            stepper(y, () -> "Размер: " + String.format("%.1f", Watermark.scale),
                    () -> Watermark.scale = Math.max(0.5f, Math.round((Watermark.scale - 0.1f) * 10) / 10f),
                    () -> Watermark.scale = Math.min(3.0f, Math.round((Watermark.scale + 0.1f) * 10) / 10f));
        } else {
            toggle(L, y, "FPS", () -> Watermark.fps, () -> Watermark.fps = !Watermark.fps);
            toggle(R, y, "Пинг", () -> Watermark.ping, () -> Watermark.ping = !Watermark.ping);
            y += 24;
            toggle(L, y, "Время", () -> Watermark.time, () -> Watermark.time = !Watermark.time);
            toggle(R, y, "Ник", () -> Watermark.name, () -> Watermark.name = !Watermark.name);
            y += 24;
            toggle(L, y, "Координаты", () -> Watermark.coords, () -> Watermark.coords = !Watermark.coords);
            toggle(R, y, "Фейерверки", () -> Watermark.fireworks, () -> Watermark.fireworks = !Watermark.fireworks);
            y += 24;
            toggle(L, y, "BPS", () -> Watermark.bps, () -> Watermark.bps = !Watermark.bps);
            toggle(R, y, "Список", () -> Watermark.moduleList, () -> Watermark.moduleList = !Watermark.moduleList);
            y += 24;
            btn(L, y, 204, () -> "Позиция: " + CORNERS[Watermark.corner], () -> Watermark.corner = (Watermark.corner + 1) % CORNERS.length);
            y += 24;
            stepper(y, () -> "Отступ X: " + Watermark.offX,
                    () -> Watermark.offX = Math.max(-400, Watermark.offX - 2),
                    () -> Watermark.offX = Math.min(400, Watermark.offX + 2));
            y += 24;
            stepper(y, () -> "Отступ Y: " + Watermark.offY,
                    () -> Watermark.offY = Math.max(-400, Watermark.offY - 2),
                    () -> Watermark.offY = Math.min(400, Watermark.offY + 2));
        }

        int by = height / 2 + 78;
        addDrawableChild(ButtonWidget.builder(Text.literal("Страница " + (page + 1) + "/2"), b -> { page = 1 - page; clearAndInit(); })
                .dimensions(cx - 102, by, 66, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Сброс"), b -> { Watermark.defaults(); clearAndInit(); })
                .dimensions(cx - 33, by, 66, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), b -> close())
                .dimensions(cx + 36, by, 66, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, "GCSE Watermark - настройки", width / 2, height / 2 - 114, 0xFFFFFFFF);
        if (page == 0) {
            ctx.drawCenteredTextWithShadow(textRenderer, "Сверху: текст. Ниже цвет 1 и цвет 2 в формате #RRGGBB", width / 2, height / 2 + 104, 0xFFAAAAAA);
        }
    }

    @Override
    public void removed() { Watermark.save(); }

    @Override
    public boolean shouldPause() { return false; }
}
