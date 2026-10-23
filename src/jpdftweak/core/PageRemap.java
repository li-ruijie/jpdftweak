package jpdftweak.core;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PRIndirectReference;
import com.itextpdf.text.pdf.PdfAnnotation;
import com.itextpdf.text.pdf.PdfArray;
import com.itextpdf.text.pdf.PdfDictionary;
import com.itextpdf.text.pdf.PdfIndirectReference;
import com.itextpdf.text.pdf.PdfName;
import com.itextpdf.text.pdf.PdfNameTree;
import com.itextpdf.text.pdf.PdfNull;
import com.itextpdf.text.pdf.PdfNumber;
import com.itextpdf.text.pdf.PdfObject;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfWriter;

/**
 * Carries what points at pages from a document into the one that a step
 * builds from its pages. Each input page is shown on output pages through
 * placement matrices. A destination goes to the first output page that
 * shows its page, with its coordinates moved by that page's matrix, and is
 * dropped if no output page shows it.
 */
class PageRemap {

	/**
	 * Gives the output's copy of an object that it shares with the imported
	 * pages, such as an optional content group or a stream.
	 */
	interface Sharer {
		PdfObject share(PRIndirectReference ref) throws IOException, DocumentException;
	}

	private static final PdfName SE = new PdfName("SE");

	/**
	 * The catalog entries carried over, besides /Names and /PageLabels. The
	 * others (the structure tree, article threads, the form) belong to the
	 * input pages, which the output only draws.
	 */
	private static final PdfName[] CATALOG_ENTRIES = { PdfName.OUTLINES, PdfName.DESTS, PdfName.VIEWERPREFERENCES,
			PdfName.PAGEMODE, PdfName.PAGELAYOUT, PdfName.OPENACTION, PdfName.LANG, PdfName.OCPROPERTIES };

	private final PdfReader reader;
	private final PdfWriter writer;
	private final Sharer sharer;
	/** The page number of each page object, by object number */
	private final Map<Integer, Integer> inputPages = new HashMap<Integer, Integer>();
	/** The first output page that shows each input page, 0 if none */
	private final int[] outputPages;
	private final float[][] matrices;
	/** The copies of the indirect objects of reader, by object number */
	private final Map<Integer, PdfIndirectReference> copies = new HashMap<Integer, PdfIndirectReference>();

	/**
	 * For a PdfWriter that draws imported pages. It writes the objects that
	 * a reference to reader names through its PdfReaderInstance, the same
	 * copies as the imported pages use.
	 */
	PageRemap(PdfReader reader, PdfWriter writer) {
		this(reader, writer, new Sharer() {
			public PdfObject share(PRIndirectReference ref) {
				return ref;
			}
		});
	}

	PageRemap(PdfReader reader, PdfWriter writer, Sharer sharer) {
		this.reader = reader;
		this.writer = writer;
		this.sharer = sharer;
		int count = reader.getNumberOfPages();
		outputPages = new int[count + 1];
		matrices = new float[count + 1][];
		for (int i = 1; i <= count; i++)
			inputPages.put(reader.getPageOrigRef(i).getNumber(), i);
	}

	/**
	 * Records that inputPage is shown on outputPage through the matrix
	 * a b c d e f, unless an earlier output page shows it.
	 */
	void place(int inputPage, int outputPage, float a, float b, float c, float d, float e, float f) {
		if (outputPages[inputPage] == 0) {
			outputPages[inputPage] = outputPage;
			matrices[inputPage] = new float[] { a, b, c, d, e, f };
		}
	}

	/**
	 * Adds the links of inputPage, placed through the matrix a b c d e f, to
	 * the current output page. Call it after placing every page.
	 */
	void copyLinks(int inputPage, float a, float b, float c, float d, float e, float f) throws IOException, DocumentException {
		PdfArray annots = reader.getPageN(inputPage).getAsArray(PdfName.ANNOTS);
		for (int i = 0; annots != null && i < annots.size(); i++) {
			PdfDictionary link = annots.getAsDict(i);
			if (link == null || !PdfName.LINK.equals(link.getAsName(PdfName.SUBTYPE)))
				continue;
			PdfDictionary copy = (PdfDictionary) copyDirect(link);
			if (lost(link, copy, PdfName.DEST) || lost(link, copy, PdfName.A))
				continue; // it points at a page that is not shown
			PdfArray rect = link.getAsArray(PdfName.RECT);
			float[] p = transform(rect.getAsNumber(0).floatValue(), rect.getAsNumber(1).floatValue(), a, b, c, d, e, f);
			float[] q = transform(rect.getAsNumber(2).floatValue(), rect.getAsNumber(3).floatValue(), a, b, c, d, e, f);
			copy.remove(PdfName.RECT);
			copy.remove(PdfName.P); // the input page
			copy.remove(PdfName.STRUCTPARENT); // the structure tree is not carried over
			PdfAnnotation annotation = new PdfAnnotation(writer, new Rectangle(Math.min(p[0], q[0]), Math.min(p[1], q[1]),
					Math.max(p[0], q[0]), Math.max(p[1], q[1])));
			annotation.putAll(copy);
			writer.addAnnotation(annotation);
		}
	}

