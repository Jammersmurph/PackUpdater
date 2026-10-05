/*
 * Decompiled with CFR 0.152.
 */
package link.infra.packwiz.installer.bootstrap;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import link.infra.packwiz.installer.bootstrap.Bootstrap;
import link.infra.packwiz.installer.bootstrap.ChainloadHandler;

public class Main {
    public static void main(String[] args) {
        if (Main.attemptChainload(System.getProperties(), args)) {
            return;
        }
        try (FileReader reader2 = new FileReader("packupdater-bootstrap.properties");){
            Properties props = new Properties();
            props.load(reader2);
            if (Main.attemptChainload(props, args)) {
                return;
            }
            throw new RuntimeException("packupdater-bootstrap.properties is invalid");
        }
        catch (FileNotFoundException reader2) {
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to read packupdater-bootstrap.properties", e);
        }
        Bootstrap.init(args);
    }

    private static boolean attemptChainload(Properties props, String[] args) {
        String chainloadClass = props.getProperty("packwiz.chainload.class");
        String chainloadJar = props.getProperty("packwiz.chainload.jar");
        if (chainloadClass != null || chainloadJar != null) {
            List<String> bootstrapArgs = Collections.emptyList();
            if (chainloadClass != null) {
                ChainloadHandler.startChainloadClass(chainloadClass, bootstrapArgs, args);
            } else {
                ChainloadHandler.startChainloadJar(chainloadJar, bootstrapArgs, args);
            }
            return true;
        }
        return false;
    }
}

