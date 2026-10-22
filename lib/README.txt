OBTAINING LIBRARIES
===================

"ant deps", which "ant compile" runs first, downloads these libraries
from Maven Central into this directory and checks them against the
SHA-256 checksums listed in build.xml:

* iText 5.5.13.6
  -> itextpdf-5.5.13.6.jar

* BouncyCastle 1.86 (Provider, PKIX, and Utilities, for Java 8 and later)
  -> bcprov-jdk18on-1.86.jar
  -> bcpkix-jdk18on-1.86.jar
  -> bcutil-jdk18on-1.86.jar

* JGoodies Forms 1.9.0
  -> jgoodies-forms-1.9.0.jar

* JGoodies Common 1.8.1
  -> jgoodies-common-1.8.1.jar

"ant mupdf-jar", which "ant compile" also runs first, downloads the
MuPDF source release, checks it against the SHA-256 checksum listed in
build.xml, and compiles its Java binding into this directory:

* MuPDF 1.28.5, from https://mupdf.com/releases
  -> mupdf-1.28.5.jar

The binding's shared library is not kept here. "ant natives" builds it
from the same source into build/natives.
