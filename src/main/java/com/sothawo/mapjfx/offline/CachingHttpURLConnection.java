/*
 Copyright 2015-2021 Peter-Josef Meisch (pj.meisch@sothawo.com)

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package com.sothawo.mapjfx.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.file.Path;
import java.security.Permission;
import java.util.List;
import java.util.Map;

/**
 * HttpURLConnection implementation that caches the data in a local file, if it is not already stored there.
 *
 * @author P.J. Meisch (pj.meisch@sothawo.com).
 */
class CachingHttpURLConnection extends HttpURLConnection {

    /** Logger for the class */
    private static final Logger logger = LoggerFactory.getLogger(CachingHttpURLConnection.class);

    /** the delegate original connection. */
    private final HttpURLConnection delegate;

    /** the file to store the cache data in. */
    private final Path cacheFile;
    /** the offline cache. */
    private final OfflineCache cache;

    /** flag wether to read from the cache file or from the delegate. */
    private boolean readFromCache = false;

    /** the input stream for this object, lazy created. */
    private InputStream inputStream;

    /** info about the cached data. */
    private CachedDataInfo cachedDataInfo;

    /**
     * inherited constructor for the HttpURLConnection, private.
     *
     * @param u
     *         the URL
     */
    private CachingHttpURLConnection(URL u) {
        super(u);
        this.cache = null;
        this.delegate = null;
        this.cacheFile = null;
    }

    /**
     * creates a CachingHttpURlConnection.
     *
     * @param cache
     *         the offline cache
     * @param delegate
     *         the delegate that provides the content
     * @throws IOException
     *         if the output file cannot be created, or the input stream from the delegate cannot be retrieved
     */
    public CachingHttpURLConnection(final OfflineCache cache, final HttpURLConnection delegate) throws IOException {
        super(delegate.getURL());
        this.cache = cache;
        this.delegate = delegate;
        this.cacheFile = cache.filenameForURL(delegate.getURL());

        cachedDataInfo = cache.readCachedDataInfo(cacheFile);
        readFromCache = cache.isCached(delegate.getURL()) && null != cachedDataInfo;
        if (!readFromCache) {
            cachedDataInfo = new CachedDataInfo();
        }

        if (logger.isTraceEnabled()) {
            logger.trace("in cache: {}, URL: {}, cache file: {}", readFromCache, delegate.getURL().toExternalForm(), cacheFile);
        }
    }

    public void connect() throws IOException {
        if (!readFromCache) {
            if (logger.isTraceEnabled()) {
                logger.trace("connect to {}", delegate.getURL().toExternalForm());
            }
            delegate.connect();
        }
    }

    public String getHeaderFieldKey(int n) {
        return delegate.getHeaderFieldKey(n);
    }

    public void setFixedLengthStreamingMode(int contentLength) {
        delegate.setFixedLengthStreamingMode(contentLength);
    }

    public void setFixedLengthStreamingMode(long contentLength) {
        delegate.setFixedLengthStreamingMode(contentLength);
    }    public void addRequestProperty(String key, String value) {
        delegate.addRequestProperty(key, value);
    }

    public void setChunkedStreamingMode(int chunklen) {
        delegate.setChunkedStreamingMode(chunklen);
    }

    public String getHeaderField(int n) {
        return delegate.getHeaderField(n);
    }



    public void disconnect() {
        if (!readFromCache) {
            delegate.disconnect();
        }
    }


    public boolean getAllowUserInteraction() {
        return delegate.getAllowUserInteraction();
    }


    public int getConnectTimeout() {
        return readFromCache ? 10 : delegate.getConnectTimeout();
    }


    public Object getContent() throws IOException {
        return delegate.getContent();
    }

    public Object getContent(Class[] classes) throws IOException {
        return delegate.getContent(classes);
    }

    public String getContentEncoding() {
        if (!readFromCache) {
            cachedDataInfo.setContentEncoding(delegate.getContentEncoding());
        }
        return cachedDataInfo.getContentEncoding();
    }

    public int getContentLength() {
        return readFromCache ? -1 : delegate.getContentLength();
    }

    public long getContentLengthLong() {
        return readFromCache ? -1 : delegate.getContentLengthLong();
    }

    public String getContentType() {
        if (!readFromCache) {
            cachedDataInfo.setContentType(delegate.getContentType());
        }
        return cachedDataInfo.getContentType();
    }

    public long getDate() {
        return readFromCache ? 0 : delegate.getDate();
    }


    public boolean getDefaultUseCaches() {
        return delegate.getDefaultUseCaches();
    }

    public boolean getDoInput() {
        return delegate.getDoInput();
    }

    public boolean getDoOutput() {
        return delegate.getDoOutput();
    }

