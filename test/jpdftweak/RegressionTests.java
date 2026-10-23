package jpdftweak;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import jpdftweak.cli.CommandLineInterface;
import jpdftweak.core.PdfBookmark;
import jpdftweak.core.PdfInputFile;
import jpdftweak.core.PdfToImage;
import jpdftweak.core.PdfTweak;
import jpdftweak.gui.MainForm;
import jpdftweak.gui.TableComponent;
import jpdftweak.gui.tabs.BookmarkTab;
import jpdftweak.gui.tabs.EncryptSignTab;
import jpdftweak.gui.tabs.InteractionTab;
import jpdftweak.gui.tabs.OutputTab;

import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PdfAction;
import com.itextpdf.text.pdf.PdfAnnotation;
import com.itextpdf.text.pdf.PdfArray;
import com.itextpdf.text.pdf.PdfDestination;
import com.itextpdf.text.pdf.PdfDictionary;
import com.itextpdf.text.pdf.PdfName;
import com.itextpdf.text.pdf.PdfNull;
import com.itextpdf.text.pdf.PdfNumber;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfStamper;
import com.itextpdf.text.pdf.PdfString;
import com.itextpdf.text.pdf.PdfWriter;
import com.itextpdf.text.pdf.SimpleBookmark;

/**
 * Regression tests, run by the "test" target of build.xml, and the runner for
 * them and for the tests in the other {@link #SUITES}.
 *
 * Each test is a static method taking its own empty working directory. The
 * method name starts with the group it belongs to. "main_" tests run in a JVM
 * that can load MuPDF's shared library, and "nolib_" tests in a JVM where a
 * file of that name exists but cannot be loaded. The system property
 * jpdftweak.test.natives names the directory holding that file.
 */
public class RegressionTests {

	interface Task {
		void run() throws Exception;
	}

	/** The classes whose test methods the runner calls. */
	static final Class<?>[] SUITES = { RegressionTests.class, OperationTests.class };

	public static void main(String[] args) throws Exception {
		String group = args[0];
		File work = new File("build/test-work", group).getAbsoluteFile();
		deleteTree(work);
		List<Method> tests = new ArrayList<Method>();
		for (Class<?> suite : SUITES) {
			for (Method m : suite.getDeclaredMethods()) {
				if (m.getName().startsWith(group + "_"))
					tests.add(m);
			}
		}
		if (tests.isEmpty())
			throw new IllegalStateException("No tests in group " + group);
		Collections.sort(tests, new Comparator<Method>() {
			public int compare(Method a, Method b) {
				return a.getName().compareTo(b.getName());
			}
		});
		int failed = 0;
		for (Method m : tests) {
			File dir = new File(work, m.getName());
			dir.mkdirs();
			try {
				m.invoke(null, dir);
				System.out.println("PASS " + m.getName());
			} catch (InvocationTargetException ex) {
				failed++;
				System.out.println("FAIL " + m.getName() + ": " + ex.getCause());
				ex.getCause().printStackTrace(System.out);
			}
		}
		System.out.println(group + ": " + (tests.size() - failed) + " passed, " + failed + " failed");
		// exit explicitly, as the GUI tests leave the AWT threads running
		System.exit(failed > 0 ? 1 : 0);
	}

	// ---- tests ----