	/**
	 * Copies the catalog entries, the named destinations and attachments of
	 * /Names, and the page labels if keepPageLabels. Call it after placing
	 * every page and before closing the document.
	 */
	void copyCatalog(boolean keepPageLabels) throws IOException, DocumentException {
		PdfDictionary catalog = reader.getCatalog();
		for (PdfName key : CATALOG_ENTRIES)
			putInCatalog(key, copy(catalog.get(key)));
		if (keepPageLabels)
			putInCatalog(PdfName.PAGELABELS, copy(catalog.get(PdfName.PAGELABELS)));
		PdfDictionary names = catalog.getAsDict(PdfName.NAMES);
		if (names == null)
			return;
		PdfDictionary newNames = new PdfDictionary();
		PdfDictionary dests = names.getAsDict(PdfName.DESTS);
		if (dests != null) {
			HashMap<String, PdfObject> newDests = new HashMap<String, PdfObject>();
			for (Map.Entry<String, PdfObject> e : PdfNameTree.readTree(dests).entrySet()) {
				PdfObject dest = copy(e.getValue());
				if (dest != null)
					newDests.put(e.getKey(), dest);
			}
			if (!newDests.isEmpty())
				newNames.put(PdfName.DESTS, writer.addToBody(PdfNameTree.writeTree(newDests, writer)).getIndirectReference());
		}
		PdfObject files = copy(names.get(PdfName.EMBEDDEDFILES));
		if (files != null)
			newNames.put(PdfName.EMBEDDEDFILES, files);
		if (newNames.size() > 0)
			putInCatalog(PdfName.NAMES, newNames);
	}

	/**
	 * The writer adds its catalog after the objects of its imported pages, so
	 * a dictionary or array goes in as an indirect object written now.
	 */
	private void putInCatalog(PdfName key, PdfObject value) throws IOException {
		if (value == null)
			return;
		if (value.isDictionary() || value.isArray())
			value = writer.addToBody(value).getIndirectReference();
		writer.getExtraCatalog().put(key, value);
	}

	private static boolean lost(PdfDictionary original, PdfDictionary copy, PdfName key) {
		return original.get(key) != null && copy.get(key) == null;
	}

	private static float[] transform(float x, float y, float a, float b, float c, float d, float e, float f) {
		return new float[] { a * x + c * y + e, b * x + d * y + f };
	}

	/**
	 * Copies an object of reader for the output, with each local destination
	 * moved, each page reference replaced by its output page, and null for
	 * what refers to a page that is not shown.
	 */
	private PdfObject copy(PdfObject object) throws IOException, DocumentException {
		if (!(object instanceof PRIndirectReference))
			return object == null ? null : copyDirect(object);
		PRIndirectReference ref = (PRIndirectReference) object;
		Integer page = inputPages.get(ref.getNumber());
		if (page != null)
			return outputPages[page] == 0 ? null : writer.getPageReference(outputPages[page]);
		PdfIndirectReference copied = copies.get(ref.getNumber());
		if (copied != null)
			return copied;
		PdfObject target = PdfReader.getPdfObject(ref);
		if (target == null || target.isNull())
			return null;
		if (target.isStream() || isType(target, PdfName.OCG) || isType(target, PdfName.OCMD))
			return sharer.share(ref);
		if (isType(target, PdfName.PAGES) || isType(target, PdfName.PAGE))
			return null; // the page tree, or a page outside of it
		if (pointsNowhere(target))
			return null;
		copied = writer.getPdfIndirectReference();
		copies.put(ref.getNumber(), copied);
		writer.addToBody(copyDirect(target), copied);
		return copied;
	}

	private PdfObject copyDirect(PdfObject object) throws IOException, DocumentException {
		if (object.isDictionary()) {
			if (pointsNowhere(object))
				return null;
			PdfDictionary dict = (PdfDictionary) object;
			PdfDictionary result = new PdfDictionary();
			for (PdfName key : dict.getKeys()) {
				if (key.equals(SE))
					continue; // the structure tree is not carried over
				PdfObject value = copy(dict.get(key));
				if (value != null)
					result.put(key, value);
			}
			return result;
		}
		if (object.isArray()) {
			PdfArray array = (PdfArray) object;
			if (inputPage(array) != 0)
				return destination(array);
			PdfArray result = new PdfArray();
			for (int i = 0; i < array.size(); i++) {
				PdfObject value = copy(array.getPdfObject(i));
				result.add(value == null ? PdfNull.PDFNULL : value);
			}
			return result;
		}
		return object;
	}

