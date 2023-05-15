package org.icepdf.core.util.updater;

import org.icepdf.core.pobjects.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simple builder to handle the multiple ways to write document changes to an output stream.
 */
public class DocumentBuilder {

    private static final Logger logger =
            Logger.getLogger(Document.class.toString());

    public long createDocument(
            WriteMode writeMode,
            Document document,
            ByteBuffer documentByteBuffer,
            OutputStream out,
            long documentLength) throws IOException {

        long appendedLength;
        try (WritableByteChannel channel = Channels.newChannel(out)) {
            if (writeMode == WriteMode.FULL_UPDATE) {
                // kick of a full rewrite of the document, replacing any updates objects with new data
                appendedLength = new FullUpdater().writeDocument(
                        document,
                        out);
                return documentLength + appendedLength;
            } else if (writeMode == WriteMode.INCREMENT_UPDATE) {
                // copy original file data
                channel.write(documentByteBuffer);
                // append the data from the incremental updater
                appendedLength = new IncrementalUpdater().appendIncrementalUpdate(
                        document,
                        out,
                        documentLength);
                return documentLength + appendedLength;
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "Error writing PDF output stream.", e);
            throw e;
        }

        return 0;
    }
}
