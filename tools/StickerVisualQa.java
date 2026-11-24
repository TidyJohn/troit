import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Read-only visual QA: first-frame contact sheet and caption-free duplicate-photo distances. */
public final class StickerVisualQa {
    private static final int GRID = 16;
    private static final double NEAR_DUPLICATE_RMSE = 0.07;

    private record Preview(String name, BufferedImage image, double[] features) {}

    private static double[] features(BufferedImage image) {
        int height = Math.max(1, image.getHeight() * 7 / 10);
        double[] values = new double[GRID * GRID * 3];
        double[] means = new double[3];
        for (int gy = 0; gy < GRID; gy++) for (int gx = 0; gx < GRID; gx++) {
            int left = gx * image.getWidth() / GRID;
            int right = Math.max(left + 1, (gx + 1) * image.getWidth() / GRID);
            int top = gy * height / GRID;
            int bottom = Math.max(top + 1, (gy + 1) * height / GRID);
            long[] total = new long[3];
            for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
                int rgb = image.getRGB(x, y);
                total[0] += (rgb >>> 16) & 255;
                total[1] += (rgb >>> 8) & 255;
                total[2] += rgb & 255;
            }
            int count = (right - left) * (bottom - top);
            for (int channel = 0; channel < 3; channel++) {
                double mean = (double) total[channel] / count;
                values[(gy * GRID + gx) * 3 + channel] = mean;
                means[channel] += mean / (GRID * GRID);
            }
        }
        // Ignore a simple color cast/brightness shift; changing a caption or tint is not a new photo.
        for (int index = 0; index < values.length; index++) values[index] = (values[index] - means[index % 3]) / 255;
        return values;
    }

    private static double distance(double[] left, double[] right) {
        double sum = 0;
        for (int index = 0; index < left.length; index++) {
            double difference = left[index] - right[index];
            sum += difference * difference;
        }
        return Math.sqrt(sum / left.length);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Use: StickerVisualQa output.png sticker1.gif sticker2.gif [...]");
        List<Preview> previews = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            File file = new File(args[index]);
            BufferedImage image = ImageIO.read(file);
            if (image == null) throw new IllegalArgumentException("Cannot decode " + file);
            previews.add(new Preview(file.getName(), image, features(image)));
        }
        int cellWidth = 320;
        int labelHeight = 32;
        int columns = Math.min(4, previews.size());
        int rows = (previews.size() + columns - 1) / columns;
        BufferedImage sheet = new BufferedImage(columns * cellWidth, rows * (cellWidth + labelHeight), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = sheet.createGraphics();
        try {
            graphics.setColor(new Color(22, 22, 22));
            graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            for (int index = 0; index < previews.size(); index++) {
                Preview preview = previews.get(index);
                int x = (index % columns) * cellWidth;
                int y = (index / columns) * (cellWidth + labelHeight);
                graphics.drawImage(preview.image, x, y, cellWidth, cellWidth, null);
                graphics.setColor(Color.WHITE);
                graphics.drawString(preview.name, x + 6, y + cellWidth + 21);
            }
        } finally { graphics.dispose(); }
        File output = new File(args[0]);
        File parent = output.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalArgumentException("Cannot create " + parent);
        ImageIO.write(sheet, "png", output);
        double minimum = Double.POSITIVE_INFINITY;
        for (int left = 0; left < previews.size(); left++) for (int right = left + 1; right < previews.size(); right++) {
            double value = distance(previews.get(left).features, previews.get(right).features);
            minimum = Math.min(minimum, value);
            if (value < NEAR_DUPLICATE_RMSE) System.out.printf("NEAR-DUPLICATE %.4f %s / %s%n", value, previews.get(left).name, previews.get(right).name);
        }
        System.out.printf("Checked %d first frames. Minimum caption-free RMSE %.4f; conservative near-duplicate threshold %.2f.%n", previews.size(), minimum, NEAR_DUPLICATE_RMSE);
        System.out.println("Preview: " + output.getPath());
    }
}