	static void main_copyKeepsPageCount(File dir) throws Exception {
		File in = fixture(dir, "in.pdf", 3);
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), out.getPath());
		assertEquals(3, pageCount(out), "page count");
	}

	static void main_shuffleScalesEachOffsetByItsOwnPercentFlag(File dir) throws Exception {
		// An A4 page (595 x 842) whose link points at itself, at the top-left corner.
		File in = fixture(dir, "in.pdf", 1, 1, 1);
		File absXPercentY = new File(dir, "absx-percenty.pdf");
		cli(in.getPath(), "-preserveLinks", "On", "-shuffle", "1:!+1N1+0+50%", absXPercentY.getPath());
		assertEquals("1 0 0 1 0 421 cm", firstPlacement(absXPercentY), "placement for +0 +50%");
		assertEquals(842f + 421f, linkDestinationTop(absXPercentY), "link destination top for +0 +50%");
		File percentXAbsY = new File(dir, "percentx-absy.pdf");
		cli(in.getPath(), "-shuffle", "1:!+1N1+50%+10", percentXAbsY.getPath());
		assertEquals("1 0 0 1 297.5 10 cm", firstPlacement(percentXAbsY), "placement for +50% +10");
	}

	static void main_shuffleDropsLinksToPagesItLeavesOut(File dir) throws Exception {
		// Page 1 links to page 2; the rule keeps the first page of every pair.
		File in = fixture(dir, "in.pdf", 2, 1, 2);
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-preserveLinks", "On", "-shuffle", "2:!+1N1+0%+0%", out.getPath());
		assertEquals(1, pageCount(out), "page count");
		PdfReader reader = new PdfReader(out.getPath());
		try {
			assertTrue(reader.getPageN(1).getAsArray(PdfName.ANNOTS) == null, "link to the left-out page dropped");
		} finally {
			reader.close();
		}
	}

	static void main_transitionHorizontalBlindsIsSelectable(File dir) throws Exception {
		File in = fixture(dir, "in.pdf", 2);
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-trans", "1:Vertical_Blinds,1", "-trans", "2:Horizontal_Blinds,1", out.getPath());
		PdfReader reader = new PdfReader(out.getPath());
		try {
			for (int page = 1; page <= 2; page++) {
				PdfDictionary trans = reader.getPageN(page).getAsDict(PdfName.TRANS);
				assertTrue(trans != null, "page " + page + " has a transition");
				assertEquals(new PdfName("Blinds"), trans.getAsName(PdfName.S), "page " + page + " /S");
				assertEquals(page == 1 ? PdfName.V : PdfName.H, trans.getAsName(PdfName.DM), "page " + page + " /Dm");
			}
		} finally {
			reader.close();
		}
	}

	static void main_transitionAcceptsMultiDigitPages(File dir) throws Exception {
		File in = fixture(dir, "in.pdf", 12);
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-trans", "10-~1:Dissolve,1", out.getPath());
		PdfReader reader = new PdfReader(out.getPath());
		try {
			for (int page = 9; page <= 12; page++) {
				PdfDictionary trans = reader.getPageN(page).getAsDict(PdfName.TRANS);
				if (page == 9) {
					assertTrue(trans == null, "page 9 has no transition");
				} else {
					assertTrue(trans != null, "page " + page + " has a transition");
					assertEquals(new PdfName("Dissolve"), trans.getAsName(PdfName.S), "page " + page + " /S");
				}
			}
		} finally {
			reader.close();
		}
	}

	static void main_imageBurstNamesOnlyChangeTheFileExtension(File dir) throws Exception {
		PdfToImage png = new PdfToImage(true, PdfToImage.ColorMode.RGB, PdfToImage.ImageType.PNG,
				PdfToImage.TiffCompression.NONE, 100, false);
		String sep = File.separator;
		assertEquals("a.dir" + sep + "pg1.png", png.getImageFileName("a.dir" + sep + "pg1", ""), "no extension");
		assertEquals("a.dir" + sep + "pg1.png", png.getImageFileName("a.dir" + sep + "pg1", ".pdf"), "extension replaced");
		assertEquals("pg1.2.png", png.getImageFileName("pg1.2", ""), "dot in the page label kept");
		assertEquals("pg1.2_x.png", png.getImageFileName("pg1.2", "_x"), "suffix without extension");

		final File in = fixture(dir, "in.pdf", 2);
		final File folder = new File(dir, "a.dir");
		withJarFolder(nativesDir(), new Task() {
			public void run() throws Exception {
				cli(in.getPath(), "-burstfiletype", "PNG", "-ob", new File(folder, "pg*").getPath());
			}
		});
		for (int page = 1; page <= 2; page++) {
			File image = new File(folder, "pg" + page + ".png");
			assertTrue(image.isFile(), image + " written");
		}
		assertTrue(!new File(dir, "a.png").exists(), "nothing written next to the folder");
	}

	static void main_tiffOutputReplacesAnExistingFile(File dir) throws Exception {
		final File in = fixture(dir, "in.pdf", 2);
		final File multi = new File(dir, "multi.tiff");
		final File burst = new File(dir, "t*.tiff");
		withJarFolder(nativesDir(), new Task() {
			public void run() throws Exception {
				for (int run = 1; run <= 2; run++) {
					cli(in.getPath(), "-oi", multi.getPath());
					cli(in.getPath(), "-burstfiletype", "TIFF", "-ob", burst.getPath());
				}
			}
		});
		assertEquals(2, tiffPageCount(multi), "pages in " + multi + " after writing it twice");
		for (int page = 1; page <= 2; page++) {
			File image = new File(dir, "t" + page + ".tiff");
			assertEquals(1, tiffPageCount(image), "pages in " + image + " after writing it twice");
		}
	}

	/** The JDK's TIFF writer cannot JPEG-compress an image with an alpha channel. */
	static void main_tiffJpegCompressionRefusesTransparency(File dir) throws Exception {
		final File in = fixture(dir, "in.pdf", 2);
		final String[][] commands = {
				{ in.getPath(), "-burstfiletype", "TIFF", "-tiffcompression", "JPEG", "-transparency", "YES",
						"-ob", new File(dir, "t*.tiff").getPath() },
				{ in.getPath(), "-tiffcompression", "JPEG", "-transparency", "YES",
						"-oi", new File(dir, "multi.tiff").getPath() } };
		for (final String[] command : commands) {
			try {
				withJarFolder(nativesDir(), new Task() {
					public void run() throws Exception {
						cli(command);
					}
				});
				throw new AssertionError("Accepted " + Arrays.toString(command));
			} catch (IOException expected) {
				assertEquals("TIFF JPEG compression cannot keep transparency. Turn transparency off or choose another compression.",
						expected.getMessage(), "error of " + Arrays.toString(command));
			}
		}
		for (String name : dir.list())
			assertTrue(!name.endsWith(".tiff"), name + " not written");
		// Only RGB keeps an alpha channel, so a grayscale TIFF can still use JPEG.
		final File gray = new File(dir, "gray.tiff");
		withJarFolder(nativesDir(), new Task() {
			public void run() throws Exception {
				cli(in.getPath(), "-colormode", "GRAY", "-tiffcompression", "JPEG", "-transparency", "YES",
						"-oi", gray.getPath());
			}
		});
		assertEquals(2, tiffPageCount(gray), "pages in " + gray);
	}

	/** The Output tab offers transparency for a TIFF only while it is not JPEG-compressed. */
	static void gui_outputTabDisablesTransparencyForTiffJpeg(File dir) throws Exception {
		final boolean[] enabled = new boolean[6];
		withJarFolder(libraryStandIn(dir), new Task() {
			public void run() throws Exception {
				SwingUtilities.invokeAndWait(new Runnable() {
					public void run() {
						try {
							OutputTab tab = new OutputTab(null);
							JCheckBox multipageTiff = (JCheckBox) field(tab, "multipageTiff");
							JCheckBox burst = (JCheckBox) field(tab, "burst");
							JCheckBox transparent = (JCheckBox) field(tab, "transparent");
							JComboBox fileType = (JComboBox) field(tab, "fileType");
							JComboBox colorMode = (JComboBox) field(tab, "colorMode");
							JComboBox compressionType = (JComboBox) field(tab, "compressionType");
							multipageTiff.doClick();
							enabled[0] = transparent.isEnabled();
							compressionType.setSelectedItem(PdfToImage.TiffCompression.JPEG);
							enabled[1] = transparent.isEnabled();
							colorMode.setSelectedItem(PdfToImage.ColorMode.GRAY);
							colorMode.setSelectedItem(PdfToImage.ColorMode.RGB);
							enabled[2] = transparent.isEnabled();
							multipageTiff.doClick();
							burst.doClick();
							fileType.setSelectedItem(PdfToImage.ImageType.TIFF);
							enabled[3] = transparent.isEnabled();
							fileType.setSelectedItem(PdfToImage.ImageType.PNG);
							enabled[4] = transparent.isEnabled();
							fileType.setSelectedItem(PdfToImage.ImageType.TIFF);
							compressionType.setSelectedItem(PdfToImage.TiffCompression.LZW);
							enabled[5] = transparent.isEnabled();
						} catch (Exception ex) {
							throw new RuntimeException(ex);
						}
					}
				});
			}
		});
		assertEquals(true, enabled[0], "transparency for an uncompressed multipage TIFF");
		assertEquals(false, enabled[1], "transparency after choosing JPEG");
		assertEquals(false, enabled[2], "transparency after switching back to RGB with JPEG");
		assertEquals(false, enabled[3], "transparency for a burst TIFF with JPEG");
		assertEquals(true, enabled[4], "transparency for PNG with JPEG still chosen for TIFF");
		assertEquals(true, enabled[5], "transparency for a burst TIFF after choosing LZW");
	}

	/** The Output tab keeps the quality slider for a TIFF compression that uses it when the file type changes. */
	static void gui_outputTabKeepsQualityForTiffJpeg(File dir) throws Exception {
		final boolean[] enabled = new boolean[5];
		withJarFolder(libraryStandIn(dir), new Task() {
			public void run() throws Exception {
				SwingUtilities.invokeAndWait(new Runnable() {
					public void run() {
						try {
							OutputTab tab = new OutputTab(null);
							JCheckBox multipageTiff = (JCheckBox) field(tab, "multipageTiff");
							JCheckBox burst = (JCheckBox) field(tab, "burst");
							JSlider qualitySlider = (JSlider) field(tab, "qualitySlider");
							JComboBox fileType = (JComboBox) field(tab, "fileType");
							JComboBox compressionType = (JComboBox) field(tab, "compressionType");
							multipageTiff.doClick();
							enabled[0] = qualitySlider.isEnabled();
							compressionType.setSelectedItem(PdfToImage.TiffCompression.JPEG);
							enabled[1] = qualitySlider.isEnabled();
							multipageTiff.doClick();
							multipageTiff.doClick();
							enabled[2] = qualitySlider.isEnabled();
							multipageTiff.doClick();
							burst.doClick();
							fileType.setSelectedItem(PdfToImage.ImageType.TIFF);
							enabled[3] = qualitySlider.isEnabled();
							compressionType.setSelectedItem(PdfToImage.TiffCompression.LZW);
							enabled[4] = qualitySlider.isEnabled();
						} catch (Exception ex) {
							throw new RuntimeException(ex);
						}
					}
				});
			}
		});
		assertEquals(false, enabled[0], "quality for an uncompressed multipage TIFF");
		assertEquals(true, enabled[1], "quality after choosing JPEG");
		assertEquals(true, enabled[2], "quality for a multipage TIFF chosen again with JPEG");
		assertEquals(true, enabled[3], "quality for a burst TIFF with JPEG");
		assertEquals(false, enabled[4], "quality for a burst TIFF after choosing LZW");
	}

	/** DEFLATE, like ZLIB, takes the quality as its compression level. */
	static void gui_outputTabOffersQualityForTiffDeflate(File dir) throws Exception {
		final boolean[] enabled = new boolean[2];
		withJarFolder(libraryStandIn(dir), new Task() {
			public void run() throws Exception {
				SwingUtilities.invokeAndWait(new Runnable() {
					public void run() {
						try {
							OutputTab tab = new OutputTab(null);
							JCheckBox multipageTiff = (JCheckBox) field(tab, "multipageTiff");
							JSlider qualitySlider = (JSlider) field(tab, "qualitySlider");
							JComboBox compressionType = (JComboBox) field(tab, "compressionType");
							multipageTiff.doClick();
							compressionType.setSelectedItem(PdfToImage.TiffCompression.DEFLATE);
							enabled[0] = qualitySlider.isEnabled();
							multipageTiff.doClick();
							multipageTiff.doClick();
							enabled[1] = qualitySlider.isEnabled();
						} catch (Exception ex) {
							throw new RuntimeException(ex);
						}
					}
				});
			}
		});
		assertEquals(true, enabled[0], "quality after choosing DEFLATE");
		assertEquals(true, enabled[1], "quality for a multipage TIFF chosen again with DEFLATE");
	}

	/**
	 * "Load from document" selected the crypto mode as a list index, which
	 * failed for AES-256 (3) and with the flag for unencrypted metadata (8),
	 * and then loaded nothing else.
	 */
	static void gui_encryptionLoadsFromAes256AndUnencryptedMetadata(File dir) throws Exception {
		File plain = fixture(dir, "plain.pdf", 1);
		final int[] modes = { PdfWriter.STANDARD_ENCRYPTION_128 | PdfWriter.DO_NOT_ENCRYPT_METADATA, PdfWriter.ENCRYPTION_AES_256 };
		final File[] files = new File[modes.length];
		for (int i = 0; i < modes.length; i++) {
			files[i] = new File(dir, "encrypted" + i + ".pdf");
			PdfStamper stamper = new PdfStamper(new PdfReader(plain.getPath()), new FileOutputStream(files[i]));
			stamper.setEncryption("user".getBytes("ISO-8859-1"), "owner".getBytes("ISO-8859-1"),
					PdfWriter.ALLOW_COPY | PdfWriter.ALLOW_SCREENREADERS, modes[i]);
			stamper.close();
		}
		final String[] loaded = new String[modes.length];
		SwingUtilities.invokeAndWait(new Runnable() {
			public void run() {
				MainForm form = new MainForm();
				try {
					EncryptSignTab tab = new EncryptSignTab(form);
					Method load = EncryptSignTab.class.getDeclaredMethod("loadEncryptionData");
					load.setAccessible(true);
					for (int i = 0; i < files.length; i++) {
						JComboBox mode = (JComboBox) field(tab, "encryptMode");
						mode.setSelectedIndex(0);
						form.setInputFile(new PdfInputFile(files[i], "owner"));
						try {
							load.invoke(tab);
						} catch (InvocationTargetException ex) {
							loaded[i] = "error " + ex.getCause();
							continue;
						}
						List<String> permissions = new ArrayList<String>();
						for (JCheckBox box : (JCheckBox[]) field(tab, "permissionBoxes")) {
							if (box.isSelected())
								permissions.add(box.getText());
						}
						loaded[i] = mode.getSelectedIndex() + " " + ((JCheckBox) field(tab, "noEncryptMetadata")).isSelected() + " "
								+ ((JTextField) field(tab, "ownerPassword")).getText() + " "
								+ ((JTextField) field(tab, "userPassword")).getText() + " " + permissions;
					}
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				} finally {
					form.dispose();
				}
			}
		});
		assertEquals("1 true owner user [Copy, ScreenReaders]", loaded[0], "128-bit RC4 without encrypted metadata");
		// AES-256 is not in the list, so the mode stays as it was. It keeps only
		// hashes of the passwords, so the user password cannot be recovered.
		assertEquals("0 false owner  [Copy, ScreenReaders]", loaded[1], "AES-256");
	}

	/**
	 * Bad input in the transitions table or in a bookmark file threw a
	 * RuntimeException, which showed the dialog for unexpected errors
	 * instead of saying what was wrong. A failed export emptied the file.
	 */
	static void gui_badTablesAndBookmarkFilesAreReported(File dir) throws Exception {
		final File in = fixture(dir, "in.pdf", 3);
		final File badCsv = OperationTests.write(new File(dir, "bad.csv"), "1;;Title;1\nnot a bookmark\n");
		final File existing = OperationTests.write(new File(dir, "existing.csv"), "1;;Kept;1\n");
		final List<String> messages = new ArrayList<String>();
		SwingUtilities.invokeAndWait(new Runnable() {
			public void run() {
				try {
					InteractionTab interaction = new InteractionTab(null);
					((JCheckBox) field(interaction, "addTransitions")).setSelected(true);
					TableComponent transitions = (TableComponent) field(interaction, "transitions");
					Object[][] rows = { { 1, null, "None", 0, -1 }, { 1, -1, "Bogus", 0, -1 }, { -5, -1, "None", 0, -1 } };
					for (Object[] row : rows) {
						transitions.clear();
						transitions.addRow(row);
						PdfTweak tweak = new PdfTweak(new PdfInputFile(in, ""), false);
						try {
							interaction.run(tweak);
							messages.add("accepted");
						} catch (IOException ex) {
							messages.add(ex.getMessage());
						} finally {
							tweak.cleanup();
						}
					}
					BookmarkTab bookmarks = new BookmarkTab(null);
					Method read = BookmarkTab.class.getDeclaredMethod("readCSV", File.class);
					read.setAccessible(true);
					try {
						read.invoke(bookmarks, badCsv);
						messages.add("accepted");
					} catch (InvocationTargetException ex) {
						messages.add(ex.getCause().getClass().getSimpleName() + ": " + ex.getCause().getMessage());
					}
					TableComponent table = (TableComponent) field(bookmarks, "bookmarks");
					table.addRow(1, false, "Title", 1, "", false, false, "foo");
					Method write = BookmarkTab.class.getDeclaredMethod("writeCSV", File.class);
					write.setAccessible(true);
					try {
						write.invoke(bookmarks, existing);
						messages.add("accepted");
					} catch (InvocationTargetException ex) {
						messages.add(ex.getCause().getClass().getSimpleName() + ": " + ex.getCause().getMessage());
					}
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			}
		});
		assertEquals("[Please fill in every cell of the transitions table., Unknown transition: Bogus, Page -5 does not exist., "
				+ "IOException: Cannot parse bookmark, IOException: Invalid option (no equals sign): foo]", messages.toString(), "messages");
		assertEquals("1;;Kept;1\n", OperationTests.read(existing), "file kept after a failed export");
	}

	/** A folder with an empty file of the library's name, which is all the Output tab checks for. */
	static File libraryStandIn(File dir) throws IOException {
		File natives = new File(dir, "natives");
		natives.mkdirs();
		new FileOutputStream(new File(natives, File.pathSeparatorChar == ';' ? "mupdf_java64.dll" : "libmupdf_java64.so")).close();
		return natives;
	}

	static Object field(Object owner, String name) throws Exception {
		Field f = owner.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(owner);
	}

	/** iText's PdfImportedLink threw a NullPointerException on an /XYZ destination without coordinates. */
	static void main_linksKeepXyzDestinationsWithoutCoordinates(File dir) throws Exception {
		PdfArray dest = new PdfArray();
		dest.add(PdfName.XYZ);
		dest.add(PdfNull.PDFNULL);
		dest.add(PdfNull.PDFNULL);
		dest.add(PdfNull.PDFNULL);
		File in = OperationTests.linkFixture(dir, "in.pdf", 2, dest);
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-preserveLinks", "On", "-scale", "1190,1684", out.getPath());
		assertEquals("[2 /XYZ null null null]", OperationTests.linkDestinations(out, 1), "link destination");
	}

	/** With -preserveLinks, a background added the external links of the page again, which PdfStamper refuses. */
	static void main_backgroundKeepsTheLinksOfThePage(File dir) throws Exception {
		File in = new File(dir, "in.pdf");
		Document document = new Document(PageSize.A4);
		PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(in));
		document.open();
		document.add(new Paragraph("Page 1"));
		writer.addAnnotation(PdfAnnotation.createLink(writer, new Rectangle(50, 50, 150, 100), PdfAnnotation.HIGHLIGHT_INVERT,
				new PdfAction("https://example.com/")));
		PdfAnnotation internal = new PdfAnnotation(writer, new Rectangle(50, 150, 150, 200));
		internal.put(PdfName.SUBTYPE, PdfName.LINK);
		PdfDestination dest = new PdfDestination(PdfDestination.XYZ, 0, 842, 0);
		dest.addPage(writer.getPageReference(1));
		internal.put(PdfName.DEST, dest);
		writer.addAnnotation(internal);
		document.close();
		File background = OperationTests.pdfWithText(dir, "background.pdf", "Background");
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-preserveLinks", "On", "-background", background.getPath(), out.getPath());
		PdfReader reader = new PdfReader(out.getPath());
		try {
			PdfArray annots = reader.getPageN(1).getAsArray(PdfName.ANNOTS);
			List<String> links = new ArrayList<String>();
			for (int i = 0; annots != null && i < annots.size(); i++) {
				PdfDictionary link = annots.getAsDict(i);
				PdfDictionary action = link.getAsDict(PdfName.A);
				links.add(link.getAsArray(PdfName.RECT) + " " + (action != null ? action.get(PdfName.URI)
						: OperationTests.destination(reader, link.getAsArray(PdfName.DEST))));
			}
			assertEquals("[[50, 50, 150, 100] https://example.com/, [50, 150, 150, 200] 1 /XYZ 0 842 0]", links.toString(), "links");
		} finally {
			reader.close();
		}
		assertTrue(OperationTests.text(out, 1).contains("Background"), "background drawn");
	}

	/** Any option made -ou compress again, as it set up the image export that turned the flag off. */
	static void main_uncompressedOutputAfterAnOption(File dir) throws Exception {
		File in = fixture(dir, "in.pdf", 1);
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-watermark", "DRAFT,20,0.5", "-ou", out.getPath());
		PdfReader reader = new PdfReader(out.getPath());
		try {
			PdfDictionary contents = (PdfDictionary) PdfReader.getPdfObject(reader.getPageN(1).get(PdfName.CONTENTS));
			assertTrue(contents.get(PdfName.FILTER) == null, "-ou after -watermark writes an unfiltered content stream");
		} finally {
			reader.close();
		}
	}

	static void main_imageOptionsOnlyNeedTheLibraryForImageOutput(File dir) throws Exception {
		final File in = fixture(dir, "in.pdf", 1);
		final File out = new File(dir, "out.pdf");
		File noLibrary = new File(dir, "nolib");
		noLibrary.mkdirs();
		withJarFolder(noLibrary, new Task() {
			public void run() throws Exception {
				cli(in.getPath(), "-colormode", "GRAY", "-tiffcompression", "LZW", "-transparency", "NO",
						"-imagequality", "80", "-burstfiletype", "PDF", out.getPath());
			}
		});
		assertTrue(out.isFile(), "PDF written although the native library is missing");
		try {
			withJarFolder(noLibrary, new Task() {
				public void run() throws Exception {
					cli(in.getPath(), "-burstfiletype", "PNG", "-ob", new File(out.getParentFile(), "p*").getPath());
				}
			});
			throw new AssertionError("-burstfiletype PNG accepted without the native library");
		} catch (DocumentException expected) {
			assertTrue(expected.getMessage().contains("does not exist"), "missing library reported: " + expected.getMessage());
		}
	}

	static void main_jarManifestNamesTheLibrariesAndAllowsNativeAccess(File dir) throws Exception {
		JarFile jar = new JarFile("jpdftweak.jar");
		Attributes manifest;
		try {
			manifest = jar.getManifest().getMainAttributes();
		} finally {
			jar.close();
		}
		assertEquals("ALL-UNNAMED", manifest.getValue("Enable-Native-Access"), "Enable-Native-Access");
		List<String> files = Arrays.asList(new File("lib").list());
		for (String entry : manifest.getValue("Class-Path").split(" ")) {
			assertTrue(entry.startsWith("lib/") && files.contains(entry.substring(4)),
					"Class-Path entry " + entry + " names a file in lib/ with the same case");
		}
	}

	static void nolib_unloadableLibraryIsReportedAsAnError(File dir) throws Exception {
		final File in = fixture(dir, "in.pdf", 1);
		final File out = new File(dir, "p*.png");
		String err = captureErr(new Task() {
			public void run() throws Exception {
				withJarFolder(nativesDir(), new Task() {
					public void run() throws Exception {
						try {
							cli(in.getPath(), "-burstfiletype", "PNG", "-ob", out.getPath());
							throw new AssertionError("export succeeded with an unloadable library");
						} catch (IOException expected) {
							assertTrue(expected.getMessage().contains("could not be loaded"),
									"unloadable library reported: " + expected.getMessage());
						}
					}
				});
			}
		});
		assertTrue(!err.contains("Could not change Java Library Path"), "no library path error printed: " + err);
	}

	static void main_bookmarkOptionsKeepTheirOrder(File dir) throws Exception {
		// B="1" A="2", with the quotes escaped as in a bookmark file. A HashMap
		// iterates "A" before "B", the opposite of this line.
		String line = "1;O;Chapter;1;B=\\221\\22 A=\\222\\22";
		assertEquals(line, PdfBookmark.parseBookmark(line).toString(), "bookmark line round trip");
		File in = fixture(dir, "in.pdf", 1);
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-bookmark", line, out.getPath());
		PdfReader reader = new PdfReader(out.getPath());
		try {
			List<HashMap<String, Object>> outline = SimpleBookmark.getBookmark(reader);
			assertTrue(outline != null && outline.size() == 1, "one bookmark written");
			assertEquals("Chapter", outline.get(0).get("Title"), "bookmark title");
		} finally {
			reader.close();
		}
	}

	static void main_infoOmitsAnEmptyPageLabelPrefix(File dir) throws Exception {
		File plain = fixture(dir, "plain.pdf", 2);
		File in = new File(dir, "in.pdf");
		PdfReader reader = new PdfReader(plain.getPath());
		PdfStamper stamper = new PdfStamper(reader, new FileOutputStream(in));
		PdfDictionary label = new PdfDictionary();
		label.put(PdfName.S, PdfName.D);
		label.put(PdfName.P, new PdfString(""));
		PdfArray nums = new PdfArray();
		nums.add(new PdfNumber(0));
		nums.add(label);
		PdfDictionary labels = new PdfDictionary();
		labels.put(PdfName.NUMS, nums);
		reader.getCatalog().put(PdfName.PAGELABELS, labels);
		stamper.close();
		File info = new File(dir, "info.txt");
		cli(in.getPath(), "-info", info.getPath());
		String text = new String(Files.readAllBytes(info.toPath()), "UTF-8");
		assertTrue(text.contains("PAGELABELS 1=1"), "page label without prefix in " + text);
	}

	static void main_rotateAcceptsEveryDocumentedSpelling(File dir) throws Exception {
		File in = fixture(dir, "in.pdf", 1);
		String[] options = { "-rot", "-rotate", "-rotexp", "-rotateexp", "-rotexplicitly", "-rotateexplicitly" };
		for (String option : options) {
			File out = new File(dir, option.substring(1) + ".pdf");
			cli(in.getPath(), option, "R", out.getPath());
			assertTrue(out.isFile(), option + " accepted");
			PdfReader reader = new PdfReader(out.getPath());
			try {
				Rectangle size = reader.getPageSizeWithRotation(1);
				assertEquals(option.contains("exp") ? 0 : 90, reader.getPageRotation(1), option + " /Rotate");
				assertTrue(size.getWidth() > size.getHeight(), option + " turns the portrait page to landscape");
			} finally {
				reader.close();
			}
		}
	}

	static void main_rotationSurvivesLaterStepsOnOnePage(File dir) throws Exception {
		// Each of these removes the /Rotate entry that -rot sets, keeping the appearance.
		File in = fixture(dir, "in.pdf", 1);
		String[][] steps = { { "-rotexp", "R" }, { "-rot", "R", "-shuffle", "1:!+1N1+0%+0%" },
				{ "-rot", "R", "--", "-rotexp", "K" } };
		for (int i = 0; i < steps.length; i++) {
			File out = new File(dir, "out" + i + ".pdf");
			String[] args = new String[steps[i].length + 2];
			args[0] = in.getPath();
			System.arraycopy(steps[i], 0, args, 1, steps[i].length);
			args[args.length - 1] = out.getPath();
			cli(args);
			PdfReader reader = new PdfReader(out.getPath());
			try {
				Rectangle size = reader.getPageSizeWithRotation(1);
				String what = Arrays.toString(steps[i]);
				assertEquals(0, reader.getPageRotation(1), what + " /Rotate");
				assertTrue(size.getWidth() > size.getHeight(), what + " keeps the page turned to landscape");
			} finally {
				reader.close();
			}
		}
	}

	static void main_helpTextIsAccurate(File dir) throws Exception {
		String tiff = help("-tiffcompression");
		assertTrue(tiff.contains("LZW") && !tiff.contains("LWZ"), "-tiffcompression lists LZW: " + tiff);
		String color = help("-colormode");
		assertTrue(!color.contains("}]"), "-colormode has no stray bracket: " + color);
		String encmode = help("-encmode");
		assertTrue(encmode.contains(" -encmode {MODE}\n -encnometadata {MODE}\n    Set encryption mode."),
				"-encmode lines are separate: " + encmode);
		String permissions = help("-permissions");
		assertTrue(permissions.contains(" -permissions {PERMISSION}"), "-permissions spelt as accepted: " + permissions);
		String pagenumbers = help("-pagenumbers");
		assertTrue(pagenumbers.contains("A page number format looks like this:"), "-pagenumbers describes page number formats: " + pagenumbers);
		String output = help("-o");
		assertTrue(output.contains("-oi"), "-o lists the multipage TIFF flag: " + output);
		assertTrue(output.contains("remove"), "-o says that -om without -ou removes page marks: " + output);
	}

	static void main_viewerPreferenceDisplayDocumentTitle(File dir) throws Exception {
		File in = fixture(dir, "in.pdf", 1);
		// The corrected name, and the misspelt one that scripts may still use
		for (String name : new String[] { "Display_document_title", "Display_document_tile" }) {
			File out = new File(dir, name + ".pdf");
			cli(in.getPath(), "-viewpref", name + "=true", out.getPath());
			assertTrue(out.isFile(), name + " accepted");
			PdfReader reader = new PdfReader(out.getPath());
			try {
				PdfDictionary prefs = reader.getCatalog().getAsDict(PdfName.VIEWERPREFERENCES);
				assertTrue(prefs != null && prefs.getAsBoolean(PdfName.DISPLAYDOCTITLE).booleanValue(),
						name + " sets /DisplayDocTitle");
			} finally {
				reader.close();
			}
		}
	}

	static void main_windowsLauncherWorksFromAnyFolder(File dir) throws Exception {
		if (File.separatorChar != '\\')
			return; // jpdftweak.cmd is for Windows only
		File installed = new File(dir, "jpdftweak");
		File lib = new File(installed, "lib");
		lib.mkdirs();
		Files.copy(new File("jpdftweak.cmd").toPath(), new File(installed, "jpdftweak.cmd").toPath());
		Files.copy(new File("jpdftweak.jar").toPath(), new File(installed, "jpdftweak.jar").toPath());
		for (File jar : new File("lib").listFiles()) {
			if (jar.getName().endsWith(".jar"))
				Files.copy(jar.toPath(), new File(lib, jar.getName()).toPath());
		}
		ProcessBuilder pb = new ProcessBuilder("cmd", "/c", new File(installed, "jpdftweak.cmd").getPath(), "-v");
		pb.directory(dir);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		String output = new String(readAll(p.getInputStream()), "UTF-8");
		p.waitFor();
		assertTrue(output.contains("JPDF Tweak"), "launcher started from another folder printed the version: " + output);
	}

	static void main_moreInfoPageSizeRangesDoNotOverlap(File dir) throws Exception {
		File in = OperationTests.pdf(dir, "in.pdf", PageSize.A4, PageSize.A4, OperationTests.LANDSCAPE_A4);
		String info = OperationTests.info(dir, in, "-moreinfo");
		assertTrue(info.contains("PAGESIZE 1-2 595.0x842.0") && info.contains("PAGESIZE 3-3 842.0x595.0"),
				"one range per page size: " + info);
	}

	static void main_imageExportWritesBmpInEveryColourMode(File dir) throws Exception {
		final File in = fixture(dir, "in.pdf", 1);
		final File folder = dir;
		withJarFolder(nativesDir(), new Task() {
			public void run() throws Exception {
				for (String mode : new String[] { "RGB", "GRAY", "BNW", "BNWI" })
					cli(in.getPath(), "-burstfiletype", "BMP", "-colormode", mode, "-ob", new File(folder, mode + "-*.bmp").getPath());
			}
		});
		for (String mode : new String[] { "RGB", "GRAY", "BNW", "BNWI" }) {
			File bmp = new File(dir, mode + "-1.bmp");
			assertTrue(bmp.isFile(), mode + " BMP written");
			ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(bmp.toPath())).order(ByteOrder.LITTLE_ENDIAN);
			assertTrue(header.get(0) == 'B' && header.get(1) == 'M', mode + " BMP signature");
			assertEquals("595x842", header.getInt(18) + "x" + Math.abs(header.getInt(22)), mode + " BMP size");
		}
	}

	static void main_imageExportReportsUnsupportedColourModes(File dir) throws Exception {
		final File in = fixture(dir, "in.pdf", 1);
		final File out = new File(dir, "p*.png");
		withJarFolder(nativesDir(), new Task() {
			public void run() throws Exception {
				try {
					cli(in.getPath(), "-burstfiletype", "PNG", "-colormode", "BNW", "-ob", out.getPath());
					throw new AssertionError("PNG in black and white reported as written");
				} catch (IOException expected) {
					assertTrue(expected.getMessage().contains("Black and White"), "colour mode named: " + expected.getMessage());
				}
			}
		});
		assertTrue(!new File(dir, "p1.png").exists(), "no file written");
	}

	static void main_burstWithoutTransformations(File dir) throws Exception {
		File in = fixture(dir, "in.pdf", 2);
		cli(in.getPath(), "-ob", new File(dir, "b*.pdf").getPath());
		for (int page = 1; page <= 2; page++) {
			File part = new File(dir, "b" + page + ".pdf");
			assertTrue(part.isFile(), part + " written");
			assertEquals(1, pageCount(part), part + " page count");
		}
	}

	static void main_backgroundFileIsClosedAfterUse(File dir) throws Exception {
		File in = fixture(dir, "in.pdf", 1);
		File background = fixture(dir, "background.pdf", 1);
		cli(in.getPath(), "-background", background.getPath(), new File(dir, "out.pdf").getPath());
		assertTrue(!isOpen(background), "background file closed");
	}

	// ---- helpers ----

	/**
	 * Whether this JVM still has file open: by the links in /proc/self/fd
	 * where they exist, otherwise by renaming the file, which Windows refuses
	 * for an open file.
	 */
	static boolean isOpen(File file) throws IOException {
		File fds = new File("/proc/self/fd");
		if (fds.isDirectory()) {
			Path path = file.toPath().toRealPath();
			for (File fd : fds.listFiles()) {
				try {
					if (Files.readSymbolicLink(fd.toPath()).equals(path))
						return true;
				} catch (IOException closedMeanwhile) {
				}
			}
			return false;
		}
		File moved = new File(file.getPath() + ".moved");
		if (!file.renameTo(moved))
			return true;
		if (!moved.renameTo(file))
			throw new IOException("Cannot rename " + moved + " back");
		return false;
	}

	static File fixture(File dir, String name, int pages) throws Exception {
		return fixture(dir, name, pages, 0, 0);
	}

	/**
	 * Creates an A4 fixture. If linkPage is not 0, that page gets an internal
	 * link whose /Dest is the top-left corner of linkTarget.
	 */
	static File fixture(File dir, String name, int pages, int linkPage, int linkTarget) throws Exception {
		File f = new File(dir, name);
		Document document = new Document(PageSize.A4);
		PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(f));
		document.open();
		for (int i = 1; i <= pages; i++) {
			if (i > 1)
				document.newPage();
			document.add(new Paragraph("Page " + i));
			if (i == linkPage) {
				PdfAnnotation link = new PdfAnnotation(writer, new Rectangle(50, 50, 150, 100));
				link.put(PdfName.SUBTYPE, PdfName.LINK);
				PdfDestination dest = new PdfDestination(PdfDestination.XYZ, 0, 842, 0);
				dest.addPage(writer.getPageReference(linkTarget));
				link.put(PdfName.DEST, dest);
				writer.addAnnotation(link);
			}
		}
		document.close();
		return f;
	}

	/** The matrix of the first "cm" operator on page 1, as written. */
	static String firstPlacement(File pdf) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			String content = new String(reader.getPageContent(1), "ISO-8859-1");
			Matcher m = Pattern.compile("(\\S+ \\S+ \\S+ \\S+ \\S+ \\S+ cm)").matcher(content);
			if (!m.find())
				throw new AssertionError("No cm operator in " + content);
			return m.group(1);
		} finally {
			reader.close();
		}
	}

	/** The top coordinate of the /XYZ destination of the first link on page 1. */
	static float linkDestinationTop(File pdf) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			PdfArray annots = reader.getPageN(1).getAsArray(PdfName.ANNOTS);
			if (annots == null)
				throw new AssertionError("Page 1 has no annotations");
			return annots.getAsDict(0).getAsArray(PdfName.DEST).getAsNumber(3).floatValue();
		} finally {
			reader.close();
		}
	}

	static void cli(String... args) throws Exception {
		new CommandLineInterface(args);
	}

	static String help(final String option) throws Exception {
		return captureOut(new Task() {
			public void run() throws Exception {
				cli("-help", option);
			}
		});
	}

	static File nativesDir() {
		return new File(System.getProperty("jpdftweak.test.natives"));
	}

	/**
	 * Runs task as if jpdftweak.jar were in folder, which is where PdfToImage
	 * looks for MuPDF's shared library.
	 */
	static void withJarFolder(File folder, Task task) throws Exception {
		String classPath = System.getProperty("java.class.path");
		String libraryPath = System.getProperty("java.library.path");
		System.setProperty("java.class.path", new File(folder, "jpdftweak.jar").getPath());
		try {
			task.run();
		} finally {
			System.setProperty("java.class.path", classPath);
			System.setProperty("java.library.path", libraryPath);
		}
	}

	static String captureErr(Task task) throws Exception {
		PrintStream old = System.err;
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		System.setErr(new PrintStream(buf, true, "UTF-8"));
		try {
			task.run();
		} finally {
			System.setErr(old);
		}
		return buf.toString("UTF-8");
	}

	static String captureOut(Task task) throws Exception {
		PrintStream old = System.out;
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		System.setOut(new PrintStream(buf, true, "UTF-8"));
		try {
			task.run();
		} finally {
			System.setOut(old);
		}
		return buf.toString("UTF-8");
	}

	static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int n;
		while ((n = in.read(buffer)) != -1)
			out.write(buffer, 0, n);
		return out.toByteArray();
	}

	static int pageCount(File pdf) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			return reader.getNumberOfPages();
		} finally {
			reader.close();
		}
	}

	/** Counts the image file directories (pages) of a TIFF file. */
	static int tiffPageCount(File tiff) throws Exception {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(tiff.toPath()));
		b.order(b.get(0) == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
		int count = 0;
		for (int ifd = b.getInt(4); ifd != 0; ifd = b.getInt(ifd + 2 + 12 * (b.getShort(ifd) & 0xffff))) {
			if (++count > 10000)
				throw new AssertionError("IFD chain of " + tiff + " does not end");
		}
		return count;
	}

	static void assertTrue(boolean condition, String what) {
		if (!condition)
			throw new AssertionError(what);
	}

	static void assertEquals(Object expected, Object actual, String what) {
		if (expected == null ? actual != null : !expected.equals(actual))
			throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
	}

	static void deleteTree(File f) {
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children)
				deleteTree(c);
		}
		f.delete();
	}
}
