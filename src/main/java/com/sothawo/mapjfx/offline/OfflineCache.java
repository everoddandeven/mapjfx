/*
 Copyright 2016-2021 Peter-Josef Meisch (pj.meisch@sothawo.com)

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

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Offline Cache functionality. The cache must be explicitly set to be active. If it is active, a call to a http or
 * https resource is intercepted. If the result of the same call is already stored in the local cache directory, it is
 * returned without a further connect to the network. If it is not in the cache directory, a network request is made and
 * the returned data is stored in the local cache directory.
 *
 * The cache is implemented as singleton.
 *
 * A list of regexp strings can be set with {@link #setNoCacheFilters(Collection)}. URLs that match any of these
 * patterns will not be cached.
 *
 * @author P.J. Meisch (pj.meisch@sothawo.com).
 */
public enum OfflineCache {
    INSTANCE;

    /** Logger for the class */
    private static final Logger logger = LoggerFactory.getLogger(OfflineCache.class);
    /** the url pattern to be mapped. */
    private static final String TILE_OPENSTREETMAP_ORG = "[a-z]\\.tile\\.openstreetmap\\.org";
    private static final int PRELOAD_DATABUFFER_SIZE = 1024 * 1024;
    /** list of Patterns that are used to match against urls to prevent caching. */
    private final Collection<Pattern> noCachePatterns = new ArrayList<>();
    /** list of Patterns that are used to match against urls to specify caching. */
    private final Collection<Pattern> cachePatterns = new ArrayList<>();
    /** flag if the URLStreamHandlerfactory is initialized */
    private boolean urlStreamHandlerFactoryIsInitialized = false;
    /** flag if the cache is active. */
    private boolean active = false;
    /** the cache directory. */
    private Path cacheDirectory;

    /**
     * helper method to recursively delete all files in a directory and the directory itself.
     *
     * @param path
     *     the directory to delete
     */
    static void clearDirectory(final Path path) throws IOException {
        Files.walkFileTree(path, new DeletingFileVisitor(path));
    }

    public Collection<String> getNoCacheFilters() {
        return noCachePatterns.stream().map(Pattern::toString).collect(Collectors.toList());
    }

    public void clearAllCacheFilters() {
        cachePatterns.clear();
        noCachePatterns.clear();
    }

    /**
     * Sets a list of Java pattern strings to define which URLs should be cached. Only URLs that do match one of the given patterns are cached.
     *
     * @param cacheFilters
     *     the patterns defining what to cache.
     */
    public void setCacheFilters(final Collection<String> cacheFilters) {
        if (!noCachePatterns.isEmpty()) {
            throw new IllegalStateException("cannot set both cacheFilters and noCacheFilters");
        }
        this.cachePatterns.clear();
        if (null != cacheFilters) {
            cacheFilters.stream().map(Pattern::compile).forEach(this.cachePatterns::add);
        }
    }

    /**
     * Sets a list of Java pattern strings to define which URLs should not be cached. URLs that do match any of the given patterns are not cached.
     *
     * @param noCacheFilters
     *     the patterns defining what not to cache.
     */
    public void setNoCacheFilters(final Collection<String> noCacheFilters) {
        if (!cachePatterns.isEmpty()) {
            throw new IllegalStateException("cannot set both cacheFilters and noCacheFilters");
        }
        this.noCachePatterns.clear();
        if (null != noCacheFilters) {
            noCacheFilters.stream().map(Pattern::compile).forEach(this.noCachePatterns::add);
        }
    }

    public Path getCacheDirectory() {
        return cacheDirectory;
    }

    /**
     * sets the cacheDirectory.
     *
     * @param cacheDirectory
     *     the new directory
     * @throws NullPointerException
     *     if cacheDirectory is null
     * @throws IllegalArgumentException
     *     if cacheDirectory does not exist or is not writeable
     */
    public void setCacheDirectory(final Path cacheDirectory) {
        final Path dir = Objects.requireNonNull(cacheDirectory);
        if (!Files.isDirectory(dir) || !Files.isWritable(dir)) {
            throw new IllegalArgumentException("cacheDirectory: " + dir);
        }
        this.cacheDirectory = dir;
    }

