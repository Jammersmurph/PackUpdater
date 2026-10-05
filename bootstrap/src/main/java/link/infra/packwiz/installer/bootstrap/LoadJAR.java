/*
 * Decompiled with CFR 0.152.
 */
package link.infra.packwiz.installer.bootstrap;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import org.apache.commons.cli.Options;

public class LoadJAR {
    private static Class<?> mainClass = null;

    private static void loadClass(String path) throws MalformedURLException, ClassNotFoundException {
        if (mainClass != null) {
            return;
        }
        if (path == null) {
            path = Bootstrap.DEFAULT_ASSET_NAME;
        }
        URLClassLoader child = new URLClassLoader(new URL[]{new File(path).toURI().toURL()}, LoadJAR.class.getClassLoader());
        mainClass = Class.forName("link.infra.packwiz.installer.Main", true, child);
    }

    public static boolean addOptions(Options options, String path) {
        try {
            LoadJAR.loadClass(path);
            Method method = mainClass.getDeclaredMethod("addNonBootstrapOptions", Options.class);
            method.invoke(null, options);
        }
        catch (Exception e) {
            return false;
        }
        return true;
    }

    public static void start(String[] args, String path) throws Exception {
        LoadJAR.loadClass(path);
        mainClass.getConstructor(String[].class).newInstance(new Object[]{args});
    }

    public static String getVersion(String path) {
        JarInputStream jarStream;
        try {
            jarStream = new JarInputStream(new FileInputStream(path));
        }
        catch (IOException e) {
            return null;
        }
        String version = null;
        try {
            Manifest mf = jarStream.getManifest();
            version = mf.getMainAttributes().getValue("Implementation-Version");
        }
        catch (Exception exception) {
            // empty catch block
        }
        try {
            jarStream.close();
        }
        catch (IOException iOException) {
            // empty catch block
        }
        return version;
    }
}

