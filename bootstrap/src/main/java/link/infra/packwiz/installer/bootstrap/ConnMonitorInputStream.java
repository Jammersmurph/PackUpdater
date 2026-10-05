/*
 * Decompiled with CFR 0.152.
 */
package link.infra.packwiz.installer.bootstrap;

import java.awt.EventQueue;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URLConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.ProgressMonitor;

class ConnMonitorInputStream
extends InputStream {
    private InputStream in = null;
    private int size = -1;
    private int bytesRead = 0;
    private final URLConnection conn;
    private ProgressMonitor mon;
    private final AtomicBoolean wasCancelled = new AtomicBoolean();
    private long lastMillisUpdated = System.currentTimeMillis() - 110L;

    public ConnMonitorInputStream(URLConnection conn, String message, String note) {
        this.conn = conn;
        EventQueue.invokeLater(() -> {
            this.mon = new ProgressMonitor(null, message, note, 0, 1);
            this.mon.setMillisToDecideToPopup(1);
            this.mon.setMillisToPopup(1);
        });
    }

    private void setup() throws IOException {
        if (this.in == null) {
            try {
                this.size = this.conn.getContentLength();
                this.in = this.conn.getInputStream();
                EventQueue.invokeLater(() -> {
                    this.mon.setProgress(0);
                    if (this.size > -1) {
                        this.mon.setMaximum(this.size);
                    }
                });
            }
            catch (IOException e) {
                EventQueue.invokeLater(() -> this.mon.close());
                throw e;
            }
        }
    }

    @Override
    public int available() {
        if (this.size > -1) {
            return this.size - this.bytesRead;
        }
        return 1;
    }

    private void setProgress() throws InterruptedIOException {
        if (System.currentTimeMillis() - this.lastMillisUpdated < 100L) {
            return;
        }
        this.lastMillisUpdated = System.currentTimeMillis();
        int progress = this.size > -1 ? this.bytesRead : -1;
        EventQueue.invokeLater(() -> {
            if (progress > -1) {
                this.mon.setProgress(progress);
            }
            this.wasCancelled.set(this.mon.isCanceled());
        });
        if (this.wasCancelled.get()) {
            throw new InterruptedIOException("Download cancelled!");
        }
    }

    @Override
    public int read() throws IOException {
        this.setup();
        int b = this.in.read();
        if (b != -1) {
            ++this.bytesRead;
            this.setProgress();
        }
        return b;
    }

    @Override
    public int read(byte[] b) throws IOException {
        this.setup();
        int read = this.in.read(b);
        this.bytesRead += read;
        this.setProgress();
        return read;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        this.setup();
        int read = this.in.read(b, off, len);
        this.bytesRead += read;
        this.setProgress();
        return read;
    }

    @Override
    public void close() throws IOException {
        super.close();
        EventQueue.invokeLater(() -> this.mon.close());
        if (this.wasCancelled.get()) {
            throw new InterruptedIOException("Download cancelled!");
        }
    }
}