    public InputStream getErrorStream() {
        return delegate.getErrorStream();
    }

    public long getExpiration() {
        return readFromCache ? 0 : delegate.getExpiration();
    }


    public String getHeaderField(String name) {
        return delegate.getHeaderField(name);
    }

    public long getHeaderFieldDate(String name, long Default) {
        return delegate.getHeaderFieldDate(name, Default);
    }

    public int getHeaderFieldInt(String name, int Default) {
        return delegate.getHeaderFieldInt(name, Default);
    }


    public long getHeaderFieldLong(String name, long Default) {
        return delegate.getHeaderFieldLong(name, Default);
    }

    public Map<String, List<String>> getHeaderFields() {
        if (!readFromCache) {
            setHeaderFieldsInCachedDataInfo();
        }
        return cachedDataInfo.getHeaderFields();
    }

    private void setHeaderFieldsInCachedDataInfo() {
        cachedDataInfo.setHeaderFields(delegate.getHeaderFields());
    }

    public long getIfModifiedSince() {
        return delegate.getIfModifiedSince();
    }

    /**
     * return the delegate's InputStream wrapped in a {@link WriteCacheFileInputStream} or a FileInputStream in case
     * when the data is already cached.
     *
     * @return wrapping InputStream
     * @throws IOException
     */
    public InputStream getInputStream() throws IOException {
        if (null == inputStream) {
            if (readFromCache) {
                inputStream = new FileInputStream(cacheFile.toFile());
            } else {
                WriteCacheFileInputStream wis = new WriteCacheFileInputStream(delegate.getInputStream(),
                        new FileOutputStream(cacheFile.toFile()));
                wis.onInputStreamClose(() -> {
                    try {
                        cachedDataInfo.setFromHttpUrlConnection(delegate);
                        final int responseCode = delegate.getResponseCode();
                        if (responseCode == HTTP_OK) {
                            cache.saveCachedDataInfo(cacheFile, cachedDataInfo);
                        } else {
                            if (logger.isWarnEnabled()) {
                                logger.warn("not caching because of response code {}: {}", responseCode, getURL());
                            }
                        }
                    } catch (final IOException e) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("cannot retrieve response code");
                        }
                    }
                });
                inputStream = wis;
            }
        }
        return inputStream;
    }

    public boolean getInstanceFollowRedirects() {
        return delegate.getInstanceFollowRedirects();
    }

    public long getLastModified() {
        return readFromCache ? 0 : delegate.getLastModified();
    }


    public OutputStream getOutputStream() throws IOException {
        return delegate.getOutputStream();
    }

    public Permission getPermission() throws IOException {
        return delegate.getPermission();
    }

    public int getReadTimeout() {
        return delegate.getReadTimeout();
    }

    public String getRequestMethod() {
        return delegate.getRequestMethod();
    }

    public Map<String, List<String>> getRequestProperties() {
        return delegate.getRequestProperties();
    }

    public String getRequestProperty(String key) {
        return delegate.getRequestProperty(key);
    }

    public int getResponseCode() throws IOException {
        return readFromCache ? HTTP_OK : delegate.getResponseCode();
    }

    public String getResponseMessage() throws IOException {
        return readFromCache ? "OK" : delegate.getResponseMessage();
    }

    public URL getURL() {
        return delegate.getURL();
    }

    public boolean getUseCaches() {
        return delegate.getUseCaches();
    }

    public void setAllowUserInteraction(boolean allowuserinteraction) {
        delegate.setAllowUserInteraction(allowuserinteraction);
    }


    public void setConnectTimeout(int timeout) {
        delegate.setConnectTimeout(timeout);
    }

    public void setDefaultUseCaches(boolean defaultusecaches) {
        delegate.setDefaultUseCaches(defaultusecaches);
    }

    public void setDoInput(boolean doinput) {
        delegate.setDoInput(doinput);
    }

    public void setDoOutput(boolean dooutput) {
        delegate.setDoOutput(dooutput);
    }


    public void setIfModifiedSince(long ifmodifiedsince) {
        delegate.setIfModifiedSince(ifmodifiedsince);
    }

    public void setInstanceFollowRedirects(boolean followRedirects) {
        delegate.setInstanceFollowRedirects(followRedirects);
    }

    public void setReadTimeout(int timeout) {
        delegate.setReadTimeout(timeout);
    }

    public void setRequestMethod(String method) throws ProtocolException {
        delegate.setRequestMethod(method);
    }

    public void setRequestProperty(String key, String value) {
        delegate.setRequestProperty(key, value);
    }

    public void setUseCaches(boolean usecaches) {
        delegate.setUseCaches(usecaches);
    }

    public boolean usingProxy() {
        return delegate.usingProxy();
    }
}
