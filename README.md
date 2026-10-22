# jPDF Tweak

> **Note:** This repository started as a mirror of https://sourceforge.net/projects/jpdftweak/ and adds bug fixes, current libraries, and automated builds to version 1.1.

This program requires Java 21 or higher, for example from https://adoptium.net/.

Start it by double clicking the jar file, or by:

```bash
java -jar jpdftweak.jar
```

For the command line options, add `-help` to the command line above.

The "burst in image file type" and "export in Tiff multipage" features use MuPDF, whose shared library must be in the same folder as jpdftweak.jar. The release archives for Windows x64, Linux x64, and Linux arm64 include it. On other systems these two features are not available. For more details please read the section "Burst as Image / Export Multipage Tiff Features" below.

See the `manual` folder for short manual.

## Java Memory Limit

By default, Java lets a program use up to a quarter of the computer's memory. When processing large PDFs, this can cause out of memory errors.

You can increase the memory that will be used by jPDF Tweak by editing the `jpdftweak.cmd` file with a text editor; change:

```batch
@java -jar "%~dp0jpdftweak.jar" %*
```

to for example:

```batch
@java -Xmx4G -jar "%~dp0jpdftweak.jar" %*
```

to allow for 4 GB of RAM. Alternatively, if you want to perform lots of operations, you can check the "Use temporary files" option on the Output tab.

## Burst as Image / Export Multipage Tiff Features

To burst pages as image files or export in multipage Tiff, the shared library of MuPDF's Java binding is required to be in the same path with this jar. The release archive for each platform includes it, and `ant natives` builds it from the MuPDF source. Images are exported at 72 dpi.

### Shared Libraries

| Platform    | Library            |
|-------------|--------------------|
| Windows x64 | mupdf_java64.dll   |
| Linux x64   | libmupdf_java64.so |
| Linux arm64 | libmupdf_java64.so |

## Building

Building needs JDK 21 or newer and Apache Ant 1.10.

```bash
ant jar      # downloads the libraries into lib/ and builds jpdftweak.jar
ant natives  # builds MuPDF's shared library for this platform into build/natives/<version>
ant test     # builds both and runs the tests
```

`build.xml` pins the version and SHA-256 checksum of every library it downloads.

`ant natives` needs Visual Studio 2022 or newer, or its Build Tools, on Windows. On other systems it needs make, a C compiler, pkg-config, and the FreeType, OpenSSL, and zlib development files. The GUI test needs a display. On Linux without one, run `xvfb-run ant test`.

## License

jPDF Tweak is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See LICENSE.

## Contact

Please send bug reports and suggestions to <schierlm@users.sourceforge.net>.

## ChangeLog

### Unreleased - Version 1.2.0

- Require Java 21 or newer
- Update iText to 5.5.13.6, BouncyCastle to 1.86, and JGoodies Forms and Common to 1.9.0 and 1.8.1, which the build now downloads from Maven Central and checks against pinned checksums
- Sign with SHA-256 in a detached CMS signature (adbe.pkcs7.detached), as iText 5.5 no longer creates the SHA-1 signatures (adbe.pkcs7.sha1) of earlier versions
- "Optimize PDF size" now shares duplicate objects, such as fonts and content streams repeated across input files, which iText 5.1 did not
- Export images with the Java binding of MuPDF 1.28.5 instead of JMuPdf 0.2b, whose project is gone and whose MuPDF dated from 2011. The build makes MuPDF's shared library from source for Windows x64, Linux x64, and Linux arm64. TIFF images are written by Java, so an RGB TIFF with transparency now marks its alpha channel properly
- Bug fixes:
  - Scale the vertical offset of a shuffle rule by the page height when it is given in percent, and not when only the horizontal offset is (also for preserved link destinations)
  - Name the horizontal blinds page transition "Horizontal Blinds" so that it can be selected, as it was listed as a second "Vertical Blinds"
  - Fix a crash when bursting pages (`-ob`) from the command line without any transformation option
  - Accept page numbers with more than one digit in `-trans` and in transition files
  - Name burst images and multipage TIFF files by changing only the extension of the file name, so that a dot in a folder name or in a page label no longer makes every page overwrite one file outside the folder
  - Replace an existing TIFF file when exporting, instead of appending the new pages to it
  - Fix a crash when preserving hyperlinks with a shuffle rule that leaves pages out, by dropping links to the pages that are left out
  - Find the shared library for image export next to jpdftweak.jar on Java 15 and later, where image export failed unless jPDF Tweak was started from that folder (on Linux it always failed), and the Output tab showed a "sys_paths" error
  - Report a shared library that cannot be loaded as an error message instead of a crash, and allow native access in the jar manifest so that current Java versions do not warn about it
  - Accept bookmark options (the last column of a bookmark line) in any order, which were rejected with "More options incorrect" unless written in Java's internal hash order
  - Do not print a colon for a page label whose prefix is present but empty in `-info` and `-moreinfo` output
  - Keep the rotation of a one-page document when rotating it explicitly, or when rotating it before scaling, shuffling, or removing the page rotation, which all lost it
  - Accept every spelling of `-rot[ate]exp[licitly]` that the help lists, as `-rotateexp` and `-rotexplicitly` were rejected as unknown options
  - Correct the command line help: list LZW (not "LWZ") for `-tiffcompression`, put `-encmode` and `-encnometadata` on separate lines, spell `-permissions` as it is accepted, call page number formats by their name in the `-pagenumbers` help, and list the `-oi` flag and what `-om` does without `-ou`
  - Rename the viewer preference "Display document tile" to "Display document title" (`-viewpref` still accepts the old name)
  - Make `jpdftweak.cmd` work when started from another folder, as it looked for jpdftweak.jar in the current folder
  - End each `PAGESIZE` range of the `-moreinfo` output at the last page of that size, instead of the first page of the next size
  - Report an image that could not be saved, such as a PNG in black and white from the command line, instead of reporting success without writing it
  - Write BMP files in the RGB and grayscale color modes, which wrote nothing (RGB is the default on the Output tab)
  - Require the shared library for image export only for `-burstfiletype` with an image type, and not for `-colormode`, `-tiffcompression`, `-transparency`, or `-imagequality`
  - Close the `-background` watermark file after use, which stayed open (and locked on Windows) until jPDF Tweak exited

