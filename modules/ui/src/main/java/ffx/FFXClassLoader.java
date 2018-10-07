/**
 * Title: Force Field X.
 * <p>
 * Description: Force Field X - Software for Molecular Biophysics.
 * <p>
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2018.
 * <p>
 * This file is part of Force Field X.
 * <p>
 * Force Field X is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 * <p>
 * Force Field X is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place, Suite 330, Boston, MA 02111-1307 USA
 * <p>
 * Linking this library statically or dynamically with other modules is making a
 * combined work based on this library. Thus, the terms and conditions of the
 * GNU General Public License cover the whole combination.
 * <p>
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent modules, and
 * to copy and distribute the resulting executable under terms of your choice,
 * provided that you also meet, for each linked independent module, the terms
 * and conditions of the license of that module. An independent module is a
 * module which is not derived from or based on this library. If you modify this
 * library, you may extend this exception to your version of the library, but
 * you are not obligated to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */
package ffx;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Class loader able to load classes and DLLs with a higher priority from a
 * given set of JARs. Its bytecode is Java 1.1 compatible to be loadable by old
 * JVMs.
 *
 * @author Michael J. Schnieders; derived from work by Emmanuel Puybaret
 */
public class FFXClassLoader extends URLClassLoader {

    private final ProtectionDomain protectionDomain;

    // private final Map<String, String> extensionDlls = new HashMap<>();

    private JarFile[] extensionJars = null;
    private final String[] applicationPackages = {
            "ffx",
            "javax.media.j3d",
            "javax.vecmath",
            "javax.media.opengl",
            "com.sun.j3d",
            "info.picocli",
            "org.codehaus.groovy",
            "org.apache.commons.configuration2",
            "org.apache.commons.io",
            "org.apache.commons.lang3",
            "org.apache.commons.math3",
            "org.jogamp",
            "org.openscience.cdk",
            "edu.rit.pj",
            "edu.uiowa.jopenmm",
            "org.bridj",
            "it.unimi.dsi",
            "jcuda",
            "simtk"
    };
    static final List<String> FFX_FILES;
    private boolean extensionsLoaded = false;

