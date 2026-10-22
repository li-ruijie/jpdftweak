package jpdftweak.core;

import java.io.File;
import java.io.IOException;

/**
 * PdfToImage Class uses MuPDF and ImageIO libraries to
 * produce image files from PDF pages. The image file types
 * that can be produced are: "JPG", "PNG", "GIF", "PAM",
 * "PNM", "BMP", "TIFF" singlepage, "TIFF" multipage.
 * MuPDF's Java binding requires a shared library located in the folder
 * of jpdftweak.jar to properly convert PDF pages to the images.
 * The MuPDF calls are in {@link MuPdfRenderer}, which is loaded
 * through a {@link NativeClassLoader} that finds the library there,
 * so this class never loads a MuPDF class itself. Those shared libraries are
 * built for Windows x64, Linux x64, and Linux arm64, so this feature
 * is not available on other systems.
 * 
 * @author thanasis
 *
 */
public class PdfToImage {

	/**
	 * The calls into MuPDF, implemented by {@link MuPdfRenderer}. Each
	 * returns false if the image could not be saved, which it does for a
	 * color mode it does not support with that file type.
	 */
	public static interface Renderer {
		boolean saveImage(byte[] docByte, String outputFilePath, ImageType imageType, ColorMode colorMode,
				TiffCompression compressionType, int quality, boolean transparent) throws Exception;
		boolean saveMultiTiff(byte[] docByte, String outputFilePath, ColorMode colorMode,
				TiffCompression compressionType, int quality, boolean transparent) throws Exception;
	}

	private static Renderer renderer;

	private ColorMode colorMode;
	private TiffCompression compressionType;
	private int quality;
	private boolean transparent;
	private ImageType imageType;
	boolean burstImages;
	
	
	public static enum ColorMode{
		RGB("RGB"),
		GRAY("Grayscale"),
		BNW("Black and White"),
		BNWI("Black and White Indexed");
		
		private String name;

		private ColorMode(String name) {
			this.name = name;
		}

		@Override
		public String toString(){
			return name;
		}
	}
	
	public static enum TiffCompression {
		NONE, LZW, JPEG, ZLIB, PACKBITS, DEFLATE;
	}
	
	public static enum ImageType {
		PDF,JPG,PNG,GIF,PAM,PNM,BMP,TIFF;
	}
	
	public PdfToImage(boolean burstImages, ColorMode colorMode, ImageType imageType, TiffCompression compressionType, int quality, boolean transparent) throws IOException {
		this.colorMode = colorMode;
		this.compressionType = compressionType;
		this.burstImages = burstImages;
		this.imageType = imageType;
		this.transparent = transparent;
		this.quality = quality;
	}

	public boolean shouldExecute() {
		return this.burstImages;
	}

	/**
	 * Gets the name of the image file for one burst page.
	 * @param base The output file name up to and including the page number
	 * @param suffix The rest of the output file name; its extension is
	 * replaced by the one of the image type, or added if it has none
	 */
	public String getImageFileName(String base, String suffix) {
		return base + replaceExtension(suffix, imageType.toString().toLowerCase());
	}

	private static String replaceExtension(String name, String extension) {
		int nameStart = Math.max(name.lastIndexOf('/'), name.lastIndexOf(File.separatorChar)) + 1;
		int dot = name.lastIndexOf('.');
		if (dot < nameStart)
			dot = name.length();
		return name.substring(0, dot) + "." + extension;
	}

	/**
	 * Gets a PDF document in byte array form and converts the
	 * first page to an image file type, user had chosen before.  
	 * @param docByte PDF document in Byte array
	 * @param outputFilePath The path that image file must be created,
	 * see {@link #getImageFileName(String, String)}
	 * @throws IOException  
	 */
	public void convertToImage(byte[] docByte, String outputFilePath)
			throws IOException {
		boolean saved;
		try {
			saved = getRenderer().saveImage(docByte, outputFilePath, imageType, colorMode, compressionType, quality, transparent);
		} catch (LinkageError e) {
			throw libraryError(e);
		} catch (Exception e) {
			IOException ioe = new IOException(e.toString());
			ioe.initCause(e);
			throw ioe;
		}
		if (!saved)
			throw new IOException("Cannot save a page as " + imageType + " in color mode \"" + colorMode + "\"");
	}

