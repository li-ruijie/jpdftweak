package jpdftweak.core;

import java.io.IOException;
import java.io.OutputStream;

import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.pdf.BadPdfFormatException;
import com.itextpdf.text.pdf.PRIndirectReference;
import com.itextpdf.text.pdf.PdfCopy;
import com.itextpdf.text.pdf.PdfName;
import com.itextpdf.text.pdf.PdfObject;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfSmartCopy;

/**
 * A PdfCopy with the copyAcroForm method of iText 5.1, which iText 5.5
 * replaced by a field merging mode. That mode only adds whole documents or
 * page lists of one document at a time, so it cannot build the page orders
 * with blank pages that PdfTweak needs.
 */
class FormCopy extends PdfCopy {

	FormCopy(Document document, OutputStream os) throws DocumentException {
		super(document, os);
	}

	/**
	 * Copies the form of reader into the output. Call it after adding the
	 * pages, so that the form refers to the copied field widgets.
	 */
	void copyAcroForm(PdfReader reader) throws IOException, BadPdfFormatException {
		PdfObject form = reader.getCatalog().get(PdfName.ACROFORM);
		if (form instanceof PRIndirectReference) {
			setFromReader(reader);
			getExtraCatalog().put(PdfName.ACROFORM, copyIndirect((PRIndirectReference) form));
		}
	}

	/** The same for a PdfSmartCopy. */
	static class Smart extends PdfSmartCopy {

		Smart(Document document, OutputStream os) throws DocumentException {
			super(document, os);
		}

		void copyAcroForm(PdfReader reader) throws IOException, BadPdfFormatException {
			PdfObject form = reader.getCatalog().get(PdfName.ACROFORM);
			if (form instanceof PRIndirectReference) {
				setFromReader(reader);
				getExtraCatalog().put(PdfName.ACROFORM, copyIndirect((PRIndirectReference) form));
			}
		}
	}
}
