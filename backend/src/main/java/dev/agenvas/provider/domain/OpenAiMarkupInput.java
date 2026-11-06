package dev.agenvas.provider.domain;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;

/** Native markup preserves the oriented source grid, independently of requested output dimensions. */
public final class OpenAiMarkupInput {
    public static final long MAX_PIXELS = 16_777_216L;
    // The application's existing upload bound is intentionally stricter than OpenAI's 50 MB bound.
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    public record Pair(byte[] source, byte[] markup, int width, int height) {}
    private OpenAiMarkupInput() {}

    public static Pair prepare(byte[] source, byte[] markup) {
        requireBytes(source); requireBytes(markup);
        if (!isPng(markup)) throw invalid();
        try {
            BufferedImage original = orient(decode(source), orientation(source));
            BufferedImage overlay = orient(decode(markup), orientation(markup));
            int width = original.getWidth(), height = original.getHeight();
            if (overlay.getWidth() != width || overlay.getHeight() != height || !overlay.getColorModel().hasAlpha()) throw invalid();
            boolean transparent = false, ink = false;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int alpha = overlay.getRGB(x, y) >>> 24;
                transparent |= alpha < 255;
                ink |= alpha > 0;
            }
            if (!transparent || !ink) throw invalid();
            // Re-encoding both layers strips EXIF so the provider cannot rotate either layer again.
            return new Pair(encode(original), encode(overlay), width, height);
        } catch (IOException | IndexOutOfBoundsException malformed) { throw invalid(); }
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output) || output.size() > MAX_BYTES) throw invalid();
        return output.toByteArray();
    }

    private static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation == 1) return image;
        int width = orientation >= 5 ? image.getHeight() : image.getWidth();
        int height = orientation >= 5 ? image.getWidth() : image.getHeight();
        var output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int ox = switch (orientation) {
                case 2, 3 -> width - 1 - x;
                case 5, 8 -> y;
                case 6, 7 -> width - 1 - y;
                default -> x;
            };
            int oy = switch (orientation) {
                case 3, 4 -> height - 1 - y;
                case 5, 6 -> x;
                case 7, 8 -> height - 1 - x;
                default -> y;
            };
            output.setRGB(ox, oy, image.getRGB(x, y));
        }
        return output;
    }

    private static void requireBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 8 || bytes.length > MAX_BYTES) throw invalid();
    }
    public static boolean isPng(byte[] bytes) {
        return bytes != null && bytes.length >= 8 && bytes[0] == (byte)0x89 && bytes[1]=='P' && bytes[2]=='N' && bytes[3]=='G'
                && bytes[4]==13 && bytes[5]==10 && bytes[6]==26 && bytes[7]==10;
    }
    private static BufferedImage decode(byte[] bytes) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Set.of("png","jpeg","jpg","webp").contains(format)
                        || (long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) throw invalid();
                var image = reader.read(0);
                if (image == null) throw invalid();
                return image;
            } finally { reader.dispose(); }
        }
    }

    /** Locate only container-declared EXIF chunks; never scan compressed pixel bytes for tag patterns. */
    private static int orientation(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        if (isPng(bytes)) {
            int p=8;
            while (p+12<=bytes.length) {
                int length=b.getInt(p);
                if(length<0 || (long)p+12+length>bytes.length) throw invalid();
                if(b.getInt(p+4)==0x65584966) return tiffOrientation(bytes,p+8,length);
                p+=12+length;
            }
        } else if(b.getInt(0)==0x52494646 && b.getInt(8)==0x57454250) {
            b.order(ByteOrder.LITTLE_ENDIAN);
            int p=12;
            while(p+8<=bytes.length) {
                int length=b.getInt(p+4);
                if(length<0 || (long)p+8+length>bytes.length) throw invalid();
                if(bytes[p]=='E' && bytes[p+1]=='X' && bytes[p+2]=='I' && bytes[p+3]=='F') return exifOrientation(bytes,p+8,length);
                p+=8+length+(length&1);
            }
        } else if(bytes[0]==(byte)0xff && bytes[1]==(byte)0xd8) {
            int p=2;
            while(p+4<=bytes.length && bytes[p]==(byte)0xff) {
                int tag=bytes[p+1]&255;
                if(tag==0xda || tag==0xd9) break;
                if(tag==0xff) {p++; continue;}
                int length=b.getShort(p+2)&65535;
                if(length<2 || (long)p+2+length>bytes.length) throw invalid();
                if(tag==0xe1 && length>=8 && b.getInt(p+4)==0x45786966) return exifOrientation(bytes,p+4,length-2);
                p+=2+length;
            }
        }
        return 1;
    }
    private static int exifOrientation(byte[] bytes,int offset,int length) {
        if(length>=6 && bytes[offset]=='E' && bytes[offset+1]=='x' && bytes[offset+2]=='i' && bytes[offset+3]=='f'
                && bytes[offset+4]==0 && bytes[offset+5]==0) {offset+=6;length-=6;}
        return tiffOrientation(bytes,offset,length);
    }
    private static int tiffOrientation(byte[] bytes,int offset,int length) {
        if(length<8) throw invalid();
        ByteBuffer b=ByteBuffer.wrap(bytes,offset,length).slice();
        short endian=b.getShort(0);
        if(endian==0x4949) b.order(ByteOrder.LITTLE_ENDIAN); else if(endian!=0x4d4d) throw invalid();
        if(b.getShort(2)!=42) throw invalid();
        int directory=b.getInt(4);
        if(directory<8 || directory>length-2) throw invalid();
        int count=b.getShort(directory)&65535;
        if((long)directory+2+12L*count>length) throw invalid();
        for(int i=0;i<count;i++) {
            int p=directory+2+12*i;
            if((b.getShort(p)&65535)==274) {
                if(b.getShort(p+2)!=3 || b.getInt(p+4)!=1) throw invalid();
                int value=b.getShort(p+8)&65535;
                if(value<1 || value>8) throw invalid();
                return value;
            }
        }
        return 1;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid native markup image pair"); }
}
