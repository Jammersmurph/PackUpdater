/*
 * Decompiled with CFR 0.152.
 */
package link.infra.packwiz.installer.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RollbackHandler {
    private final Path rollbackPath;
    private byte[] storage = null;
    private boolean hasRollback = false;

    public RollbackHandler(String path) {
        this.rollbackPath = Paths.get(path, new String[0]);
        try {
            this.storage = Files.readAllBytes(this.rollbackPath);
            this.hasRollback = true;
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    public void rollback() throws IOException {
        if (this.hasRollback) {
            Files.write(this.rollbackPath, this.storage, new OpenOption[0]);
        }
    }
}