    static {
        FFX_FILES = new ArrayList<>(Arrays.asList(new String[]{
                "edu.uiowa.eng.ffx/algorithms.jar",
                "edu.uiowa.eng.ffx/autoparm.jar",
                "edu.uiowa.eng.ffx/binding.jar",
                "edu.uiowa.eng.ffx/crystal.jar",
                "edu.uiowa.eng.ffx/numerics.jar",
                "edu.uiowa.eng.ffx/potential.jar",
                "edu.uiowa.eng.ffx/ui.jar",
                "edu.uiowa.eng.ffx/utilities.jar",
                "edu.uiowa.eng.ffx/xray.jar",
                "edu.uiowa.eng.ffx/pj.jar",
                // Force Field X Extensions
                "edu.uiowa.eng.ffx/algorithms-ext.jar",
                "edu.uiowa.eng.ffx/xray-ext.jar",
                // Groovy
                "org.codehaus.groovy/groovy-console.jar",
                "org.codehaus.groovy/groovy-test.jar",
                "org.codehaus.groovy/groovy.jar",
                "org.codehaus.groovy/groovy-cli-picocli.jar",
                "org.codehaus.groovy/groovy-xml.jar",
                "org.codehaus.groovy/groovy-datetime.jar",
                "org.codehaus.groovy/groovy-jsr223.jar",
                "org.codehaus.groovy/groovy-macro.jar",
                "org.codehaus.groovy/groovy-test-junit5.jar",
                "org.codehaus.groovy/groovy-ant.jar",
                "org.codehaus.groovy/groovy-groovydoc.jar",
                "org.codehaus.groovy/groovy-templates.jar",
                "org.codehaus.groovy/groovy-bsf.jar",
                "org.codehaus.groovy/groovy-swing.jar",
                "org.codehaus.groovy/groovy-jmx.jar",
                "org.codehaus.groovy/groovy-json.jar",
                "org.codehaus.groovy/groovy-nio.jar",
                "org.codehaus.groovy/groovy-servlet.jar",
                "org.codehaus.groovy/groovy-sql.jar",
                "org.codehaus.groovy/groovy-testng.jar",
                "org.codehaus.groovy/groovy-docgenerator.jar",
                "org.codehaus.groovy/groovy-groovysh.jar",
                // Pico CLI
                "info.picocli/picocli.jar",
                // MRJ Toolkit for OS X"
                "mrj/MRJToolkitStubs.jar",
                // CUDA
                "jcuda/jcuda-all.jar",
                // Java3D 1.6.0 (depends on JOGL v. 2.3.0)
                "java3d/j3dcore.jar",
                "java3d/j3dutils.jar",
                "java3d/j3dvrml.jar",
                "java3d/vecmath.jar",
                // JOGAMP Fat Jar (includes GLUEGEN, JOGL and JOCL)
                "org.jogamp/jogamp-fat.jar",
                // Apache Commons
                // "commons-digester/commons-digester.jar",
                "commons-logging/commons-logging.jar",
                "org.apache.commons/commons-beanutils.jar",
                "org.apache.commons/commons-collections4.jar",
                "org.apache.commons/commons-configuration2.jar",
                "org.apache.commons/commons-io.jar",
                "org.apache.commons/commons-lang3.jar",
                "org.apache.commons/commons-math3.jar",
                // CDK Libraries
                "org.openscience.cdk/cdk-interfaces.jar",
                "org.openscience.cdk/cdk-ioformats.jar",
                "org.openscience.cdk/cdk-standard.jar",
                "org.openscience.cdk/cdk-qsarmolecular.jar",
                "org.openscience.cdk/cdk-charges.jar",
                "org.openscience.cdk/cdk-smarts.jar",
                "org.openscience.cdk/cdk-reaction.jar",
                "org.openscience.cdk/cdk-fragment.jar",
                "org.openscience.cdk/cdk-dict.jar",
                "org.openscience.cdk/cdk-qsar.jar",
                "org.openscience.cdk/cdk-formula.jar",
                "org.openscience.cdk/cdk-hash.jar",
                "org.openscience.cdk/cdk-atomtype.jar",
                "org.openscience.cdk/cdk-isomorphism.jar",
                "org.openscience.cdk/cdk-valencycheck.jar",
                "org.openscience.cdk/cdk-smiles.jar",
                "org.openscience.cdk/",
                "org.openscience.cdk/cdk-io.jar",
                "org.openscience.cdk/cdk-core.jar",
                "org.openscience.cdk/cdk-silent.jar",
                "org.openscience.cdk/cdk-inchi.jar",
                "org.openscience.cdk/cdk-builder3d.jar",
                "org.openscience.cdk/cdk-forcefield.jar",
                "org.openscience.cdk/cdk-sdg.jar",
                "org.openscience.cdk/cdk-data.jar",
                "org.openscience.cdk/cdk-extra.jar",
                "org.openscience.cdk/cdk-smsd.jar",
                "org.openscience.cdk/cdk-core.jar",
                "org.openscience.cdk/cdk-dict.jar",
                "org.openscience.cdk/cdk-formula.jar",
                "org.openscience.cdk/cdk-interfaces.jar",
                "org.openscience.cdk/cdk-ioformats.jar",
                "org.openscience.cdk/cdk-standard.jar",
                //Google Libraraies
                "com.google.guava/guava.jar",
                //ebi.beam Libraries
                "uk.ac.ebi.beam/beam-core.jar",
                "uk.ac.ebi.beam/beam-func.jar",
                // FastUtil Libraries
                "it.unimi.dsi/fastutil.jar",
                // Java Help
                "javax.help/javahelp.jar",
                // BioJava
                "org.biojava/biojava3-core.jar",
                "org.biojava/core.jar",
                "org.biojava/bytecode.jar",
                "org.biojava/biojava3-structure.jar",
                "org.biojava/biojava3-alignment.jar",
                "org.biojava/biojava3-phylo.jar",
                // Lars Behnke's hierarchical-clustering-java
                "com.apporiented/hierarchical-clustering.jar",
                // JOpenMM
                "edu.uiowa.jopenmm/jopenmm-fat.jar",
                // OpenMM
                "simtk/openmm.jar",
                "simtk/openmm-fat.jar",
                "net.java.dev.jna/jna.jar"
        }));

    }

    /**
     * Force Field X custom class loader considers JARs and DLLs of
     * <code>extensionJarsAndDlls</code> as classpath and libclasspath elements
     * with a higher priority than the ones of default classpath. It will load
     * itself all the classes belonging to packages of
     * <code>applicationPackages</code>.
     *
     * @param parent a {@link java.lang.ClassLoader} object.
     */
    public FFXClassLoader(final ClassLoader parent) {
        super(new URL[0], parent);
        protectionDomain = FFXClassLoader.class.getProtectionDomain();
    }