    /**
     * sets the cacheDirectory.
     *
     * @param cacheDirectory
     *     the new directory
     * @throws NullPointerException
     *     if cacheDirectory is null
     * @throws IllegalArgumentException
     *     if cacheDirectory does not exist or is not writeable
     */
    public void setCacheDirectory(final String cacheDirectory) {
        setCacheDirectory(FileSystems.getDefault().getPath(Objects.requireNonNull(cacheDirectory)));
    }

    /**
     * checks whether a URL should be cached at all.
     *
     * @param u
     *     the URL to check
     * @return true if the URL should be cached.
     */
    boolean urlShouldBeCached(final URL u) {
        final String urlString = u.toString();

        if (!noCachePatterns.isEmpty()) {
            return noCachePatterns.stream()
                .noneMatch(pattern -> pattern.matcher(urlString).matches());
        }

        if (!cachePatterns.isEmpty()) {
            return cachePatterns.stream()
                .anyMatch(pattern -> pattern.matcher(urlString).matches());
        }

        return true;
    }

    public boolean isNotActive() {
        return !active;
    }

    /**
     * sets the active state of the cache
     *
     * @param active
     *     new state
     * @throws IllegalArgumentException
     *     if active is true and no cacheDirectory has been set.
     * @throws IllegalStateException
     *     if the factory cannot be initialized.
     */
    public void setActive(final boolean active) {
        if (active && null == cacheDirectory) {
            throw new IllegalArgumentException("cannot setActive when no cacheDirectory is set");
        }
        if (active) {
            setupURLStreamHandlerFactory();
        }
        this.active = active;
    }

    /**
     * sets up the URLStreamHandlerFactory.
     *
     * @throws IllegalStateException
     *     if the factory cannot be initialized.
     */
    private void setupURLStreamHandlerFactory() {
        if (!urlStreamHandlerFactoryIsInitialized) {
            final String msg;
            try {
                URL.setURLStreamHandlerFactory(new CachingURLStreamHandlerFactory(this));
                urlStreamHandlerFactoryIsInitialized = true;
                return;
            } catch (final Error e) {
                msg = "cannot setup URLStreamFactoryHandler, it is already set in this application. " + e.getMessage();
                if (logger.isErrorEnabled()) {
                    logger.error(msg);
                }
            } catch (final SecurityException e) {
                msg = "cannot setup URLStreamFactoryHandler. " + e.getMessage();
                if (logger.isErrorEnabled()) {
                    logger.error(msg);
                }
            }
            throw new IllegalStateException(msg);
        }
    }

    /**
     * check wether an URL is cached.
     *
     * @param url
     *     the URL to check
     * @return true if cached
     */
    boolean isCached(final URL url) {
        try {
            final Path cacheFile = filenameForURL(url);
            return (Files.exists(cacheFile) && Files.isReadable(cacheFile) && Files.size(cacheFile) > 0);
        } catch (final IOException e) {
            if (logger.isWarnEnabled()) {
                logger.warn(e.getMessage());
            }
        }
        return false;
    }

    /**
     * returns the filename path for a cachefile
     *
     * @param url
     *     the url to store in the cache
     * @return the filename path for the url
     * @throws IllegalStateException
     *     if no cacheDirectory is set.
     * @throws UnsupportedEncodingException
     *     if the url cannot be UTF-8
     * @throws NullPointerException
     *     if url or it's external form is null
     */
    Path filenameForURL(final URL url) throws UnsupportedEncodingException {
        if (null == cacheDirectory) {
            throw new IllegalStateException("cannot resolve filename for url");
        }
        final String mappedString = Objects.requireNonNull(doMappings(url.toExternalForm()));
        final String encodedString = URLEncoder.encode(mappedString, "UTF-8");
        return cacheDirectory.resolve(encodedString);
    }

    /**
     * do some special mappings, i.e. map the openstreetmap urls with [a,b,c].tile.openstreetmap.org to x.tile
     * .openstreetmap.org.
     *
     * @param urlString
     *     the string to map
     * @return the mapped string
     */
    private String doMappings(final String urlString) {
        if (null == urlString || urlString.isEmpty()) {
            return urlString;
        }

        return urlString.replaceAll(TILE_OPENSTREETMAP_ORG, "x.tile.openstreetmap.org");
    }

