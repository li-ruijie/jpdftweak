package jpdftweak;

import static jpdftweak.RegressionTests.assertEquals;
import static jpdftweak.RegressionTests.assertTrue;
import static jpdftweak.RegressionTests.cli;
import static jpdftweak.RegressionTests.fixture;
import static jpdftweak.RegressionTests.pageCount;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import jpdftweak.gui.MainForm;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.exceptions.BadPasswordException;
import com.itextpdf.text.pdf.AcroFields;
import com.itextpdf.text.pdf.PdfAction;
import com.itextpdf.text.pdf.PdfAnnotation;
import com.itextpdf.text.pdf.PdfArray;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfDestination;
import com.itextpdf.text.pdf.PdfDictionary;
import com.itextpdf.text.pdf.PdfGState;
import com.itextpdf.text.pdf.PdfIndirectReference;
import com.itextpdf.text.pdf.PdfLayer;
import com.itextpdf.text.pdf.PdfName;
import com.itextpdf.text.pdf.PdfNameTree;
import com.itextpdf.text.pdf.PdfNumber;
import com.itextpdf.text.pdf.PRStream;
import com.itextpdf.text.pdf.PdfObject;
import com.itextpdf.text.pdf.PdfOutline;
import com.itextpdf.text.pdf.PdfPageLabels;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfRectangle;
import com.itextpdf.text.pdf.PdfStamper;
import com.itextpdf.text.pdf.PdfString;
import com.itextpdf.text.pdf.PdfWriter;
import com.itextpdf.text.pdf.SimpleBookmark;
import com.itextpdf.text.pdf.TextField;
import com.itextpdf.text.pdf.parser.ImageRenderInfo;
import com.itextpdf.text.pdf.parser.PdfReaderContentParser;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import com.itextpdf.text.pdf.parser.RenderListener;
import com.itextpdf.text.pdf.parser.TextRenderInfo;
import com.itextpdf.text.pdf.parser.Vector;

/**
 * Tests that pin down what each operation produces, so that a change of the
 * libraries underneath shows up as a failure. They check the meaning of the
 * output (page geometry, document structure, valid signatures, image
 * formats), not its bytes.
 */
public class OperationTests {

	static final Rectangle LANDSCAPE_A4 = new Rectangle(842, 595);

	// ---- page geometry ----

	static void main_cropUsesTheBoxOrItsFallback(File dir) throws Exception {
		File plain = pdf(dir, "plain.pdf", PageSize.A4);
		File in = withPageEntries(plain, new File(dir, "in.pdf"), 1,
				PdfName.CROPBOX, new PdfRectangle(50, 100, 545, 742), PdfName.ROTATE, new PdfNumber(90));
		// TrimBox and ArtBox fall back to the CropBox, as neither is set
		String[] boxes = { "CropBox", "TrimBox", "ArtBox", "MediaBox" };
		for (String box : boxes) {
			File out = new File(dir, box + ".pdf");
			cli(in.getPath(), "-crop", box, out.getPath());
			if (box.equals("MediaBox"))
				assertBox(595, 842, mediaBox(out, 1), box);
			else
				assertBox(495, 642, mediaBox(out, 1), box);
			assertEquals(90, rotation(out, 1), box + " keeps /Rotate");
		}
		assertMatrix(new float[] { 1, 0, 0, 1, -50, -100 }, placements(new File(dir, "CropBox.pdf"), 1)[0], "CropBox placement");
	}

