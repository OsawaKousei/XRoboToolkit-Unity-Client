package com.picovr.robotassistantlib;

import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.util.Log;

import com.picoxr.fbolibrary.FBOPlugin;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** TCP H.264 receiver used by the Unity MediaDecoder wrapper. */
public class MediaDecoder {
    private static final String TAG = "MediaDecoder";
    private static final int MAX_ACCESS_UNIT_BYTES = 16 * 1024 * 1024;
    private static final long STOP_JOIN_TIMEOUT_MS = 1000L;

    private final Object socketLock = new Object();
    private MediaCodec mediaCodec;
    private FBOPlugin fboPlugin;
    private Thread tcpThread;
    private volatile boolean receiving;
    private ServerSocket serverSocket;
    private Socket socket;
    private int width;
    private int height;
    private long lastTimestamp;
    private FileOutputStream fileOutputStream;
    private volatile boolean savePng;

    public void initialize(int unityTextureId, int width, int height) throws Exception {
        Log.i(TAG, "initialize: textureId=" + unityTextureId + ", size=" + width + "x" + height);
        if (mediaCodec != null) {
            release();
        }

        this.width = width;
        this.height = height;
        if (fboPlugin == null) {
            fboPlugin = new FBOPlugin();
        }
        fboPlugin.init();
        fboPlugin.BuildTexture(unityTextureId, width, height);

        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger("color-format", 2135033992);
            format.setInteger("priority", 0);
            format.setInteger("low-latency", 1);
            mediaCodec = MediaCodec.createDecoderByType("video/avc");
            mediaCodec.configure(format, fboPlugin.getSurface(), (MediaCrypto) null, 0);
            mediaCodec.start();
        } catch (Exception exception) {
            if (mediaCodec != null) {
                try {
                    mediaCodec.release();
                } catch (Exception releaseException) {
                    Log.e(TAG, "Error releasing failed MediaCodec", releaseException);
                }
                mediaCodec = null;
            }
            throw exception;
        }

