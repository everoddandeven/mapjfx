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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * FilterInputStream that dumps all data passed through it to a cache file before passing the data on.
 *
 * @author P.J. Meisch (pj.meisch@sothawo.com).
 */
class WriteCacheFileInputStream extends FilterInputStream {

    /** the output stream where the data is stored. */
    private final OutputStream out;

    /** a Runnable to be called when the inputstream is closed. */
    private Runnable notifyOnClose;


    /**
     * Creates a <code>FilterInputStream</code> by assigning the  argument <code>in</code> to the field
     * <code>this.in</code> so as to remember it for later use.
     *
     * @param in
     *         the underlying input stream, or <code>null</code> if this instance is to be created without an underlying
     *         stream.
     * @param out
     *         the stream where the data read from in should be written to.
     */
    protected WriteCacheFileInputStream(InputStream in, OutputStream out) {
        super(in);
        this.out = out;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        final int numBytes = super.read(b, off, len);
        if (null != out && numBytes > 0) {
            out.write(b, off, numBytes);
        }
        return numBytes;
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (null != out) {
            out.flush();
            out.close();
        }
        if (null != notifyOnClose) {
            notifyOnClose.run();
        }
    }

    public void onInputStreamClose(Runnable r) {
        notifyOnClose = r;
    }
}