    /**
     * Implementation of this method is to allow use of the NetBeans JFluid
     * profiler.
     *
     * @param value
     */
    private void appendToClassPathForInstrumentation(String value) {
        String tempDir = System.getProperty("java.io.tmpdir");
        File toDir = new File(tempDir + "deployed");
        toDir.mkdir();
        toDir = new File(tempDir + "deployed/jdk16");
        toDir.mkdir();
        toDir = new File(tempDir + "deployed/jdk16/mac");
        toDir.mkdir();

        String prof = tempDir + "deployed/jdk16/mac/libprofilerinterface.jnilib";
        File toFile = new File(prof);
        prof = "/Applications/NetBeans/NetBeans 8.0.app/Contents/Resources/NetBeans/profiler/lib/deployed/jdk16/mac/libprofilerinterface.jnilib";
        File fromFile = new File(prof);

        InputStream input = null;
        OutputStream output = null;
        try {
            input = new FileInputStream(fromFile);
            output = new BufferedOutputStream(new FileOutputStream(toFile));
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                output.write(buffer, 0, size);
            }
        } catch (Exception ex) {
            System.out.println(ex.toString());
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
                if (output != null) {
                    output.close();
                }
            } catch (Exception e) {
                System.out.println(e.toString());
            }
        }
        toFile.deleteOnExit();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Finds and defines the given class among the extension JARs given in
     * constructor, then among resources.
     */
    @Override
    protected Class findClass(String name) throws ClassNotFoundException {

        /*
         if (name.startsWith("com.jogamp")) {
         System.out.println(" Class requested:" + name);
         } */
        if (!extensionsLoaded) {
            loadExtensions();
        }

        // Build class file from its name
        String classFile = name.replace('.', '/') + ".class";
        InputStream classInputStream = null;
        if (extensionJars != null) {
            for (JarFile extensionJar : extensionJars) {
                JarEntry jarEntry = extensionJar.getJarEntry(classFile);
                if (jarEntry != null) {
                    try {
                        classInputStream = extensionJar.getInputStream(jarEntry);
                    } catch (IOException ex) {
                        throw new ClassNotFoundException("Couldn't read class " + name, ex);
                    }
                }
            }
        }

        // If it's not an extension class, search if its an application
        // class that can be read from resources
        if (classInputStream == null) {
            URL url = getResource(classFile);
            if (url == null) {
                throw new ClassNotFoundException("Class " + name);
            }
            try {
                classInputStream = url.openStream();
            } catch (IOException ex) {
                throw new ClassNotFoundException("Couldn't read class " + name, ex);
            }
        }

        ByteArrayOutputStream out = null;
        BufferedInputStream in = null;
        try {
            // Read class input content to a byte array
            out = new ByteArrayOutputStream();
            in = new BufferedInputStream(classInputStream);
            byte[] buffer = new byte[8192];
            int size;
            while ((size = in.read(buffer)) != -1) {
                out.write(buffer, 0, size);
            }
            // Define class
            return defineClass(name, out.toByteArray(), 0, out.size(),
                    this.protectionDomain);
        } catch (IOException ex) {
            throw new ClassNotFoundException("Class " + name, ex);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
                if (out != null) {
                    out.close();
                }
                if (classInputStream != null) {
                    classInputStream.close();
                }
            } catch (IOException e) {
                throw new ClassNotFoundException("Class " + name, e);
            }

        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns the URL of the given resource searching first if it exists among
     * the extension JARs given in constructor.
     */
    @Override
    public URL findResource(String name) {
        if (!extensionsLoaded) {
            loadExtensions();
        }

        if (name.equals("List test scripts")) {
            listScripts(true);
            return null;
        } else if (name.equals("List scripts")) {
            listScripts(false);
            return null;
        }

        if (extensionJars != null) {
            for (JarFile extensionJar : extensionJars) {
                JarEntry jarEntry = extensionJar.getJarEntry(name);
                if (jarEntry != null) {
                    File file = new File(extensionJar.getName() + "!/" + jarEntry.getName());
                    String path = "jar:" + file.toURI().toString();
                    try {
                        return new URL(path);
                    } catch (MalformedURLException ex) {
                        System.out.println(path + "\n" + ex.toString());
                    }
                }
            }
        }

        return super.findResource(name);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Loads a class with this class loader if its package belongs to
     * <code>applicationPackages</code> given in constructor.
     */
    @Override
    protected Class loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!extensionsLoaded) {
            loadExtensions();
        }

        // If no extension jars were found use the super.loadClass method.
        if (extensionJars == null) {
            return super.loadClass(name, resolve);
        }

        // Check if the class has already been loaded
        Class loadedClass = findLoadedClass(name);
        if (loadedClass == null) {
            try {
                for (String applicationPackage : applicationPackages) {
                    int applicationPackageLength = applicationPackage.length();
                    if ((applicationPackageLength == 0 && name.indexOf('.') == 0)
                            || (applicationPackageLength > 0 && name.startsWith(applicationPackage))) {
                        loadedClass = findClass(name);
                        break;
                    }
                }
            } catch (ClassNotFoundException ex) {
                // Do Nothin.
            }
            // Try to load the class via the default implementation.
            if (loadedClass == null) {
                loadedClass = super.loadClass(name, resolve);
            }
        }
        if (resolve) {
            resolveClass(loadedClass);
        }
        return loadedClass;
    }

    private void loadExtensions() {
        if (extensionsLoaded) {
            return;
        }
        extensionsLoaded = true;

        String extensionJarsAndDlls[] = FFX_FILES.toArray(new String[FFX_FILES.size()]);

        // Find extension Jars and DLLs
        ArrayList<JarFile> extensionJarList = new ArrayList<>();
        for (String extensionJarOrDll : extensionJarsAndDlls) {
            try {
                URL extensionJarOrDllUrl = getResource(extensionJarOrDll);
                if (extensionJarOrDllUrl != null) {
                    int lastSlashIndex = extensionJarOrDll.lastIndexOf('/');
                    if (extensionJarOrDll.endsWith(".jar")) {

                        int start = lastSlashIndex + 1;
                        int end = extensionJarOrDll.indexOf(".jar");
                        String name = extensionJarOrDll.substring(start, end);
                        // Copy jar to a tmp file
                        String extensionJar = copyInputStreamToTmpFile(extensionJarOrDllUrl.openStream(),
                                name, ".jar");
                        // Add extracted file to the extension jars list
                        extensionJarList.add(new JarFile(extensionJar, false));
                    }
                }
            } catch (IOException ex) {
                System.out.println(extensionJarOrDll);
                throw new RuntimeException(" Couldn't extract extension jar.\n", ex);
            }
        }

        // Create extensionJars array
        if (extensionJarList.size() > 0) {
            extensionJars = extensionJarList.toArray(new JarFile[extensionJarList.size()]);
        }
    }

    protected void listScripts(boolean listTestScripts) {
        if (extensionJars != null) {
            List<String> scripts = new ArrayList<>();
            for (JarFile extensionJar : extensionJars) {
                Enumeration<JarEntry> entries = extensionJar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    // System.out.println(name);
                    if (name.startsWith("ffx") && name.endsWith(".groovy")) {
                        name = name.replace('/', '.');
                        name = name.replace("ffx.scripts.", "");
                        name = name.replace(".groovy", "");
                        if (name.toUpperCase().contains("TEST")) {
                            if (listTestScripts) {
                                scripts.add(name);
                            }
                        } else {
                            if (!listTestScripts) {
                                scripts.add(name);
                            }
                        }
                    }
                }
            }

            String[] scriptArray = scripts.toArray(new String[scripts.size()]);
            Arrays.sort(scriptArray);
            for (String script : scriptArray) {
                System.out.println(" " + script);
            }
        }
    }

    /**
     * Returns the file name of a temporary copy of <code>input</code> content.
     *
     * @param input  a {@link java.io.InputStream} object.
     * @param name   a {@link java.lang.String} object.
     * @param suffix a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     * @throws java.io.IOException if any.
     */
    public static String copyInputStreamToTmpFile(final InputStream input,
                                                  String name, final String suffix) throws IOException {
        File tmpFile = null;

        try {
            name = "ffx." + name + ".";
            tmpFile = File.createTempFile(name, suffix);
        } catch (IOException e) {
            System.out.println(" Could not extract a Force Field X library.");
            System.err.println(e.toString());
            System.exit(-1);
        }

        tmpFile.deleteOnExit();

        OutputStream output = null;
        try {
            output = new BufferedOutputStream(new FileOutputStream(tmpFile));
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                output.write(buffer, 0, size);
            }
        } finally {
            if (input != null) {
                input.close();
            }
            if (output != null) {
                output.close();
            }
        }

        return tmpFile.toString();
    }
}