    /**
     * writes the datainfo for a cache file.
     *
     * @param cacheFile
     *     the cache file
     * @param cachedDataInfo
     *     the data info
     */
    void saveCachedDataInfo(final Path cacheFile, final CachedDataInfo cachedDataInfo) {
        final Path cacheDataFile = Paths.get(cacheFile + ".dataInfo");
        try (final ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(cacheDataFile.toFile()))) {
            oos.writeObject(cachedDataInfo);
            oos.flush();
            if (logger.isTraceEnabled()) {
                logger.trace("saving dataInfo {}", cachedDataInfo);
                logger.trace("saved dataInfo to {}", cacheDataFile);
            }
        } catch (final Exception e) {
            if (logger.isWarnEnabled()) {
                logger.warn("could not save dataInfo {}", cacheDataFile);
            }
        }
    }

    /**
     * reads the cached data info for a cache file
     *
     * @param cacheFile
     *     the cache file
     * @return the cached data info.
     */
    CachedDataInfo readCachedDataInfo(final Path cacheFile) {
        CachedDataInfo cachedDataInfo = null;
        final Path cacheDataFile = Paths.get(cacheFile + ".dataInfo");
        if (Files.exists(cacheDataFile)) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(cacheDataFile.toFile()))) {
                cachedDataInfo = (CachedDataInfo) ois.readObject();
            } catch (final Exception e) {
                if (logger.isWarnEnabled()) {
                    logger.warn("could not read dataInfo from {}, {}", cacheDataFile, e.getMessage());
                }
            }
        }
        return cachedDataInfo;
    }

    /**
     * deletes all files from the cache directory. Make sure before calling this method, that the cache directory was
     * set to a directory that only contains the cache's files and is not used for something else.
     */
    public void clear() throws IOException {
        if (null != cacheDirectory) {
            clearDirectory(cacheDirectory);
        }
    }

    /**
     * calls {@link #preloadURLs(Collection, int)} with a paraellism value of zero.
     *
     * @param urls
     */
    public void preloadURLs(final Collection<String> urls) {
        preloadURLs(urls, 0);
    }

    /**
     * loads the given URL and so puts them into the cache. If the cache is disabled, the call is ignored.
     * Any errors during load are logged and further ignored.
     *
     * @param urls
     *     the list of URLs
     * @param parallelism
     *     the number of threads to use for loading. If set to zero or below, the number of available processors is used.
     */
    public void preloadURLs(final Collection<String> urls, int parallelism) {

        if (urls == null || isNotActive()) {
            return;
        }

        ForkJoinPool customThreadPool = new ForkJoinPool(parallelism < 1 ? Runtime.getRuntime().availableProcessors() : parallelism);
        try {
            customThreadPool.submit(() -> urls.parallelStream().forEach(url -> {
                try {
                    URLConnection urlConnection = new URL(url).openConnection();
                    urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:66.0) Gecko/20100101 Firefox/66.0");
                    try (BufferedInputStream in = new BufferedInputStream(urlConnection.getInputStream())) {
                        byte[] dataBuffer = new byte[PRELOAD_DATABUFFER_SIZE];
                        while (in.read(dataBuffer, 0, PRELOAD_DATABUFFER_SIZE) != -1) {
                            // NOOP
                        }
                    }
                } catch (Exception e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("could not load data from url {}", url, e);
                    }
                }
            }));
        } finally {
            customThreadPool.shutdown();
        }
    }

    /**
     * class to recursivly delete files.
     */
    private static class DeletingFileVisitor extends SimpleFileVisitor<Path> {
        /** the top directory, this will not be deleted. */
        private final Path rootDir;

        public DeletingFileVisitor(final Path path) {
            this.rootDir = path;
        }

        @Override
        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
            if (!attrs.isDirectory()) {
                Files.delete(file);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
            if (!dir.equals(rootDir)) {
                Files.delete(dir);
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
