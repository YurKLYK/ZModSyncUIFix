package com.modsync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HttpFileServer {
    private static final HttpFileServer INSTANCE = new HttpFileServer();
    private static final int TRANSFER_BUFFER_SIZE = 65536;

    private final Map<String, ManifestEntry> approvedEntries = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService executorService;

    private HttpFileServer() {
    }

    public static HttpFileServer getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (!ConfigManager.enableHttpServer() || server != null) {
            return;
        }

        // The JDK's built-in HttpServer leaves TCP_NODELAY off by default, which combined with
        // delayed ACKs can stall file transfers for tens/hundreds of ms per write and tank
        // download throughput. Enable it unless the user already set the property explicitly.
        if (System.getProperty("sun.net.httpserver.nodelay") == null) {
            System.setProperty("sun.net.httpserver.nodelay", "true");
        }

        try {
            server = HttpServer.create(new InetSocketAddress(ConfigManager.serverHttpBind(), ConfigManager.serverHttpPort()), 0);
            server.createContext("/manifest", new ManifestHandler());
            server.createContext("/files", new FileHandler());
            executorService = Executors.newFixedThreadPool(Math.max(2, ConfigManager.downloadThreads()));
            server.setExecutor(executorService);
            server.start();
            LoggerUtils.info("HTTP file server started on " + ConfigManager.serverHttpBind() + ":" + ConfigManager.serverHttpPort());
        } catch (IOException exception) {
            shutdownExecutor();
            server = null;
            LoggerUtils.error("Failed to start HTTP file server", exception);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            shutdownExecutor();
            LoggerUtils.info("HTTP file server stopped");
        }
    }

    private void shutdownExecutor() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

    public void updateManifest(ManifestData data) {
        approvedEntries.clear();
        for (ManifestEntry entry : data.getEntries()) {
            approvedEntries.put(entry.getIdentityKey(), entry.copy());
        }
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    static ManifestEntry resolveApprovedEntry(String rawPath, Map<String, ManifestEntry> approvedEntries) {
        String prefix = "/files/";
        if (rawPath == null || !rawPath.startsWith(prefix)) {
            return null;
        }

        String subPath = rawPath.substring(prefix.length());
        int slashIndex = subPath.indexOf('/');
        if (slashIndex <= 0) {
            throw new IllegalArgumentException("Malformed file request path: " + rawPath);
        }

        CategoryType category = CategoryType.fromHttpSegment(subPath.substring(0, slashIndex));
        String relativePath = HttpPathCodec.decodeRelativePath(subPath.substring(slashIndex + 1));
        return approvedEntries.get(category.name() + ":" + relativePath);
    }

    private final class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405);
                return;
            }

            String path = exchange.getRequestURI().getRawPath();
            if (path == null || !path.startsWith("/files/")) {
                respond(exchange, 404);
                return;
            }

            try {
                ManifestEntry approved = resolveApprovedEntry(path, approvedEntries);
                if (approved == null) {
                    respond(exchange, 404);
                    return;
                }

                Path file = FileUtils.resolveSafeChild(
                        FileUtils.resolveServerSourceRoot(approved.getCategory()),
                        approved.getRelativePath()
                );
                if (!Files.exists(file)) {
                    respond(exchange, 404);
                    return;
                }

                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, Files.size(file));
                try (OutputStream outputStream = exchange.getResponseBody();
                     InputStream inputStream = Files.newInputStream(file)) {
                    byte[] buffer = new byte[TRANSFER_BUFFER_SIZE];
                    int read;
                    while ((read = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, read);
                    }
                }
            } catch (Exception exception) {
                LoggerUtils.warn("Rejected HTTP file request: " + exception.getMessage());
                respond(exchange, 400);
            }
        }
    }

    private final class ManifestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405);
                return;
            }

            String hostHeader = exchange.getRequestHeaders().getFirst("Host");
            String host = hostHeader == null ? "127.0.0.1" : ServerManifestHttpHandler.extractHost(hostHeader);
            byte[] payload = ServerManifestHttpHandler.manifestBytes(host);

            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        }
    }
}
