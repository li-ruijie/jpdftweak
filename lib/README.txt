OBTAINING LIBRARIES
===================

"ant deps", which "ant compile" runs first, downloads these libraries
from Maven Central into this directory and checks them against the
SHA-256 checksums listed in build.xml, which also names their versions:

* iText 5
  -> itextpdf-<version>.jar

* BouncyCastle (Provider, PKIX, and Utilities, for Java 8 and later)
  -> bcprov-jdk18on-<version>.jar
  -> bcpkix-jdk18on-<version>.jar
  -> bcutil-jdk18on-<version>.jar

* JGoodies Forms
  -> jgoodies-forms-<version>.jar

* JGoodies Common
  -> jgoodies-common-<version>.jar

"ant mupdf-jar", which "ant compile" also runs first, downloads the
MuPDF source release, checks it against the SHA-256 checksum listed in
build.xml, and compiles its Java binding into this directory:

* MuPDF, from https://mupdf.com/releases
  -> mupdf-<version>.jar

The binding's shared library is not kept here. "ant natives" builds it
from the same source into build/natives/<version>.
