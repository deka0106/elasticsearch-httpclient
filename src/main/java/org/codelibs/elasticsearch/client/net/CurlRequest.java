package org.codelibs.elasticsearch.client.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

import org.codelibs.elasticsearch.client.net.Curl.Method;
import org.elasticsearch.node.Node;

public class CurlRequest {
    protected String url;

    protected Proxy proxy;

    protected String encoding = "UTF-8";

    protected Method method;

    protected List<String> paramList;

    protected List<String[]> headerList;

    protected String body;

    protected ForkJoinPool threadPool;

    private ConnectionBuilder connectionBuilder;

    public CurlRequest(final Method method, final String url) {
        this.method = method;
        this.url = url;
    }

    public CurlRequest(final Method method, final Node node, final String path) {
        this.method = method;
        final StringBuilder urlBuf = new StringBuilder(200);
        urlBuf.append("http://localhost:").append(node.settings().get("http.port"));
        if (path.startsWith("/")) {
            urlBuf.append(path);
        } else {
            urlBuf.append('/').append(path);
        }
        url = urlBuf.toString();
    }

    public Proxy proxy() {
        return proxy;
    }

    public String encoding() {
        return encoding;
    }

    public Method method() {
        return method;
    }

    public String body() {
        return body;
    }

    public CurlRequest proxy(final Proxy proxy) {
        this.proxy = proxy;
        return this;
    }

    public CurlRequest encoding(final String encoding) {
        if (paramList != null) {
            throw new CurlException("This method must be called before param method.");
        }
        this.encoding = encoding;
        return this;
    }

    public CurlRequest body(final String body) {
        this.body = body;
        return this;
    }

    public CurlRequest onConnect(final ConnectionBuilder connectionBuilder) {
        this.connectionBuilder = connectionBuilder;
        return this;
    }

    public CurlRequest param(final String key, final String value) {
        if (value == null) {
            return this;
        }
        if (paramList == null) {
            paramList = new ArrayList<>();
        }
        paramList.add(encode(key) + "=" + encode(value));
        return this;
    }

    public CurlRequest header(final String key, final String value) {
        if (headerList == null) {
            headerList = new ArrayList<>();
        }
        headerList.add(new String[] { key, value });
        return this;
    }

    public void connect(final Consumer<HttpURLConnection> actionListener, final Consumer<Exception> exceptionListener) {
        final Runnable task = () -> {
            if (paramList != null) {
                char sp;
                if (url.indexOf('?') == -1) {
                    sp = '?';
                } else {
                    sp = '&';
                }
                final StringBuilder urlBuf = new StringBuilder(100);
                for (final String param : paramList) {
                    urlBuf.append(sp).append(param);
                    if (sp == '?') {
                        sp = '&';
                    }
                }
                url = url + urlBuf.toString();
            }

            HttpURLConnection connection = null;
            try {
                final URL u = new URL(url);
                connection = (HttpURLConnection) (proxy != null ? u.openConnection(proxy) : u.openConnection());
                connection.setRequestMethod(method.toString());
                if (headerList != null) {
                    for (final String[] values : headerList) {
                        connection.addRequestProperty(values[0], values[1]);
                    }
                }
                if (connectionBuilder != null) {
                    connectionBuilder.onConnect(this, connection);
                } else {
                    if (body != null) {
                        connection.setDoOutput(true);
                        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), encoding))) {
                            writer.write(body);
                            writer.flush();
                        }
                    }
                }
                actionListener.accept(connection);
            } catch (final Exception e) {
                exceptionListener.accept(new CurlException("Failed to access to " + url, e));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        };
        if (threadPool != null) {
            threadPool.execute(task);
        } else {
            task.run();
        }
    }

    public void execute(final Consumer<CurlResponse> actionListener, final Consumer<Exception> exceptionListener) {
        connect(con -> {
            final RequestProcessor processor = new RequestProcessor(encoding);
            processor.accept(con);
            actionListener.accept(processor.getResponse());
        }, exceptionListener);
    }

    public CurlResponse execute() {
        this.threadPool = null;
        final RequestProcessor processor = new RequestProcessor(encoding);
        connect(processor, e -> {
            throw new CurlException("Failed to process a request.", e);
        });
        return processor.getResponse();
    }

    private interface InputStreamHandler {
        InputStream open() throws IOException;
    }

    protected String encode(final String value) {
        try {
            return URLEncoder.encode(value, encoding);
        } catch (final UnsupportedEncodingException e) {
            throw new CurlException("Invalid encoding: " + encoding, e);
        }
    }

    public static interface ConnectionBuilder {
        void onConnect(CurlRequest curlRequest, HttpURLConnection connection);
    }

    public CurlRequest threadPool(final ForkJoinPool threadPool) {
        this.threadPool = threadPool;
        return this;
    }

    public static class RequestProcessor implements Consumer<HttpURLConnection> {
        protected CurlResponse response = new CurlResponse();

        private final String encoding;

        public RequestProcessor(final String encoding) {
            this.encoding = encoding;
        }

        public CurlResponse getResponse() {
            return response;
        }

        @Override
        public void accept(final HttpURLConnection con) {
            try {
                response.setEncoding(encoding);
                response.setHttpStatusCode(con.getResponseCode());
                writeContent(() -> {
                    final InputStream in = con.getInputStream();
                    if (in != null) {
                        return in;
                    }
                    return con.getErrorStream();
                });
            } catch (final Exception e) {
                final InputStream errorStream = con.getErrorStream();
                if (errorStream != null) {
                    writeContent(() -> errorStream);
                    // overwrite
                    response.setContentException(e);
                } else {
                    throw new CurlException("Failed to access the response.", e);
                }
            }
        }

        private void writeContent(final InputStreamHandler handler) {
            final Path tempFile;
            try {
                tempFile = Files.createTempFile("esclient-", ".tmp");
            } catch (final IOException e) {
                throw new CurlException("Failed to create a temporary file.", e);
            }
            try (BufferedInputStream bis = new BufferedInputStream(handler.open());
                    BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(tempFile, StandardOpenOption.WRITE))) {
                byte[] bytes = new byte[4096];
                try {
                    int length = bis.read(bytes);
                    while (length != -1) {
                        if (length != 0) {
                            bos.write(bytes, 0, length);
                        }
                        length = bis.read(bytes);
                    }
                } finally {
                    bytes = null;
                }
                bos.flush();
                response.setContentFile(tempFile);
            } catch (final Exception e) {
                response.setContentException(e);
                try {
                    Files.deleteIfExists(tempFile);
                } catch (final Exception ignore) {
                    // ignore
                }
            }
        }
    }
}