	/**
	 * Gets a PDF PdfDocument in byte array form and converts it
	 * into a multipage "TIFF" image file.
	 * @param docByte PDF PdfDocument in Byte array
	 * @param outputPath The path that "TIFF" file must be created
	 * @throws IOException
	 */
	public void convertToMultiTiff(byte[] docByte, String outputPath) throws IOException{
		boolean saved;
		try {
			saved = getRenderer().saveMultiTiff(docByte, replaceExtension(outputPath, "tiff"), colorMode, compressionType, quality, transparent);
		} catch (LinkageError e) {
			throw libraryError(e);
		} catch (Exception e) {
			IOException ioe = new IOException(e.toString());
			ioe.initCause(e);
			throw ioe;
		}
		if (!saved)
			throw new IOException("Cannot save the pages as TIFF in color mode \"" + colorMode + "\"");
	}

	private static synchronized Renderer getRenderer() throws Exception {
		if (renderer == null) {
			// A name, not MuPdfRenderer.class, so that the parent loader does not load it too
			String rendererClass = "jpdftweak.core.MuPdfRenderer";
			ClassLoader loader = new NativeClassLoader(PdfToImage.class.getClassLoader(), new File(getJarFolder()),
					"com.artifex.mupdf.fitz.", rendererClass);
			renderer = (Renderer) loader.loadClass(rendererClass).getDeclaredConstructor().newInstance();
		}
		return renderer;
	}

	/**
	 * A shared library that is present but cannot be loaded (wrong
	 * architecture, damaged file) makes MuPDF throw an Error.
	 */
	private static IOException libraryError(LinkageError e) {
		IOException ioe = new IOException("The MuPDF shared library in \"" + getJarFolder() + "\" could not be loaded: " + e);
		ioe.initCause(e);
		return ioe;
	}

	/**
	 * A "trick" method to get not the path that the jpdftweak.jar is running
	 * but the path that jpdftweak.jar exists.
	 * @return
	 */
	public static String getJarFolder() {
		String s = System.getProperty("java.class.path");
		String separateSymbol = File.pathSeparator;
		if (s.contains(separateSymbol)) { //jpdftweak.jar was executed from a Java IDE
			String[] one = s.split(separateSymbol);
			for (String a : one) {
				if (a.contains("lib")) {
					return a.substring(0, a.lastIndexOf("lib")+3);
				}
			}
		} else {
			s = new File(s).getAbsoluteFile().getParentFile().getPath();
		}
		return s;

	}

	public static boolean is64bit() {
		String val = System.getProperty("sun.arch.data.model");
		return val.equals("64");
	}
	
	/**
	 * According to System type and architecture search for 
	 * appropriate shared library. 
	 * @return
	 */
	public static String checkForLibraries(){
		try {
			// Without initializing it, as that would load the library through the wrong class loader
			Class.forName("com.artifex.mupdf.fitz.Context", false, PdfToImage.class.getClassLoader());
		} catch (Throwable t) {
			return "nomupdf";
		}
		String sharedLibraryName = null;
			if(is64bit()){
				if (File.pathSeparatorChar == ';'){ //Windows Systems
					sharedLibraryName = "mupdf_java64.dll";
				}
				else{
					sharedLibraryName = "libmupdf_java64.so";
				}
			}
			else{
				if (File.pathSeparatorChar == ';'){
					sharedLibraryName = "mupdf_java32.dll";
				}
				else{
					sharedLibraryName = "libmupdf_java32.so";
				}
			}
			File f = new File(getJarFolder(),sharedLibraryName);
			if (f.exists()){
				return null;
			}
			else{
				return sharedLibraryName;
			}
	}

}
