/*
 * Decompiled with CFR 0.152.
 */
package link.infra.packwiz.installer.bootstrap;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import java.awt.EventQueue;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import link.infra.packwiz.installer.bootstrap.ConnMonitorInputStream;
import link.infra.packwiz.installer.bootstrap.LoadJAR;
import link.infra.packwiz.installer.bootstrap.RollbackHandler;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class Bootstrap {
    public static final String DEFAULT_ASSET_NAME = "packwiz-installer.jar";
    private static String updateURL = null;
    private static String assetName = DEFAULT_ASSET_NAME;
    private static boolean skipUpdate = false;
    private static boolean useGUI = true;
    private static String jarPath = null;
    private static String accessToken = null;

    public static void init(String[] args) {
        try {
            Bootstrap.parseOptions(args);
        }
        catch (ParseException e) {
            Bootstrap.showError(e, "There was an error parsing command line arguments:");
            System.exit(1);
        }
        if (jarPath == null) {
            jarPath = assetName;
        }
        if (useGUI) {
            EventQueue.invokeLater(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                }
                catch (Exception exception) {
                    // empty catch block
                }
            });
        }
        if (skipUpdate) {
            try {
                LoadJAR.start(args, jarPath);
            }
            catch (ClassNotFoundException e) {
                Bootstrap.showError(e, "pack-updater cannot be found, or there was an error loading it:");
                System.exit(1);
            }
            catch (Exception e) {
                Bootstrap.showError(e, "There was an error loading pack-updater:");
                System.exit(1);
            }
            return;
        }
        try {
            Bootstrap.doUpdate();
        }
        catch (Exception e) {
            Bootstrap.showError(e, "There was an error downloading pack-updater:");
        }
        try {
            LoadJAR.start(args, jarPath);
        }
        catch (Exception e) {
            Bootstrap.showError(e, "There was an error loading pack-updater (did it download properly?):");
            System.exit(1);
        }
    }

    private static void doUpdate() throws IOException, GithubException {
        if (updateURL == null || updateURL.isEmpty()) {
            System.out.println("No installer update URL configured, skipping pack-updater self-update.");
            return;
        }
        String currVersion = LoadJAR.getVersion(jarPath);
        Release ghRelease = Bootstrap.requestRelease();
        if (ghRelease == null) {
            return;
        }
        System.out.println("Current version is: " + currVersion);
        System.out.println("New version is: " + ghRelease.tagName);
        if (!ghRelease.tagName.equals(currVersion)) {
            System.out.println("Attempting to update...");
            RollbackHandler backup = new RollbackHandler(jarPath);
            try {
                Bootstrap.downloadUpdate(ghRelease.downloadURL, ghRelease.assetURL, jarPath);
            }
            catch (InterruptedIOException e) {
                try {
                    backup.rollback();
                }
                catch (IOException e1) {
                    e1.printStackTrace();
                }
                return;
            }
            catch (IOException e) {
                Bootstrap.showError(e, "Update download failed, attempting to rollback:");
                try {
                    backup.rollback();
                }
                catch (IOException e1) {
                    e1.printStackTrace();
                }
                return;
            }
            System.out.println("Update successful!");
        } else {
            System.out.println("Already up to date!");
        }
    }

    private static void showError(Exception e, String message) {
        if (useGUI) {
            e.printStackTrace();
            try {
                EventQueue.invokeAndWait(() -> JOptionPane.showMessageDialog(null, message + "\n" + e.getClass().getCanonicalName() + ": " + e.getMessage(), "packupdater-bootstrap", 0));
            }
            catch (InterruptedException | InvocationTargetException ex) {
                System.out.println("Unexpected interruption while showing error message");
                ex.printStackTrace();
            }
        } else {
            System.out.println(message);
            e.printStackTrace();
        }
    }

    private static void parseOptions(String[] args) throws ParseException {
        Options options = new Options();
        options.addOption(null, "bootstrap-update-url", true, "Github API URL for checking for updates");
        options.addOption(null, "bootstrap-update-token", true, "Github API Access Token, for private repositories");
        options.addOption(null, "bootstrap-asset", true, "Release asset name to download (default: " + DEFAULT_ASSET_NAME + ")");
        options.addOption(null, "bootstrap-no-update", false, "Don't update pack-updater");
        options.addOption(null, "bootstrap-main-jar", true, "Location of the pack-updater JAR file");
        options.addOption("g", "no-gui", false, "Don't display a GUI to show update progress");
        options.addOption("h", "help", false, "Display this message");
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, Bootstrap.filterArgs(args, options));
        if (cmd.hasOption("bootstrap-main-jar")) {
            jarPath = cmd.getOptionValue("bootstrap-main-jar");
        }
        if (cmd.hasOption("help")) {
            HelpFormatter formatter = new HelpFormatter();
            boolean jarLoaded = LoadJAR.addOptions(options, jarPath);
            formatter.printHelp("java -jar packupdater-bootstrap.jar", options);
            if (!jarLoaded) {
                System.out.println("Options for pack-updater will be visible once it has been downloaded.");
            }
            System.exit(0);
        }
        if (cmd.hasOption("bootstrap-update-url")) {
            updateURL = cmd.getOptionValue("bootstrap-update-url");
        }
        if (cmd.hasOption("bootstrap-update-token")) {
            accessToken = cmd.getOptionValue("bootstrap-update-token");
        }
        if (cmd.hasOption("bootstrap-asset")) {
            assetName = cmd.getOptionValue("bootstrap-asset");
        }
        if (cmd.hasOption("bootstrap-no-update")) {
            skipUpdate = true;
        }
        if (cmd.hasOption("no-gui")) {
            useGUI = false;
        }
    }

    private static String[] filterArgs(String[] args, Options options) {
        ArrayList<String> argsList = new ArrayList<String>(args.length);
        boolean prevOptWasArg = false;
        for (String arg : args) {
            if (arg.isEmpty()) continue;
            if (arg.charAt(0) == '-' && options.hasOption(arg)) {
                if (options.getOption(arg).hasArg()) {
                    prevOptWasArg = true;
                }
            } else {
                if (!prevOptWasArg) continue;
                prevOptWasArg = false;
            }
            argsList.add(arg);
        }
        return argsList.toArray(new String[0]);
    }

    private static Release requestRelease() throws IOException, GithubException {
        JsonObject object;
        Release rel = new Release();
        URL url = new URL(updateURL);
        URLConnection conn = url.openConnection();
        Bootstrap.addAuthorizationHeader(conn);
        conn.setReadTimeout(30000);
        InputStream in = useGUI ? new ConnMonitorInputStream(conn, "Checking for pack-updater updates...", null) : conn.getInputStream();
        InputStreamReader streamReader = new InputStreamReader(in);
        try {
            object = Json.parse(streamReader).asObject();
        }
        catch (InterruptedIOException e) {
            System.out.println("Update check cancelled!");
            return null;
        }
        ((Reader)streamReader).close();
        rel.tagName = Bootstrap.getStringProperty("tag_name", object, "Tag name");
        JsonValue assets = object.get("assets");
        if (assets == null || !assets.isArray()) {
            throw new GithubException("Assets array cannot be found");
        }
        for (JsonValue assetValue : assets.asArray()) {
            if (!assetValue.isObject()) {
                throw new GithubException();
            }
            JsonObject asset = assetValue.asObject();
            String name = Bootstrap.getStringProperty("name", asset, "Asset name");
            if (!name.equalsIgnoreCase(assetName)) continue;
            rel.downloadURL = Bootstrap.getAssetUrl("browser_download_url", asset);
            rel.assetURL = Bootstrap.getAssetUrl("url", asset);
            break;
        }
        if (rel.tagName == null) {
            throw new GithubException("Latest release asset cannot be found");
        }
        if (rel.downloadURL == null) {
            throw new GithubException("Release does not contain an asset named '" + assetName + "'");
        }
        return rel;
    }

    private static String getAssetUrl(String property, JsonObject asset) throws GithubException {
        return Bootstrap.getStringProperty(property, asset, "Asset Download URL property");
    }

    private static String getStringProperty(String property, JsonObject obj, String displayName) throws GithubException {
        JsonValue value = obj.get(property);
        if (value == null || !value.isString()) {
            throw new GithubException(displayName + " (" + property + ") cannot be found");
        }
        return value.asString();
    }

    private static void downloadUpdate(String downloadURL, String assetURL, String path) throws IOException {
        URL url = new URL(downloadURL);
        URLConnection conn = url.openConnection();
        Bootstrap.addAuthorizationHeader(conn);
        conn.addRequestProperty("Accept", "application/octet-stream");
        conn.setReadTimeout(30000);
        InputStream in = useGUI ? new ConnMonitorInputStream(conn, "Updating pack-updater...", null) : conn.getInputStream();
        Files.copy(in, Paths.get(path, new String[0]), StandardCopyOption.REPLACE_EXISTING);
        in.close();
    }

    private static void addAuthorizationHeader(URLConnection conn) {
        if (accessToken != null) {
            conn.addRequestProperty("Authorization", accessToken);
        }
    }

    private static class GithubException
    extends Exception {
        private static final long serialVersionUID = 3843811090801607241L;

        public GithubException() {
            super("Invalid Github API response");
        }

        public GithubException(String message) {
            super("Invalid Github API response: " + message);
        }
    }

    private static class Release {
        String tagName = null;
        String downloadURL = null;
        String assetURL = null;

        private Release() {
        }
    }
}

