package core;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * ImageMatrix: Represents a 28x28 normalized grayscale image matrix (784 features).
 * Supports reading from real photograph files (.png, .jpg, .bmp) as well as
 * generating deterministic synthetic patterns with zero external dependencies.
 */
public class ImageMatrix {
    public static final int DEFAULT_WIDTH = 28;
    public static final int DEFAULT_HEIGHT = 28;
    public static final int WIDTH = 28;
    public static final int HEIGHT = 28;
    public static final int TOTAL_PIXELS = WIDTH * HEIGHT;

    public static final int HIGH_RES_WIDTH = 720;
    public static final int HIGH_RES_HEIGHT = 720;
    public static final int HIGH_RES_PIXELS = HIGH_RES_WIDTH * HIGH_RES_HEIGHT;

    private final int width;
    private final int height;
    private final float[] pixels;
    private final String label;
    private BufferedImage sourceImage;

    public ImageMatrix(float[] pixels, String label) {
        if (pixels == null || pixels.length == 0) {
            throw new IllegalArgumentException("Pixels array must not be empty");
        }
        int side = (int) Math.round(Math.sqrt(pixels.length));
        if (side * side == pixels.length) {
            this.width = side;
            this.height = side;
        } else {
            this.width = DEFAULT_WIDTH;
            this.height = DEFAULT_HEIGHT;
        }
        this.pixels = Arrays.copyOf(pixels, pixels.length);
        this.label = (label != null) ? label : "unknown";
    }

    public ImageMatrix(int width, int height, float[] pixels, String label) {
        if (pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException("Pixels array length (" + (pixels != null ? pixels.length : 0)
                    + ") must equal width * height (" + (width * height) + ")");
        }
        this.width = width;
        this.height = height;
        this.pixels = Arrays.copyOf(pixels, pixels.length);
        this.label = (label != null) ? label : "unknown";
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getTotalPixels() {
        return pixels.length;
    }

    public float get(int x, int y) {
        return pixels[y * width + x];
    }

    public float[] getPixels() {
        return pixels;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Serializes pixels as comma-separated floats.
     */
    public String toCsvString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pixels.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.4f", pixels[i]));
        }
        return sb.toString();
    }

    /**
     * Parses an ImageMatrix from comma-separated values.
     * Supports arbitrary resolution square matrices with or without a leading class label.
     */
    public static ImageMatrix fromCsvString(String csv, String defaultLabel) {
        String[] tokens = csv.split(",");
        String label = defaultLabel;
        int offset = 0;
        int count = tokens.length;

        int side = (int) Math.round(Math.sqrt(count));
        if (side * side == count) {
            offset = 0;
        } else {
            int sideWithoutLabel = (int) Math.round(Math.sqrt(count - 1));
            if (sideWithoutLabel * sideWithoutLabel == count - 1) {
                label = tokens[0].trim();
                offset = 1;
                count = count - 1;
            } else {
                throw new IllegalArgumentException("Invalid pixel count: " + tokens.length
                        + " (neither count nor count-1 is a perfect square)");
            }
        }

        float[] p = new float[count];
        for (int i = 0; i < count; i++) {
            p[i] = Float.parseFloat(tokens[offset + i].trim());
        }
        return new ImageMatrix(p, label);
    }

    /**
     * Ingests a real photograph file (.png, .jpg, .bmp), resizes it bilinearly
     * to the target resolution, and normalizes RGB to standard grayscale luminance [0.0, 1.0].
     */
    public static ImageMatrix fromFile(File file, String label, int targetWidth, int targetHeight) throws IOException {
        BufferedImage original = ImageIO.read(file);
        if (original == null) {
            throw new IOException("Unsupported or unreadable image file: " + file.getName());
        }

        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = resized.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g2.dispose();

        int total = targetWidth * targetHeight;
        float[] p = new float[total];
        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int rgb = resized.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // Standard ITU-R BT.601 luminance
                p[y * targetWidth + x] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
            }
        }
        ImageMatrix matrix = new ImageMatrix(targetWidth, targetHeight, p, label != null ? label : file.getName());
        matrix.sourceImage = original;
        return matrix;
    }

    public BufferedImage getSourceImage() {
        return sourceImage;
    }

    public void setSourceImage(BufferedImage img) {
        this.sourceImage = img;
    }

    /**
     * Ingests a real photograph file with standard 28x28 CNN dimension for fast neural processing,
     * while preserving the original high-resolution photography in sourceImage for the visualizer.
     */
    public static ImageMatrix fromFile(File file, String label) throws IOException {
        return fromFile(file, label, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * Converts matrix pixels to a BufferedImage for display.
     */
    public BufferedImage toBufferedImage() {
        if (sourceImage != null) return sourceImage;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray = Math.max(0, Math.min(255, (int) (pixels[y * width + x] * 255.0f)));
                int rgb = (gray << 16) | (gray << 8) | gray;
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    /**
     * Loads the real photograph sample from the official dataset directory (dataset/real/test/<className>/).
     * Exclusively uses genuine photography (zero synthetic mock data).
     */
    public static ImageMatrix createSample(String className) {
        String norm = (className != null && !className.trim().isEmpty()) ? className.toLowerCase().trim() : "panda";
        File dir = new File("dataset/real/test/" + norm);
        if (!dir.exists()) dir = new File("../dataset/real/test/" + norm);
        File[] photos = (dir.exists() && dir.isDirectory())
                ? dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg") || n.toLowerCase().endsWith(".jpeg"))
                : null;
        if (photos != null && photos.length > 0) {
            java.util.Arrays.sort(photos, java.util.Comparator.comparing(File::getName));
            try {
                return fromFile(photos[0], norm);
            } catch (Exception ex) {
                throw new RuntimeException("Error cargando fotografia real para " + norm + ": " + ex.getMessage(), ex);
            }
        }
        throw new IllegalStateException("Sample image not found in dataset/real/test/" + norm);
    }
}