### 2011-12-19 - Version 1.1

- Add new interleave option to CLI and GUI to interleave two documents
- Add support to add empty pages before including a PdfPageRange
- Add new `-crop` option to GUI and CLI to crop the document to one of the PDF Page Boxes (if the document contains them)
- Add "book printing" feature: Repeat shuffle rule for blocks of a fixed size
- Add "Split 2-Up" shuffle rule contributed by Paolo Bonzini
- Add new function to export PDF pages as images in various image formats or export PDF Documents to multi-page TIFF on supported platforms, uses native JMuPdf library
- Bug fixes and internal improvements:
  - Update iText (5.1.3), BouncyCastle (1.46), JGoodies Forms (1.4.2) and Proguard (4.6); iText fixes (among other things) incorrect page label rendering for EMPTY page labels
  - Show an error if page number label does not fit completely inside the page bounding box
  - Properly complain when trying to sign with an alias that has no private key assigned
  - Properly reject font sizes of 0 on the Watermark tab as they will cause a crash later
  - Create proper error messages when using invalid Page specification in a shuffle rule
  - Try to load the PDF in non-partial mode if loading in partial mode fails, since non-partial mode can rebuild the xref table when broken
  - Change the way to generate empty pages in shuffle rules; the new way will create slightly smaller PDFs
  - Create directory for burst pages if it does not exist
  - Use page labels in burst filenames if present

### 2010-12-27 - Version 1.0

- Add shuffle rule to reverse page order
- Add application icon contributed anonymously
- Update manual
- Bug fixes:
  - Show an error when using batch processing but no output variables are used
  - Detect missing library files and show a friendly error message
  - Include lib/README file in nolib version
  - Fix buildfile so that it does not add libraries to compact version

### 2010-11-06 - Version 1.0-rc1

- Formal changes:
  - As the program got quite stable, this is the first version that is no longer beta but a release candidate
  - Update to iText 5.0.5, BouncyCastle 1.45 and JGoodies Forms 1.3
  - Due to changes in licensing of the PDF library used by jPDF Tweak (iText), the license of the current version changed to GNU Affero General Public License version 3 (AGPLv3+)
- Support more than one transformation block at once in the command line interface
- Add support for PDF 1.5 compression (Acrobat 6.0)
- Enable multiselect in the file chooser for multifile and batch modes
- Page number improvements:
  - Add support for printing page labels either as pre-formatted logical page numbers (i.e. Roman/Arabic etc.) or as custom formatted logical page numbers (e.g. with leading zeroes)
  - Add page number UI on watermark tab to change (printed) page numbers before performing shuffle rule
  - Add option to print page numbers on inner/outer edge (flip on even/odd pages)
- Bug fixes:
  - Improve validation of page ranges on the input tab
  - Fix handling of dirty cell editors and invalid cell values
  - Fix `-ot` (temp files) command line option
  - Fix handling of XMP metadata when changing document info
  - Preserve outlines (bookmarks), viewer preferences and page numbers when optimizing for size

### 2009-07-13 - Version 0.9.5

- Update to iText 2.1.7, BouncyCastle 1.43 and JGoodies Forms 1.2.1
- Add option to change mask used for printing page numbers (to do things like "Page 2 of 11")
- New "optimize for size" option
- Add an option to save intermediary results into temporary files
- Add color option for text watermark
- Add "Tile Copy" shuffle rules by Stefan Michel
- Show a red ugly dialog box when a fatal exception occurs
- Complain if output file is one of the input files
- Complain when setting permissions without setting an owner password
- Bug fixes:
  - Do not show logical page twice (once instead of physical page) when loading page numbers
  - Close all input/output streams when finished
  - Fix exception when removing elements from the attachment tab
  - Copy info dictionary after every operation that might destroy it
  - Fix a ClassCastException and catch NullPointerException when trying to parse shuffle rules
  - Fix crash when deleting a row in a table when a cell editor is active
  - Catch BadPasswordException to only show a password prompt when the reason is really a bad password
  - Fix adding new PDF bookmarks to a PDF file that did not have any
  - Fix a typo in "Penguin Small/Large Paperback" page size
  - Catch OutOfMemoryError and display an error message

### 2007-09-10 - Version 0.9

- Update to iText 2.0.5
- Preserve hyperlinks when resizing and shuffling if desired
- Add frames to n-up printings if desired
- Add page label edit support
- Add batch processing support to UI (and more wildcards for output tab)
- Add command line support
- Export bookmarks to CSV, import from CSV or PDF
- Add more predefined shuffle rules
- Bug fixes:
  - Load unicode strings in info dictionary correctly
  - Encryption GUI fixes:
    - Fix initialization of "Do not encrypt metadata" checkbox
    - Fix wrong permissions displayed when using 40-bit encryption
    - Use 128-bit encryption by default
    - Disable unsupported checkboxes when using 40-bit encryption
  - Fix crash when loading chapter bookmarks from pdf without bookmarks
  - Fix crash when trying to output pages in reverse order

### 2007-04-09 - Version 0.1

- First public release
