import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/** Deterministic assembly of an existing 3x2 animation contact sheet; no generated artwork changes. */
public final class EncodeStickerGif {
    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equals(name)) return (IIOMetadataNode) root.item(i);
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 5) throw new IllegalArgumentException("Use: EncodeStickerGif input.png output.gif [size=320] [delayMs=140] [preview.png]");
        File input = new File(args[0]);
        File output = new File(args[1]);
        if (input.getCanonicalFile().equals(output.getCanonicalFile())) throw new IllegalArgumentException("Output must differ from source");
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 320;
        int delay = args.length > 3 ? Integer.parseInt(args[3]) : 140;
        if (size < 64 || size > 512 || delay < 80 || delay > 1000) throw new IllegalArgumentException("Use 64–512 pixels and 80–1000 ms");
        BufferedImage sheet = ImageIO.read(input);
        if (sheet == null || sheet.getWidth() % 3 != 0 || sheet.getHeight() % 2 != 0) throw new IllegalArgumentException("Input must be a complete 3x2 PNG contact sheet");
        int cellWidth = sheet.getWidth() / 3;
        int cellHeight = sheet.getHeight() / 2;
        if (cellWidth != cellHeight) throw new IllegalArgumentException("Contact-sheet cells must be square");
        BufferedImage[] cells = new BufferedImage[6];
        for (int cell = 0; cell < cells.length; cell++) {
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, size, size);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            int x = (cell % 3) * cellWidth;
            int y = (cell / 3) * cellHeight;
            graphics.drawImage(sheet, 0, 0, size, size, x, y, x + cellWidth, y + cellHeight, null);
            graphics.dispose();
            cells[cell] = image;
        }
        File parent = output.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalArgumentException("Cannot create output directory");
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        int[] order = {0, 1, 2, 3, 4, 5, 4, 3, 2, 1};
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (int index = 0; index < order.length; index++) {
                BufferedImage image = cells[order[index]];
                ImageWriteParam parameters = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), parameters);
                String format = metadata.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
                IIOMetadataNode control = child(root, "GraphicControlExtension");
                control.setAttribute("disposalMethod", "none");
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("delayTime", Integer.toString(Math.max(1, delay / 10)));
                control.setAttribute("transparentColorIndex", "0");
                if (index == 0) {
                    IIOMetadataNode extensions = child(root, "ApplicationExtensions");
                    IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID", "NETSCAPE");
                    loop.setAttribute("authenticationCode", "2.0");
                    loop.setUserObject(new byte[] {1, 0, 0});
                    extensions.appendChild(loop);
                }
                metadata.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(image, null, metadata), parameters);
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        if (args.length == 5) {
            File preview = new File(args[4]);
            File previewParent = preview.getAbsoluteFile().getParentFile();
            if (!previewParent.isDirectory() && !previewParent.mkdirs()) throw new IllegalArgumentException("Cannot create preview directory");
            ImageIO.write(cells[0], "png", preview);
        }
        System.out.println(output.getPath() + " · " + size + "x" + size + " · " + order.length + " frames · " + output.length() + " bytes");
    }
}
