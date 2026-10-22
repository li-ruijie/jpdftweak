package jpdftweak.core;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import jpdftweak.core.PdfToImage.ColorMode;
import jpdftweak.core.PdfToImage.ImageType;
import jpdftweak.core.PdfToImage.TiffCompression;

import com.artifex.mupdf.fitz.ColorSpace;
import com.artifex.mupdf.fitz.Document;
import com.artifex.mupdf.fitz.Matrix;
import com.artifex.mupdf.fitz.Page;
import com.artifex.mupdf.fitz.Pixmap;

/**
 * The part of {@link PdfToImage} that calls MuPDF. PdfToImage loads this
 * class through a {@link NativeClassLoader}, so that MuPDF's Java binding
 * finds its shared library next to jpdftweak.jar.
 *
 * Pages are rendered at 72 dpi, one pixel per PostScript point. MuPDF writes
 * JPG, PNG, PAM, and PNM itself, for the RGB and grayscale color modes only.
 * GIF, BMP, and TIFF go through ImageIO, which also takes the black and white
 * modes. Black and white TIFF images keep 8 bits per pixel, with only the
 * values 0 and 255.
 */
public class MuPdfRenderer implements PdfToImage.Renderer {

	public boolean saveImage(byte[] docByte, String outputFilePath, ImageType imageType, ColorMode colorMode,
			TiffCompression compressionType, int quality, boolean transparent) throws Exception {
		boolean binary = colorMode == ColorMode.BNW || colorMode == ColorMode.BNWI;
		Document document = Document.openDocument(docByte, "application/pdf");
		try {
			switch (imageType) {
			case JPG:
			case PNG:
			case PAM:
			case PNM: {
				if (binary)
					return false;
				// JPG and PNM have no alpha channel
				boolean alpha = transparent && (imageType == ImageType.PNG || imageType == ImageType.PAM);
				Pixmap pixmap = render(document, 0, colorMode, alpha);
				try {
					if (imageType == ImageType.JPG)
						pixmap.saveAsJPEG(outputFilePath, quality);
					else if (imageType == ImageType.PNG)
						pixmap.saveAsPNG(outputFilePath);
					else if (imageType == ImageType.PAM)
						pixmap.saveAsPAM(outputFilePath);
					else
						pixmap.saveAsPNM(outputFilePath);
				} finally {
					pixmap.destroy();
				}
				return true;
			}
			case GIF:
			case BMP:
				return ImageIO.write(toImage(document, 0, colorMode, false), imageType.toString(), new File(outputFilePath));
			case TIFF:
				writeTiff(document, 0, 1, outputFilePath, colorMode, compressionType, quality, transparent);
				return true;
			default:
				return false;
			}
		} finally {
			document.destroy();
		}
	}

	public boolean saveMultiTiff(byte[] docByte, String outputFilePath, ColorMode colorMode,
			TiffCompression compressionType, int quality, boolean transparent) throws Exception {
		Document document = Document.openDocument(docByte, "application/pdf");
		try {
			writeTiff(document, 0, document.countPages(), outputFilePath, colorMode, compressionType, quality, transparent);
			return true;
		} finally {
			document.destroy();
		}
	}

	/** Renders a page in gray for the gray and black and white modes, in RGB otherwise. */
	private Pixmap render(Document document, int pageIndex, ColorMode colorMode, boolean alpha) {
		Page page = document.loadPage(pageIndex);
		try {
			ColorSpace colorSpace = colorMode == ColorMode.RGB ? ColorSpace.DeviceRGB : ColorSpace.DeviceGray;
			Pixmap pixmap = page.toPixmap(Matrix.Identity(), colorSpace, alpha, true);
			pixmap.setResolution(72, 72);
			return pixmap;
		} finally {
			page.destroy();
		}
	}