	private static boolean isType(PdfObject object, PdfName type) {
		return object.isDictionary() && type.equals(((PdfDictionary) object).getAsName(PdfName.TYPE));
	}

	/**
	 * Whether object is a destination array, or holds one in /D (as a GoTo
	 * action or a /Dests entry does), that points at a page not shown.
	 */
	private boolean pointsNowhere(PdfObject object) {
		if (object.isDictionary())
			object = PdfReader.getPdfObject(((PdfDictionary) object).get(PdfName.D));
		if (object == null || !object.isArray())
			return false;
		int page = inputPage((PdfArray) object);
		return page != 0 && outputPages[page] == 0;
	}

	/** The input page of a local destination, 0 if array is none. */
	private int inputPage(PdfArray array) {
		if (array.size() < 2 || !(array.getPdfObject(0) instanceof PRIndirectReference) || array.getAsName(1) == null)
			return 0;
		Integer page = inputPages.get(((PRIndirectReference) array.getPdfObject(0)).getNumber());
		return page == null ? 0 : page;
	}

	/**
	 * Moves a local destination to the output, or returns null if its page
	 * is not shown. The matrices are axis-aligned or turn by a multiple of
	 * 90 degrees, so under a quarter turn /FitH becomes /FitV and the other
	 * way round. A coordinate computed from a null coordinate stays null.
	 */
	private PdfArray destination(PdfArray dest) {
		int page = inputPage(dest);
		if (outputPages[page] == 0)
			return null;
		float[] m = matrices[page];
		boolean quarterTurn = m[0] == 0 && m[3] == 0;
		PdfName type = dest.getAsName(1);
		PdfArray result = new PdfArray();
		result.add(writer.getPageReference(outputPages[page]));
		if (type.equals(PdfName.XYZ)) {
			Float x = number(dest, 2), y = number(dest, 3);
			result.add(type);
			result.add(coordinate(m[0], x, m[2], y, m[4]));
			result.add(coordinate(m[1], x, m[3], y, m[5]));
			for (int i = 4; i < dest.size(); i++)
				result.add(dest.getPdfObject(i)); // the zoom
		} else if (type.equals(PdfName.FITH) || type.equals(PdfName.FITBH)) {
			Float top = number(dest, 2);
			if (quarterTurn) {
				result.add(type.equals(PdfName.FITH) ? PdfName.FITV : PdfName.FITBV);
				result.add(coordinate(0, null, m[2], top, m[4]));
			} else {
				result.add(type);
				result.add(coordinate(0, null, m[3], top, m[5]));
			}
		} else if (type.equals(PdfName.FITV) || type.equals(PdfName.FITBV)) {
			Float left = number(dest, 2);
			if (quarterTurn) {
				result.add(type.equals(PdfName.FITV) ? PdfName.FITH : PdfName.FITBH);
				result.add(coordinate(m[1], left, 0, null, m[5]));
			} else {
				result.add(type);
				result.add(coordinate(m[0], left, 0, null, m[4]));
			}
		} else if (type.equals(PdfName.FITR) && dest.size() >= 6) {
			float[] p = transform(dest.getAsNumber(2).floatValue(), dest.getAsNumber(3).floatValue(), m[0], m[1], m[2], m[3], m[4], m[5]);
			float[] q = transform(dest.getAsNumber(4).floatValue(), dest.getAsNumber(5).floatValue(), m[0], m[1], m[2], m[3], m[4], m[5]);
			result.add(type);
			result.add(new PdfNumber(Math.min(p[0], q[0])));
			result.add(new PdfNumber(Math.min(p[1], q[1])));
			result.add(new PdfNumber(Math.max(p[0], q[0])));
			result.add(new PdfNumber(Math.max(p[1], q[1])));
		} else {
			for (int i = 1; i < dest.size(); i++)
				result.add(dest.getPdfObject(i)); // /Fit and /FitB keep only their page
		}
		return result;
	}

	private static Float number(PdfArray array, int index) {
		PdfNumber n = index < array.size() ? array.getAsNumber(index) : null;
		return n == null ? null : n.floatValue();
	}

	/** p * u + q * v + k, or null if a term with a coefficient that is not 0 is null. */
	private static PdfObject coordinate(float p, Float u, float q, Float v, float k) {
		if ((p != 0 && u == null) || (q != 0 && v == null))
			return PdfNull.PDFNULL;
		return new PdfNumber((p == 0 ? 0 : p * u) + (q == 0 ? 0 : q * v) + k);
	}
}