        lastTimestamp = 0L;
        savePng = false;
    }

    public synchronized void startServer(final int port, final boolean record) throws IOException {
        stopServer();
        receiving = true;
        tcpThread = new Thread(() -> receiveLoop(port, record), "MediaDecoder-TCP-" + port);
        tcpThread.start();
    }

    private void receiveLoop(int port, boolean record) {
        ServerSocket localServerSocket = null;
        Socket localSocket = null;
        try {
            synchronized (socketLock) {
                if (!receiving || Thread.currentThread().isInterrupted()) {
                    return;
                }
                localServerSocket = new ServerSocket(port);
                serverSocket = localServerSocket;
            }

            localSocket = localServerSocket.accept();
            synchronized (socketLock) {
                if (!receiving || Thread.currentThread().isInterrupted()) {
                    closeQuietly(localSocket);
                    return;
                }
                socket = localSocket;
            }
            Log.i(TAG, "Socket connected:" + port);

            try (DataInputStream input = new DataInputStream(localSocket.getInputStream())) {
                while (receiving && !Thread.currentThread().isInterrupted()) {
                    byte[] header = new byte[4];
                    input.readFully(header);
                    int bodyLength = ByteBuffer.wrap(header)
                        .order(ByteOrder.BIG_ENDIAN)
                        .getInt();
                    if (bodyLength <= 0 || bodyLength > MAX_ACCESS_UNIT_BYTES) {
                        throw new IOException("Invalid H.264 access unit length: " + bodyLength);
                    }

                    byte[] body = new byte[bodyLength];
                    input.readFully(body);
                    if (!receiving || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    if (record) {
                        record(body, bodyLength);
                    }
                    decode(body, bodyLength);
                }
            }
        } catch (IOException exception) {
            if (receiving && !Thread.currentThread().isInterrupted()) {
                Log.e(TAG, "TCP server error", exception);
            }
        } catch (Exception exception) {
            if (receiving && !Thread.currentThread().isInterrupted()) {
                Log.e(TAG, "Video decode error", exception);
            }
        } finally {
            synchronized (socketLock) {
                closeQuietly(localSocket);
                closeQuietly(localServerSocket);
                if (socket == localSocket) {
                    socket = null;
                }
                if (serverSocket == localServerSocket) {
                    serverSocket = null;
                }
            }
            Log.i(TAG, "TCP thread finished");
        }
    }

    public boolean isUpdateFrame() {
        return fboPlugin != null && fboPlugin.isUpdateFrame();
    }

    public void updateTexture() {
        if (fboPlugin != null && isUpdateFrame()) {
            fboPlugin.updateTexture();
        }
    }

    private long computePresentationTime() {
        long currentTime = System.nanoTime() / 1000L;
        lastTimestamp = Math.max(lastTimestamp + 1L, currentTime);
        return lastTimestamp;
    }

    private void decode(byte[] data, int length) throws Exception {
        MediaCodec codec = mediaCodec;
        if (codec == null) {
            return;
        }

        int inputBufferIndex = codec.dequeueInputBuffer(0L);
        if (inputBufferIndex < 0) {
            return;
        }
        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
        if (inputBuffer == null || inputBuffer.capacity() < length) {
            Log.w(TAG, "Decoder input buffer is too small for " + length + " bytes");
            return;
        }
        inputBuffer.clear();
        inputBuffer.put(data, 0, length);
        codec.queueInputBuffer(inputBufferIndex, 0, length, computePresentationTime(), 0);

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        for (int count = 0; count < 10 && receiving; count++) {
            int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, count == 0 ? 10L : 1000L);
            if (outputBufferIndex >= 0) {
                codec.releaseOutputBuffer(outputBufferIndex, true);
                savePng = false;
                continue;
            }
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "Decoder output format: " + codec.getOutputFormat());
            }
            break;
        }
    }

    private void record(byte[] data, int length) {
        try {
            if (fileOutputStream == null) {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
                fileOutputStream = new FileOutputStream(
                    new File("/sdcard/Download/received_data_" + timestamp + ".bin"));
            }
            fileOutputStream.write(data, 0, length);
        } catch (IOException exception) {
            Log.e(TAG, "Error recording H.264 stream", exception);
        }
    }

    public void release() throws IOException {
        stopServer();
        if (mediaCodec != null) {
            try {
                mediaCodec.flush();
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception exception) {
                Log.e(TAG, "Error releasing MediaCodec", exception);
            } finally {
                mediaCodec = null;
            }
        }
        if (fboPlugin != null) {
            try {
                fboPlugin.release();
            } catch (Exception exception) {
                Log.w(TAG, "Error releasing FBOPlugin", exception);
            } finally {
                fboPlugin = null;
            }
        }
        lastTimestamp = 0L;
        savePng = false;
        width = 0;
        height = 0;
    }

    public void SavePng() {
        // Kept for API compatibility. Surface decoding does not expose YUV bytes.
        savePng = true;
    }

    public synchronized void stopServer() {
        receiving = false;
        Thread thread = tcpThread;

        // Closing sockets is what unblocks ServerSocket.accept() and
        // DataInputStream.readFully(); Thread.interrupt() alone cannot do that.
        synchronized (socketLock) {
            closeQuietly(socket);
            closeQuietly(serverSocket);
            socket = null;
            serverSocket = null;
        }

        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(STOP_JOIN_TIMEOUT_MS);
                if (thread.isAlive()) {
                    Log.w(TAG, "TCP thread did not terminate after sockets were closed");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Interrupted while waiting for TCP thread", exception);
            }
        }
        if (tcpThread == thread) {
            tcpThread = null;
        }

        if (fileOutputStream != null) {
            try {
                fileOutputStream.close();
            } catch (IOException exception) {
                Log.e(TAG, "Error closing recording", exception);
            } finally {
                fileOutputStream = null;
            }
        }
    }

    private static void closeQuietly(Socket value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (IOException exception) {
            Log.w(TAG, "Error closing video socket", exception);
        }
    }

    private static void closeQuietly(ServerSocket value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (IOException exception) {
            Log.w(TAG, "Error closing video server socket", exception);
        }
    }
}
