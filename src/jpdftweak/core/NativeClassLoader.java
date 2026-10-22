package jpdftweak.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the classes that call a native library, so that their
 * System.loadLibrary finds the library in a given folder. Java asks the class
 * loader of the calling class for the library before it searches
 * java.library.path, which can no longer be changed at run time since Java 15.
 *
 * The classes whose names start with one of the given prefixes are defined by
 * this loader from the class files the parent loader sees. All other classes
 * come from the parent, so these classes can only be used through types the
 * parent loader defines.
 */
class NativeClassLoader extends ClassLoader {

	private final File libraryFolder;
	private final String[] prefixes;

	NativeClassLoader(ClassLoader parent, File libraryFolder, String... prefixes) {
		super(parent);
		this.libraryFolder = libraryFolder;
		this.prefixes = prefixes;
	}

	@Override
	protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		if (!isOwnClass(name))
			return super.loadClass(name, resolve);
		Class<?> c = findLoadedClass(name);
		if (c == null) {
			byte[] bytes;
			try {
				bytes = readClassFile(name);
			} catch (IOException ex) {
				throw new ClassNotFoundException(name, ex);
			}
			c = defineClass(name, bytes, 0, bytes.length);
		}
		if (resolve)
			resolveClass(c);
		return c;
	}

	@Override
	protected String findLibrary(String libname) {
		File library = new File(libraryFolder, System.mapLibraryName(libname));
		return library.isFile() ? library.getAbsolutePath() : null;
	}

	private boolean isOwnClass(String name) {
		for (String prefix : prefixes) {
			if (name.startsWith(prefix))
				return true;
		}
		return false;
	}

	private byte[] readClassFile(String name) throws IOException {
		InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class");
		if (in == null)
			throw new IOException("Class file not found");
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int n;
			while ((n = in.read(buffer)) != -1)
				out.write(buffer, 0, n);
			return out.toByteArray();
		} finally {
			in.close();
		}
	}
}