	static void main_scaleFitsCentresOrStretches(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4);
		float f = 595f / 842f;
		String[] values = { "842,595", "1190,1684,c", "1190,842,r", "1190,1684" };
		float[][] matrices = {
				{ f, 0, 0, f, (842 - 595 * f) / 2, 0 }, // fit, keeping the aspect ratio
				{ 1, 0, 0, 1, 297.5f, 421 }, // centre instead of enlarging
				{ 2, 0, 0, 1, 0, 0 }, // stretch
				{ 2, 0, 0, 2, 0, 0 } }; // enlarge
		for (int i = 0; i < values.length; i++) {
			String value = values[i];
			File out = new File(dir, "scale" + i + ".pdf");
			cli(in.getPath(), "-scale", value, out.getPath());
			String[] size = value.split(",");
			assertBox(Float.parseFloat(size[0]), Float.parseFloat(size[1]), mediaBox(out, 1), value);
			assertMatrix(matrices[i], placements(out, 1)[0], value + " placement");
		}
	}

	static void main_rotateTurnsPortraitAndLandscapePagesSeparately(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4, LANDSCAPE_A4, PageSize.A4);
		File implicit = new File(dir, "implicit.pdf");
		cli(in.getPath(), "-rotate", "KL", implicit.getPath());
		int[] expected = { 0, 270, 0 };
		for (int page = 1; page <= 3; page++)
			assertEquals(expected[page - 1], rotation(implicit, page), "page " + page + " /Rotate");
		File explicit = new File(dir, "explicit.pdf");
		cli(in.getPath(), "-rotexp", "RK", explicit.getPath());
		for (int page = 1; page <= 3; page++) {
			assertEquals(0, rotation(explicit, page), "page " + page + " /Rotate after -rotexp");
			assertBox(842, 595, mediaBox(explicit, page), "page " + page + " after -rotexp");
		}
	}

	static void main_shufflePlacesPagesForTwoUpAndBooklets(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4, PageSize.A4, PageSize.A4, PageSize.A4);
		File twoUp = new File(dir, "two-up.pdf");
		cli(in.getPath(), "-shuffle", "2:!+1L0.707+0%-100%,+2L.707+100%-100%F2", twoUp.getPath());
		assertEquals(2, pageCount(twoUp), "2-up page count");
		float[][] m = placements(twoUp, 1);
		assertEquals(2, m.length, "pages placed on the first sheet");
		assertMatrix(new float[] { 0, .707f, -.707f, 0, 842 * .707f, 0 }, m[0], "first page of the sheet");
		assertMatrix(new float[] { 0, .707f, -.707f, 0, 842 * .707f, 595 * .707f }, m[1], "second page of the sheet");
		assertTrue(text(twoUp, 1).contains("Page 1") && text(twoUp, 1).contains("Page 2"), "sheet 1 holds pages 1 and 2");
		assertTrue(text(twoUp, 2).contains("Page 3") && text(twoUp, 2).contains("Page 4"), "sheet 2 holds pages 3 and 4");
		assertTrue(content(twoUp, 1).contains("2 w"), "frame line width set on the second rule");

		File booklet = new File(dir, "booklet.pdf");
		cli(in.getPath(), "-shuffle", "-4:!-4N1+0%+0%,!+1N1+0%+0%,!+2N1+0%+0%,!-3N1+0%+0%", booklet.getPath());
		int[] order = { 4, 1, 2, 3 };
		for (int page = 1; page <= 4; page++)
			assertTrue(text(booklet, page).contains("Page " + order[page - 1]), "booklet page " + page + " is page " + order[page - 1]);
	}

	static void main_preserveLinksMovesThemWithTheCrop(File dir) throws Exception {
		// The link covers 50,50 to 150,100 and points at 0,842 on its own page.
		File plain = fixture(dir, "plain.pdf", 1, 1, 1);
		File in = withPageEntries(plain, new File(dir, "in.pdf"), 1, PdfName.CROPBOX, new PdfRectangle(50, 100, 545, 742));
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-preserveLinks", "On", "-crop", "CropBox", out.getPath());
		PdfReader reader = new PdfReader(out.getPath());
		try {
			PdfArray annots = reader.getPageN(1).getAsArray(PdfName.ANNOTS);
			assertTrue(annots != null && annots.size() == 1, "one link kept");
			PdfArray rect = annots.getAsDict(0).getAsArray(PdfName.RECT);
			assertMatrix(new float[] { 0, -50, 100, 0 }, floats(rect), "link rectangle");
			PdfArray dest = annots.getAsDict(0).getAsArray(PdfName.DEST);
			assertMatrix(new float[] { -50, 742 }, new float[] { dest.getAsNumber(2).floatValue(), dest.getAsNumber(3).floatValue() },
					"link destination");
		} finally {
			reader.close();
		}
		File dropped = new File(dir, "dropped.pdf");
		cli(in.getPath(), "-crop", "CropBox", dropped.getPath());
		reader = new PdfReader(dropped.getPath());
		try {
			assertTrue(reader.getPageN(1).getAsArray(PdfName.ANNOTS) == null, "links dropped without -preserveLinks");
		} finally {
			reader.close();
		}
	}

	/**
	 * A link destination goes to the first output page that shows its page,
	 * with its coordinates placed like that page, whatever its type.
	 */
	static void main_linkDestinationsFollowTheirPage(File dir) throws Exception {
		PdfArray fitH = new PdfArray();
		fitH.add(PdfName.FITH);
		fitH.add(new PdfNumber(500));
		PdfArray fitR = new PdfArray();
		fitR.add(PdfName.FITR);
		fitR.add(new PdfNumber(100));
		fitR.add(new PdfNumber(200));
		fitR.add(new PdfNumber(300));
		fitR.add(new PdfNumber(400));
		File in = linkFixture(dir, "in.pdf", 2, fitH, fitR);
		File scaled = new File(dir, "scaled.pdf");
		cli(in.getPath(), "-preserveLinks", "On", "-scale", "1190,1684", scaled.getPath());
		assertEquals("[2 /FitH 1000, 2 /FitR 200 400 600 800]", linkDestinations(scaled, 1), "after scaling by 2");
		// A page turned by 90 degrees and then drawn upright: x' = y, y' = 595 - x
		File turned = new File(dir, "turned.pdf");
		cli(in.getPath(), "-preserveLinks", "On", "-rotexp", "R", turned.getPath());
		assertEquals("[2 /FitV 500, 2 /FitR 200 295 400 495]", linkDestinations(turned, 1), "after turning the pages");
		// Each page shown at full size, then at half size
		File twice = new File(dir, "twice.pdf");
		cli(in.getPath(), "-preserveLinks", "On", "-shuffle", "1:!+1N1+0%+0%,!+1N0.5+0%+0%", twice.getPath());
		assertEquals("[3 /FitH 500, 3 /FitR 100 200 300 400]", linkDestinations(twice, 1), "links on the full size page");
		assertEquals("[3 /FitH 500, 3 /FitR 100 200 300 400]", linkDestinations(twice, 2), "links on the half size page");
	}

	/** The catalog entries that refer to pages, and the ones that go with them, survive the steps that rebuild the pages. */
	static void main_rebuiltPagesKeepTheCatalog(File dir) throws Exception {
		File in = catalogFixture(dir);
		String common = "attachment Note: Attached\n"
				+ "/PageMode: /UseOutlines\n/PageLayout: /TwoColumnLeft\n/Lang: en-GB\nHideToolbar: true\n";
		assertEquals("bookmark XYZ: 2 /XYZ 100 700 0\nbookmark FitH: 3 /FitH 500\nbookmark FitR: 1 /FitR 100 200 300 400\n"
				+ "named named: 3 /XYZ 50 400 0\ndests /old: 1 /Fit\nopen: 2 /FitV 200\n" + common
				+ "labels: [i, ii, iii]\nhidden layer shared: true\n", catalogSummary(in), "fixture");
		// Pages 2 and 3 cropped by 50 from the left and 100 from the bottom
		File boxed = withPageEntries(in, new File(dir, "boxed2.pdf"), 2, PdfName.CROPBOX, new PdfRectangle(50, 100, 545, 742));
		boxed = withPageEntries(boxed, new File(dir, "boxed.pdf"), 3, PdfName.CROPBOX, new PdfRectangle(50, 100, 545, 742));
		File cropped = new File(dir, "cropped.pdf");
		cli(boxed.getPath(), "-crop", "CropBox", cropped.getPath());
		assertEquals("bookmark XYZ: 2 /XYZ 50 600 0\nbookmark FitH: 3 /FitH 400\nbookmark FitR: 1 /FitR 100 200 300 400\n"
				+ "named named: 3 /XYZ 0 300 0\ndests /old: 1 /Fit\nopen: 2 /FitV 150\n" + common
				+ "labels: [i, ii, iii]\nhidden layer shared: true\n", catalogSummary(cropped), "after -crop");
		File scaled = new File(dir, "scaled.pdf");
		cli(in.getPath(), "-scale", "1190,1684", scaled.getPath());
		assertEquals("bookmark XYZ: 2 /XYZ 200 1400 0\nbookmark FitH: 3 /FitH 1000\nbookmark FitR: 1 /FitR 200 400 600 800\n"
				+ "named named: 3 /XYZ 100 800 0\ndests /old: 1 /Fit\nopen: 2 /FitV 400\n" + common
				+ "labels: [i, ii, iii]\nhidden layer shared: true\n", catalogSummary(scaled), "after -scale");
		// Turned by 90 degrees and drawn upright: x' = y, y' = 595 - x
		File turned = new File(dir, "turned.pdf");
		cli(in.getPath(), "-rotexp", "R", turned.getPath());
		assertEquals("bookmark XYZ: 2 /XYZ 700 495 0\nbookmark FitH: 3 /FitV 500\nbookmark FitR: 1 /FitR 200 295 400 495\n"
				+ "named named: 3 /XYZ 400 545 0\ndests /old: 1 /Fit\nopen: 2 /FitH 395\n" + common
				+ "labels: [i, ii, iii]\nhidden layer shared: true\n", catalogSummary(turned), "after -rotexp");
		// Each page shown at full size, then at half size: destinations go to the first
		File twice = new File(dir, "twice.pdf");
		cli(in.getPath(), "-shuffle", "1:!+1N1+0%+0%,!+1N0.5+0%+0%", twice.getPath());
		assertEquals("bookmark XYZ: 3 /XYZ 100 700 0\nbookmark FitH: 5 /FitH 500\nbookmark FitR: 1 /FitR 100 200 300 400\n"
				+ "named named: 5 /XYZ 50 400 0\ndests /old: 1 /Fit\nopen: 3 /FitV 200\n" + common
				+ "labels: null\nhidden layer shared: true\n", catalogSummary(twice), "after -shuffle showing each page twice");
		// Pages 1 and 3 only: what points at page 2 goes, the bookmark stays as a label
		File odd = new File(dir, "odd.pdf");
		cli(in.getPath(), "-shuffle", "2:!+1N1+0%+0%", odd.getPath());
		assertEquals("bookmark XYZ: -\nbookmark FitH: 2 /FitH 500\nbookmark FitR: 1 /FitR 100 200 300 400\n"
				+ "named named: 2 /XYZ 50 400 0\ndests /old: 1 /Fit\nopen: -\n" + common
				+ "labels: null\nhidden layer shared: true\n", catalogSummary(odd), "after -shuffle leaving out page 2");
	}

	/**
	 * Three A4 pages with what a catalog holds besides them: bookmarks to
	 * /XYZ on page 2 (by /Dest), /FitH on page 3 (by a GoTo action), and /FitR
	 * on page 1, named destinations in the name tree and in /Dests, an
	 * attachment, viewer settings, an open action, a language, page labels,
	 * and a layer on page 1 that is hidden by default.
	 */
	static File catalogFixture(File dir) throws Exception {
		File f = new File(dir, "catalog.pdf");
		Document document = new Document(PageSize.A4);
		PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(f));
		writer.setViewerPreferences(PdfWriter.PageLayoutTwoColumnLeft | PdfWriter.PageModeUseOutlines | PdfWriter.HideToolbar);
		document.open();
		document.add(new Paragraph("Page 1"));
		PdfLayer layer = new PdfLayer("Hidden", writer);
		layer.setOn(false);
		PdfContentByte canvas = writer.getDirectContent();
		canvas.beginLayer(layer);
		canvas.rectangle(100, 100, 50, 50);
		canvas.fill();
		canvas.endLayer();
		for (int i = 2; i <= 3; i++) {
			document.newPage();
			document.add(new Paragraph("Page " + i));
		}
		PdfOutline root = writer.getRootOutline();
		PdfDestination xyz = new PdfDestination(PdfDestination.XYZ, 100, 700, 0);
		xyz.addPage(writer.getPageReference(2));
		new PdfOutline(root, xyz, "XYZ");
		new PdfOutline(root, PdfAction.gotoLocalPage(3, new PdfDestination(PdfDestination.FITH, 500), writer), "FitH");
		PdfDestination fitR = new PdfDestination(PdfDestination.FITR, 100, 200, 300, 400);
		fitR.addPage(writer.getPageReference(1));
		new PdfOutline(root, fitR, "FitR");
		Map<String, String> named = new HashMap<String, String>();
		named.put("named", "3 XYZ 50 400 0");
		writer.addNamedDestinations(named, 0);
		PdfArray oldDest = new PdfArray();
		oldDest.add(writer.getPageReference(1));
		oldDest.add(PdfName.FIT);
		PdfDictionary oldValue = new PdfDictionary();
		oldValue.put(PdfName.D, oldDest);
		PdfDictionary dests = new PdfDictionary();
		dests.put(new PdfName("old"), oldValue);
		writer.getExtraCatalog().put(PdfName.DESTS, dests);
		writer.addFileAttachment("Note", "Attached".getBytes("ISO-8859-1"), null, "note.txt");
		writer.setOpenAction(PdfAction.gotoLocalPage(2, new PdfDestination(PdfDestination.FITV, 200), writer));
		writer.getExtraCatalog().put(PdfName.LANG, new PdfString("en-GB"));
		PdfPageLabels labels = new PdfPageLabels();
		labels.addPageLabel(1, PdfPageLabels.LOWERCASE_ROMAN_NUMERALS);
		writer.setPageLabels(labels);
		document.close();
		return f;
	}

	/** The catalog entries of catalogFixture, one per line, with each destination as its page and the rest of its array. */
	static String catalogSummary(File pdf) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			PdfDictionary catalog = reader.getCatalog();
			StringBuilder s = new StringBuilder();
			PdfDictionary outlines = catalog.getAsDict(PdfName.OUTLINES);
			for (PdfDictionary item = outlines == null ? null : outlines.getAsDict(PdfName.FIRST); item != null; item = item.getAsDict(PdfName.NEXT)) {
				PdfObject target = item.get(PdfName.DEST) != null ? item.get(PdfName.DEST) : item.get(PdfName.A);
				s.append("bookmark ").append(item.getAsString(PdfName.TITLE)).append(": ").append(target(reader, target)).append('\n');
			}
			PdfDictionary names = catalog.getAsDict(PdfName.NAMES);
			if (names != null && names.getAsDict(PdfName.DESTS) != null) {
				for (Map.Entry<String, PdfObject> e : new TreeMap<String, PdfObject>(PdfNameTree.readTree(names.getAsDict(PdfName.DESTS))).entrySet())
					s.append("named ").append(e.getKey()).append(": ").append(target(reader, e.getValue())).append('\n');
			}
			PdfDictionary dests = catalog.getAsDict(PdfName.DESTS);
			if (dests != null) {
				for (PdfName key : dests.getKeys())
					s.append("dests ").append(key).append(": ").append(target(reader, dests.get(key))).append('\n');
			}
			s.append("open: ").append(target(reader, catalog.get(PdfName.OPENACTION))).append('\n');
			if (names != null && names.getAsDict(PdfName.EMBEDDEDFILES) != null) {
				for (Map.Entry<String, PdfObject> e : new TreeMap<String, PdfObject>(PdfNameTree.readTree(names.getAsDict(PdfName.EMBEDDEDFILES))).entrySet()) {
					PdfDictionary spec = (PdfDictionary) PdfReader.getPdfObject(e.getValue());
					PRStream file = (PRStream) PdfReader.getPdfObject(spec.getAsDict(PdfName.EF).get(PdfName.F));
					s.append("attachment ").append(e.getKey()).append(": ").append(new String(PdfReader.getStreamBytes(file), "ISO-8859-1")).append('\n');
				}
			}
			for (PdfName key : new PdfName[] { PdfName.PAGEMODE, PdfName.PAGELAYOUT, PdfName.LANG })
				s.append(key).append(": ").append(PdfReader.getPdfObject(catalog.get(key))).append('\n');
			PdfDictionary preferences = catalog.getAsDict(PdfName.VIEWERPREFERENCES);
			s.append("HideToolbar: ").append(preferences == null ? null : preferences.get(PdfName.HIDETOOLBAR)).append('\n');
			s.append("labels: ").append(Arrays.toString(PdfPageLabels.getPageLabels(reader))).append('\n');
			s.append("hidden layer shared: ").append(hiddenLayerShared(reader)).append('\n');
			return s.toString();
		} finally {
			reader.close();
		}
	}

	/** A destination, or what holds one in /D (a GoTo action or a /Dests entry), as its page and the rest of its array. */
	static String target(PdfReader reader, PdfObject object) {
		object = PdfReader.getPdfObject(object);
		if (object != null && object.isDictionary())
			object = PdfReader.getPdfObject(((PdfDictionary) object).get(PdfName.D));
		if (object == null)
			return "-";
		if (!object.isArray())
			return "named " + object;
		return destination(reader, (PdfArray) object);
	}

	/** Whether the layer hidden by default is the one the content of page 1 uses, so that it stays hidden. */
	static boolean hiddenLayerShared(PdfReader reader) {
		PdfDictionary properties = reader.getCatalog().getAsDict(PdfName.OCPROPERTIES);
		if (properties == null)
			return false;
		PdfArray off = properties.getAsDict(PdfName.D).getAsArray(PdfName.OFF);
		return usesObject(reader.getPageN(1).getAsDict(PdfName.RESOURCES), ((PdfIndirectReference) off.getPdfObject(0)).getNumber());
	}

	/** Whether resources, or the resources of a form they hold, name the object as a property list. */
	static boolean usesObject(PdfDictionary resources, int number) {
		if (resources == null)
			return false;
		PdfDictionary properties = resources.getAsDict(PdfName.PROPERTIES);
		for (PdfName key : properties == null ? new HashSet<PdfName>() : properties.getKeys()) {
			PdfObject value = properties.get(key);
			if (value instanceof PdfIndirectReference && ((PdfIndirectReference) value).getNumber() == number)
				return true;
		}
		PdfDictionary forms = resources.getAsDict(PdfName.XOBJECT);
		for (PdfName key : forms == null ? new HashSet<PdfName>() : forms.getKeys()) {
			if (usesObject(((PdfDictionary) PdfReader.getPdfObject(forms.get(key))).getAsDict(PdfName.RESOURCES), number))
				return true;
		}
		return false;
	}

	/** Three A4 pages, the first with one link for each destination, given without its page. */
	static File linkFixture(File dir, String name, int targetPage, PdfArray... destinations) throws Exception {
		File f = new File(dir, name);
		Document document = new Document(PageSize.A4);
		PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(f));
		document.open();
		document.add(new Paragraph("Page 1"));
		for (int i = 0; i < destinations.length; i++) {
			PdfAnnotation link = new PdfAnnotation(writer, new Rectangle(50, 50 + 60 * i, 150, 100 + 60 * i));
			link.put(PdfName.SUBTYPE, PdfName.LINK);
			PdfArray dest = new PdfArray(destinations[i]);
			dest.add(0, writer.getPageReference(targetPage));
			link.put(PdfName.DEST, dest);
			writer.addAnnotation(link);
		}
		for (int i = 2; i <= 3; i++) {
			document.newPage();
			document.add(new Paragraph("Page " + i));
		}
		document.close();
		return f;
	}

	/** The destinations of the links on a page, each as its page number and the rest of its array. */
	static String linkDestinations(File pdf, int page) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			List<String> result = new ArrayList<String>();
			PdfArray annots = reader.getPageN(page).getAsArray(PdfName.ANNOTS);
			for (int i = 0; annots != null && i < annots.size(); i++)
				result.add(destination(reader, annots.getAsDict(i).getAsArray(PdfName.DEST)));
			return result.toString();
		} finally {
			reader.close();
		}
	}

	/** A destination as its page number and the rest of its array. */
	static String destination(PdfReader reader, PdfArray dest) {
		StringBuilder s = new StringBuilder();
		PdfIndirectReference ref = (PdfIndirectReference) dest.getPdfObject(0);
		for (int page = 1; page <= reader.getNumberOfPages(); page++) {
			if (reader.getPageOrigRef(page).getNumber() == ref.getNumber())
				s.append(page);
		}
		for (int i = 1; i < dest.size(); i++)
			s.append(' ').append(dest.getPdfObject(i));
		return s.toString();
	}

	// ---- page content ----

	static void main_backgroundPutsTheFirstPageUnderEveryPage(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4, PageSize.A4);
		File background = pdfWithText(dir, "background.pdf", "Background");
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-background", background.getPath(), out.getPath());
		for (int page = 1; page <= 2; page++) {
			String text = text(out, page);
			assertTrue(text.contains("Background") && text.contains("Page " + page), "page " + page + " text: " + text);
			assertTrue(text.indexOf("Background") < text.indexOf("Page " + page), "background drawn first on page " + page);
		}
	}

	static void main_textWatermarkIsDiagonalTransparentHelvetica(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4);
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-watermark", "Confidential,40,0.25,#FF0000", out.getPath());
		List<Object[]> texts = textPositions(out, 1);
		Object[] mark = null;
		for (Object[] t : texts)
			if ("Confidential".equals(t[0]))
				mark = t;
		assertTrue(mark != null, "watermark text on the page: " + texts.size() + " texts");
		// From top left to bottom right, with the middle of the text (half the
		// font size above the baseline) in the middle of the page
		double angle = Math.atan(842.0 / 595.0);
		assertMatrix(new float[] { (float) (297.5 - Math.sin(angle) * 20), (float) (421 - Math.cos(angle) * 20) },
				new float[] { (Float) mark[1], (Float) mark[2] }, "watermark baseline centre");
		assertMatrix(new float[] { (float) -Math.toDegrees(angle) }, new float[] { (Float) mark[3] }, "watermark angle");
		PdfReader reader = new PdfReader(out.getPath());
		try {
			PdfDictionary resources = reader.getPageN(1).getAsDict(PdfName.RESOURCES);
			boolean transparent = false;
			PdfDictionary states = resources.getAsDict(PdfName.EXTGSTATE);
			for (PdfName key : states.getKeys())
				transparent |= states.getAsDict(key).getAsNumber(PdfName.ca).floatValue() == 0.25f;
			assertTrue(transparent, "fill opacity 0.25 in " + states);
			boolean helvetica = false;
			PdfDictionary fonts = resources.getAsDict(PdfName.FONT);
			for (PdfName key : fonts.getKeys())
				helvetica |= new PdfName("Helvetica").equals(fonts.getAsDict(key).getAsName(PdfName.BASEFONT));
			assertTrue(helvetica, "Helvetica among " + fonts);
		} finally {
			reader.close();
		}
		assertTrue(content(out, 1).contains("1 0 0 rg"), "red fill colour set");
	}

	static void main_pageNumbersFollowPositionMaskAndLabels(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4, PageSize.A4);
		// RB: 30 points in from the right edge, 20 up from the bottom
		File right = new File(dir, "right.pdf");
		cli(in.getPath(), "-numbers", "RB,10,30,20,%1$d/%2$d", right.getPath());
		for (int page = 1; page <= 2; page++)
			assertTextAt(page + "/2", 565, 20, right, page);
		// OB: on the outer edge, so right on odd pages and left on even ones
		File outer = new File(dir, "outer.pdf");
		cli(in.getPath(), "-numbers", "OB,10,30,20", outer.getPath());
		assertTextAt("1", 565, 20, outer, 1);
		assertTextAt("2", 30, 20, outer, 2);
		// T: top centre, printing page labels set in an earlier block
		File labels = new File(dir, "labels.pdf");
		cli(in.getPath(), "-pagenumbers", "1=1i", "--", "-numbers", "T,10,0,20,%4$s (%3$d)", labels.getPath());
		assertTextAt("i (1)", 297.5f, 822, labels, 1);
		assertTextAt("ii (2)", 297.5f, 822, labels, 2);
		try {
			cli(in.getPath(), "-numbers", "L,10,1,20", new File(dir, "outside.pdf").getPath());
			throw new AssertionError("page number outside the page accepted");
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("not within page bounding box"), expected.getMessage());
		}
	}

	// ---- document structure ----

	static void main_pageLabelsAreSetRemovedAndLoaded(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4, PageSize.A4, PageSize.A4, PageSize.A4);
		File labelled = new File(dir, "labelled.pdf");
		cli(in.getPath(), "-pagenumbers", "1=Intro:1i", "-pagenumbers", "3=1", labelled.getPath());
		assertEquals("[Introi, Introii, 1, 2]", pageLabels(labelled), "labels");
		String info = info(dir, labelled, "-info");
		assertTrue(info.contains("PAGELABELS 1=Intro:1i") && info.contains("PAGELABELS 3=1"), "labels in -info: " + info);
		File removed = new File(dir, "removed.pdf");
		cli(labelled.getPath(), "-pagenumbers", "-", removed.getPath());
		// iText's PdfPageLabels always starts with a decimal format at page 1
		assertEquals("[1, 2, 3, 4]", pageLabels(removed), "labels after -pagenumbers -");
		File list = write(new File(dir, "labels.txt"), "1=1A\n2=5\n");
		File loaded = new File(dir, "loaded.pdf");
		cli(in.getPath(), "-loadpagenumbers", list.getPath(), loaded.getPath());
		assertEquals("[A, 5, 6, 7]", pageLabels(loaded), "labels from a file");
	}

	static void main_bookmarksAreAddedSavedLoadedAndRemoved(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4, PageSize.A4);
		String part = "1;O;Part;1 Fit";
		String chapter = "2;BI;Chapter \\3B one;2 XYZ 0 800 0";
		File marked = new File(dir, "marked.pdf");
		cli(in.getPath(), "-bookmark", part, "-bookmark", chapter, marked.getPath());
		PdfReader reader = new PdfReader(marked.getPath());
		try {
			List<HashMap<String, Object>> outline = SimpleBookmark.getBookmark(reader);
			assertEquals(1, outline.size(), "top level bookmarks");
			assertEquals("Part", outline.get(0).get("Title"), "top level title");
			@SuppressWarnings("unchecked")
			List<HashMap<String, Object>> kids = (List<HashMap<String, Object>>) outline.get(0).get("Kids");
			assertEquals("Chapter ; one", kids.get(0).get("Title"), "nested title");
			assertEquals("italic bold", kids.get(0).get("Style"), "nested style");
		} finally {
			reader.close();
		}
		File saved = new File(dir, "saved.txt");
		cli(marked.getPath(), "-savebookmarks", saved.getPath());
		// A PDF keeps "open" only for bookmarks with children, so the chapter comes back open
		assertEquals(part + "\n" + chapter.replace(";BI;", ";OBI;") + "\n", read(saved).replace("\r\n", "\n"), "-savebookmarks output");
		File loaded = new File(dir, "loaded.pdf");
		cli(in.getPath(), "-loadbookmarks", saved.getPath(), loaded.getPath());
		File savedAgain = new File(dir, "saved-again.txt");
		cli(loaded.getPath(), "-savebookmarks", savedAgain.getPath());
		assertEquals(read(saved), read(savedAgain), "bookmarks loaded from the saved file");
		File removed = new File(dir, "removed.pdf");
		cli(marked.getPath(), "-bookmark", "-", removed.getPath());
		reader = new PdfReader(removed.getPath());
		try {
			assertTrue(SimpleBookmark.getBookmark(reader) == null, "no bookmarks after -bookmark -");
		} finally {
			reader.close();
		}
	}

	static void main_attachmentIsEmbedded(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4);
		File note = write(new File(dir, "note.txt"), "hello");
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-attach", note.getPath(), out.getPath());
		PdfReader reader = new PdfReader(out.getPath());
		try {
			PdfArray names = reader.getCatalog().getAsDict(PdfName.NAMES).getAsDict(PdfName.EMBEDDEDFILES).getAsArray(PdfName.NAMES);
			assertEquals(2, names.size(), "one name tree entry");
			PdfDictionary spec = names.getAsDict(1);
			assertEquals("note.txt", spec.getAsString(PdfName.F).toUnicodeString(), "file name");
			PRStream stream = (PRStream) PdfReader.getPdfObject(spec.getAsDict(PdfName.EF).get(PdfName.F));
			assertEquals("hello", new String(PdfReader.getStreamBytes(stream), "UTF-8"), "file content");
		} finally {
			reader.close();
		}
	}

	static void main_viewerPreferencesPageModeAndLayout(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4);
		File out = new File(dir, "out.pdf");
		// Values as the GUI shows them, names with their slash
		cli(in.getPath(), "-pagemode", "Outline", "-pagelayout", "Two_Columns_Left", "-viewpref", "Hide_Toolbar=true",
				"-viewpref", "Direction=/R2L", "-viewpref", "Number_of_copies=2", out.getPath());
		PdfReader reader = new PdfReader(out.getPath());
		try {
			PdfDictionary catalog = reader.getCatalog();
			assertEquals(PdfName.USEOUTLINES, catalog.getAsName(PdfName.PAGEMODE), "/PageMode");
			assertEquals(PdfName.TWOCOLUMNLEFT, catalog.getAsName(PdfName.PAGELAYOUT), "/PageLayout");
			PdfDictionary prefs = catalog.getAsDict(PdfName.VIEWERPREFERENCES);
			assertTrue(prefs.getAsBoolean(PdfName.HIDETOOLBAR).booleanValue(), "/HideToolbar");
			assertEquals(PdfName.R2L, prefs.getAsName(PdfName.DIRECTION), "/Direction");
			assertEquals(2, prefs.getAsNumber(PdfName.NUMCOPIES).intValue(), "/NumCopies");
		} finally {
			reader.close();
		}
	}

	static void main_documentInfoIsChangedAndXmpDropped(File dir) throws Exception {
		File in = new File(dir, "in.pdf");
		Document document = new Document(PageSize.A4);
		PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(in));
		document.addTitle("Old title");
		document.addAuthor("Someone");
		writer.createXmpMetadata();
		document.open();
		document.add(new Paragraph("Page 1"));
		document.close();
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-docinfo", "Title=Report über", "-docinfo", "Author=", out.getPath());
		PdfReader reader = new PdfReader(out.getPath());
		try {
			assertEquals("Report über", reader.getInfo().get("Title"), "title");
			assertTrue(!reader.getInfo().containsKey("Author"), "empty value removes the author");
			assertTrue(reader.getCatalog().get(PdfName.METADATA) == null, "XMP metadata dropped");
		} finally {
			reader.close();
		}
		String info = info(dir, out, "-info");
		assertTrue(info.contains("DOCINFO Title=Report über"), "title in -info: " + info);
	}

	// ---- security ----

	static void main_encryptionModesPasswordsAndPermissions(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4);
		for (int mode = 0; mode <= 2; mode++) {
			File out = new File(dir, "mode" + mode + ".pdf");
			cli(in.getPath(), "-ownerpw", "owner", "-userpw", "user", "-encmode", "" + mode,
					"-permissions", "Printing,Copy", out.getPath());
			try {
				new PdfReader(out.getPath()).close();
				throw new AssertionError("mode " + mode + " opened without a password");
			} catch (BadPasswordException expected) {
			}
			PdfReader user = new PdfReader(out.getPath(), "user".getBytes("ISO-8859-1"));
			try {
				assertTrue(!user.isOpenedWithFullPermissions(), "mode " + mode + " user password gives restricted access");
			} finally {
				user.close();
			}
			PdfReader owner = new PdfReader(out.getPath(), "owner".getBytes("ISO-8859-1"));
			try {
				assertEquals(mode, owner.getCryptoMode(), "crypto mode");
				long permissions = owner.getPermissions();
				// "Printing" is bit 3 alone; "HQPrinting" (bit 12) is a separate permission
				assertTrue((permissions & PdfWriter.ALLOW_DEGRADED_PRINTING) != 0, "mode " + mode + " allows printing");
				assertTrue((permissions & PdfWriter.ALLOW_COPY) != 0, "mode " + mode + " allows copying");
				assertTrue((permissions & PdfWriter.ALLOW_MODIFY_CONTENTS) == 0, "mode " + mode + " forbids changes");
				assertTrue(owner.isMetadataEncrypted(), "mode " + mode + " encrypts metadata");
			} finally {
				owner.close();
			}
			try {
				info(dir, out, "-info");
				throw new AssertionError("mode " + mode + " -info without a password");
			} catch (BadPasswordException expected) {
			}
		}
		File noMetadata = new File(dir, "nometadata.pdf");
		cli(in.getPath(), "-ownerpw", "owner", "-encnometadata", "2", noMetadata.getPath());
		PdfReader reader = new PdfReader(noMetadata.getPath(), "owner".getBytes("ISO-8859-1"));
		try {
			assertTrue(!reader.isMetadataEncrypted(), "-encnometadata leaves metadata unencrypted");
		} finally {
			reader.close();
		}
		try {
			cli(in.getPath(), "-ownerpw", "", new File(dir, "empty.pdf").getPath());
			throw new AssertionError("empty owner password accepted");
		} catch (IOException expected) {
			assertEquals("Owner password may not be empty", expected.getMessage(), "error");
		}
	}

	static void main_encryptedInputNeedsTheOwnerPassword(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4);
		File encrypted = new File(dir, "encrypted.pdf");
		cli(in.getPath(), "-ownerpw", "owner", "-userpw", "user", "-encmode", "1", "-permissions", "Printing", encrypted.getPath());
		try {
			cli("-password", "user", encrypted.getPath(), new File(dir, "user.pdf").getPath());
			throw new AssertionError("opened with the user password");
		} catch (BadPasswordException expected) {
		}
		File info = new File(dir, "info.txt");
		cli("-password", "owner", encrypted.getPath(), "-info", info.getPath());
		String text = read(info);
		assertTrue(text.contains("ENCRYPTION 1 METADATA Printing") && text.contains("OWNERPW owner") && text.contains("USERPW user"),
				"-info: " + text);
		File decrypted = new File(dir, "decrypted.pdf");
		cli("-password", "owner", encrypted.getPath(), decrypted.getPath());
		PdfReader reader = new PdfReader(decrypted.getPath());
		try {
			assertTrue(!reader.isEncrypted(), "output of an encrypted input without -ownerpw is not encrypted");
		} finally {
			reader.close();
		}
	}

	static void main_signatureCoversTheFileAndVerifies(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4);
		File keystore = keystore(dir);
		File signed = new File(dir, "signed.pdf");
		cli(in.getPath(), "-keystore", keystore.getPath(), "-sign", "secret", signed.getPath());
		assertEquals("/adbe.pkcs7.detached", assertValidSignature(signed), "signature format");
		File certified = new File(dir, "certified.pdf");
		cli(in.getPath(), "-keystore", keystore.getPath(), "-keyalias", "mykey", "-certlevel", "1", "-signvisible", "secret",
				certified.getPath());
		assertValidSignature(certified);
		PdfReader reader = new PdfReader(certified.getPath());
		try {
			PdfDictionary perms = reader.getCatalog().getAsDict(PdfName.PERMS);
			assertTrue(perms != null && perms.get(PdfName.DOCMDP) != null, "certification recorded in /Perms");
			PdfArray annots = reader.getPageN(1).getAsArray(PdfName.ANNOTS);
			assertTrue(annots != null && annots.size() == 1, "one widget on page 1");
			assertEquals(PdfName.SIG, annots.getAsDict(0).getAsName(PdfName.FT), "the widget is a signature field");
			assertMatrix(new float[] { 100, 100, 200, 200 }, floats(annots.getAsDict(0).getAsArray(PdfName.RECT)), "visible signature");
		} finally {
			reader.close();
		}
		try {
			cli(in.getPath(), "-keystore", keystore.getPath(), "-sign", "secret", "-ownerpw", "owner",
					new File(dir, "both.pdf").getPath());
			throw new AssertionError("signed and encrypted in one go");
		} catch (DocumentException expected) {
		}
	}

	// ---- input and output ----

	static void main_pageRangesSelectReverseAndPad(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4, PageSize.A4, PageSize.A4, PageSize.A4, PageSize.A4, PageSize.A4);
		String[][] cases = {
				{ "-i3-4", "[3, 4]" }, { "-i~2", "[5]" }, { "-i5-", "[5, 6]" }, { "-i4-2", "[4, 3, 2]" },
				{ "-ie", "[2, 4, 6]" }, { "-io", "[1, 3, 5]" }, { "-i+2", "[0, 0, 1, 2, 3, 4, 5, 6]" },
				{ "-i1-~3o+1", "[0, 1, 3]" } };
		for (int i = 0; i < cases.length; i++) {
			File out = new File(dir, "range" + i + ".pdf");
			cli(cases[i][0], in.getPath(), out.getPath());
			assertEquals(cases[i][1], pageNumbers(out), cases[i][0]);
		}
	}

	static void main_inputFilesAliasesAndInterleaving(File dir) throws Exception {
		File a = pdfWithPages(dir, "a.pdf", "A", 2);
		File b = pdfWithPages(dir, "b.pdf", "B", 1);
		File joined = new File(dir, "joined.pdf");
		cli("-o", joined.getPath(), a.getPath(), b.getPath());
		assertEquals("[A1, A2, B1]", pageLabelsOf(joined), "files after -o");
		File mixed = new File(dir, "mixed.pdf");
		cli("-o", mixed.getPath(), "-i=A", a.getPath(), "-i=B", b.getPath(), "-i", "B", "-i1", "A", "-i", "B");
		assertEquals("[B1, A1, B1]", pageLabelsOf(mixed), "aliases");
		File interleaved = new File(dir, "interleaved.pdf");
		cli("-interleave", "1", "-o", interleaved.getPath(), a.getPath(), b.getPath());
		assertEquals("[A1, B1, A2, ]", pageLabelsOf(interleaved), "interleaved, with a blank page for the shorter file");
	}

	static void main_outputFlags(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4, PageSize.A4);
		File plain = new File(dir, "plain.pdf");
		cli(in.getPath(), plain.getPath());
		File uncompressed = new File(dir, "uncompressed.pdf");
		cli(in.getPath(), "-ou", uncompressed.getPath());
		File marked = new File(dir, "marked.pdf");
		cli(in.getPath(), "-oum", marked.getPath());
		File unmarked = new File(dir, "unmarked.pdf");
		cli(marked.getPath(), "-om", unmarked.getPath());
		PdfReader reader = new PdfReader(uncompressed.getPath());
		try {
			PdfDictionary contents = (PdfDictionary) PdfReader.getPdfObject(reader.getPageN(1).get(PdfName.CONTENTS));
			assertTrue(contents.get(PdfName.FILTER) == null, "-ou writes unfiltered content streams");
		} finally {
			reader.close();
		}
		for (int page = 1; page <= 2; page++) {
			assertEquals(page, pageEntry(marked, page, "pdftk_PageNum"), "-oum page mark on page " + page);
			assertEquals(-1, pageEntry(unmarked, page, "pdftk_PageNum"), "-om removes the page mark on page " + page);
		}
		File compressed = new File(dir, "compressed.pdf");
		cli(in.getPath(), "-oc", compressed.getPath());
		assertTrue(read(compressed).contains("/ObjStm") && !read(plain).contains("/ObjStm"), "-oc uses object streams");
		// Each step replaces the temporary file of the step before it
		File temp = new File(dir, "temp.pdf");
		cli(in.getPath(), "-crop", "MediaBox", "-rotexp", "R", "-ot", temp.getPath());
		assertEquals("[1, 2]", pageNumbers(temp), "-ot pages");
		assertBox(842, 595, mediaBox(temp, 2), "-ot result");
		File tempMarks = new File(dir, "temp-marks.pdf");
		cli(in.getPath(), "-watermark", "X,10,0.5", "-bookmark", "1;O;T;1", "--", "-crop", "MediaBox", "-ot", tempMarks.getPath());
		assertEquals(2, pageCount(tempMarks), "-ot after a watermark and bookmarks");

		// Two copies of one file, which PdfCopy reads separately, so that the result
		// holds each content stream and font twice. (A file given twice is read once.)
		File same = pdf(dir, "same.pdf", PageSize.A4, PageSize.A4);
		File copy = new File(dir, "copy.pdf");
		Files.copy(same.toPath(), copy.toPath());
		File twice = new File(dir, "twice.pdf");
		cli("-o", twice.getPath(), same.getPath(), copy.getPath());
		File optimized = new File(dir, "optimized.pdf");
		cli(twice.getPath(), "-os", optimized.getPath());
		assertEquals("[1, 2, 1, 2]", pageNumbers(optimized), "-os pages");
		assertTrue(objectCount(optimized) < objectCount(twice), "-os shares identical objects: " + objectCount(optimized)
				+ " objects instead of " + objectCount(twice));
	}

	static void main_outputNamesUseVariablesAndLabels(File dir) throws Exception {
		File in = pdf(dir, "in.pdf", PageSize.A4, PageSize.A4);
		String sep = File.separator;
		cli(in.getPath(), "<P>" + sep + "<F>-copy.pdf");
		assertTrue(new File(dir, "in-copy.pdf").isFile(), "<P> and <F>");
		cli(in.getPath(), "<P>" + sep + "<FX>.copy");
		assertTrue(new File(dir, "in.pdf.copy").isFile(), "<FX>");
		cli(in.getPath(), "<P>" + sep + "n<#>.pdf");
		cli(in.getPath(), "<P>" + sep + "n<#>.pdf");
		assertTrue(new File(dir, "n1.pdf").isFile() && new File(dir, "n2.pdf").isFile(), "<#> takes the next free number");
		File labelled = new File(dir, "labelled.pdf");
		cli(in.getPath(), "-pagenumbers", "1=1a", labelled.getPath());
		cli(labelled.getPath(), "-ob", new File(dir, "page-*.pdf").getPath());
		assertTrue(new File(dir, "page-a.pdf").isFile() && new File(dir, "page-b.pdf").isFile(), "burst names use page labels");
		try {
			cli(in.getPath(), in.getPath());
			throw new AssertionError("output over the input accepted");
		} catch (IOException expected) {
			assertEquals("Output file must be different from input file(s)", expected.getMessage(), "error");
		}
		try {
			cli(in.getPath(), "-ob", new File(dir, "nostar.pdf").getPath());
			throw new AssertionError("burst without * accepted");
		} catch (IOException expected) {
			assertEquals("Output filename does not contain *", expected.getMessage(), "error");
		}
	}

	static void main_pageOperationsKeepInfoXmpAndForms(File dir) throws Exception {
		File in = new File(dir, "in.pdf");
		Document document = new Document(PageSize.A4);
		PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(in));
		document.addTitle("Kept title");
		writer.createXmpMetadata();
		document.open();
		document.add(new Paragraph("Page 1"));
		TextField field = new TextField(writer, new Rectangle(100, 700, 300, 720), "name");
		field.setText("value");
		writer.addAnnotation(field.getTextField());
		document.close();
		File cropped = new File(dir, "cropped.pdf");
		cli(in.getPath(), "-crop", "MediaBox", cropped.getPath());
		PdfReader reader = new PdfReader(cropped.getPath());
		try {
			assertEquals("Kept title", reader.getInfo().get("Title"), "title after a page operation");
			assertTrue(reader.getMetadata() != null, "XMP metadata after a page operation");
		} finally {
			reader.close();
		}
		File copied = new File(dir, "copied.pdf");
		cli("-o", copied.getPath(), in.getPath());
		reader = new PdfReader(copied.getPath());
		try {
			assertEquals("value", reader.getAcroFields().getField("name"), "form field after multi-file input");
			assertEquals("Kept title", reader.getInfo().get("Title"), "title after multi-file input");
		} finally {
			reader.close();
		}
	}

	static void main_shuffleRepeatsPerBlock(File dir) throws Exception {
		Rectangle[] sizes = new Rectangle[8];
		Arrays.fill(sizes, PageSize.A4);
		File in = pdf(dir, "in.pdf", sizes);
		File out = new File(dir, "out.pdf");
		cli(in.getPath(), "-shuffle", "-4,4:!-4N1+0%+0%,!+1N1+0%+0%,!+2N1+0%+0%,!-3N1+0%+0%", out.getPath());
		assertEquals("[4, 1, 2, 3, 8, 5, 6, 7]", pageNumbers(out), "booklet order within each block of 4");
	}

	// ---- image export ----

	static void main_imageExportFormatsAndColorModes(File dir) throws Exception {
		final File in = pdf(dir, "in.pdf", PageSize.A4);
		final File folder = dir;
		// type, color mode, transparency
		final String[][] runs = {
				{ "JPG", "RGB", "NO" }, { "JPG", "GRAY", "NO" },
				{ "PNG", "RGB", "NO" }, { "PNG", "RGB", "YES" }, { "PNG", "GRAY", "NO" }, { "PNG", "GRAY", "YES" },
				{ "GIF", "RGB", "NO" }, { "GIF", "BNW", "NO" },
				{ "PAM", "RGB", "NO" }, { "PAM", "RGB", "YES" }, { "PAM", "GRAY", "NO" }, { "PAM", "GRAY", "YES" },
				{ "PNM", "RGB", "NO" }, { "PNM", "GRAY", "NO" },
				{ "BMP", "BNW", "NO" },
				{ "TIFF", "RGB", "NO" }, { "TIFF", "RGB", "YES" }, { "TIFF", "GRAY", "NO" }, { "TIFF", "BNW", "NO" },
				{ "TIFF", "BNWI", "NO" } };
		RegressionTests.withJarFolder(RegressionTests.nativesDir(), new RegressionTests.Task() {
			public void run() throws Exception {
				for (String[] r : runs)
					cli(in.getPath(), "-burstfiletype", r[0], "-colormode", r[1], "-transparency", r[2],
							"-ob", new File(folder, r[0] + "-" + r[1] + "-" + r[2] + "-*").getPath());
			}
		});
		for (String[] r : runs) {
			String name = r[0] + "-" + r[1] + "-" + r[2] + "-1." + r[0].toLowerCase();
			byte[] image = Files.readAllBytes(new File(dir, name).toPath());
			String start = new String(image, 0, 8, "ISO-8859-1");
			ByteBuffer big = ByteBuffer.wrap(image);
			if (r[0].equals("JPG")) {
				assertTrue((image[0] & 0xff) == 0xff && (image[1] & 0xff) == 0xd8, name + " is a JPEG");
			} else if (r[0].equals("PNG")) {
				assertTrue(start.startsWith("\u0089PNG"), name + " is a PNG");
				assertEquals("595x842", big.getInt(16) + "x" + big.getInt(20), name + " size");
				int colorType = r[1].equals("GRAY") ? (r[2].equals("YES") ? 4 : 0) : (r[2].equals("YES") ? 6 : 2);
				assertEquals(colorType, (int) image[25], name + " PNG color type");
			} else if (r[0].equals("GIF")) {
				assertTrue(start.startsWith("GIF8"), name + " is a GIF");
				ByteBuffer little = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
				assertEquals("595x842", (little.getShort(6) & 0xffff) + "x" + (little.getShort(8) & 0xffff), name + " size");
			} else if (r[0].equals("PAM")) {
				String header = new String(image, 0, 200, "ISO-8859-1");
				header = header.substring(0, header.indexOf("ENDHDR"));
				String tuple = (r[1].equals("GRAY") ? "GRAYSCALE" : "RGB") + (r[2].equals("YES") ? "_ALPHA" : "");
				assertTrue(header.startsWith("P7") && header.contains("WIDTH 595") && header.contains("HEIGHT 842")
						&& header.contains("TUPLTYPE " + tuple + "\n"), name + " PAM header: " + header);
			} else if (r[0].equals("PNM")) {
				String magic = r[1].equals("GRAY") ? "P5" : "P6";
				assertTrue(start.startsWith(magic) && new String(image, 0, 20, "ISO-8859-1").contains("595 842"),
						name + " is a " + magic + " of 595 by 842: " + start);
			} else if (r[0].equals("BMP")) {
				assertTrue(start.startsWith("BM"), name + " is a BMP");
			} else {
				Map<Integer, long[]> tags = tiffTags(image);
				assertEquals("595x842", tags.get(256)[0] + "x" + tags.get(257)[0], name + " size");
				assertEquals(r[1].equals("RGB") ? 2L : 1L, tags.get(262)[0], name + " photometric interpretation");
				assertEquals(r[1].equals("RGB") ? (r[2].equals("YES") ? 4L : 3L) : 1L, tags.containsKey(277) ? tags.get(277)[0] : 1L,
						name + " samples per pixel");
				assertEquals(8L, tags.get(258)[0], name + " bits per sample");
				if (r[1].startsWith("BNW"))
					assertEquals(2, distinctStripValues(image, tags), name + " gray levels");
			}
		}
	}

	static void main_imageExportTiffCompressionAndJpegQuality(File dir) throws Exception {
		final File in = pdf(dir, "in.pdf", PageSize.A4);
		final File folder = dir;
		final String[] compressions = { "NONE", "LZW", "JPEG", "ZLIB", "PACKBITS", "DEFLATE" };
		RegressionTests.withJarFolder(RegressionTests.nativesDir(), new RegressionTests.Task() {
			public void run() throws Exception {
				for (String c : compressions)
					cli(in.getPath(), "-burstfiletype", "TIFF", "-tiffcompression", c, "-ob", new File(folder, c + "-*.tiff").getPath());
				for (String q : new String[] { "10", "90" })
					cli(in.getPath(), "-burstfiletype", "JPG", "-imagequality", q, "-ob", new File(folder, "q" + q + "-*.jpg").getPath());
			}
		});
		long[] expected = { 1, 5, 7, 8, 32773, 32946 };
		for (int i = 0; i < compressions.length; i++) {
			Map<Integer, long[]> tags = tiffTags(Files.readAllBytes(new File(dir, compressions[i] + "-1.tiff").toPath()));
			assertEquals(expected[i], tags.get(259)[0], compressions[i] + " compression tag");
		}
		assertTrue(new File(dir, "q10-1.jpg").length() < new File(dir, "q90-1.jpg").length(), "lower JPEG quality, smaller file");
	}

	/** A half transparent red square stays red at half opacity, in a PNG (written by MuPDF) and a TIFF (by Java). */
	static void main_imageExportKeepsTheColourOfTransparentPixels(File dir) throws Exception {
		final File in = new File(dir, "in.pdf");
		Document document = new Document(PageSize.A4);
		PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(in));
		document.open();
		PdfContentByte canvas = writer.getDirectContent();
		PdfGState half = new PdfGState();
		half.setFillOpacity(0.5f);
		canvas.setGState(half);
		canvas.setColorFill(BaseColor.RED);
		canvas.rectangle(100, 100, 200, 200);
		canvas.fill();
		document.close();
		final File folder = dir;
		RegressionTests.withJarFolder(RegressionTests.nativesDir(), new RegressionTests.Task() {
			public void run() throws Exception {
				for (String type : new String[] { "PNG", "TIFF" })
					cli(in.getPath(), "-burstfiletype", type, "-colormode", "RGB", "-transparency", "YES",
							"-ob", new File(folder, type + "-*").getPath());
			}
		});
		for (String name : new String[] { "PNG-1.png", "TIFF-1.tiff" }) {
			// the middle of the square, with y counted from the top of the page
			int argb = ImageIO.read(new File(dir, name)).getRGB(200, 842 - 200);
			int alpha = argb >>> 24, red = (argb >> 16) & 0xff, green = (argb >> 8) & 0xff, blue = argb & 0xff;
			assertTrue(alpha >= 126 && alpha <= 129 && red >= 250 && green <= 3 && blue <= 3,
					name + " pixel is red at half opacity: " + alpha + "," + red + "," + green + "," + blue);
		}
	}

	// ---- GUI ----

	/** Builds the main window, which lays out every tab with JGoodies Forms. */
	static void gui_mainFormBuildsEveryTab(File dir) throws Exception {
		final MainForm[] form = new MainForm[1];
		final int[] tabs = new int[1];
		SwingUtilities.invokeAndWait(new Runnable() {
			public void run() {
				try {
					UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
				form[0] = new MainForm();
				tabs[0] = tabbedPane(form[0].getContentPane()).getTabCount();
			}
		});
		try {
			assertEquals(11, tabs[0], "tabs");
		} finally {
			SwingUtilities.invokeAndWait(new Runnable() {
				public void run() {
					form[0].dispose();
				}
			});
		}
	}

	static JTabbedPane tabbedPane(Container c) {
		for (Component child : c.getComponents()) {
			if (child instanceof JTabbedPane)
				return (JTabbedPane) child;
			if (child instanceof Container) {
				JTabbedPane found = tabbedPane((Container) child);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	// ---- helpers ----

	/**
	 * The tags of the first image file directory of a TIFF. The values of
	 * SHORT and LONG tags are read as numbers; other tags have no values.
	 */
	static Map<Integer, long[]> tiffTags(byte[] tiff) {
		ByteBuffer b = ByteBuffer.wrap(tiff);
		b.order(b.get(0) == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
		assertEquals(42, (int) b.getShort(2), "TIFF signature");
		int ifd = b.getInt(4);
		Map<Integer, long[]> tags = new HashMap<Integer, long[]>();
		for (int i = 0, n = b.getShort(ifd) & 0xffff; i < n; i++) {
			int entry = ifd + 2 + 12 * i;
			int tag = b.getShort(entry) & 0xffff, type = b.getShort(entry + 2), count = b.getInt(entry + 4);
			if (type != 3 && type != 4) {
				tags.put(tag, new long[0]);
				continue;
			}
			int size = type == 3 ? 2 : 4;
			int at = count * size <= 4 ? entry + 8 : b.getInt(entry + 8);
			long[] values = new long[count];
			for (int v = 0; v < count; v++)
				values[v] = size == 2 ? b.getShort(at + 2 * v) & 0xffff : b.getInt(at + 4 * v) & 0xffffffffL;
			tags.put(tag, values);
		}
		return tags;
	}

	/** The number of distinct byte values in the strips of an uncompressed TIFF. */
	static int distinctStripValues(byte[] tiff, Map<Integer, long[]> tags) {
		assertEquals(1L, tags.get(259)[0], "uncompressed TIFF");
		boolean[] seen = new boolean[256];
		int distinct = 0;
		long[] offsets = tags.get(273), counts = tags.get(279);
		for (int s = 0; s < offsets.length; s++) {
			for (long i = offsets[s]; i < offsets[s] + counts[s]; i++) {
				int v = tiff[(int) i] & 0xff;
				if (!seen[v]) {
					seen[v] = true;
					distinct++;
				}
			}
		}
		return distinct;
	}

	/** A PDF of A4 pages saying prefix1, prefix2, and so on. */
	static File pdfWithPages(File dir, String name, String prefix, int pages) throws Exception {
		File f = new File(dir, name);
		Document document = new Document(PageSize.A4);
		PdfWriter.getInstance(document, new FileOutputStream(f));
		document.open();
		for (int i = 1; i <= pages; i++) {
			if (i > 1)
				document.newPage();
			document.add(new Paragraph(prefix + i));
		}
		document.close();
		return f;
	}

	/** The text of each page, trimmed. */
	static String pageLabelsOf(File pdf) throws Exception {
		List<String> result = new ArrayList<String>();
		for (int page = 1; page <= pageCount(pdf); page++)
			result.add(text(pdf, page).trim());
		return result.toString();
	}

	/** n for each page saying "Page n", 0 for blank pages. */
	static String pageNumbers(File pdf) throws Exception {
		List<Integer> result = new ArrayList<Integer>();
		for (int page = 1; page <= pageCount(pdf); page++) {
			String text = text(pdf, page).trim();
			result.add(text.length() == 0 ? 0 : Integer.parseInt(text.substring("Page ".length())));
		}
		return result.toString();
	}

	/** The number stored under key in a page dictionary, or -1. */
	static int pageEntry(File pdf, int page, String key) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			PdfNumber n = reader.getPageN(page).getAsNumber(new PdfName(key));
			return n == null ? -1 : n.intValue();
		} finally {
			reader.close();
		}
	}

	static int objectCount(File pdf) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			int count = 0;
			for (int i = 1; i < reader.getXrefSize(); i++)
				if (reader.getPdfObjectRelease(i) != null)
					count++;
			return count;
		} finally {
			reader.close();
		}
	}

	/** A JKS keystore with an RSA key "mykey", store and key password "secret". */
	static File keystore(File dir) throws Exception {
		File ks = new File(dir, "keystore.jks");
		String keytool = new File(System.getProperty("java.home"), "bin/keytool").getPath();
		Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "mykey", "-keyalg", "RSA", "-keysize", "2048",
				"-dname", "CN=jPDF Tweak Test", "-validity", "2", "-keystore", ks.getPath(), "-storetype", "JKS",
				"-storepass", "secret", "-keypass", "secret").redirectErrorStream(true).start();
		String output = new String(RegressionTests.readAll(p.getInputStream()), "UTF-8");
		if (p.waitFor() != 0)
			throw new AssertionError("keytool failed: " + output);
		return ks;
	}

	/**
	 * Checks that the file has one signature, that its byte ranges cover the
	 * whole file except the signature itself, and that the CMS signature
	 * verifies over them. The JDK's own PKCS7 class does the check, through
	 * reflection, as it is not exported. Returns the signature's /SubFilter.
	 */
	static String assertValidSignature(File pdf) throws Exception {
		byte[] file = Files.readAllBytes(pdf.toPath());
		PdfReader reader = new PdfReader(pdf.getPath());
		PdfDictionary sig;
		try {
			AcroFields fields = reader.getAcroFields();
			List<String> names = fields.getSignatureNames();
			assertEquals(1, names.size(), "signatures in " + pdf.getName());
			sig = fields.getSignatureDictionary(names.get(0));
		} finally {
			reader.close();
		}
		int[] range = new int[4];
		for (int i = 0; i < 4; i++)
			range[i] = sig.getAsArray(PdfName.BYTERANGE).getAsNumber(i).intValue();
		assertTrue(range[0] == 0 && range[2] + range[3] == file.length, "byte ranges cover the file: " + Arrays.toString(range));
		java.io.ByteArrayOutputStream signedBytes = new java.io.ByteArrayOutputStream();
		signedBytes.write(file, range[0], range[1]);
		signedBytes.write(file, range[2], range[3]);
		byte[] data = signedBytes.toByteArray();
		byte[] contents = sig.getAsString(PdfName.CONTENTS).getOriginalBytes();
		Class<?> pkcs7 = Class.forName("sun.security.pkcs.PKCS7");
		Object p7 = pkcs7.getConstructor(byte[].class).newInstance((Object) contents);
		String subFilter = sig.getAsName(PdfName.SUBFILTER).toString();
		if (subFilter.equals("/adbe.pkcs7.sha1")) {
			// The signed content is the SHA-1 digest of the byte ranges. verify(null)
			// verifies the signature over the content inside the PKCS7 block.
			assertTrue(pkcs7.getMethod("verify", byte[].class).invoke(p7, (Object) null) != null,
					pdf.getName() + " signature verifies");
			Object contentInfo = pkcs7.getMethod("getContentInfo").invoke(p7);
			byte[] signedDigest = (byte[]) contentInfo.getClass().getMethod("getData").invoke(contentInfo);
			assertTrue(Arrays.equals(MessageDigest.getInstance("SHA-1").digest(data), signedDigest),
					pdf.getName() + " signed digest matches the byte ranges");
		} else {
			assertTrue(pkcs7.getMethod("verify", byte[].class).invoke(p7, (Object) data) != null,
					pdf.getName() + " signature verifies over the byte ranges (" + subFilter + ")");
		}
		return subFilter;
	}

	/** A PDF with one page of each size, each saying "Page n". */
	static File pdf(File dir, String name, Rectangle... sizes) throws Exception {
		File f = new File(dir, name);
		Document document = new Document(sizes[0]);
		PdfWriter.getInstance(document, new FileOutputStream(f));
		document.open();
		for (int i = 0; i < sizes.length; i++) {
			if (i > 0) {
				document.setPageSize(sizes[i]);
				document.newPage();
			}
			document.add(new Paragraph("Page " + (i + 1)));
		}
		document.close();
		return f;
	}

	static File write(File f, String text) throws Exception {
		Files.write(f.toPath(), text.getBytes("UTF-8"));
		return f;
	}

	static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()), "UTF-8");
	}

	/** The output of "jpdftweak pdf option file" for -info, -moreinfo, or -savebookmarks. */
	static String info(File dir, File pdf, String option) throws Exception {
		File f = File.createTempFile("info", ".txt", dir);
		cli(pdf.getPath(), option, f.getPath());
		return read(f);
	}

	static String pageLabels(File pdf) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			return Arrays.toString(PdfPageLabels.getPageLabels(reader));
		} finally {
			reader.close();
		}
	}

	/** A one-page A4 PDF that shows the given text. */
	static File pdfWithText(File dir, String name, String text) throws Exception {
		File f = new File(dir, name);
		Document document = new Document(PageSize.A4);
		PdfWriter.getInstance(document, new FileOutputStream(f));
		document.open();
		document.add(new Paragraph(text));
		document.close();
		return f;
	}

	/**
	 * The texts shown on a page, each as { text, centre x of its baseline,
	 * y where the baseline starts, angle of the baseline in degrees }.
	 */
	static List<Object[]> textPositions(File pdf, int page) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			final List<Object[]> result = new ArrayList<Object[]>();
			new PdfReaderContentParser(reader).processContent(page, new RenderListener() {
				public void beginTextBlock() {
				}

				public void endTextBlock() {
				}

				public void renderImage(ImageRenderInfo info) {
				}

				public void renderText(TextRenderInfo info) {
					Vector start = info.getBaseline().getStartPoint();
					Vector end = info.getBaseline().getEndPoint();
					float dx = end.get(Vector.I1) - start.get(Vector.I1), dy = end.get(Vector.I2) - start.get(Vector.I2);
					float centreX = (start.get(Vector.I1) + end.get(Vector.I1)) / 2;
					float centreY = (start.get(Vector.I2) + end.get(Vector.I2)) / 2;
					float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
					// the watermark is checked by its centre, page numbers by where they start
					result.add(new Object[] { info.getText(), centreX, angle == 0 ? start.get(Vector.I2) : centreY, angle });
				}
			});
			return result;
		} finally {
			reader.close();
		}
	}

	/** Checks that text is shown centred on x, with its baseline at y. */
	static void assertTextAt(String text, float x, float y, File pdf, int page) throws Exception {
		List<Object[]> texts = textPositions(pdf, page);
		StringBuilder seen = new StringBuilder();
		for (Object[] t : texts) {
			if (text.equals(t[0])) {
				assertMatrix(new float[] { x, y }, new float[] { (Float) t[1], (Float) t[2] }, "\"" + text + "\" on page " + page);
				return;
			}
			seen.append(" \"").append(t[0]).append('"');
		}
		throw new AssertionError("\"" + text + "\" not on page " + page + ", found" + seen);
	}

	/** Copies in to out with the given key and value pairs set in one page dictionary. */
	static File withPageEntries(File in, File out, int page, PdfObject... keysAndValues) throws Exception {
		PdfReader reader = new PdfReader(in.getPath());
		PdfStamper stamper = new PdfStamper(reader, new FileOutputStream(out));
		for (int i = 0; i < keysAndValues.length; i += 2)
			reader.getPageN(page).put((PdfName) keysAndValues[i], keysAndValues[i + 1]);
		stamper.close();
		return out;
	}

	static Rectangle mediaBox(File pdf, int page) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			return reader.getPageSize(page);
		} finally {
			reader.close();
		}
	}

	static int rotation(File pdf, int page) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			return reader.getPageRotation(page);
		} finally {
			reader.close();
		}
	}

	static String content(File pdf, int page) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			return new String(reader.getPageContent(page), "ISO-8859-1");
		} finally {
			reader.close();
		}
	}

	static String text(File pdf, int page) throws Exception {
		PdfReader reader = new PdfReader(pdf.getPath());
		try {
			return PdfTextExtractor.getTextFromPage(reader, page);
		} finally {
			reader.close();
		}
	}

	/** The matrices of all "cm" operators in the content of a page, in order. */
	static float[][] placements(File pdf, int page) throws Exception {
		List<float[]> result = new ArrayList<float[]>();
		String n = "(-?[0-9.]+)";
		Matcher m = Pattern.compile(n + " " + n + " " + n + " " + n + " " + n + " " + n + " cm").matcher(content(pdf, page));
		while (m.find()) {
			float[] matrix = new float[6];
			for (int i = 0; i < 6; i++)
				matrix[i] = Float.parseFloat(m.group(i + 1));
			result.add(matrix);
		}
		return result.toArray(new float[result.size()][]);
	}

	static float[] floats(PdfArray array) {
		float[] result = new float[array.size()];
		for (int i = 0; i < result.length; i++)
			result[i] = array.getAsNumber(i).floatValue();
		return result;
	}

	static void assertBox(float width, float height, Rectangle box, String what) {
		assertMatrix(new float[] { width, height }, new float[] { box.getWidth(), box.getHeight() }, what + " page size");
	}

	/** Compares numbers to within 0.01, which is below what the PDF writers round to. */
	static void assertMatrix(float[] expected, float[] actual, String what) {
		boolean same = expected.length == actual.length;
		for (int i = 0; same && i < expected.length; i++)
			same = Math.abs(expected[i] - actual[i]) < 0.01f;
		if (!same)
			throw new AssertionError(what + ": expected " + java.util.Arrays.toString(expected) + " but was "
					+ java.util.Arrays.toString(actual));
	}
}
