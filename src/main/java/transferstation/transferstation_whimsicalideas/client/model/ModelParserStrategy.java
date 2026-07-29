package transferstation.transferstation_whimsicalideas.client.model;

import java.io.IOException;
import java.nio.file.Path;

public interface ModelParserStrategy {

    boolean isAvailable();

    String getPlatformName();

    MdlDataTypes.ParsedModel parseMdl(byte[] data) throws IOException;

    VvdParser.ParsedVvd parseVvd(byte[] data) throws IOException;

    VtxParser.ParsedVtx parseVtx(byte[] data) throws IOException;

    SourceModelData loadModel(Path packageDir) throws IOException;

    void clearCache();
}