	/**
	 * Renders a page into a BufferedImage: RGB (with premultiplied alpha if
	 * asked for), 8-bit gray, or for the black and white modes, 8-bit gray
	 * with black and white only, by a threshold or by Floyd-Steinberg
	 * dithering.
	 */
	private BufferedImage toImage(Document document, int pageIndex, ColorMode colorMode, boolean alpha) {
		boolean rgb = colorMode == ColorMode.RGB;
		Pixmap pixmap = render(document, pageIndex, colorMode, alpha && rgb);
		try {
			int width = pixmap.getWidth(), height = pixmap.getHeight();
			int n = pixmap.getNumberOfComponents(); // including alpha
			int stride = pixmap.getStride();
			byte[] samples = pixmap.getSamples();
			if (rgb) {
				BufferedImage image = new BufferedImage(width, height,
						alpha ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB);
				int[] row = new int[width];
				for (int y = 0; y < height; y++) {
					for (int x = 0, i = y * stride; x < width; x++, i += n) {
						int argb = (samples[i] & 0xff) << 16 | (samples[i + 1] & 0xff) << 8 | (samples[i + 2] & 0xff);
						row[x] = argb | (alpha ? (samples[i + 3] & 0xff) << 24 : 0xff000000);
					}
					// MuPDF's samples are premultiplied like the raster's, which setRGB would multiply again
					image.getRaster().setDataElements(0, y, width, 1, row);
				}
				return image;
			}
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
			byte[] gray = new byte[width * height];
			for (int y = 0; y < height; y++)
				System.arraycopy(samples, y * stride, gray, y * width, width);
			if (colorMode == ColorMode.BNW)
				threshold(gray);
			else if (colorMode == ColorMode.BNWI)
				dither(gray, width, height);
			image.getRaster().setDataElements(0, 0, width, height, gray);
			return image;
		} finally {
			pixmap.destroy();
		}
	}

	private static void threshold(byte[] gray) {
		for (int i = 0; i < gray.length; i++)
			gray[i] = (gray[i] & 0xff) < 128 ? 0 : (byte) 255;
	}

	/** Floyd-Steinberg error diffusion to black and white. */
	private static void dither(byte[] gray, int width, int height) {
		int[] error = new int[(width + 2) * 2];
		for (int y = 0; y < height; y++) {
			int cur = (y % 2) * (width + 2), next = ((y + 1) % 2) * (width + 2);
			java.util.Arrays.fill(error, next, next + width + 2, 0);
			for (int x = 0; x < width; x++) {
				int i = y * width + x;
				int value = (gray[i] & 0xff) + error[cur + x + 1] / 16;
				int out = value < 128 ? 0 : 255;
				gray[i] = (byte) out;
				int e = value - out;
				error[cur + x + 2] += e * 7;
				error[next + x] += e * 3;
				error[next + x + 1] += e * 5;
				error[next + x + 2] += e;
			}
		}
	}

	/**
	 * Writes pages first to first + count - 1 to one TIFF file, replacing
	 * the file if it exists, with each page rendered only while it is written.
	 */
	private void writeTiff(Document document, int first, int count, String outputFilePath, ColorMode colorMode,
			TiffCompression compressionType, int quality, boolean transparent) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("TIFF");
		if (!writers.hasNext())
			throw new IOException("This Java runtime cannot write TIFF images");
		ImageWriter writer = writers.next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		if (compressionType == TiffCompression.NONE) {
			param.setCompressionMode(ImageWriteParam.MODE_DISABLED);
		} else {
			param.setCompressionType(tiffCompressionName(compressionType));
			if (compressionType == TiffCompression.JPEG || compressionType == TiffCompression.ZLIB
					|| compressionType == TiffCompression.DEFLATE)
				param.setCompressionQuality(quality / 100f);
		}
		File file = new File(outputFilePath);
		if (file.exists() && !file.delete())
			throw new IOException("Cannot replace " + file);
		ImageOutputStream out = ImageIO.createImageOutputStream(file);
		try {
			writer.setOutput(out);
			writer.prepareWriteSequence(null);
			for (int i = first; i < first + count; i++)
				writer.writeToSequence(new IIOImage(toImage(document, i, colorMode, transparent), null, null), param);
			writer.endWriteSequence();
		} finally {
			writer.dispose();
			out.close();
		}
	}

	/** The names of the compression types of the JDK's TIFF writer. */
	private static String tiffCompressionName(TiffCompression compressionType) {
		switch (compressionType) {
		case LZW:
			return "LZW";
		case JPEG:
			return "JPEG";
		case ZLIB:
			return "ZLib";
		case PACKBITS:
			return "PackBits";
		case DEFLATE:
			return "Deflate";
		default:
			throw new IllegalArgumentException(compressionType.toString());
		}
	}
}
