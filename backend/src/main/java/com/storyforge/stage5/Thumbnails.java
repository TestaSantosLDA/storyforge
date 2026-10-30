package com.storyforge.stage5;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Thumbnails: a strong shot cropped to 1280×720 with 2–4 words of overlay text, cream letters with a dark outline
 * (readable at small sizes, in the channel's muted palette). JPEG under YouTube's 2 MB limit.
 */
public final class Thumbnails {

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final Color CREAM = new Color(0xFB, 0xF4, 0xE2);
    private static final Color INK = new Color(0x2B, 0x22, 0x1C);

    private Thumbnails() {
    }

    public static byte[] render(byte[] shotImage, String text) {
        try {
            BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(shotImage));
            BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Cover-crop to 16:9.
            double scale = Math.max((double) WIDTH / src.getWidth(), (double) HEIGHT / src.getHeight());
            int w = (int) Math.round(src.getWidth() * scale);
            int h = (int) Math.round(src.getHeight() * scale);
            g.drawImage(src, (WIDTH - w) / 2, (HEIGHT - h) / 2, w, h, null);
            // Soft dark band at the bottom so the text reads on any picture.
            g.setPaint(new GradientPaint(0, HEIGHT * 0.55f, new Color(0, 0, 0, 0), 0, HEIGHT, new Color(20, 14, 10, 170)));
            g.fillRect(0, (int) (HEIGHT * 0.55), WIDTH, (int) (HEIGHT * 0.45) + 1);

            List<String> rows = rows(text.strip().toUpperCase(Locale.ROOT));
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, rows.size() > 1 ? 104 : 124);
            FontRenderContext frc = g.getFontRenderContext();
            double y = HEIGHT - 48 - (rows.size() - 1) * font.getSize() * 1.02;
            for (String row : rows) {
                TextLayout layout = new TextLayout(row, font, frc);
                double width = layout.getBounds().getWidth();
                Font sized = width > WIDTH - 96 ? font.deriveFont((float) (font.getSize() * (WIDTH - 96) / width)) : font;
                layout = new TextLayout(row, sized, frc);
                Shape shape = layout.getOutline(AffineTransform.getTranslateInstance(
                        (WIDTH - layout.getBounds().getWidth()) / 2 - layout.getBounds().getX(), y));
                g.setStroke(new BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(INK);
                g.draw(shape);
                g.setColor(CREAM);
                g.fill(shape);
                y += sized.getSize() * 1.02;
            }
            g.dispose();
            return jpeg(out, 0.9f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One row for up to two words, else two balanced rows. */
    static List<String> rows(String text) {
        String[] words = text.split("\\s+");
        if (words.length <= 2) {
            return List.of(text);
        }
        int half = (words.length + 1) / 2;
        List<String> out = new ArrayList<>();
        out.add(String.join(" ", java.util.Arrays.copyOfRange(words, 0, half)));
        out.add(String.join(" ", java.util.Arrays.copyOfRange(words, half, words.length)));
        return out;
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
